package vn.fis.jiraaifixer.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.stereotype.Component;

/** Gọi git CLI của máy (dùng credential manager sẵn có để fetch/push). */
@Component
public class GitCli {

    public static final Duration LOCAL = Duration.ofMinutes(2);
    public static final Duration NETWORK = Duration.ofMinutes(5);

    public record Result(int exitCode, String output) {
        public boolean ok() {
            return exitCode == 0;
        }
    }

    public Result run(Path dir, Duration timeout, List<String> args) {
        // safecrlf=false: bỏ cảnh báo "LF will be replaced by CRLF" lẫn vào output diff
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "core.quotepath=false", "-c", "core.safecrlf=false"));
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        try {
            Process p = pb.start();
            p.getOutputStream().close();
            FutureTask<String> reader = new FutureTask<>(
                    () -> new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            Thread.ofVirtual().start(reader);
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                throw new GitException("git " + args.getFirst() + " quá " + timeout.toSeconds() + " giây, đã dừng.");
            }
            return new Result(p.exitValue(), reader.get(30, TimeUnit.SECONDS));
        } catch (IOException e) {
            throw new GitException("Không chạy được git: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitException("Lệnh git bị ngắt", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new GitException("Không đọc được output của git: " + e.getMessage(), e);
        }
    }

    public Result run(Path dir, Duration timeout, String... args) {
        return run(dir, timeout, List.of(args));
    }

    public String runOrThrow(Path dir, Duration timeout, List<String> args) {
        Result r = run(dir, timeout, args);
        if (!r.ok()) {
            throw new GitException("git " + String.join(" ", args) + " lỗi (exit " + r.exitCode() + "):\n"
                    + r.output().strip());
        }
        return r.output();
    }

    public String runOrThrow(Path dir, Duration timeout, String... args) {
        return runOrThrow(dir, timeout, List.of(args));
    }
}
