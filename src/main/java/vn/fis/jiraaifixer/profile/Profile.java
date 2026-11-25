package vn.fis.jiraaifixer.profile;

import java.util.List;
import java.util.Optional;

import vn.fis.jiraaifixer.config.AppProperties.Project;

/**
 * Một cấu hình dự án: Jira (link + tài khoản) và các phần mềm/repo source code tương ứng.
 * Chuyển dự án = chuyển profile đang dùng.
 *
 * @param jiraPassword mật khẩu Jira ĐÃ MÃ HÓA (xem {@link SecretBox}); không bao giờ trả ra API
 * @param sourceRoot   thư mục local chứa code (tìm bản clone sẵn có / nơi clone repo mới)
 * @param sourceLinks  các link source người dùng đã dán (để phân tích lại)
 */
public record Profile(String id, String name, String jiraBaseUrl, String jiraUsername, String jiraPassword,
                      String defaultJql, String moduleField, String sourceRoot, List<String> sourceLinks,
                      List<String> jiraProjectKeys, List<Project> projects, String updatedAt) {

    public Profile {
        sourceLinks = sourceLinks == null ? List.of() : List.copyOf(sourceLinks);
        jiraProjectKeys = jiraProjectKeys == null ? List.of() : List.copyOf(jiraProjectKeys);
        projects = projects == null ? List.of() : List.copyOf(projects);
    }

    public Optional<Project> project(String name) {
        return projects.stream().filter(p -> p.name().equals(name)).findFirst();
    }
}
