package vn.fis.jiraaifixer.task;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.DoubleAdder;

import vn.fis.jiraaifixer.config.AppProperties.Repo;
import vn.fis.jiraaifixer.jira.JiraModels.IssueDetail;
import vn.fis.jiraaifixer.jira.JiraSettings;

/**
 * Một phiên xử lý issue: thư mục phiên chứa worktree của mọi repo thuộc phần mềm,
 * các lượt chạy Claude, kết quả push/MR theo từng repo.
 */
public final class FixTask {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_LOG_LINES = 20_000;

    public record LogSlice(List<String> lines, int next, String state, String stateLabel, boolean busy) {
    }

    /** Trạng thái một repo trong phiên. Worktree nằm ở {@code <thư mục phiên>/<tên repo>}. */
    public static final class RepoWork {
        private final Repo repo;
        private final Path repoPath;
        private final Path worktree;
        private volatile String branch;
        private volatile String baseSha;
        private volatile boolean created;
        private volatile String mrUrl;
        private volatile boolean mrCreated;
        private volatile String pushedHead;

        RepoWork(Repo repo, Path repoPath, Path worktree) {
            this.repo = repo;
            this.repoPath = repoPath;
            this.worktree = worktree;
        }

        public Repo repo() { return repo; }
        public String name() { return repo.name(); }
        public String remote() { return repo.remote(); }
        public String targetBranch() { return repo.targetBranch(); }
        public Path repoPath() { return repoPath; }
        public Path worktree() { return worktree; }
        public String branch() { return branch; }
        public String baseSha() { return baseSha; }
        public boolean created() { return created; }
        public String mrUrl() { return mrUrl; }
        public boolean mrCreated() { return mrCreated; }
        public String pushedHead() { return pushedHead; }

        void setBranch(String branch) { this.branch = branch; }
        void setBaseSha(String sha) { this.baseSha = sha; }
        void markCreated() { this.created = true; }
        void setPushedHead(String sha) { this.pushedHead = sha; }

        void setMr(String url, boolean created) {
            this.mrUrl = url;
            this.mrCreated = created;
        }
    }

    private final String id;
    private final String profileId;
    private final String profileName;
    /** Thông tin Jira của profile lúc tạo phiên – đổi profile giữa chừng không ảnh hưởng phiên đang chạy. */
    private final JiraSettings jira;
    private final String issueKey;
    private final String projectName;
    private final String projectTitle;
    private final Path taskDir;
    private final List<RepoWork> repos;
    private final List<Path> contextDirs;
    private final String model;
    private final String instruction;
    private final Instant createdAt = Instant.now();
    private final List<String> logs = new ArrayList<>();
    private final List<String> warnings = new CopyOnWriteArrayList<>();
    private final DoubleAdder cost = new DoubleAdder();

    private volatile TaskState state = TaskState.QUEUED;
    private volatile List<Path> attachments = List.of();
    private volatile List<String> skippedAttachments = List.of();
    private volatile IssueDetail issue;
    private volatile String issueSummary;
    private volatile String coderSessionId;
    private volatile String coderSummary;
    private volatile String reviewSummary;
    private volatile String error;
    private volatile boolean cancelRequested;
    private volatile Process process;

    FixTask(String id, String profileId, String profileName, JiraSettings jira, String issueKey, String projectName,
            String projectTitle, Path taskDir, List<RepoWork> repos, List<Path> contextDirs, String model,
            String instruction) {
        this.id = id;
        this.profileId = profileId;
        this.profileName = profileName;
        this.jira = jira;
        this.issueKey = issueKey;
        this.projectName = projectName;
        this.projectTitle = projectTitle;
        this.taskDir = taskDir;
        this.repos = List.copyOf(repos);
        this.contextDirs = List.copyOf(contextDirs);
        this.model = model;
        this.instruction = instruction;
    }

    public void log(String text) {
        String stamp = LocalTime.now().format(CLOCK);
        synchronized (logs) {
            if (logs.size() < MAX_LOG_LINES) {
                logs.add(stamp + "  " + text);
            }
        }
    }

    public LogSlice logsFrom(int from) {
        TaskState s = state;
        synchronized (logs) {
            int start = Math.max(0, Math.min(from, logs.size()));
            return new LogSlice(new ArrayList<>(logs.subList(start, logs.size())), logs.size(), s.name(), s.label(),
                    s.busy());
        }
    }

    void setState(TaskState next) {
        this.state = next;
        log("== " + next.label() + " ==");
    }

    /** Chuyển trạng thái nguyên tử nếu đang ở một trong các trạng thái cho phép. */
    synchronized boolean transition(Set<TaskState> allowedFrom, TaskState next) {
        if (!allowedFrom.contains(state)) {
            return false;
        }
        setState(next);
        return true;
    }

    /** Cảnh báo cần người duyệt chú ý (hiện trên giao diện), đồng thời ghi log. */
    void addWarning(String text) {
        warnings.add(text);
        log("CẢNH BÁO: " + text);
    }

    void setAttachments(List<Path> saved, List<String> skipped) {
        this.attachments = List.copyOf(saved);
        this.skippedAttachments = List.copyOf(skipped);
    }

    void addCost(Double usd) {
        if (usd != null) {
            cost.add(usd);
        }
    }

    /** Thư mục file đính kèm Jira, nằm trong thư mục phiên (ngoài các worktree). */
    public Path attachmentDir() {
        return taskDir.resolve("_jira");
    }

    public Path tmpDir() {
        return taskDir.resolve("_tmp");
    }

    public boolean anyWorktree() {
        return repos.stream().anyMatch(r -> r.created() && r.baseSha() != null);
    }

    public String id() { return id; }
    public String profileId() { return profileId; }
    public String profileName() { return profileName; }
    public JiraSettings jira() { return jira; }
    public String issueKey() { return issueKey; }
    public String projectName() { return projectName; }
    public String projectTitle() { return projectTitle; }
    public Path taskDir() { return taskDir; }
    public List<RepoWork> repos() { return repos; }
    public List<Path> contextDirs() { return contextDirs; }
    public String model() { return model; }
    public String instruction() { return instruction; }
    public Instant createdAt() { return createdAt; }
    public TaskState state() { return state; }
    public IssueDetail issue() { return issue; }
    public String issueSummary() { return issueSummary; }
    public String coderSessionId() { return coderSessionId; }
    public String coderSummary() { return coderSummary; }
    public String reviewSummary() { return reviewSummary; }
    public String error() { return error; }
    public boolean cancelRequested() { return cancelRequested; }
    public Process process() { return process; }
    public double costUsd() { return cost.sum(); }
    /** File đính kèm đã tải về (ảnh/text) – Claude bắt buộc phải mở. */
    public List<Path> attachments() { return attachments; }
    /** Tên file đính kèm không tải/không xem được (Excel, video, quá lớn...). */
    public List<String> skippedAttachments() { return skippedAttachments; }
    public List<String> warnings() { return List.copyOf(warnings); }

    void setIssue(IssueDetail issue) {
        this.issue = issue;
        this.issueSummary = issue.summary();
    }

    void setCoderSessionId(String id) { this.coderSessionId = id; }
    void setCoderSummary(String s) { this.coderSummary = s; }
    void setReviewSummary(String s) { this.reviewSummary = s; }
    void setError(String error) { this.error = error; }
    void setCancelRequested(boolean v) { this.cancelRequested = v; }
    void setProcess(Process p) { this.process = p; }
}
