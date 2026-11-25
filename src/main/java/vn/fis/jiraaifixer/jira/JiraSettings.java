package vn.fis.jiraaifixer.jira;

/**
 * Thông tin kết nối Jira của một profile (đã giải mã, chỉ giữ trong bộ nhớ).
 *
 * @param cookie      cookie phiên dán trên giao diện (ưu tiên hơn Basic auth), có thể null
 * @param moduleField custom field "Module" dùng để phân loại phần mềm, có thể null
 */
public record JiraSettings(String baseUrl, String username, String password, String cookie, String defaultJql,
                           String moduleField) {

    public JiraSettings {
        baseUrl = baseUrl == null ? null : baseUrl.trim().replaceAll("/+$", "");
    }

    public String authMode() {
        if (cookie != null && !cookie.isBlank()) {
            return "cookie";
        }
        return hasBasicAuth() ? "basic" : "none";
    }

    public boolean hasBasicAuth() {
        return username != null && !username.isBlank() && password != null && !password.isBlank();
    }

    @Override
    public String toString() {
        // không in mật khẩu/cookie ra log
        return "JiraSettings[" + baseUrl + ", user=" + username + ", auth=" + authMode() + "]";
    }
}
