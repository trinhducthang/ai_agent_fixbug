package vn.fis.jiraaifixer.task;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import vn.fis.jiraaifixer.config.AppProperties;
import vn.fis.jiraaifixer.config.AppProperties.Project;
import vn.fis.jiraaifixer.jira.JiraModels.IssueDetail;
import vn.fis.jiraaifixer.jira.JiraModels.IssueSummary;

/**
 * Xác định issue thuộc phần mềm (project) nào:
 * 1. Jira project key chỉ gắn với một phần mềm → chọn ngay.
 * 2. Chấm điểm từ khóa: Module = 3 điểm, tiêu đề = 2 điểm, Component/Label = 1 điểm.
 *    Component ở Jira này hay được dùng như mục chi phí/sản phẩm bàn giao nên kém tin cậy hơn tiêu đề.
 *    Điểm cao nhất thắng; hòa hoặc 0 điểm → không xác định (người dùng chọn tay).
 */
@Component
public class ProjectRouter {

    public record Route(String project, String reason) {
        static Route none(String reason) {
            return new Route(null, reason);
        }
    }

    private final List<Project> projects;

    public ProjectRouter(AppProperties props) {
        this.projects = props.projects();
    }

    public Route route(IssueSummary i) {
        return route(i.projectKey(), i.module(), i.components(), i.labels(), i.summary());
    }

    public Route route(IssueDetail i) {
        return route(i.projectKey(), i.module(), i.components(), i.labels(), i.summary());
    }

    public Route route(String jiraProjectKey, String module, List<String> components, List<String> labels,
                       String summary) {
        List<Project> candidates = projects.stream()
                .filter(p -> p.jiraProjects().stream().anyMatch(k -> k.equalsIgnoreCase(jiraProjectKey)))
                .toList();
        if (candidates.size() == 1) {
            return new Route(candidates.getFirst().name(), "Jira project " + jiraProjectKey);
        }
        if (candidates.isEmpty()) {
            candidates = projects;
        }

        List<String> otherParts = new ArrayList<>(components == null ? List.of() : components);
        otherParts.addAll(labels == null ? List.of() : labels);
        String moduleText = norm(module);
        String summaryText = norm(summary);
        String otherText = norm(String.join(" | ", otherParts));

        String best = null;
        String bestReason = null;
        int bestScore = 0;
        boolean tie = false;
        for (Project p : candidates) {
            int score = 0;
            int reasonWeight = 0;
            String reason = null;
            for (String kw : p.keywords()) {
                String k = norm(kw);
                if (k.isBlank()) {
                    continue;
                }
                int w = 0;
                String where = null;
                if (moduleText.contains(k)) {
                    w = 3;
                    where = "Module";
                } else if (summaryText.contains(k)) {
                    w = 2;
                    where = "tiêu đề";
                } else if (otherText.contains(k)) {
                    w = 1;
                    where = "Component/Label";
                }
                score += w;
                if (w > reasonWeight) {
                    reasonWeight = w;
                    reason = "\"" + kw + "\" trong " + where;
                }
            }
            if (score > bestScore) {
                best = p.name();
                bestReason = reason;
                bestScore = score;
                tie = false;
            } else if (score > 0 && score == bestScore) {
                tie = true;
            }
        }
        if (best == null) {
            return Route.none("Không khớp từ khóa nào");
        }
        if (tie) {
            return Route.none("Khớp nhiều project với cùng điểm");
        }
        return new Route(best, bestReason);
    }

    /** Chữ thường, NFC, bỏ dấu câu, gộp khoảng trắng: "Kiểm tra, rà soát" -> " kiểm tra rà soát ". */
    static String norm(String s) {
        if (s == null) {
            return " ";
        }
        String n = Normalizer.normalize(s, Normalizer.Form.NFC).toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        return " " + n + " ";
    }
}
