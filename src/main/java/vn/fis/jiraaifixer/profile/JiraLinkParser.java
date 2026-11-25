package vn.fis.jiraaifixer.profile;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tách địa chỉ Jira và mã project từ bất kỳ link Jira nào người dùng dán:
 * trang chủ, /browse/KEY-1, /browse/KEY, /projects/KEY, /issues/?jql=..., board (?projectKey=KEY), filter...
 * Giữ cả context path (vd: https://host/jira).
 */
public final class JiraLinkParser {

    public record JiraLink(String baseUrl, List<String> projectKeys, String issueKey) {
    }

    private static final List<String> MARKERS = List.of("/browse/", "/projects/", "/issues/", "/secure/", "/rest/",
            "/plugins/", "/servicedesk/", "/login.jsp", "/dashboard", "/issues", "/browse");
    private static final Pattern BROWSE_ISSUE = Pattern.compile("/browse/([A-Z][A-Z0-9_]+)-(\\d+)");
    private static final Pattern BROWSE_PROJECT = Pattern.compile("/browse/([A-Z][A-Z0-9_]+)/?$");
    private static final Pattern PROJECTS = Pattern.compile("/projects/([A-Z][A-Z0-9_]+)");
    private static final Pattern KEY = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern ISSUE = Pattern.compile("([A-Z][A-Z0-9_]+)-\\d+");
    private static final Pattern JQL_PROJECT = Pattern.compile("(?i)\\bproject\\s*(?:=|in)\\s*\\(?([^)&]+?)\\)?(?:\\s+(?:and|or|order)\\b|$)");

    private JiraLinkParser() {
    }

    public static JiraLink parse(String link) {
        if (link == null || link.isBlank()) {
            throw new IllegalArgumentException("Chưa nhập link Jira");
        }
        URI u;
        try {
            u = URI.create(link.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Link Jira không hợp lệ: " + link);
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https") || u.getHost() == null) {
            throw new IllegalArgumentException("Link Jira phải bắt đầu bằng http:// hoặc https://");
        }
        String path = u.getRawPath() == null ? "" : u.getRawPath();
        int cut = path.length();
        for (String m : MARKERS) {
            int i = path.indexOf(m);
            if (i >= 0 && i < cut) {
                cut = i;
            }
        }
        String context = path.substring(0, cut).replaceAll("/+$", "");
        String base = scheme + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "") + context;

        Set<String> keys = new LinkedHashSet<>();
        String issueKey = null;
        Matcher bi = BROWSE_ISSUE.matcher(path);
        if (bi.find()) {
            keys.add(bi.group(1));
            issueKey = bi.group(1) + "-" + bi.group(2);
        }
        Matcher bp = BROWSE_PROJECT.matcher(path);
        if (bp.find()) {
            keys.add(bp.group(1));
        }
        Matcher pr = PROJECTS.matcher(path);
        if (pr.find()) {
            keys.add(pr.group(1));
        }
        for (String param : u.getRawQuery() == null ? new String[0] : u.getRawQuery().split("&")) {
            int eq = param.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = param.substring(0, eq);
            String value = URLDecoder.decode(param.substring(eq + 1), StandardCharsets.UTF_8).trim();
            switch (name) {
                case "projectKey" -> {
                    if (KEY.matcher(value).matches()) {
                        keys.add(value);
                    }
                }
                case "selectedIssue" -> {
                    Matcher m = ISSUE.matcher(value);
                    if (m.matches()) {
                        keys.add(m.group(1));
                        issueKey = issueKey == null ? value : issueKey;
                    }
                }
                case "jql" -> keys.addAll(projectsInJql(value));
                default -> {
                }
            }
        }
        return new JiraLink(base, new ArrayList<>(keys), issueKey);
    }

    static List<String> projectsInJql(String jql) {
        List<String> out = new ArrayList<>();
        Matcher m = JQL_PROJECT.matcher(jql);
        while (m.find()) {
            for (String part : m.group(1).split(",")) {
                String k = part.trim().replaceAll("^[\"']|[\"']$", "").trim().toUpperCase(Locale.ROOT);
                if (KEY.matcher(k).matches()) {
                    out.add(k);
                }
            }
        }
        return out;
    }
}
