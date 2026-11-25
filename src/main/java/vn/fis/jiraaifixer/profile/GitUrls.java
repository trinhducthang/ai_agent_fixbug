package vn.fis.jiraaifixer.profile;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Chuẩn hóa link repo: link web GitLab/GitHub, link clone https, SSH. */
public final class GitUrls {

    private GitUrls() {
    }

    public static boolean looksLikeUrl(String s) {
        String l = s.trim().toLowerCase(Locale.ROOT);
        return l.startsWith("http://") || l.startsWith("https://") || l.startsWith("git@") || l.startsWith("ssh://");
    }

    /**
     * Link bất kỳ của repo -> link clone. Bỏ phần xem file/nhánh (/-/tree/..., /tree/..., /blob/...),
     * bỏ thông tin đăng nhập nhúng trong URL, thêm .git.
     *
     * @return null nếu không hiểu link
     */
    public static String toCloneUrl(String link) {
        if (link == null || !looksLikeUrl(link)) {
            return null;
        }
        String s = link.trim();
        if (s.startsWith("git@") || s.toLowerCase(Locale.ROOT).startsWith("ssh://")) {
            return s.endsWith(".git") ? s : s.replaceAll("/+$", "") + ".git";
        }
        URI u;
        try {
            u = URI.create(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (u.getHost() == null || u.getRawPath() == null) {
            return null;
        }
        String path = u.getRawPath();
        for (String marker : List.of("/-/", "/tree/", "/blob/", "/commits/", "/merge_requests", "/pulls", "/issues")) {
            int i = path.indexOf(marker);
            if (i > 0) {
                path = path.substring(0, i);
            }
        }
        path = path.replaceAll("/+$", "");
        if (path.chars().filter(c -> c == '/').count() < 2) {
            return null; // cần ít nhất /group/repo
        }
        if (!path.endsWith(".git")) {
            path = path + ".git";
        }
        return u.getScheme().toLowerCase(Locale.ROOT) + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "") + path;
    }

    /** Khóa so sánh hai remote: host/path chữ thường, không scheme, không user, không .git. */
    public static String normalize(String url) {
        if (url == null) {
            return "";
        }
        String s = url.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("git@")) {
            s = s.substring(4).replaceFirst(":", "/");
        } else {
            s = s.replaceFirst("^[a-z+]+://", "").replaceFirst("^[^/@]+@", "");
        }
        s = s.replaceAll("/+$", "");
        if (s.endsWith(".git")) {
            s = s.substring(0, s.length() - 4);
        }
        return s;
    }

    /** Bỏ user:password nhúng trong URL https trước khi hiển thị/lưu. */
    public static String stripCredentials(String url) {
        return url == null ? null : url.trim().replaceFirst("^(https?://)[^/@]+@", "$1");
    }

    /** Các đoạn path của repo: [fis-gs, gs-hn, btp_ntsplvn_2026_pm, ktrs, btp-ntsplvn-ktrs-be]. */
    public static List<String> pathSegments(String url) {
        String n = normalize(url);
        int slash = n.indexOf('/');
        List<String> out = new ArrayList<>();
        if (slash < 0) {
            return out;
        }
        for (String p : n.substring(slash + 1).split("/")) {
            if (!p.isBlank()) {
                out.add(p);
            }
        }
        return out;
    }

    public static String repoName(String url) {
        List<String> seg = pathSegments(url);
        return seg.isEmpty() ? null : seg.getLast();
    }

    /** Group trực tiếp chứa repo (vd: ktrs), null nếu không có. */
    public static String parentGroup(String url) {
        List<String> seg = pathSegments(url);
        return seg.size() < 2 ? null : seg.get(seg.size() - 2);
    }
}
