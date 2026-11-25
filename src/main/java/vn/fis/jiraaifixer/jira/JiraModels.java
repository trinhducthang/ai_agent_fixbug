package vn.fis.jiraaifixer.jira;

import java.util.List;

public final class JiraModels {

    private JiraModels() {
    }

    public record JiraUser(String name, String displayName) {
    }

    public record IssueSummary(String key, String projectKey, String summary, String type, String status,
                               String priority, String reporter, String updated, String dueDate, String module,
                               List<String> components, List<String> labels, String url) {
    }

    public record IssueDetail(String key, String projectKey, String summary, String type, String status,
                              String priority, String reporter, String assignee, String created, String updated,
                              String dueDate, String module, String description, String environment,
                              List<String> components, List<String> labels, List<Comment> comments,
                              List<Attachment> attachments, String url) {
    }

    public record Comment(String author, String created, String body) {
    }

    public record Attachment(String filename, String mimeType, long size, String contentUrl) {
    }
}
