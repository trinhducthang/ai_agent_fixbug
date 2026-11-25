package vn.fis.jiraaifixer.jira;

import static vn.fis.jiraaifixer.util.Texts.isBlank;
import static vn.fis.jiraaifixer.util.Texts.text;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.fis.jiraaifixer.config.AppProperties;
import vn.fis.jiraaifixer.jira.JiraModels.Attachment;
import vn.fis.jiraaifixer.jira.JiraModels.Comment;
import vn.fis.jiraaifixer.jira.JiraModels.IssueDetail;
import vn.fis.jiraaifixer.jira.JiraModels.IssueSummary;
import vn.fis.jiraaifixer.jira.JiraModels.JiraUser;
import vn.fis.jiraaifixer.util.Texts;

/** Client REST cho Jira Server 6.4 (/rest/api/2). Không có PAT nên dùng cookie phiên hoặc Basic auth. */
@Component
public class JiraClient {

    private static final Pattern ISSUE_KEY = Pattern.compile("[A-Z][A-Z0-9_]+-\\d+");
    private static final Pattern CURL_COOKIE = Pattern.compile("(?:-b|--cookie)\\s+(['\"])(.*?)\\1", Pattern.DOTALL);
    private static final Pattern COOKIE_HEADER = Pattern.compile("(?i)cookie:\\s*([^'\"\\r\\n]+)");
    private static final Pattern CUSTOM_FIELD = Pattern.compile("customfield_\\d+");
    private static final String SEARCH_FIELDS = "project,summary,status,issuetype,priority,reporter,updated,duedate,"
            + "components,labels";
    private static final String DETAIL_FIELDS = "project,summary,description,environment,status,issuetype,priority,"
            + "reporter,assignee,created,updated,duedate,comment,attachment,components,labels";

    private final AppProperties.Jira cfg;
    private final ObjectMapper json;
    private final RestClient http;
    private final AtomicReference<String> sessionCookie;
    private final String moduleField;

    public JiraClient(AppProperties props, ObjectMapper json) {
        this.cfg = props.jira();
        this.json = json;
        this.moduleField = cfg.moduleField() != null && CUSTOM_FIELD.matcher(cfg.moduleField()).matches()
                ? cfg.moduleField() : null;
        this.sessionCookie = new AtomicReference<>(normalizeCookie(cfg.cookie()));
        this.http = RestClient.builder()
                .baseUrl(cfg.baseUrl())
                .requestInterceptor((request, body, execution) -> {
                    applyAuth(request.getHeaders());
                    return execution.execute(request, body);
                })
                .build();
    }

    public static void requireKey(String key) {
        if (key == null || !ISSUE_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Mã issue không hợp lệ: " + key);
        }
    }

    public String authMode() {
        if (sessionCookie.get() != null) {
            return "cookie";
        }
        return hasBasicAuth() ? "basic" : "none";
    }

    public void updateCookie(String raw) {
        sessionCookie.set(normalizeCookie(raw));
    }

    public String browseUrl(String key) {
        return cfg.baseUrl() + "/browse/" + key;
    }

    public JiraUser myself() {
        JsonNode n = getJson("/rest/api/2/myself", Map.of());
        return new JiraUser(text(n, "name"), text(n, "displayName"));
    }

    public List<IssueSummary> search(String jql) {
        String query = isBlank(jql) ? cfg.defaultJql() : jql.trim();
        JsonNode n = getJson("/rest/api/2/search", Map.of(
                "jql", query,
                "maxResults", String.valueOf(cfg.maxResults()),
                "fields", withModule(SEARCH_FIELDS)));
        List<IssueSummary> out = new ArrayList<>();
        for (JsonNode i : n.path("issues")) {
            JsonNode f = i.path("fields");
            String key = text(i, "key");
            out.add(new IssueSummary(key, text(f, "project", "key"), text(f, "summary"), text(f, "issuetype", "name"),
                    text(f, "status", "name"), text(f, "priority", "name"), text(f, "reporter", "displayName"),
                    text(f, "updated"), text(f, "duedate"), module(f), names(f.path("components")),
                    strings(f.path("labels")), browseUrl(key)));
        }
        return out;
    }

    private String withModule(String fields) {
        return moduleField == null ? fields : fields + "," + moduleField;
    }

    /** Field "Module" có thể là chuỗi, option {value} hoặc mảng option tùy plugin. */
    private String module(JsonNode fields) {
        if (moduleField == null) {
            return null;
        }
        JsonNode m = fields.path(moduleField);
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

    public IssueDetail getIssue(String key) {
        requireKey(key);
        JsonNode n = getJson("/rest/api/2/issue/" + key, Map.of("fields", withModule(DETAIL_FIELDS)));
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
                text(f, "updated"), text(f, "duedate"), module(f), text(f, "description"), text(f, "environment"),
                names(f.path("components")), strings(f.path("labels")), comments, attachments, browseUrl(key));
    }

    /** Tải file đính kèm. Chỉ cho URL thuộc Jira để không gửi cookie/mật khẩu sang host khác. */
    public byte[] download(String url) {
        if (url == null || !url.startsWith(cfg.baseUrl() + "/")) {
            throw new JiraException(400, "URL đính kèm không thuộc Jira: " + url);
        }
        try {
            return http.get().uri(URI.create(url)).accept(MediaType.ALL).retrieve().body(byte[].class);
        } catch (RestClientResponseException e) {
            throw translate(e);
        }
    }

    private JsonNode getJson(String path, Map<String, String> query) {
        ResponseEntity<String> res;
        try {
            res = http.get()
                    .uri(b -> {
                        b.path(path);
                        query.keySet().forEach(k -> b.queryParam(k, "{" + k + "}"));
                        return b.build(query);
                    })
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .toEntity(String.class);
        } catch (RestClientResponseException e) {
            throw translate(e);
        } catch (ResourceAccessException e) {
            throw new JiraException(502, "Không kết nối được Jira: " + e.getMessage());
        }
        if ("anonymous".equalsIgnoreCase(res.getHeaders().getFirst("X-AUSERNAME"))) {
            throw new JiraException.Auth("Jira coi request là anonymous: cookie phiên đã hết hạn hoặc chưa cấu hình đăng nhập.");
        }
        String body = res.getBody();
        return json.readTree(isBlank(body) ? "{}" : body);
    }

    private JiraException translate(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        if (status == 401 || status == 403) {
            return new JiraException.Auth("Jira từ chối xác thực (HTTP " + status
                    + "). Cập nhật cookie phiên hoặc JIRA_USERNAME/JIRA_PASSWORD.");
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

    private void applyAuth(HttpHeaders headers) {
        String cookie = sessionCookie.get();
        if (cookie != null) {
            headers.set(HttpHeaders.COOKIE, cookie);
        } else if (hasBasicAuth()) {
            headers.setBasicAuth(cfg.username(), cfg.password(), StandardCharsets.UTF_8);
        }
    }

    private boolean hasBasicAuth() {
        return !isBlank(cfg.username()) && !isBlank(cfg.password());
    }

    /** Nhận cookie dạng thô, header "Cookie: ...", hoặc nguyên lệnh curl có -b '...'. */
    static String normalizeCookie(String raw) {
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
