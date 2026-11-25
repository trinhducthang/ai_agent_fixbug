package vn.fis.jiraaifixer.jira;

import static vn.fis.jiraaifixer.util.Texts.isBlank;
import static vn.fis.jiraaifixer.util.Texts.text;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.fis.jiraaifixer.jira.JiraModels.Attachment;
import vn.fis.jiraaifixer.jira.JiraModels.Comment;
import vn.fis.jiraaifixer.jira.JiraModels.IssueDetail;
import vn.fis.jiraaifixer.jira.JiraModels.IssueSummary;
import vn.fis.jiraaifixer.jira.JiraModels.JiraUser;
import vn.fis.jiraaifixer.util.Texts;

/**
 * Client REST cho Jira Server (/rest/api/2). Mọi lời gọi nhận {@link JiraSettings} của profile tương ứng,
 * nên có thể làm việc với nhiều Jira khác nhau. Xác thực bằng cookie phiên hoặc Basic auth.
 */
@Component
public class JiraClient {

    private static final Pattern ISSUE_KEY = Pattern.compile("[A-Z][A-Z0-9_]+-\\d+");
    private static final Pattern PROJECT_KEY = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern CUSTOM_FIELD = Pattern.compile("customfield_\\d+");
    private static final Pattern CURL_COOKIE = Pattern.compile("(?:-b|--cookie)\\s+(['\"])(.*?)\\1", Pattern.DOTALL);
    private static final Pattern COOKIE_HEADER = Pattern.compile("(?i)cookie:\\s*([^'\"\\r\\n]+)");
    private static final String SEARCH_FIELDS = "project,summary,status,issuetype,priority,reporter,updated,duedate,"
            + "components,labels";
    private static final String DETAIL_FIELDS = "project,summary,description,environment,status,issuetype,priority,"
            + "reporter,assignee,created,updated,duedate,comment,attachment,components,labels";
    private static final int MAX_RESULTS = 100;

    public record ServerInfo(String baseUrl, String version, String title) {
    }

    public record ProjectInfo(String key, String name, int components) {
    }

    public record FieldInfo(String id, String name) {
    }

    private final ObjectMapper json;
    private final RestClient http = RestClient.builder().build();

    public JiraClient(ObjectMapper json) {
        this.json = json;
    }

    public static void requireKey(String key) {
        if (key == null || !ISSUE_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Mã issue không hợp lệ: " + key);
        }
    }

    public static String browseUrl(JiraSettings s, String key) {
        return s.baseUrl() + "/browse/" + key;
    }

    /** Thông tin server – gọi được cả khi chưa đăng nhập. */
    public ServerInfo serverInfo(JiraSettings s) {
        JsonNode n = getJson(s, "/rest/api/2/serverInfo", Map.of(), false);
        return new ServerInfo(text(n, "baseUrl"), text(n, "version"), text(n, "serverTitle"));
    }

    public JiraUser myself(JiraSettings s) {
        JsonNode n = getJson(s, "/rest/api/2/myself", Map.of(), true);
        return new JiraUser(text(n, "name"), text(n, "displayName"));
    }

    public ProjectInfo project(JiraSettings s, String key) {
        if (key == null || !PROJECT_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Mã project không hợp lệ: " + key);
        }
        JsonNode n = getJson(s, "/rest/api/2/project/" + key, Map.of(), true);
        return new ProjectInfo(text(n, "key"), text(n, "name"), n.path("components").size());
    }

    public List<FieldInfo> fields(JiraSettings s) {
        List<FieldInfo> out = new ArrayList<>();
        for (JsonNode f : getJson(s, "/rest/api/2/field", Map.of(), true)) {
            out.add(new FieldInfo(text(f, "id"), text(f, "name")));
        }
        return out;
    }

    public List<String> statuses(JiraSettings s) {
        List<String> out = new ArrayList<>();
        for (JsonNode st : getJson(s, "/rest/api/2/status", Map.of(), true)) {
            out.add(text(st, "name"));
        }
        return out;
    }

    /** @return null nếu JQL hợp lệ trên Jira này, ngược lại là thông báo lỗi của Jira. */
    public String jqlError(JiraSettings s, String jql) {
        try {
            getJson(s, "/rest/api/2/search", Map.of("jql", jql, "maxResults", "0", "fields", "key"), true);
            return null;
        } catch (JiraException e) {
            return e.getMessage();
        }
    }

    public List<IssueSummary> search(JiraSettings s, String jql) {
        String query = isBlank(jql) ? s.defaultJql() : jql.trim();
        if (isBlank(query)) {
            throw new IllegalArgumentException("Chưa có JQL (cấu hình JQL mặc định trong profile)");
        }
        JsonNode n = getJson(s, "/rest/api/2/search", Map.of(
                "jql", query,
                "maxResults", String.valueOf(MAX_RESULTS),
                "fields", withModule(s, SEARCH_FIELDS)), true);
        List<IssueSummary> out = new ArrayList<>();
        for (JsonNode i : n.path("issues")) {
            JsonNode f = i.path("fields");
            String key = text(i, "key");
            out.add(new IssueSummary(key, text(f, "project", "key"), text(f, "summary"), text(f, "issuetype", "name"),
                    text(f, "status", "name"), text(f, "priority", "name"), text(f, "reporter", "displayName"),
                    text(f, "updated"), text(f, "duedate"), module(s, f), names(f.path("components")),
                    strings(f.path("labels")), browseUrl(s, key)));
        }
        return out;
    }

    public IssueDetail getIssue(JiraSettings s, String key) {
        requireKey(key);
        JsonNode n = getJson(s, "/rest/api/2/issue/" + key, Map.of("fields", withModule(s, DETAIL_FIELDS)), true);
        JsonNode f = n.path("fields");

        List<Comment> comments = new ArrayList<>();
        for (JsonNode c : f.path("comment").path("comments")) {
            comments.add(new Comment(text(c, "author", "displayName"), text(c, "created"), text(c, "body")));
        }
        List<Attachment> attachments = new ArrayList<>();
        for (JsonNode a : f.path("attachment")) {
            attachments.add(new Attachment(text(a, "filename"), text(a, "mimeType"), a.path("size").asLong(0),
                    text(a, "content")));
        }
        return new IssueDetail(text(n, "key"), text(f, "project", "key"), text(f, "summary"),
                text(f, "issuetype", "name"), text(f, "status", "name"), text(f, "priority", "name"),
                text(f, "reporter", "displayName"), text(f, "assignee", "displayName"), text(f, "created"),
                text(f, "updated"), text(f, "duedate"), module(s, f), text(f, "description"), text(f, "environment"),
                names(f.path("components")), strings(f.path("labels")), comments, attachments, browseUrl(s, key));
    }

    /** Tải file đính kèm. Chỉ cho URL thuộc chính Jira đó để không gửi thông tin đăng nhập sang host khác. */
    public byte[] download(JiraSettings s, String url) {
        if (url == null || !url.startsWith(s.baseUrl() + "/")) {
            throw new JiraException(400, "URL đính kèm không thuộc Jira: " + url);
        }
        try {
            return http.get().uri(URI.create(url)).headers(h -> applyAuth(h, s)).accept(MediaType.ALL)
                    .retrieve().body(byte[].class);
        } catch (RestClientResponseException e) {
            throw translate(e);
        }
    }

    private JsonNode getJson(JiraSettings s, String path, Map<String, String> query, boolean requireLogin) {
        if (isBlank(s.baseUrl()) || !s.baseUrl().matches("(?i)https?://.+")) {
            throw new JiraException(400, "Chưa cấu hình địa chỉ Jira hợp lệ (http/https): " + s.baseUrl());
        }
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(s.baseUrl() + path);
        query.keySet().forEach(k -> b.queryParam(k, "{" + k + "}"));
        URI uri = b.encode().buildAndExpand(query).toUri();
        ResponseEntity<String> res;
        try {
            res = http.get().uri(uri).headers(h -> applyAuth(h, s)).accept(MediaType.APPLICATION_JSON)
                    .retrieve().toEntity(String.class);
        } catch (RestClientResponseException e) {
            throw translate(e);
        } catch (ResourceAccessException e) {
            throw new JiraException(502, "Không kết nối được Jira " + s.baseUrl() + ": " + e.getMessage());
        }
        if (requireLogin && "anonymous".equalsIgnoreCase(res.getHeaders().getFirst("X-AUSERNAME"))) {
            throw new JiraException.Auth("Jira coi request là anonymous: chưa đăng nhập hoặc cookie phiên đã hết hạn.");
        }
        String body = res.getBody();
        try {
            return json.readTree(isBlank(body) ? "{}" : body);
        } catch (RuntimeException e) {
            throw new JiraException(502, "Jira trả về dữ liệu không phải JSON (sai địa chỉ Jira?): "
                    + Texts.truncate(Texts.oneLine(body), 200));
        }
    }

    private JiraException translate(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        if (status == 401 || status == 403) {
            return new JiraException.Auth("Jira từ chối xác thực (HTTP " + status
                    + "). Kiểm tra tài khoản/mật khẩu hoặc cookie phiên của profile.");
        }
        String detail = e.getResponseBodyAsString();
        try {
            JsonNode n = json.readTree(detail);
            List<String> msgs = new ArrayList<>();
            n.path("errorMessages").forEach(m -> msgs.add(m.asString()));
            n.path("errors").properties().forEach(en -> msgs.add(en.getKey() + ": " + en.getValue().asString()));
            if (!msgs.isEmpty()) {
                detail = String.join("; ", msgs);
            }
        } catch (RuntimeException ignored) {
            detail = Texts.truncate(Texts.oneLine(detail), 300);
        }
        return new JiraException(status == 404 ? 404 : 502, "Jira trả lỗi HTTP " + status + ": " + detail);
    }

    private static void applyAuth(HttpHeaders headers, JiraSettings s) {
        if (!isBlank(s.cookie())) {
            headers.set(HttpHeaders.COOKIE, s.cookie());
        } else if (s.hasBasicAuth()) {
            headers.setBasicAuth(s.username(), s.password(), StandardCharsets.UTF_8);
        }
    }

    private static String withModule(JiraSettings s, String fields) {
        return validModuleField(s) == null ? fields : fields + "," + s.moduleField();
    }

    private static String validModuleField(JiraSettings s) {
        return s.moduleField() != null && CUSTOM_FIELD.matcher(s.moduleField()).matches() ? s.moduleField() : null;
    }

    /** Field "Module" có thể là chuỗi, option {value} hoặc mảng option tùy plugin. */
    private static String module(JiraSettings s, JsonNode fields) {
        String field = validModuleField(s);
        if (field == null) {
            return null;
        }
        JsonNode m = fields.path(field);
        if (m.isMissingNode() || m.isNull()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (JsonNode v : m.isArray() ? m : List.of(m)) {
            if (v.isValueNode()) {
                parts.add(v.asString());
            } else if (text(v, "value") != null) {
                parts.add(text(v, "value"));
            } else if (text(v, "name") != null) {
                parts.add(text(v, "name"));
            }
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private static List<String> names(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(c -> out.add(text(c, "name")));
        return out;
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(l -> out.add(l.asString()));
        return out;
    }

    /** Nhận cookie dạng thô, header "Cookie: ...", hoặc nguyên lệnh curl có -b '...'. */
    public static String normalizeCookie(String raw) {
        if (isBlank(raw)) {
            return null;
        }
        String s = raw.trim();
        Matcher curl = CURL_COOKIE.matcher(s);
        if (curl.find()) {
            s = curl.group(2);
        } else {
            Matcher header = COOKIE_HEADER.matcher(s);
            if (header.find()) {
                s = header.group(1);
            }
        }
        s = s.trim();
        if (s.isEmpty()) {
            return null;
        }
        return s.contains("=") ? s : "JSESSIONID=" + s;
    }
}
