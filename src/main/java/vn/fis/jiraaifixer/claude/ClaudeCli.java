package vn.fis.jiraaifixer.claude;

import static vn.fis.jiraaifixer.util.Texts.isBlank;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;
import vn.fis.jiraaifixer.config.AppProperties;

/**
 * Chạy Claude Code CLI ở chế độ headless (`claude -p`), dùng chính tài khoản Claude đang đăng nhập trên máy.
 */
@Component
public class ClaudeCli {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCli.class);
    private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");
    private static final Pattern MODEL = Pattern.compile("[A-Za-z0-9._\\-\\[\\]]{1,64}");

    private final AppProperties.Claude cfg;
    private final ObjectMapper json;

    public ClaudeCli(AppProperties props, ObjectMapper json) {
        this.cfg = props.claude();
        this.json = json;
    }

    public static String validateModel(String model) {
        if (isBlank(model)) {
            return null;
        }
        if (!MODEL.matcher(model.trim()).matches()) {
            throw new IllegalArgumentException("Tên model không hợp lệ: " + model);
        }
        return model.trim();
    }

    public Optional<Path> locate() {
        if (!isBlank(cfg.cliPath())) {
            Path p = Path.of(cfg.cliPath().trim());
            return Files.isRegularFile(p) ? Optional.of(p) : Optional.empty();
        }
        String exe = isWindows() ? "claude.exe" : "claude";
        for (String dir : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            try {
                Path p = Path.of(dir.trim(), exe);
                if (Files.isRegularFile(p)) {
                    return Optional.of(p);
                }
            } catch (InvalidPathException ignored) {
                // PATH có thể chứa mục lỗi
            }
        }
        Path home = Path.of(System.getProperty("user.home"));
        Path local = home.resolve(".local").resolve("bin").resolve(exe);
        if (Files.isRegularFile(local)) {
            return Optional.of(local);
        }
        return newestVsCodeBinary(home.resolve(".vscode").resolve("extensions"), exe);
    }

    public ClaudeRunResult run(ClaudeRequest req, Consumer<String> sink, Consumer<Process> onStart)
            throws IOException, InterruptedException {
        Path exe = locate().orElseThrow(() -> new IllegalStateException(
                "Không tìm thấy Claude CLI. Cài Claude Code hoặc đặt app.claude.cli-path / CLAUDE_CLI_PATH."));
        List<String> cmd = buildCommand(exe, req);
        log.info("Chạy Claude trong {}: {}", req.workDir(), cmd.subList(1, cmd.size()));

        ProcessBuilder pb = new ProcessBuilder(cmd).directory(req.workDir().toFile()).redirectErrorStream(true);
        pb.environment().remove("CLAUDECODE");
        Process process = pb.start();
        onStart.accept(process);

        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(req.prompt().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // CLI thoát sớm (sai tham số...): vẫn đọc output bên dưới để lấy thông báo lỗi
            log.warn("Không ghi được prompt vào stdin của Claude: {}", e.getMessage());
        }

        StreamJsonParser parser = new StreamJsonParser(json, req.workDir(), sink);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                parser.accept(line);
            }
        }
        return parser.result(process.waitFor());
    }

    List<String> buildCommand(Path exe, ClaudeRequest req) {
        List<String> cmd = new ArrayList<>(List.of(exe.toString(), "-p",
                "--output-format", "stream-json", "--verbose",
                "--permission-mode", "acceptEdits",
                // không nạp MCP server/connector nào: Claude chỉ làm việc với code trong worktree
                "--strict-mcp-config"));
        if (!isBlank(cfg.tools())) {
            cmd.add("--tools");
            cmd.add(compactList(cfg.tools()));
        }
        List<String> allowed = new ArrayList<>();
        if (!isBlank(cfg.allowedTools())) {
            allowed.add(compactList(cfg.allowedTools()));
        }
        allowed.addAll(req.extraAllowedTools());
        if (!allowed.isEmpty()) {
            cmd.add("--allowedTools");
            cmd.add(String.join(",", allowed));
        }
        String model = req.model() != null ? req.model() : validateModel(cfg.model());
        if (model != null) {
            cmd.add("--model");
            cmd.add(model);
        }
        if (cfg.maxBudgetUsd() > 0) {
            cmd.add("--max-budget-usd");
            cmd.add(String.valueOf(cfg.maxBudgetUsd()));
        }
        if (!isBlank(req.resumeSessionId())) {
            cmd.add("--resume");
            cmd.add(req.resumeSessionId());
        }
        for (Path dir : req.addDirs()) {
            cmd.add("--add-dir");
            cmd.add(dir.toString());
        }
        return cmd;
    }

    /** "a, b,\n c" -> "a,b,c" (giữ nguyên khoảng trắng bên trong ngoặc như "Bash(git diff:*)"). */
    static String compactList(String list) {
        return list.trim().replaceAll("\\s*,\\s*", ",").replaceAll(",+$", "");
    }

    static Optional<Path> newestVsCodeBinary(Path extensionsDir, String exe) {
        if (!Files.isDirectory(extensionsDir)) {
            return Optional.empty();
        }
        try (Stream<Path> dirs = Files.list(extensionsDir)) {
            return dirs
                    .filter(d -> d.getFileName().toString().startsWith("anthropic.claude-code-"))
                    .filter(d -> Files.isRegularFile(d.resolve("resources").resolve("native-binary").resolve(exe)))
                    .max(Comparator.comparingLong(d -> versionKey(d.getFileName().toString())))
                    .map(d -> d.resolve("resources").resolve("native-binary").resolve(exe));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    static long versionKey(String name) {
        Matcher m = VERSION.matcher(name);
        if (!m.find()) {
            return -1;
        }
        return Long.parseLong(m.group(1)) * 100_000_000L + Long.parseLong(m.group(2)) * 10_000L
                + Long.parseLong(m.group(3));
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
