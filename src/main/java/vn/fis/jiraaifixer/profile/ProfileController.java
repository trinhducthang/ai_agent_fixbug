package vn.fis.jiraaifixer.profile;

import static vn.fis.jiraaifixer.util.Texts.isBlank;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import vn.fis.jiraaifixer.config.AppProperties.Project;
import vn.fis.jiraaifixer.git.GitCli;
import vn.fis.jiraaifixer.jira.JiraClient;
import vn.fis.jiraaifixer.jira.JiraClient.FieldInfo;
import vn.fis.jiraaifixer.jira.JiraClient.ProjectInfo;
import vn.fis.jiraaifixer.jira.JiraClient.ServerInfo;
import vn.fis.jiraaifixer.jira.JiraException;
import vn.fis.jiraaifixer.jira.JiraSettings;
import vn.fis.jiraaifixer.profile.JiraLinkParser.JiraLink;
import vn.fis.jiraaifixer.profile.ProfileService.ProfileInput;
import vn.fis.jiraaifixer.profile.SourceAnalyzer.Proposal;

/** Module "Cấu hình dự án": quản lý profile, tự nhận Jira từ link, tự map source code. */
@RestController
@RequestMapping("/api/profiles")
public class ProfileController {

    private static final List<String> WANTED_STATUSES = List.of("Open", "Reopened", "In Progress");

    /** Profile trả ra giao diện – không bao giờ chứa mật khẩu. */
    public record ProfileView(String id, String name, boolean active, String jiraBaseUrl, String jiraUsername,
                              boolean hasPassword, String defaultJql, String moduleField, String sourceRoot,
                              List<String> sourceLinks, List<String> jiraProjectKeys, List<Project> projects,
                              String updatedAt) {
    }

    public record ProfilesView(String activeId, String dataDir, List<ProfileView> profiles) {
    }

    public record JiraAnalyzeRequest(String profileId, String link, String username, String password) {
    }

    public record JiraAnalysis(String baseUrl, String serverVersion, String serverTitle, String issueKey,
                               boolean loginOk, String user, String loginError, List<ProjectInfo> projects,
                               List<String> projectErrors, String moduleField, String moduleFieldName,
                               String suggestedJql) {
    }

    public record SourceAnalyzeRequest(List<String> links, String sourceRoot, List<String> jiraProjectKeys) {
    }

    public record CloneRequest(String url, String path) {
    }

    private final ProfileService profiles;
    private final JiraClient jira;
    private final SourceAnalyzer analyzer;
    private final GitCli git;

    public ProfileController(ProfileService profiles, JiraClient jira, SourceAnalyzer analyzer, GitCli git) {
        this.profiles = profiles;
        this.jira = jira;
        this.analyzer = analyzer;
        this.git = git;
    }

    @GetMapping
    public ProfilesView list() {
        String active = profiles.active().id();
        return new ProfilesView(active, profiles.dataDir().toString(),
                profiles.list().stream().map(p -> view(p, active)).toList());
    }

    @GetMapping("/{id}")
    public ProfileView get(@PathVariable String id) {
        return view(profiles.get(id), profiles.active().id());
    }

    @PostMapping
    public ProfileView save(@RequestBody ProfileInput in) {
        Profile p = profiles.save(in);
        return view(p, profiles.active().id());
    }

    @PostMapping("/{id}/activate")
    public ProfileView activate(@PathVariable String id) {
        profiles.activate(id);
        return view(profiles.get(id), id);
    }

    @DeleteMapping("/{id}")
    public Map<String, String> delete(@PathVariable String id) {
        profiles.delete(id);
        return Map.of("message", "Đã xóa profile");
    }

    @PostMapping("/import-yml")
    public ProfileView importYaml() {
        return view(profiles.importYaml(), profiles.active().id());
    }

    /** Dán link Jira bất kỳ + tài khoản → địa chỉ Jira, project, field Module, JQL gợi ý. */
    @PostMapping("/analyze-jira")
    public JiraAnalysis analyzeJira(@RequestBody JiraAnalyzeRequest req) {
        JiraLink link = JiraLinkParser.parse(req.link());
        JiraSettings s = profiles.jiraForTest(req.profileId(), link.baseUrl(), req.username(), req.password());
        ServerInfo info;
        try {
            info = jira.serverInfo(s);
        } catch (JiraException e) {
            throw new IllegalArgumentException("Không đọc được Jira tại " + link.baseUrl() + ": " + e.getMessage());
        }

        String user = null;
        String loginError = null;
        List<ProjectInfo> projectInfos = new ArrayList<>();
        List<String> projectErrors = new ArrayList<>();
        FieldInfo module = null;
        List<String> statuses = List.of();
        String jql = null;
        try {
            var me = jira.myself(s);
            user = me.displayName() == null ? me.name() : me.displayName();
            for (String key : link.projectKeys()) {
                try {
                    projectInfos.add(jira.project(s, key));
                } catch (RuntimeException e) {
                    projectErrors.add(key + ": " + e.getMessage());
                }
            }
            module = findModuleField(jira.fields(s));
            statuses = jira.statuses(s);
            // Ưu tiên đủ 3 trạng thái Open/Reopened/In Progress nếu Jira chấp nhận JQL đó (danh sách /status có thể
            // không liệt kê hết); Jira báo lỗi thì chỉ giữ trạng thái thực sự có.
            List<String> keys = keysOf(projectInfos, link);
            String full = suggestJql(keys, WANTED_STATUSES);
            jql = jira.jqlError(s, full) == null ? full : suggestJql(keys, statuses);
        } catch (JiraException e) {
            loginError = e.getMessage();
        }
        List<String> keys = keysOf(projectInfos, link);
        return new JiraAnalysis(link.baseUrl(), info.version(), info.title(), link.issueKey(), loginError == null,
                user, loginError, projectInfos, projectErrors, module == null ? null : module.id(),
                module == null ? null : module.name(), jql != null ? jql : suggestJql(keys, WANTED_STATUSES));
    }

    private static List<String> keysOf(List<ProjectInfo> infos, JiraLink link) {
        return infos.isEmpty() ? link.projectKeys() : infos.stream().map(ProjectInfo::key).toList();
    }

    /** Dán link repo / thư mục local → đề xuất phần mềm + repo đã map sẵn. */
    @PostMapping("/analyze-sources")
    public Proposal analyzeSources(@RequestBody SourceAnalyzeRequest req) {
        return analyzer.analyze(req.links(), req.sourceRoot(), req.jiraProjectKeys());
    }

    /** Clone repo còn thiếu bản local (dùng tài khoản git đã lưu trong Git Credential Manager). */
    @PostMapping("/clone")
    public Map<String, String> cloneRepo(@RequestBody CloneRequest req) {
        String url = GitUrls.toCloneUrl(req.url());
        if (url == null) {
            throw new IllegalArgumentException("Link repo không hợp lệ: " + req.url());
        }
        Path target;
        try {
            target = Path.of(req.path()).toAbsolutePath().normalize();
        } catch (InvalidPathException | NullPointerException e) {
            throw new IllegalArgumentException("Đường dẫn clone không hợp lệ: " + req.path());
        }
        if (Files.exists(target)) {
            throw new IllegalStateException("Thư mục đã tồn tại: " + target);
        }
        try {
            Files.createDirectories(target.getParent());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Không tạo được thư mục " + target.getParent() + ": " + e.getMessage());
        }
        String out = git.runOrThrow(target.getParent(), Duration.ofMinutes(15), List.of("clone", "--", url, target.toString()));
        return Map.of("path", target.toString(), "output", out.strip());
    }

    static FieldInfo findModuleField(List<FieldInfo> fields) {
        FieldInfo contains = null;
        for (FieldInfo f : fields) {
            if (f.id() == null || !f.id().startsWith("customfield_") || f.name() == null) {
                continue;
            }
            if (f.name().equalsIgnoreCase("Module")) {
                return f;
            }
            if (contains == null && f.name().toLowerCase(Locale.ROOT).contains("module")) {
                contains = f;
            }
        }
        return contains;
    }

    /** JQL gợi ý: chỉ dùng các trạng thái Open/Reopened/In Progress thực sự có trên Jira đó. */
    static String suggestJql(List<String> projectKeys, List<String> statuses) {
        List<String> parts = new ArrayList<>();
        if (!projectKeys.isEmpty()) {
            parts.add("project in (" + String.join(", ", projectKeys) + ")");
        }
        List<String> present = WANTED_STATUSES.stream()
                .filter(w -> statuses.stream().anyMatch(s -> s.equalsIgnoreCase(w))).toList();
        parts.add(present.isEmpty() ? "resolution = Unresolved"
                : "status in (" + String.join(", ", present.stream().map(s -> "\"" + s + "\"").toList()) + ")");
        parts.add("assignee = currentUser()");
        return String.join(" AND ", parts) + " ORDER BY priority DESC, updated DESC";
    }

    private static ProfileView view(Profile p, String activeId) {
        return new ProfileView(p.id(), p.name(), p.id().equals(activeId), p.jiraBaseUrl(), p.jiraUsername(),
                !isBlank(p.jiraPassword()), p.defaultJql(), p.moduleField(), p.sourceRoot(), p.sourceLinks(),
                p.jiraProjectKeys(), p.projects(), p.updatedAt());
    }
}
