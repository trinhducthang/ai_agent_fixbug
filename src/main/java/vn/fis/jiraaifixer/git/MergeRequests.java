package vn.fis.jiraaifixer.git;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Xử lý link merge request GitLab. */
public final class MergeRequests {

    private static final Pattern MR_URL = Pattern.compile("https?://\\S+?/merge_requests/\\d+");

    private MergeRequests() {
    }

    /** GitLab in "View merge request for ...: <url>" khi push với -o merge_request.create (hoặc khi MR đã có). */
    public static Optional<String> findMrUrl(String pushOutput) {
        if (pushOutput == null) {
            return Optional.empty();
        }
        Matcher m = MR_URL.matcher(pushOutput);
        return m.find() ? Optional.of(m.group()) : Optional.empty();
    }

    /** Link tạo MR thủ công khi server không xử lý push option. */
    public static String newMrUrl(String remoteUrl, String sourceBranch, String targetBranch) {
        return webUrl(remoteUrl) + "/-/merge_requests/new?merge_request%5Bsource_branch%5D=" + enc(sourceBranch)
                + "&merge_request%5Btarget_branch%5D=" + enc(targetBranch);
    }

    /** https://user:token@host/g/p.git | git@host:g/p.git | ssh://git@host/g/p.git -> https://host/g/p */
    public static String webUrl(String remoteUrl) {
        String u = remoteUrl.trim();
        if (u.startsWith("git@")) {
            int colon = u.indexOf(':');
            u = "https://" + u.substring(4, colon) + "/" + u.substring(colon + 1);
        } else if (u.startsWith("ssh://")) {
            URI x = URI.create(u);
            u = "https://" + x.getHost() + x.getPath();
        } else {
            u = u.replaceFirst("^(https?://)[^/@]+@", "$1");
        }
        if (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        if (u.endsWith(".git")) {
            u = u.substring(0, u.length() - 4);
        }
        return u;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
