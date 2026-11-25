package vn.fis.jiraaifixer.profile;

import static vn.fis.jiraaifixer.util.Texts.isBlank;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.fis.jiraaifixer.config.AppProperties.Project;
import vn.fis.jiraaifixer.config.AppProperties.Repo;
import vn.fis.jiraaifixer.git.GitCli;

/**
 * Từ danh sách link source (link GitLab/GitHub hoặc thư mục local) tự đề xuất cấu hình phần mềm:
 * tìm bản clone local, nhận BE/FE/tài liệu theo file build, chọn nhánh nhận MR, lệnh kiểm tra build,
 * thư mục node_modules dùng chung, gom repo theo phần mềm và gắn Jira project.
 */
@Service
public class SourceAnalyzer {

    private static final List<String> TARGET_PRIORITY = List.of("develop", "dev", "main", "master");
    private static final Set<String> SKIP_DIRS = Set.of("node_modules", "target", "build", "dist", ".git", ".idea");
    private static final int MAX_SHARED_DIRS = 20;

    /** Kết quả phân tích một repo. {@code exists=false}: chưa có bản local, cần clone vào {@code path}. */
    public record RepoFinding(String input, String software, String name, String role, String path, boolean exists,
                              String remoteUrl, String buildTool, List<String> branches, String targetBranch,
                              String verifyHint, List<String> sharedDirs, String note) {
    }

    public record Proposal(List<Project> projects, List<RepoFinding> repos, List<String> warnings) {
    }

    /** Thông tin thô trước khi gom nhóm. */
    private record Raw(String input, Path path, boolean exists, String remoteUrl, String buildTool,
                       List<String> branches, String targetBranch, List<String> sharedDirs, String note,
                       String software, String dirName) {
    }

    private final GitCli git;
    private final ObjectMapper json;

    public SourceAnalyzer(GitCli git, ObjectMapper json) {
        this.git = git;
        this.json = json;
    }

    public Proposal analyze(List<String> links, String sourceRoot, List<String> jiraProjectKeys) {
        List<String> warnings = new ArrayList<>();
        Path root = null;
        if (!isBlank(sourceRoot)) {
            try {
                root = Path.of(sourceRoot.trim()).toAbsolutePath().normalize();
                if (!Files.isDirectory(root)) {
                    warnings.add("Thư mục chứa code không tồn tại: " + root + " (sẽ được tạo khi clone)");
                }
            } catch (InvalidPathException e) {
                warnings.add("Thư mục chứa code không hợp lệ: " + sourceRoot);
            }
        }
        Map<String, Path> localIndex = null; // remote chuẩn hóa -> bản clone trong sourceRoot (lazy)

        List<Raw> raws = new ArrayList<>();
        for (String link : links == null ? List.<String>of() : links) {
            String l = link == null ? "" : link.trim();
            if (l.isEmpty() || l.startsWith("#")) {
                continue;
            }
            if (GitUrls.looksLikeUrl(l)) {
                String clone = GitUrls.toCloneUrl(l);
                if (clone == null) {
                    warnings.add("Không hiểu link repo: " + l + " (cần link tới một repo, vd https://git.../group/repo)");
                    continue;
                }
                if (localIndex == null) {
                    localIndex = indexClones(root);
                }
                Path local = localIndex.get(GitUrls.normalize(clone));
                if (local != null) {
                    raws.add(analyzeLocal(l, local));
                } else {
                    raws.add(planClone(l, clone, root, warnings));
                }
            } else {
                Path p;
                try {
                    p = Path.of(l).toAbsolutePath().normalize();
                } catch (InvalidPathException e) {
                    warnings.add("Không hiểu đường dẫn: " + l);
                    continue;
                }
                if (!Files.isDirectory(p)) {
                    warnings.add("Thư mục không tồn tại: " + p);
                    continue;
                }
                List<Path> repos = isGitRepo(p) ? List.of(p) : findGitRepos(p, 2);
                if (repos.isEmpty()) {
                    warnings.add("Không tìm thấy git repo nào trong " + p);
                }
                repos.forEach(r -> raws.add(analyzeLocal(l, r)));
            }
        }

        List<Raw> unique = dedupe(raws, warnings);
        return group(unique, jiraProjectKeys == null ? List.of() : jiraProjectKeys, warnings);
    }

    // ---------------------------------------------------------------- phân tích từng repo

    private Raw analyzeLocal(String input, Path repoPath) {
        Path path = repoPath;
        try {
            path = repoPath.toRealPath(); // bỏ tên ngắn 8.3 (THANGT~1) – Claude Code chặn ghi vào đường dẫn dạng đó
        } catch (IOException ignored) {
            // giữ nguyên
        }
        String remote = GitUrls.stripCredentials(
                git.run(path, GitCli.LOCAL, "remote", "get-url", "origin").output().strip());
        if (remote.isEmpty() || remote.startsWith("error") || remote.startsWith("fatal")) {
            remote = null;
        }
        List<String> branches = new ArrayList<>();
        for (String line : git.run(path, GitCli.LOCAL, "branch", "-r", "--format=%(refname:short)").output().split("\\R")) {
            String b = line.trim();
            if (b.startsWith("origin/") && !b.equals("origin/HEAD") && !b.equals("origin")) {
                branches.add(b.substring("origin/".length()));
            }
        }
        String note = remote == null ? "Repo không có remote origin – không push/tạo MR được" : null;
        String software = sanitize(path.getParent() == null ? path.getFileName().toString()
                : path.getParent().getFileName().toString());
        return new Raw(input, path, true, remote, buildTool(path), branches, pickTarget(branches),
                sharedDirs(path), note, software, path.getFileName().toString());
    }

    private Raw planClone(String input, String cloneUrl, Path root, List<String> warnings) {
        String repo = GitUrls.repoName(cloneUrl);
        String group = GitUrls.parentGroup(cloneUrl);
        String software = sanitize(group == null ? repo : group);
        Path target = root == null ? null : root.resolve(software).resolve(repo);
        if (root == null) {
            warnings.add("Chưa nhập thư mục chứa code nên không biết clone " + repo + " vào đâu");
        }
        List<String> branches = new ArrayList<>();
        String note = "Chưa có bản local – bấm Clone";
        GitCli.Result r = git.run(root != null && Files.isDirectory(root) ? root : Path.of(System.getProperty("user.home")),
                Duration.ofSeconds(60), List.of("ls-remote", "--heads", cloneUrl)); // cloneUrl luôn bắt đầu bằng scheme/git@
        if (r.ok()) {
            for (String line : r.output().split("\\R")) {
                int i = line.indexOf("refs/heads/");
                if (i >= 0) {
                    branches.add(line.substring(i + "refs/heads/".length()).trim());
                }
            }
        } else {
            note += " (không đọc được danh sách nhánh: " + firstLine(r.output()) + ")";
        }
        return new Raw(input, target, false, GitUrls.stripCredentials(cloneUrl), null, branches, pickTarget(branches),
                List.of(), note, software, repo);
    }

    /** Hai đường dẫn cùng trỏ một remote: giữ bản có tên thư mục trùng tên repo (bỏ bản backup/bản sao). */
    private static List<Raw> dedupe(List<Raw> raws, List<String> warnings) {
        Map<String, Raw> byKey = new LinkedHashMap<>();
        for (Raw r : raws) {
            String key = r.remoteUrl() != null ? GitUrls.normalize(r.remoteUrl()) : "path:" + r.path();
            Raw prev = byKey.get(key);
            if (prev == null) {
                byKey.put(key, r);
                continue;
            }
            if (prev.path() != null && prev.path().equals(r.path())) {
                continue;
            }
            String repoName = r.remoteUrl() == null ? null : GitUrls.repoName(r.remoteUrl());
            boolean better = repoName != null && r.dirName().equalsIgnoreCase(repoName)
                    && !prev.dirName().equalsIgnoreCase(repoName);
            Raw keep = better ? r : prev;
            Raw drop = better ? prev : r;
            byKey.put(key, keep);
            warnings.add("Bỏ qua " + drop.path() + " vì trùng remote với " + keep.path());
        }
        return new ArrayList<>(byKey.values());
    }

    // ---------------------------------------------------------------- gom nhóm thành phần mềm

    private Proposal group(List<Raw> raws, List<String> jiraKeys, List<String> warnings) {
        Map<String, List<Raw>> bySoftware = new LinkedHashMap<>();
        raws.forEach(r -> bySoftware.computeIfAbsent(r.software(), k -> new ArrayList<>()).add(r));

        Map<String, List<String>> jiraBySoftware = mapJiraProjects(bySoftware, jiraKeys);
        List<Project> projects = new ArrayList<>();
        List<RepoFinding> findings = new ArrayList<>();
        for (Map.Entry<String, List<Raw>> e : bySoftware.entrySet()) {
            String software = e.getKey();
            List<Raw> code = e.getValue().stream().filter(r -> !"docs".equals(role(r))).toList();
            List<String> contextDirs = e.getValue().stream().filter(r -> "docs".equals(role(r)) && r.exists())
                    .map(r -> r.path().toString()).toList();
            Map<String, Long> roleCount = new LinkedHashMap<>();
            code.forEach(r -> roleCount.merge(role(r), 1L, Long::sum));

            List<Repo> repos = new ArrayList<>();
            for (Raw r : code) {
                String role = role(r);
                String name = roleCount.get(role) > 1 || "unknown".equals(role) ? sanitize(r.dirName()) : role;
                String hint = verifyHint(r, name);
                if (r.path() != null) {
                    repos.add(new Repo(name, r.path().toString().replace('\\', '/'), "origin",
                            r.targetBranch() == null ? "develop" : r.targetBranch(), hint, r.sharedDirs()));
                }
                findings.add(new RepoFinding(r.input(), software, name, role,
                        r.path() == null ? null : r.path().toString(), r.exists(), r.remoteUrl(), r.buildTool(),
                        r.branches(), r.targetBranch(), hint, r.sharedDirs(), r.note()));
            }
            e.getValue().stream().filter(r -> "docs".equals(role(r))).forEach(r -> findings.add(new RepoFinding(
                    r.input(), software, sanitize(r.dirName()), "docs", r.path() == null ? null : r.path().toString(),
                    r.exists(), r.remoteUrl(), null, r.branches(), null, null, List.of(),
                    "Repo tài liệu (không có file build) – dùng làm tài liệu tham khảo chỉ đọc")));
            if (repos.isEmpty()) {
                warnings.add("Phần mềm " + software + " không có repo code nào (chỉ tài liệu) – bỏ qua");
                continue;
            }
            if (e.getValue().stream().anyMatch(r -> r.targetBranch() == null)) {
                warnings.add("Phần mềm " + software + ": có repo không tìm thấy nhánh develop/dev/main/master – kiểm tra nhánh đích");
            }
            projects.add(new Project(software, software.toUpperCase(Locale.ROOT), jiraBySoftware.getOrDefault(software, List.of()),
                    keywords(software), contextDirs, repos));
        }
        if (projects.size() > 1 && jiraBySoftware.isEmpty() && !jiraKeys.isEmpty()) {
            warnings.add("Nhiều phần mềm dùng chung Jira project: issue sẽ được phân loại theo từ khóa (Module/tiêu đề/component) – kiểm tra cột Từ khóa");
        }
        return new Proposal(projects, findings, warnings);
    }

    /**
     * Gắn Jira project vào phần mềm khi tên group trên GitLab trùng mã Jira project
     * (vd group btp_gdbd_s7_2026_pm ↔ BTP_GDBD_S7_2026_PM). Chỉ gắn khi một Jira project ứng với đúng một phần mềm;
     * nếu chỉ có một phần mềm và một Jira project thì gắn luôn.
     */
    static Map<String, List<String>> mapJiraProjects(Map<String, List<Raw>> bySoftware, List<String> jiraKeys) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (bySoftware.size() == 1 && jiraKeys.size() == 1) {
            out.put(bySoftware.keySet().iterator().next(), List.of(jiraKeys.getFirst()));
            return out;
        }
        for (String key : jiraKeys) {
            List<String> matched = new ArrayList<>();
            for (Map.Entry<String, List<Raw>> e : bySoftware.entrySet()) {
                boolean hit = e.getValue().stream().filter(r -> r.remoteUrl() != null)
                        .anyMatch(r -> GitUrls.pathSegments(r.remoteUrl()).stream().anyMatch(s -> s.equalsIgnoreCase(key)));
                if (hit) {
                    matched.add(e.getKey());
                }
            }
            if (matched.size() == 1) {
                out.computeIfAbsent(matched.getFirst(), k -> new ArrayList<>()).add(key);
            }
        }
        return out;
    }

    static List<String> keywords(String software) {
        List<String> out = new ArrayList<>();
        out.add(software);
        for (String part : software.split("[_-]")) {
            if (part.length() >= 3 && !part.matches("\\d+") && !out.contains(part)) {
                out.add(part);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- nhận diện

    private static String role(Raw r) {
        String b = r.buildTool();
        if (b == null) {
            return r.exists() ? "docs" : guessRoleByName(r.dirName());
        }
        return switch (b) {
            case "maven", "gradle" -> "be";
            case "npm", "pnpm", "yarn" -> "fe";
            default -> "unknown";
        };
    }

    /** Repo chưa clone: đoán vai trò theo tên (…_be, -backend, …_fe, -web). */
    static String guessRoleByName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.matches(".*(^|[_.-])(be|backend|api|server|service)$")) {
            return "be";
        }
        if (n.matches(".*(^|[_.-])(fe|frontend|web|client|portal)$")) {
            return "fe";
        }
        return "unknown";
    }

    private static String buildTool(Path repo) {
        if (Files.exists(repo.resolve("pom.xml"))) {
            return "maven";
        }
        if (Files.exists(repo.resolve("build.gradle")) || Files.exists(repo.resolve("build.gradle.kts"))) {
            return "gradle";
        }
        if (Files.exists(repo.resolve("package.json"))) {
            if (Files.exists(repo.resolve("pnpm-lock.yaml"))) {
                return "pnpm";
            }
            return Files.exists(repo.resolve("yarn.lock")) ? "yarn" : "npm";
        }
        return null;
    }

    private String verifyHint(Raw r, String name) {
        String tool = r.buildTool();
        if (tool == null) {
            return guessRoleByName(r.dirName()).equals("be") ? "`mvn -q -o compile -f " + name + "/pom.xml` (sau khi clone)"
                    : "đọc lại code (chưa biết công cụ build – phân tích lại sau khi clone)";
        }
        Set<String> scripts = r.exists() ? scripts(r.path()) : Set.of();
        return switch (tool) {
            case "maven" -> "`mvn -q -o compile -f " + name + "/pom.xml`";
            case "pnpm" -> scripts.contains("typecheck") ? "`pnpm --dir " + name + " run typecheck`"
                    : scripts.contains("build") ? "`pnpm --dir " + name + " run build`" : "đọc lại code";
            case "npm", "yarn" -> scripts.contains("build") ? "`npm run build --prefix " + name + "`" : "đọc lại code";
            default -> "đọc lại code (công cụ build " + tool + " chưa có trong allowlist)";
        };
    }

    private Set<String> scripts(Path repo) {
        try {
            JsonNode n = json.readTree(Files.readString(repo.resolve("package.json"), StandardCharsets.UTF_8));
            Set<String> out = new HashSet<>();
            n.path("scripts").properties().forEach(e -> out.add(e.getKey()));
            return out;
        } catch (IOException | RuntimeException e) {
            return Set.of();
        }
    }

    /** node_modules ở gốc và ở các package con cấp 2 (monorepo: apps/x, packages/y). */
    static List<String> sharedDirs(Path repo) {
        if (!Files.exists(repo.resolve("package.json"))) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        if (Files.isDirectory(repo.resolve("node_modules"))) {
            out.add("node_modules");
        }
        for (Path d : listDirs(repo)) {
            for (Path c : listDirs(d)) {
                if (out.size() < MAX_SHARED_DIRS && Files.isDirectory(c.resolve("node_modules"))
                        && Files.exists(c.resolve("package.json"))) {
                    out.add(repo.relativize(c.resolve("node_modules")).toString().replace('\\', '/'));
                }
            }
        }
        return out;
    }

    static String pickTarget(List<String> branches) {
        for (String b : TARGET_PRIORITY) {
            if (branches.contains(b)) {
                return b;
            }
        }
        return null;
    }

    /** "bpbd_s7" / "Quản Trị" -> tên an toàn cho thư mục và cấu hình (bỏ dấu tiếng Việt). */
    static String sanitize(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .replace('đ', 'd').replace('Đ', 'D').toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "_").replaceAll("_{2,}", "_").replaceAll("^[_.-]+|[_.-]+$", "");
        if (n.isEmpty()) {
            n = "repo";
        }
        return n.length() > 40 ? n.substring(0, 40) : n;
    }

    // ---------------------------------------------------------------- tìm repo trên đĩa

    private Map<String, Path> indexClones(Path root) {
        Map<String, Path> out = new LinkedHashMap<>();
        if (root == null || !Files.isDirectory(root)) {
            return out;
        }
        for (Path repo : findGitRepos(root, 3)) {
            String remote = git.run(repo, GitCli.LOCAL, "remote", "get-url", "origin").output().strip();
            if (!remote.isEmpty() && !remote.startsWith("error") && !remote.startsWith("fatal")) {
                out.putIfAbsent(GitUrls.normalize(remote), repo);
            }
        }
        return out;
    }

    private static boolean isGitRepo(Path p) {
        return Files.exists(p.resolve(".git"));
    }

    /** Tìm git repo trong thư mục con (không vào bên trong repo, bỏ qua thư mục ẩn/node_modules). */
    static List<Path> findGitRepos(Path dir, int depth) {
        List<Path> out = new ArrayList<>();
        if (depth <= 0) {
            return out;
        }
        for (Path child : listDirs(dir)) {
            if (isGitRepo(child)) {
                out.add(child);
            } else {
                out.addAll(findGitRepos(child, depth - 1));
            }
        }
        return out;
    }

    private static List<Path> listDirs(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> Files.isDirectory(p) && !Files.isSymbolicLink(p))
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return !n.startsWith(".") && !SKIP_DIRS.contains(n.toLowerCase(Locale.ROOT));
                    })
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static String firstLine(String s) {
        return s == null ? "" : s.strip().lines().findFirst().orElse("");
    }
}
