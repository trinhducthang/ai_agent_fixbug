package vn.fis.jiraaifixer.profile;

import static vn.fis.jiraaifixer.util.Texts.isBlank;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;
import vn.fis.jiraaifixer.config.AppProperties;
import vn.fis.jiraaifixer.config.AppProperties.Project;
import vn.fis.jiraaifixer.config.AppProperties.Repo;
import vn.fis.jiraaifixer.jira.JiraClient;
import vn.fis.jiraaifixer.jira.JiraSettings;

/**
 * Quản lý profile (cấu hình dự án): lưu ở {@code <dataDir>/profiles.json}, mật khẩu mã hóa bằng {@link SecretBox}.
 * Lần chạy đầu tạo profile mặc định từ application.yml.
 */
@Service
public class ProfileService {

    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);
    private static final Pattern CUSTOM_FIELD = Pattern.compile("customfield_\\d+");
    private static final Pattern BRANCH = Pattern.compile("[A-Za-z0-9._/\\-]{1,200}");

    /** Dữ liệu lưu file. */
    public record Store(String activeId, List<Profile> profiles) {
        public Store {
            profiles = profiles == null ? List.of() : List.copyOf(profiles);
        }
    }

    /** Dữ liệu từ giao diện. {@code jiraPassword} rỗng = giữ mật khẩu cũ (trừ khi {@code clearPassword}). */
    public record ProfileInput(String id, String name, String jiraBaseUrl, String jiraUsername, String jiraPassword,
                               boolean clearPassword, String defaultJql, String moduleField, String sourceRoot,
                               List<String> sourceLinks, List<String> jiraProjectKeys, List<Project> projects) {
    }

    private final AppProperties props;
    private final ObjectMapper json;
    private final Path dataDir;
    private final Path file;
    private final SecretBox secrets;
    private final Map<String, String> cookies = new ConcurrentHashMap<>();
    private Store store;

    public ProfileService(AppProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
        this.dataDir = Path.of(isBlank(props.dataDir())
                ? System.getProperty("user.home") + "/.jira-ai-fixer" : props.dataDir()).toAbsolutePath().normalize();
        this.file = dataDir.resolve("profiles.json");
        this.secrets = new SecretBox(dataDir.resolve("secret.key"));
        this.store = load();
        if (store.profiles().isEmpty()) {
            Profile seed = fromYaml("Mặc định (application.yml)");
            store = new Store(seed.id(), List.of(seed));
            persist();
            log.info("Tạo profile mặc định từ application.yml tại {}", file);
        }
    }

    public Path dataDir() {
        return dataDir;
    }

    public synchronized List<Profile> list() {
        return store.profiles();
    }

    public synchronized String activeId() {
        return store.activeId();
    }

    public synchronized Profile active() {
        return find(store.activeId()).orElseGet(() -> store.profiles().getFirst());
    }

    public synchronized Optional<Profile> find(String id) {
        return store.profiles().stream().filter(p -> p.id().equals(id)).findFirst();
    }

    public synchronized Profile get(String id) {
        return find(id).orElseThrow(() -> new NoSuchElementException("Không có profile " + id));
    }

    /** Thông tin Jira đã giải mã của profile (kèm cookie phiên đang giữ trong bộ nhớ). */
    public JiraSettings jira(Profile p) {
        return new JiraSettings(p.jiraBaseUrl(), p.jiraUsername(), secrets.decrypt(p.jiraPassword()),
                cookies.get(p.id()), p.defaultJql(), validModuleField(p.moduleField()));
    }

    public JiraSettings activeJira() {
        return jira(active());
    }

    /** Thông tin Jira để kiểm tra kết nối khi đang sửa profile (mật khẩu rỗng = dùng mật khẩu đã lưu). */
    public JiraSettings jiraForTest(String profileId, String baseUrl, String username, String password) {
        String pass = password;
        if (isBlank(pass) && !isBlank(profileId)) {
            pass = find(profileId).map(p -> secrets.decrypt(p.jiraPassword())).orElse(null);
        }
        return new JiraSettings(baseUrl, username, pass, null, null, null);
    }

    public void setCookie(String profileId, String raw) {
        String c = JiraClient.normalizeCookie(raw);
        if (c == null) {
            cookies.remove(profileId);
        } else {
            cookies.put(profileId, c);
        }
    }

    public synchronized Profile save(ProfileInput in) {
        if (isBlank(in.name())) {
            throw new IllegalArgumentException("Chưa đặt tên profile");
        }
        if (isBlank(in.jiraBaseUrl()) || !in.jiraBaseUrl().trim().matches("(?i)https?://\\S+")) {
            throw new IllegalArgumentException("Địa chỉ Jira phải bắt đầu bằng http:// hoặc https://");
        }
        if (!isBlank(in.moduleField()) && validModuleField(in.moduleField()) == null) {
            throw new IllegalArgumentException("Field Module phải có dạng customfield_12345");
        }
        validateProjects(in.projects());

        Optional<Profile> existing = isBlank(in.id()) ? Optional.empty() : find(in.id());
        String password;
        if (in.clearPassword()) {
            password = null;
        } else if (!isBlank(in.jiraPassword())) {
            password = secrets.encrypt(in.jiraPassword());
        } else {
            password = existing.map(Profile::jiraPassword).orElse(null);
        }
        Profile p = new Profile(existing.map(Profile::id).orElse(UUID.randomUUID().toString()), in.name().trim(),
                new JiraSettings(in.jiraBaseUrl(), null, null, null, null, null).baseUrl(),
                isBlank(in.jiraUsername()) ? null : in.jiraUsername().trim(), password,
                isBlank(in.defaultJql()) ? null : in.defaultJql().trim(),
                isBlank(in.moduleField()) ? null : in.moduleField().trim(),
                isBlank(in.sourceRoot()) ? null : in.sourceRoot().trim(),
                clean(in.sourceLinks()), clean(in.jiraProjectKeys()), in.projects(), Instant.now().toString());

        List<Profile> next = new ArrayList<>(store.profiles());
        next.removeIf(x -> x.id().equals(p.id()));
        next.add(p);
        store = new Store(store.activeId(), next);
        persist();
        return p;
    }

    public synchronized void activate(String id) {
        get(id);
        store = new Store(id, store.profiles());
        persist();
    }

    public synchronized void delete(String id) {
        get(id);
        if (store.profiles().size() == 1) {
            throw new IllegalStateException("Không xóa được profile cuối cùng");
        }
        List<Profile> next = new ArrayList<>(store.profiles());
        next.removeIf(p -> p.id().equals(id));
        String active = id.equals(store.activeId()) ? next.getFirst().id() : store.activeId();
        store = new Store(active, next);
        cookies.remove(id);
        persist();
    }

    /** Tạo thêm một profile từ cấu hình application.yml hiện tại. */
    public synchronized Profile importYaml() {
        Profile p = fromYaml("Từ application.yml " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        List<Profile> next = new ArrayList<>(store.profiles());
        next.add(p);
        store = new Store(store.activeId(), next);
        persist();
        return p;
    }

    private Profile fromYaml(String name) {
        AppProperties.Jira j = props.jira();
        return new Profile(UUID.randomUUID().toString(), name,
                j == null ? null : j.baseUrl(), j == null ? null : j.username(),
                j == null ? null : secrets.encrypt(j.password()), j == null ? null : j.defaultJql(),
                j == null ? null : j.moduleField(), null, List.of(), List.of(), props.projects(),
                Instant.now().toString());
    }

    private static void validateProjects(List<Project> projects) {
        Set<String> names = new HashSet<>();
        for (Project p : projects == null ? List.<Project>of() : projects) {
            if (!names.add(p.name())) {
                throw new IllegalArgumentException("Trùng tên phần mềm: " + p.name());
            }
            Set<String> repoNames = new HashSet<>();
            for (Repo r : p.repos()) {
                if (!repoNames.add(r.name())) {
                    throw new IllegalArgumentException("Phần mềm " + p.name() + " có 2 repo cùng tên " + r.name());
                }
                if (isBlank(r.path())) {
                    throw new IllegalArgumentException("Repo " + p.name() + "/" + r.name() + " chưa có đường dẫn local");
                }
                if (isBlank(r.targetBranch()) || !BRANCH.matcher(r.targetBranch()).matches()) {
                    throw new IllegalArgumentException("Repo " + p.name() + "/" + r.name() + " có nhánh đích không hợp lệ: "
                            + r.targetBranch());
                }
            }
        }
    }

    private static List<String> clean(List<String> values) {
        return values == null ? List.of() : values.stream().filter(v -> !isBlank(v)).map(String::trim).toList();
    }

    private static String validModuleField(String f) {
        return f != null && CUSTOM_FIELD.matcher(f.trim()).matches() ? f.trim() : null;
    }

    private Store load() {
        if (!Files.exists(file)) {
            return new Store(null, List.of());
        }
        try {
            return json.readValue(Files.readString(file, StandardCharsets.UTF_8), Store.class);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Không đọc được " + file + ": " + e.getMessage(), e);
        }
    }

    /** Ghi file an toàn: ghi ra file tạm rồi đổi tên. */
    private void persist() {
        try {
            Files.createDirectories(dataDir);
            Path tmp = dataDir.resolve("profiles.json.tmp");
            Files.writeString(tmp, json.writerWithDefaultPrettyPrinter().writeValueAsString(store), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("Không ghi được " + file + ": " + e.getMessage(), e);
        }
    }
}
