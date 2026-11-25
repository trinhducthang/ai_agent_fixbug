package vn.fis.jiraaifixer.task;

import static vn.fis.jiraaifixer.task.TaskState.CANCELLED;
import static vn.fis.jiraaifixer.task.TaskState.CHATTING;
import static vn.fis.jiraaifixer.task.TaskState.CODING;
import static vn.fis.jiraaifixer.task.TaskState.DONE;
import static vn.fis.jiraaifixer.task.TaskState.FAILED;
import static vn.fis.jiraaifixer.task.TaskState.PULLING;
import static vn.fis.jiraaifixer.task.TaskState.PUSHING;
import static vn.fis.jiraaifixer.task.TaskState.REVIEWING;
import static vn.fis.jiraaifixer.task.TaskState.WAITING_APPROVAL;
import static vn.fis.jiraaifixer.util.Texts.isBlank;
import static vn.fis.jiraaifixer.util.Texts.oneLine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import vn.fis.jiraaifixer.claude.ClaudeCli;
import vn.fis.jiraaifixer.claude.ClaudeRequest;
import vn.fis.jiraaifixer.claude.ClaudeRunResult;
import vn.fis.jiraaifixer.config.AppProperties;
import vn.fis.jiraaifixer.config.AppProperties.Project;
import vn.fis.jiraaifixer.config.AppProperties.Repo;
import vn.fis.jiraaifixer.git.DirLinks;
import vn.fis.jiraaifixer.git.GitCli;
import vn.fis.jiraaifixer.git.GitException;
import vn.fis.jiraaifixer.git.MergeRequests;
import vn.fis.jiraaifixer.jira.JiraClient;
import vn.fis.jiraaifixer.jira.JiraModels.Attachment;
import vn.fis.jiraaifixer.jira.JiraModels.IssueDetail;
import vn.fis.jiraaifixer.task.FixTask.RepoWork;
import vn.fis.jiraaifixer.util.Texts;

/**
 * Luồng: lấy issue Jira -> xác định phần mềm -> worktree cho mọi repo của phần mềm -> Claude sửa code
 * -> Claude review (phiên độc lập) -> người dùng duyệt diff / chat thêm -> commit, push, tạo MR cho từng repo
 * có thay đổi bằng GitLab push option.
 */
@Service
public class FixTaskService {

    private static final Logger log = LoggerFactory.getLogger(FixTaskService.class);
    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Pattern BRANCH = Pattern.compile("[A-Za-z0-9._/\\-]{1,200}");
    private static final Set<String> IMAGE_OR_TEXT = Set.of("image/png", "image/jpeg", "image/gif", "image/webp",
            "text/plain", "text/csv", "application/json", "application/xml", "text/xml");
    private static final List<String> GIT_READ_COMMANDS = List.of("status", "diff", "log", "show", "blame");
    private static final int MAX_DIFF_CHARS = 3_000_000;

    public record StartRequest(String issueKey, String project, String model, String instruction) {
    }

    public record BatchItem(String issueKey, String project) {
    }

    public record BatchResult(String issueKey, String project, String taskId, String error) {
    }

    private final AppProperties props;
    private final JiraClient jira;
    private final ClaudeCli claude;
    private final GitCli git;
    private final ProjectRouter router;
    private final Path workspaceDir;
    private final Semaphore claudeSlots;
    private final Map<String, FixTask> tasks = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public FixTaskService(AppProperties props, JiraClient jira, ClaudeCli claude, GitCli git, ProjectRouter router) {
        this.props = props;
        this.jira = jira;
        this.claude = claude;
        this.git = git;
        this.router = router;
        this.workspaceDir = realDir(Path.of(props.workspace().dir()));
        this.claudeSlots = new Semaphore(Math.max(1, props.claude().maxParallel()));
    }

    /**
     * Đường dẫn thật (mở rộng tên ngắn 8.3 kiểu THANGT~1): Claude Code chặn ghi file vào đường dẫn
     * chứa mẫu 8.3 vì coi là "suspicious Windows path".
     */
    private static Path realDir(Path dir) {
        try {
            Files.createDirectories(dir);
            return dir.toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException("Không tạo được thư mục workspace " + dir + ": " + e.getMessage(), e);
        }
    }

    @PreDestroy
    void shutdown() {
        tasks.values().forEach(FixTaskService::killProcess);
        executor.shutdownNow();
    }

    public List<FixTask> list() {
        return tasks.values().stream().sorted(Comparator.comparing(FixTask::createdAt).reversed()).toList();
    }

    public FixTask get(String id) {
        FixTask t = tasks.get(id);
        if (t == null) {
            throw new NoSuchElementException("Không có phiên xử lý " + id);
        }
        return t;
    }

    public List<BatchResult> startBatch(List<BatchItem> items, String model, String instruction) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Chưa chọn issue nào");
        }
        List<BatchResult> out = new ArrayList<>();
        for (BatchItem item : items) {
            try {
                FixTask t = start(new StartRequest(item.issueKey(), item.project(), model, instruction));
                out.add(new BatchResult(item.issueKey(), t.projectName(), t.id(), null));
            } catch (RuntimeException e) {
                out.add(new BatchResult(item.issueKey(), item.project(), null, e.getMessage()));
            }
        }
        return out;
    }

    public FixTask start(StartRequest req) {
        JiraClient.requireKey(req.issueKey());
        String model = ClaudeCli.validateModel(req.model());

        IssueDetail issue = null;
        Project project;
        if (isBlank(req.project())) {
            issue = jira.getIssue(req.issueKey());
            ProjectRouter.Route route = router.route(issue);
            if (route.project() == null) {
                throw new IllegalArgumentException("Không tự xác định được phần mềm cho " + req.issueKey() + " ("
                        + route.reason() + "), hãy chọn project bằng tay");
            }
            project = props.project(route.project()).orElseThrow();
        } else {
            project = props.project(req.project())
                    .orElseThrow(() -> new IllegalArgumentException("Project không có trong cấu hình: " + req.project()));
        }
        boolean duplicate = tasks.values().stream().anyMatch(t -> t.issueKey().equals(req.issueKey())
                && t.projectName().equals(project.name()) && t.state().busy());
        if (duplicate) {
            throw new IllegalStateException(req.issueKey() + " đang có phiên chạy trên project " + project.name());
        }

        String id = uniqueId(req.issueKey());
        Path taskDir = workspaceDir.resolve(id);
        List<RepoWork> repos = new ArrayList<>();
        for (Repo r : project.repos()) {
            repos.add(new RepoWork(r, realRepoPath(r), taskDir.resolve(r.name())));
        }
        List<Path> contextDirs = new ArrayList<>();
        for (String d : project.contextDirs()) {
            try {
                contextDirs.add(Path.of(d).toRealPath());
            } catch (IOException e) {
                log.warn("Bỏ qua thư mục tham khảo không tồn tại: {}", d);
            }
        }

        FixTask t = new FixTask(id, req.issueKey(), project.name(), project.title(), taskDir, repos, contextDirs,
                model, req.instruction());
        if (issue != null) {
            t.setIssue(issue);
        }
        tasks.put(id, t);
        t.log("Tạo phiên " + id + " cho " + req.issueKey() + " → " + project.title() + " ("
                + String.join(", ", project.repos().stream().map(r -> r.name() + "→" + r.targetBranch()).toList()) + ")");
        executor.submit(() -> pipeline(t));
        return t;
    }

    public FixTask chat(String id, String message) {
        FixTask t = get(id);
        if (isBlank(message)) {
            throw new IllegalArgumentException("Tin nhắn trống");
        }
        if (!t.anyWorktree()) {
            throw new IllegalStateException("Phiên chưa có worktree để Claude làm việc");
        }
        if (!t.transition(EnumSet.of(WAITING_APPROVAL, DONE, FAILED, CANCELLED), CHATTING)) {
            throw new IllegalStateException("Phiên đang bận (" + t.state().label() + ")");
        }
        t.setCancelRequested(false);
        t.setError(null);
        executor.submit(() -> {
            try {
                t.log("Bạn: " + message.strip());
                boolean resumed = t.coderSessionId() != null;
                ClaudeRunResult r = runClaude(t, Prompts.chat(message, resumed, t), t.coderSessionId());
                if (r.sessionId() != null) {
                    t.setCoderSessionId(r.sessionId());
                }
                if (r.failed()) {
                    t.log("Lượt chat kết thúc với lỗi, kiểm tra log và diff.");
                }
                markNewFiles(t);
            } catch (TaskCancelledException e) {
                t.log("Đã dừng lượt chat.");
            } catch (Exception e) {
                t.log("Lỗi khi chat: " + e.getMessage());
                log.warn("Chat {} lỗi", t.id(), e);
            } finally {
                t.setCancelRequested(false);
                t.setState(WAITING_APPROVAL);
            }
        });
        return t;
    }

    public FixTask push(String id, String commitMessage, String mrTitle) {
        FixTask t = get(id);
        if (isBlank(commitMessage)) {
            throw new IllegalArgumentException("Commit message trống");
        }
        if (!t.transition(EnumSet.of(WAITING_APPROVAL), PUSHING)) {
            throw new IllegalStateException("Chỉ push được khi phiên đang chờ duyệt (hiện tại: " + t.state().label() + ")");
        }
        t.setError(null);
        String title = isBlank(mrTitle) ? oneLine(commitMessage.lines().findFirst().orElse(t.issueKey())) : oneLine(mrTitle);
        executor.submit(() -> doPush(t, commitMessage.strip(), title));
        return t;
    }

    public FixTask cancel(String id) {
        FixTask t = get(id);
        if (!t.state().busy() || t.state() == PUSHING) {
            throw new IllegalStateException("Không hủy được ở trạng thái " + t.state().label());
        }
        t.setCancelRequested(true);
        t.log("Đang hủy...");
        killProcess(t);
        return t;
    }

    /** Gỡ worktree + nhánh local của mọi repo (nhánh/MR đã push lên GitLab vẫn giữ nguyên). */
    public void discard(String id) {
        FixTask t = get(id);
        if (t.state().busy()) {
            throw new IllegalStateException("Phiên đang chạy, hãy Hủy trước khi xóa");
        }
        for (RepoWork r : t.repos()) {
            if (!r.created() || !Files.exists(r.worktree())) {
                continue;
            }
            for (String d : r.repo().sharedDirs()) {
                try {
                    DirLinks.remove(r.worktree().resolve(d));
                } catch (IOException e) {
                    throw new IllegalStateException("Không gỡ được liên kết " + r.name() + "/" + d
                            + ", dừng để tránh xóa nhầm thư mục của repo gốc: " + e.getMessage(), e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Bị ngắt khi gỡ liên kết " + d, e);
                }
            }
            git.runOrThrow(r.repoPath(), GitCli.LOCAL, "worktree", "remove", "--force", r.worktree().toString());
        }
        for (RepoWork r : t.repos()) {
            if (r.branch() != null) {
                git.run(r.repoPath(), GitCli.LOCAL, "branch", "-D", r.branch());
            }
        }
        deleteRecursively(t.attachmentDir());
        deleteRecursively(t.tmpDir());
        try {
            Files.deleteIfExists(t.taskDir());
        } catch (DirectoryNotEmptyException e) {
            log.warn("Thư mục phiên {} còn file lạ (Claude tạo?), giữ lại để bạn tự kiểm tra", t.taskDir());
        } catch (IOException e) {
            log.warn("Không xóa được {}: {}", t.taskDir(), e.getMessage());
        }
        tasks.remove(id);
        log.info("Đã xóa phiên {}", id);
    }

    public String diff(String id) {
        FixTask t = get(id);
        if (!t.anyWorktree()) {
            return "(Chưa có worktree)";
        }
        markNewFiles(t);
        return Texts.truncate(combinedDiff(t), MAX_DIFF_CHARS);
    }

    // ---------------------------------------------------------------- pipeline

    private void pipeline(FixTask t) {
        try {
            t.setState(PULLING);
            IssueDetail issue = t.issue() != null ? t.issue() : jira.getIssue(t.issueKey());
            t.setIssue(issue);
            t.log("Jira: " + issue.key() + " – " + issue.summary() + " (" + issue.comments().size() + " bình luận, "
                    + issue.attachments().size() + " file đính kèm)");
            Files.createDirectories(t.taskDir());
            downloadAttachments(t, issue);
            for (RepoWork r : t.repos()) {
                checkCancelled(t);
                prepareRepo(t, r);
            }
            checkCancelled(t);

            t.setState(CODING);
            ClaudeRunResult fix = runClaude(t, Prompts.fix(issue, t), null);
            t.setCoderSessionId(fix.sessionId());
            t.setCoderSummary(fix.resultText());
            if (fix.failed()) {
                throw new IllegalStateException("Claude không hoàn thành bước sửa code (exit " + fix.exitCode()
                        + "). Có thể chat thêm để tiếp tục hoặc xóa phiên.");
            }
            ClaudeRunResult fixFollowUp = ensureAttachmentsRead(t, fix, false);
            if (fixFollowUp != fix) {
                if (fixFollowUp.sessionId() != null) {
                    t.setCoderSessionId(fixFollowUp.sessionId());
                }
                if (!isBlank(fixFollowUp.resultText())) {
                    t.setCoderSummary(fixFollowUp.resultText());
                }
            }

            boolean changed = markNewFiles(t);
            if (!changed) {
                t.log("Claude chưa thay đổi file nào – xem tóm tắt (có thể issue thiếu thông tin), chat thêm nếu cần.");
            } else if (props.claude().reviewEnabled()) {
                t.setState(REVIEWING);
                ClaudeRunResult review = runClaude(t, Prompts.review(issue, t, combinedDiff(t)), null);
                t.setReviewSummary(review.resultText());
                if (review.failed()) {
                    t.log("Bước review kết thúc với lỗi – vẫn chuyển sang chờ duyệt, hãy đọc diff kỹ.");
                } else {
                    ClaudeRunResult reviewFollowUp = ensureAttachmentsRead(t, review, true);
                    if (reviewFollowUp != review && !isBlank(reviewFollowUp.resultText())) {
                        t.setReviewSummary(reviewFollowUp.resultText());
                    }
                }
                markNewFiles(t);
            }
            t.setState(WAITING_APPROVAL);
            t.log("Xem tab Diff, chat thêm nếu cần, rồi bấm \"Commit, push & tạo MR\".");
        } catch (TaskCancelledException e) {
            t.setState(CANCELLED);
        } catch (Exception e) {
            fail(t, e);
        } finally {
            t.setCancelRequested(false);
        }
    }

    private void prepareRepo(FixTask t, RepoWork r) {
        t.log("[" + r.name() + "] git fetch " + r.remote() + " " + r.targetBranch());
        git.runOrThrow(r.repoPath(), GitCli.NETWORK, "fetch", r.remote(), r.targetBranch());
        String branch = resolveBranchName(t, r);
        r.setBranch(branch);
        t.log("[" + r.name() + "] tạo worktree nhánh " + branch + " từ " + r.remote() + "/" + r.targetBranch());
        git.runOrThrow(r.repoPath(), GitCli.LOCAL, "worktree", "add", "-b", branch, r.worktree().toString(),
                r.remote() + "/" + r.targetBranch());
        r.markCreated();
        r.setBaseSha(git.runOrThrow(r.worktree(), GitCli.LOCAL, "rev-parse", "HEAD").strip());
        linkSharedDirs(t, r);
    }

    private void doPush(FixTask t, String commitMessage, String mrTitle) {
        List<String> errors = new ArrayList<>();
        int pushed = 0;
        for (RepoWork r : t.repos()) {
            if (!r.created() || r.baseSha() == null) {
                continue;
            }
            try {
                if (pushRepo(t, r, commitMessage, mrTitle)) {
                    pushed++;
                }
            } catch (Exception e) {
                errors.add(r.name() + ": " + e.getMessage());
                t.log("[" + r.name() + "] Lỗi: " + e.getMessage());
                log.warn("Push {}/{} lỗi", t.id(), r.name(), e);
            }
        }
        boolean anyMr = t.repos().stream().anyMatch(r -> r.mrUrl() != null);
        if (!errors.isEmpty()) {
            t.setError(String.join("\n", errors));
            t.setState(WAITING_APPROVAL);
        } else if (pushed == 0 && !anyMr) {
            t.setError("Không có thay đổi nào để push.");
            t.setState(WAITING_APPROVAL);
        } else {
            t.setState(DONE);
        }
    }

    /** @return true nếu đã push lần này; false nếu repo không có gì mới để push. */
    private boolean pushRepo(FixTask t, RepoWork r, String commitMessage, String mrTitle) throws IOException {
        Path wt = r.worktree();
        stage(r, "-A");
        boolean staged = !git.run(wt, GitCli.LOCAL, "diff", "--cached", "--quiet").ok();
        if (staged) {
            Files.createDirectories(t.tmpDir());
            Path msgFile = t.tmpDir().resolve("commit-" + r.name() + ".txt");
            Files.writeString(msgFile, commitMessage + "\n", StandardCharsets.UTF_8);
            t.log("[" + r.name() + "] " + git.runOrThrow(wt, GitCli.LOCAL, "commit", "-F", msgFile.toString()).strip());
        }
        int ahead = Integer.parseInt(
                git.runOrThrow(wt, GitCli.LOCAL, "rev-list", "--count", r.baseSha() + "..HEAD").strip());
        if (ahead == 0) {
            t.log("[" + r.name() + "] không có thay đổi, bỏ qua.");
            return false;
        }
        String head = git.runOrThrow(wt, GitCli.LOCAL, "rev-parse", "HEAD").strip();
        if (head.equals(r.pushedHead())) {
            t.log("[" + r.name() + "] đã push commit này trước đó, bỏ qua.");
            return false;
        }

        List<String> args = List.of("push", "-u", r.remote(), r.branch(),
                "-o", "merge_request.create",
                "-o", "merge_request.target=" + r.targetBranch(),
                "-o", "merge_request.title=" + mrTitle,
                "-o", "merge_request.description=Jira: " + jira.browseUrl(t.issueKey()));
        t.log("[" + r.name() + "] git push " + r.remote() + " " + r.branch() + " (tạo MR → " + r.targetBranch() + ")");
        GitCli.Result res = git.run(wt, GitCli.NETWORK, args);
        t.log(res.output().strip());
        if (!res.ok()) {
            throw new IllegalStateException("git push lỗi (exit " + res.exitCode() + ")");
        }
        r.setPushedHead(head);

        Optional<String> mr = MergeRequests.findMrUrl(res.output());
        if (mr.isPresent()) {
            r.setMr(mr.get(), true);
            t.log("[" + r.name() + "] Merge request: " + mr.get());
        } else {
            String remoteUrl = git.runOrThrow(wt, GitCli.LOCAL, "remote", "get-url", r.remote()).strip();
            String manual = MergeRequests.newMrUrl(remoteUrl, r.branch(), r.targetBranch());
            r.setMr(manual, false);
            t.log("[" + r.name() + "] GitLab không trả link MR (push option có thể bị tắt). Tạo MR thủ công: " + manual);
        }
        return true;
    }

    // ---------------------------------------------------------------- helpers

    private ClaudeRunResult runClaude(FixTask t, String prompt, String resumeSessionId)
            throws IOException, InterruptedException {
        if (!claudeSlots.tryAcquire()) {
            t.log("Đang chờ lượt (tối đa " + props.claude().maxParallel() + " phiên Claude chạy cùng lúc)...");
            claudeSlots.acquire();
        }
        try {
            checkCancelled(t);
            ClaudeRunResult r = claude.run(new ClaudeRequest(t.taskDir(), prompt, resumeSessionId, t.model(),
                    t.contextDirs(), gitReadRules(t)), t::log, t::setProcess);
            t.addCost(r.costUsd());
            checkCancelled(t);
            return r;
        } finally {
            t.setProcess(null);
            claudeSlots.release();
        }
    }

    /** Cho phép Claude chạy lệnh git CHỈ ĐỌC trên từng repo: git -C be diff..., không có commit/push. */
    private static List<String> gitReadRules(FixTask t) {
        List<String> rules = new ArrayList<>();
        for (RepoWork r : t.repos()) {
            for (String cmd : GIT_READ_COMMANDS) {
                rules.add("Bash(git -C " + r.name() + " " + cmd + ":*)");
                rules.add("PowerShell(git -C " + r.name() + " " + cmd + ":*)");
            }
        }
        return rules;
    }

    private String combinedDiff(FixTask t) {
        StringBuilder sb = new StringBuilder();
        for (RepoWork r : t.repos()) {
            if (!r.created() || r.baseSha() == null) {
                continue;
            }
            sb.append("===== ").append(r.name()).append("/ (").append(r.branch()).append(" → ")
              .append(r.targetBranch()).append(") =====\n");
            String stat = git.runOrThrow(r.worktree(), GitCli.LOCAL, withPathspec(r, "diff", "--stat", r.baseSha()));
            String patch = git.runOrThrow(r.worktree(), GitCli.LOCAL, withPathspec(r, "diff", r.baseSha()));
            if (stat.isBlank() && patch.isBlank()) {
                sb.append("(không thay đổi)\n\n");
            } else {
                sb.append(stat).append('\n').append(patch).append('\n');
            }
        }
        return sb.toString();
    }

    private void downloadAttachments(FixTask t, IssueDetail issue) {
        List<Path> saved = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        long maxBytes = (long) props.jira().maxAttachmentMb() * 1024 * 1024;
        for (Attachment a : issue.attachments()) {
            String mime = a.mimeType() == null ? "" : a.mimeType().toLowerCase(Locale.ROOT);
            if (saved.size() >= props.jira().maxAttachments()) {
                skipped.add(a.filename() + " (vượt giới hạn " + props.jira().maxAttachments() + " file)");
                continue;
            }
            if (!IMAGE_OR_TEXT.contains(mime) || a.size() > maxBytes) {
                skipped.add(a.filename() + " (" + mime + ", " + a.size() / 1024 + " KB)");
                continue;
            }
            try {
                byte[] data = jira.download(a.contentUrl());
                Files.createDirectories(t.attachmentDir());
                Path file = t.attachmentDir().resolve(safeFileName(saved.size(), a.filename()));
                Files.write(file, data);
                saved.add(file);
            } catch (Exception e) {
                skipped.add(a.filename() + " (tải lỗi: " + e.getMessage() + ")");
            }
        }
        t.setAttachments(saved, skipped);
        if (!saved.isEmpty()) {
            t.log("Đã tải " + saved.size() + " file đính kèm vào " + t.attachmentDir() + ": "
                    + String.join(", ", saved.stream().map(p -> p.getFileName().toString()).toList()));
        }
        if (!skipped.isEmpty()) {
            t.addWarning("Claude không xem được " + skipped.size() + " file đính kèm: " + String.join("; ", skipped)
                    + ". Hãy tự mở trên Jira nếu chúng quan trọng.");
        }
    }

    /**
     * Bắt buộc Claude đã mở mọi file đính kèm trong lượt vừa chạy (theo các lệnh Read thực tế trong stream).
     * Thiếu thì nhắc lại một lần trong cùng phiên; vẫn thiếu thì ghi cảnh báo cho người duyệt.
     *
     * @return kết quả lượt nhắc (nếu có), ngược lại trả lại {@code r}
     */
    private ClaudeRunResult ensureAttachmentsRead(FixTask t, ClaudeRunResult r, boolean reviewer)
            throws IOException, InterruptedException {
        List<Path> files = t.attachments();
        if (files.isEmpty()) {
            return r;
        }
        String who = reviewer ? "Reviewer" : "Claude";
        List<Path> missing = unread(files, r.readPaths());
        if (missing.isEmpty()) {
            t.log(who + " đã mở đủ " + files.size() + " file đính kèm.");
            return r;
        }
        if (r.sessionId() == null) {
            t.addWarning(who + " chưa mở file đính kèm: " + fileNames(missing) + " – hãy tự xem trên Jira trước khi duyệt.");
            return r;
        }
        t.log(who + " mới mở " + (files.size() - missing.size()) + "/" + files.size() + " file đính kèm, còn thiếu "
                + fileNames(missing) + " – yêu cầu mở và đối chiếu lại.");
        ClaudeRunResult again = runClaude(t, Prompts.readAttachments(t, missing, reviewer), r.sessionId());
        List<Path> still = unread(missing, again.readPaths());
        if (still.isEmpty()) {
            t.log(who + " đã mở đủ file đính kèm sau khi được nhắc.");
        } else {
            t.addWarning(who + " vẫn chưa mở file đính kèm: " + fileNames(still)
                    + " – hãy tự xem trên Jira trước khi duyệt.");
        }
        return again;
    }

    /** File đính kèm chưa được Read (so theo tên file; tên đã có tiền tố số thứ tự nên duy nhất trong phiên). */
    static List<Path> unread(List<Path> files, List<String> readPaths) {
        Set<String> read = new HashSet<>();
        for (String p : readPaths) {
            String s = p.replace('\\', '/');
            read.add(s.substring(s.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT));
        }
        return files.stream()
                .filter(f -> !read.contains(f.getFileName().toString().toLowerCase(Locale.ROOT)))
                .toList();
    }

    private static String fileNames(List<Path> files) {
        return String.join(", ", files.stream().map(p -> p.getFileName().toString()).toList());
    }

    private String resolveBranchName(FixTask t, RepoWork r) {
        String base = props.workspace().branchPattern().replace("{key}", t.issueKey());
        requireBranchName(base);
        boolean exists = git.run(r.repoPath(), GitCli.LOCAL, "rev-parse", "--verify", "--quiet", "refs/heads/" + base).ok()
                || git.run(r.repoPath(), GitCli.NETWORK, "ls-remote", "--exit-code", "--heads", r.remote(), base).ok();
        if (!exists) {
            return base;
        }
        String unique = base + "-" + LocalDateTime.now().format(ID_TIME);
        t.log("[" + r.name() + "] nhánh " + base + " đã tồn tại, dùng " + unique);
        return unique;
    }

    private void linkSharedDirs(FixTask t, RepoWork r) {
        for (String d : r.repo().sharedDirs()) {
            Path src = r.repoPath().resolve(d);
            Path dst = r.worktree().resolve(d);
            if (!Files.isDirectory(src) || Files.exists(dst, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isDirectory(dst.getParent())) {
                continue;
            }
            try {
                DirLinks.create(dst, src);
                t.log("[" + r.name() + "] liên kết " + d + " → " + src);
            } catch (IOException e) {
                t.log("[" + r.name() + "] không liên kết được " + d + ": " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Bị ngắt khi liên kết " + d, e);
            }
        }
    }

    /** Đánh dấu intent-to-add cho file mới để diff thấy được; trả true nếu có repo nào thay đổi. */
    private boolean markNewFiles(FixTask t) {
        boolean any = false;
        for (RepoWork r : t.repos()) {
            if (!r.created() || r.baseSha() == null) {
                continue;
            }
            try {
                stage(r, "-N");
            } catch (GitException e) {
                // vd: index.lock khi Claude đang chạy git cùng lúc – diff vẫn hiển thị file đã track
                t.log("[" + r.name() + "] không đánh dấu được file mới cho diff: " + Texts.truncate(e.getMessage(), 300));
            }
            String status = git.runOrThrow(r.worktree(), GitCli.LOCAL, withPathspec(r, "status", "--porcelain"));
            String ahead = git.run(r.worktree(), GitCli.LOCAL, "rev-list", "--count", r.baseSha() + "..HEAD")
                    .output().strip();
            if (!status.isBlank() || !"0".equals(ahead)) {
                any = true;
            }
        }
        return any;
    }

    /**
     * git add (-A hoặc -N) toàn worktree rồi bỏ stage các thư mục liên kết (node_modules...) để chúng không bao giờ
     * vào commit. Không dùng pathspec :(exclude) với add vì git báo lỗi khi thư mục đó đã nằm trong .gitignore.
     */
    private void stage(RepoWork r, String mode) {
        git.runOrThrow(r.worktree(), GitCli.LOCAL, "add", mode, "--", ".");
        for (String d : r.repo().sharedDirs()) {
            git.run(r.worktree(), GitCli.LOCAL, "reset", "-q", "--", d);
        }
    }

    /** Pathspec loại trừ các thư mục liên kết khỏi status/diff. */
    private static List<String> withPathspec(RepoWork r, String... args) {
        List<String> all = new ArrayList<>(List.of(args));
        all.add("--");
        all.add(".");
        r.repo().sharedDirs().forEach(d -> all.add(":(exclude)" + d));
        return all;
    }

    private Path realRepoPath(Repo r) {
        Path p;
        try {
            p = Path.of(r.path()).toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("Không tìm thấy thư mục repo " + r.name() + ": " + r.path());
        }
        if (!Files.exists(p.resolve(".git"))) {
            throw new IllegalArgumentException("Không phải git repo: " + p);
        }
        requireBranchName(r.targetBranch());
        return p;
    }

    private String uniqueId(String issueKey) {
        String base = issueKey + "-" + LocalDateTime.now().format(ID_TIME);
        String id = base;
        for (int i = 2; tasks.containsKey(id) || Files.exists(workspaceDir.resolve(id)); i++) {
            id = base + "-" + i;
        }
        return id;
    }

    private static void checkCancelled(FixTask t) {
        if (t.cancelRequested()) {
            throw new TaskCancelledException();
        }
    }

    private static void killProcess(FixTask t) {
        Process p = t.process();
        if (p != null) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
        }
    }

    private void fail(FixTask t, Exception e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        t.setError(msg);
        t.log("Lỗi: " + msg);
        t.setState(FAILED);
        log.warn("Phiên {} lỗi", t.id(), e);
    }

    private static void requireBranchName(String name) {
        if (name == null || !BRANCH.matcher(name).matches() || name.contains("..") || name.startsWith("-")) {
            throw new IllegalArgumentException("Tên nhánh không hợp lệ: " + name);
        }
    }

    private static String safeFileName(int index, String name) {
        String clean = (name == null ? "file" : name).replaceAll("[^A-Za-z0-9._-]", "_");
        return String.format("%02d-%s", index + 1, clean);
    }

    /** Chỉ dùng cho thư mục app tự tạo (_jira, _tmp) – không chứa junction. */
    private static void deleteRecursively(Path p) {
        if (p == null || !Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(p)) {
            walk.sorted(Comparator.reverseOrder()).forEach(x -> {
                try {
                    Files.deleteIfExists(x);
                } catch (IOException e) {
                    log.warn("Không xóa được {}: {}", x, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Không xóa được {}: {}", p, e.getMessage());
        }
    }

    static final class TaskCancelledException extends RuntimeException {
        TaskCancelledException() {
            super("Đã hủy");
        }
    }
}
