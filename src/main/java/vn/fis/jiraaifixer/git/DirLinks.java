package vn.fis.jiraaifixer.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Liên kết thư mục (junction trên Windows, symlink trên hệ khác) để worktree dùng chung node_modules với repo gốc.
 * Khi gỡ chỉ xóa liên kết, KHÔNG xóa nội dung thư mục đích.
 */
public final class DirLinks {

    private DirLinks() {
    }

    public static void create(Path link, Path target) throws IOException, InterruptedException {
        if (isWindows()) {
            exec(List.of("cmd", "/c", "mklink", "/J", link.toString(), target.toString()));
        } else {
            Files.createSymbolicLink(link, target);
        }
    }

    public static void remove(Path link) throws IOException, InterruptedException {
        if (!Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (isWindows()) {
            // rmdir KHÔNG có /s: với junction chỉ gỡ liên kết; với thư mục thật còn file thì báo lỗi, không xóa gì.
            exec(List.of("cmd", "/c", "rmdir", link.toString()));
        } else if (Files.isSymbolicLink(link)) {
            Files.delete(link);
        } else {
            throw new IOException(link + " không phải symlink, không xóa.");
        }
        if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Không gỡ được liên kết " + link);
        }
    }

    private static void exec(List<String> cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getOutputStream().close();
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("Lệnh quá thời gian: " + cmd);
        }
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (p.exitValue() != 0) {
            throw new IOException(String.join(" ", cmd) + " lỗi (exit " + p.exitValue() + "): " + out);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
