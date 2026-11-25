package vn.fis.jiraaifixer.web;

import java.util.List;
import java.util.Set;

import vn.fis.jiraaifixer.task.FixTask;
import vn.fis.jiraaifixer.task.TaskState;

public record TaskView(String id, String issueKey, String issueSummary, String project, String projectTitle,
                       String taskDir, List<RepoView> repos, String model, String state, String stateLabel,
                       boolean busy, String error, List<String> warnings, List<String> attachments,
                       List<String> skippedAttachments, String coderSummary, String reviewSummary, double costUsd,
                       String createdAt, boolean canChat, boolean canPush, boolean canCancel, boolean canDiscard) {

    public record RepoView(String name, String branch, String targetBranch, String worktree, boolean created,
                           String mrUrl, boolean mrCreated, boolean pushed) {
    }

    private static final Set<TaskState> CHATTABLE = Set.of(TaskState.WAITING_APPROVAL, TaskState.DONE,
            TaskState.FAILED, TaskState.CANCELLED);

    public static TaskView of(FixTask t) {
        TaskState s = t.state();
        List<RepoView> repos = t.repos().stream()
                .map(r -> new RepoView(r.name(), r.branch(), r.targetBranch(), r.worktree().toString(), r.created(),
                        r.mrUrl(), r.mrCreated(), r.pushedHead() != null))
                .toList();
        return new TaskView(t.id(), t.issueKey(), t.issueSummary(), t.projectName(), t.projectTitle(),
                t.taskDir().toString(), repos, t.model(), s.name(), s.label(), s.busy(), t.error(), t.warnings(),
                t.attachments().stream().map(p -> p.getFileName().toString()).toList(), t.skippedAttachments(),
                t.coderSummary(),
                t.reviewSummary(), t.costUsd(), t.createdAt().toString(),
                t.anyWorktree() && CHATTABLE.contains(s), s == TaskState.WAITING_APPROVAL,
                s.busy() && s != TaskState.PUSHING, !s.busy());
    }
}
