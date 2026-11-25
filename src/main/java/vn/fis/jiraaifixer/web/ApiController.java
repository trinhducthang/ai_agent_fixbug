package vn.fis.jiraaifixer.web;

import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import vn.fis.jiraaifixer.claude.ClaudeCli;
import vn.fis.jiraaifixer.config.AppProperties;
import vn.fis.jiraaifixer.jira.JiraClient;
import vn.fis.jiraaifixer.jira.JiraModels.IssueDetail;
import vn.fis.jiraaifixer.jira.JiraModels.IssueSummary;
import vn.fis.jiraaifixer.jira.JiraModels.JiraUser;
import vn.fis.jiraaifixer.task.FixTask.LogSlice;
import vn.fis.jiraaifixer.task.FixTaskService;
import vn.fis.jiraaifixer.task.FixTaskService.BatchItem;
import vn.fis.jiraaifixer.task.FixTaskService.BatchResult;
import vn.fis.jiraaifixer.task.FixTaskService.StartRequest;
import vn.fis.jiraaifixer.task.ProjectRouter;
import vn.fis.jiraaifixer.task.ProjectRouter.Route;
import vn.fis.jiraaifixer.util.Texts;

@RestController
@RequestMapping("/api")
public class ApiController {

    public record RepoConfigView(String name, String path, String targetBranch) {
    }

    public record ProjectView(String name, String title, List<RepoConfigView> repos) {
    }

    public record ConfigView(String jiraBaseUrl, String defaultJql, List<ProjectView> projects, String claudeCli,
                             String defaultModel, boolean reviewEnabled, double maxBudgetUsd) {
    }

    /** Issue kèm phần mềm được tự xác định ({@code route} null = không xác định). */
    public record IssueView(String key, String projectKey, String summary, String type, String status,
                            String priority, String reporter, String updated, String dueDate, String module,
                            List<String> components, String route, String routeReason, String url) {
    }

    public record IssueDetailView(IssueDetail issue, String route, String routeReason) {
    }

    public record CookieRequest(String cookie) {
    }

    public record ChatRequest(String message) {
    }

    public record PushRequest(String commitMessage, String mrTitle) {
    }

    public record BatchRequest(List<BatchItem> items, String model, String instruction) {
    }

    private final AppProperties props;
    private final JiraClient jira;
    private final ClaudeCli claude;
    private final FixTaskService tasks;
    private final ProjectRouter router;

    public ApiController(AppProperties props, JiraClient jira, ClaudeCli claude, FixTaskService tasks,
                         ProjectRouter router) {
        this.props = props;
        this.jira = jira;
        this.claude = claude;
        this.tasks = tasks;
        this.router = router;
    }

    @GetMapping("/config")
    public ConfigView config() {
        List<ProjectView> projects = props.projects().stream()
                .map(p -> new ProjectView(p.name(), p.title(), p.repos().stream()
                        .map(r -> new RepoConfigView(r.name(), r.path(), r.targetBranch())).toList()))
                .toList();
        String cli = claude.locate().map(Object::toString).orElse(null);
        return new ConfigView(props.jira().baseUrl(), props.jira().defaultJql(), projects, cli,
                Texts.nz(props.claude().model()), props.claude().reviewEnabled(), props.claude().maxBudgetUsd());
    }

    @GetMapping("/jira/me")
    public Map<String, String> me() {
        JiraUser u = jira.myself();
        return Map.of("name", Texts.nz(u.name()), "displayName", Texts.nz(u.displayName()), "authMode", jira.authMode());
    }

    @PostMapping("/jira/cookie")
    public Map<String, String> updateCookie(@RequestBody CookieRequest req) {
        jira.updateCookie(req.cookie());
        return me();
    }

    @GetMapping("/issues")
    public List<IssueView> issues(@RequestParam(required = false) String jql) {
        return jira.search(jql).stream().map(this::view).toList();
    }

    @GetMapping("/issues/{key}")
    public IssueDetailView issue(@PathVariable String key) {
        IssueDetail d = jira.getIssue(key);
        Route r = router.route(d);
        return new IssueDetailView(d, r.project(), r.reason());
    }

    @GetMapping("/tasks")
    public List<TaskView> listTasks() {
        return tasks.list().stream().map(TaskView::of).toList();
    }

    @PostMapping("/tasks")
    public TaskView start(@RequestBody StartRequest req) {
        return TaskView.of(tasks.start(req));
    }

    @PostMapping("/tasks/batch")
    public List<BatchResult> startBatch(@RequestBody BatchRequest req) {
        return tasks.startBatch(req.items(), req.model(), req.instruction());
    }

    @GetMapping("/tasks/{id}")
    public TaskView task(@PathVariable String id) {
        return TaskView.of(tasks.get(id));
    }

    @GetMapping("/tasks/{id}/logs")
    public LogSlice logs(@PathVariable String id, @RequestParam(defaultValue = "0") int from) {
        return tasks.get(id).logsFrom(from);
    }

    @GetMapping(value = "/tasks/{id}/diff", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public String diff(@PathVariable String id) {
        return tasks.diff(id);
    }

    @PostMapping("/tasks/{id}/chat")
    public TaskView chat(@PathVariable String id, @RequestBody ChatRequest req) {
        return TaskView.of(tasks.chat(id, req.message()));
    }

    @PostMapping("/tasks/{id}/push")
    public TaskView push(@PathVariable String id, @RequestBody PushRequest req) {
        return TaskView.of(tasks.push(id, req.commitMessage(), req.mrTitle()));
    }

    @PostMapping("/tasks/{id}/cancel")
    public TaskView cancel(@PathVariable String id) {
        return TaskView.of(tasks.cancel(id));
    }

    @DeleteMapping("/tasks/{id}")
    public Map<String, String> discard(@PathVariable String id) {
        tasks.discard(id);
        return Map.of("message", "Đã xóa worktree và nhánh local của phiên " + id);
    }

    private IssueView view(IssueSummary i) {
        Route r = router.route(i);
        return new IssueView(i.key(), i.projectKey(), i.summary(), i.type(), i.status(), i.priority(), i.reporter(),
                i.updated(), i.dueDate(), i.module(), i.components(), r.project(), r.reason(), i.url());
    }
}
