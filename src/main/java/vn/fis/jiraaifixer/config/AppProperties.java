package vn.fis.jiraaifixer.config;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình từ application.yml. {@code jira} và {@code projects} chỉ dùng để tạo profile mặc định ở lần chạy đầu;
 * sau đó profile (module cấu hình dự án, lưu ở {@code dataDir}) là nguồn cấu hình chính.
 *
 * @param dataDir thư mục lưu profile (mặc định ~/.jira-ai-fixer, nằm ngoài project nên không bị commit)
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(Jira jira, Claude claude, Workspace workspace, List<Project> projects, String dataDir) {

    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]{1,40}");

    public AppProperties {
        projects = projects == null ? List.of() : List.copyOf(projects);
    }

    public Optional<Project> project(String name) {
        return projects.stream().filter(p -> p.name().equals(name)).findFirst();
    }

    public record Jira(String baseUrl, String username, String password, String cookie, String defaultJql,
                       String moduleField, int maxResults, int maxAttachments, int maxAttachmentMb) {
    }

    public record Claude(String cliPath, String model, double maxBudgetUsd, String tools,
                         String allowedTools, boolean reviewEnabled, int maxParallel) {
    }

    public record Workspace(String dir, String branchPattern) {
    }

    /**
     * Một phần mềm gồm nhiều repo (thường là BE + FE). Claude làm việc trên worktree của tất cả repo cùng lúc.
     *
     * @param jiraProjects key Jira project chỉ thuộc phần mềm này (khớp là chọn ngay)
     * @param keywords     từ khóa tìm trong Module/Component/Label (mạnh) và tiêu đề issue (yếu)
     * @param contextDirs  thư mục tài liệu tham khảo, Claude chỉ đọc
     */
    public record Project(String name, String title, List<String> jiraProjects, List<String> keywords,
                          List<String> contextDirs, List<Repo> repos) {

        public Project {
            requireSafe("project", name);
            title = title == null || title.isBlank() ? name : title;
            jiraProjects = jiraProjects == null ? List.of() : List.copyOf(jiraProjects);
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
            contextDirs = contextDirs == null ? List.of() : List.copyOf(contextDirs);
            repos = repos == null ? List.of() : List.copyOf(repos);
            if (repos.isEmpty()) {
                throw new IllegalArgumentException("Project " + name + " chưa khai báo repo nào");
            }
        }
    }

    /** @param name tên thư mục của repo trong workspace phiên (vd: be, fe) */
    public record Repo(String name, String path, String remote, String targetBranch,
                       String verifyHint, List<String> sharedDirs) {

        public Repo {
            requireSafe("repo", name);
            remote = (remote == null || remote.isBlank()) ? "origin" : remote;
            sharedDirs = sharedDirs == null ? List.of() : List.copyOf(sharedDirs);
        }
    }

    private static void requireSafe(String kind, String name) {
        if (name == null || !SAFE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Tên " + kind + " không hợp lệ (chỉ chữ, số, . _ -): " + name);
        }
    }
}
