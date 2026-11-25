package vn.fis.jiraaifixer.claude;

import static vn.fis.jiraaifixer.util.Texts.isBlank;
import static vn.fis.jiraaifixer.util.Texts.oneLine;
import static vn.fis.jiraaifixer.util.Texts.text;
import static vn.fis.jiraaifixer.util.Texts.truncate;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Đọc từng dòng output `claude -p --output-format stream-json` và chuyển thành log dễ đọc. */
final class StreamJsonParser {

    private static final List<String> INPUT_KEYS = List.of("file_path", "command", "pattern", "path", "description");

    private final ObjectMapper json;
    private final Path workDir;
    private final Consumer<String> sink;
    private final Deque<String> rawTail = new ArrayDeque<>();
    private final List<String> denials = new ArrayList<>();
    private final List<String> readPaths = new ArrayList<>();

    private String sessionId;
    private String resultText;
    private boolean sawResult;
    private boolean error;
    private Double cost;

    StreamJsonParser(ObjectMapper json, Path workDir, Consumer<String> sink) {
        this.json = json;
        this.workDir = workDir;
        this.sink = sink;
    }

    void accept(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        JsonNode ev;
        try {
            ev = json.readTree(line);
        } catch (RuntimeException notJson) {
            raw(line);
            return;
        }
        if (ev == null || !ev.isObject()) {
            raw(line);
            return;
        }
        String type = text(ev, "type");
        if (type == null) {
            return;
        }
        switch (type) {
            case "system" -> onSystem(ev);
            case "assistant" -> onAssistant(ev);
            case "user" -> onUser(ev);
            case "result" -> onResult(ev);
            default -> {
                // rate_limit_event, stream_event...: bỏ qua
            }
        }
    }

    ClaudeRunResult result(int exitCode) {
        if (!sawResult) {
            error = true;
            if (resultText == null) {
                resultText = String.join("\n", rawTail);
            }
        }
        return new ClaudeRunResult(exitCode, sessionId, resultText, error, cost, List.copyOf(denials),
                List.copyOf(readPaths));
    }

    private void raw(String line) {
        sink.accept(line);
        rawTail.addLast(line);
        if (rawTail.size() > 20) {
            rawTail.removeFirst();
        }
    }

    private void onSystem(JsonNode ev) {
        if ("init".equals(text(ev, "subtype"))) {
            sessionId = text(ev, "session_id");
            sink.accept("Claude bắt đầu (model " + text(ev, "model") + ", session " + sessionId + ")");
        }
    }

    private void onAssistant(JsonNode ev) {
        for (JsonNode c : ev.path("message").path("content")) {
            String ct = text(c, "type");
            if ("text".equals(ct)) {
                String t = text(c, "text");
                if (!isBlank(t)) {
                    sink.accept("Claude: " + t.strip());
                }
            } else if ("tool_use".equals(ct)) {
                String name = text(c, "name");
                sink.accept("→ " + name + " " + describeInput(c.path("input")));
                String filePath = text(c.path("input"), "file_path");
                if ("Read".equals(name) && !isBlank(filePath)) {
                    readPaths.add(filePath);
                }
            }
        }
    }

    private void onUser(JsonNode ev) {
        for (JsonNode c : ev.path("message").path("content")) {
            if ("tool_result".equals(text(c, "type")) && c.path("is_error").asBoolean(false)) {
                sink.accept("  ✗ " + truncate(oneLine(contentText(c.path("content"))), 300));
            }
        }
    }

    private void onResult(JsonNode ev) {
        sawResult = true;
        resultText = text(ev, "result");
        error = ev.path("is_error").asBoolean(false) || !"success".equals(text(ev, "subtype"));
        JsonNode c = ev.path("total_cost_usd");
        if (c.isNumber()) {
            cost = c.asDouble();
        }
        if (sessionId == null) {
            sessionId = text(ev, "session_id");
        }
        for (JsonNode d : ev.path("permission_denials")) {
            denials.add(text(d, "tool_name") + " " + truncate(oneLine(d.path("tool_input").toString()), 200));
        }
        sink.accept(String.format("Claude kết thúc: %s (%s), %d lượt, chi phí ước tính $%.2f",
                error ? "LỖI" : "xong", text(ev, "subtype"), ev.path("num_turns").asInt(0), cost == null ? 0.0 : cost));
        if (!denials.isEmpty()) {
            sink.accept("Lệnh bị chặn do không nằm trong allowed-tools: " + String.join("; ", denials));
        }
    }

    private String describeInput(JsonNode input) {
        for (String k : INPUT_KEYS) {
            String v = text(input, k);
            if (!isBlank(v)) {
                boolean isPath = k.equals("file_path") || k.equals("path");
                return truncate(oneLine(isPath ? relativize(v) : v), 240);
            }
        }
        return truncate(input.toString(), 200);
    }

    private String relativize(String p) {
        try {
            Path abs = Path.of(p);
            return abs.isAbsolute() && abs.startsWith(workDir) ? workDir.relativize(abs).toString() : p;
        } catch (InvalidPathException e) {
            return p;
        }
    }

    private static String contentText(JsonNode node) {
        if (node.isArray()) {
            List<String> parts = new ArrayList<>();
            node.forEach(p -> parts.add(isBlank(text(p, "text")) ? p.toString() : text(p, "text")));
            return String.join(" ", parts);
        }
        return node.isValueNode() ? node.asString() : node.toString();
    }
}
