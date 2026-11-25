package vn.fis.jiraaifixer.claude;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class StreamJsonParserTest {

    private final Path workDir = Path.of("F:/ws/KEY-1").toAbsolutePath();

    @Test
    void parsesInitToolUseErrorAndResult() {
        List<String> logs = new ArrayList<>();
        StreamJsonParser p = new StreamJsonParser(JsonMapper.builder().build(), workDir, logs::add);
        String file = workDir.resolve("src").resolve("A.java").toString().replace("\\", "\\\\");

        p.accept("{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"s-1\",\"model\":\"claude-opus-5-5\"}");
        p.accept("{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"thinking\"},"
                + "{\"type\":\"tool_use\",\"name\":\"Edit\",\"input\":{\"file_path\":\"" + file + "\"}}]}}");
        p.accept("{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"is_error\":true,"
                + "\"content\":[{\"type\":\"text\",\"text\":\"boom\"}]}]}}");
        p.accept("{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"Xong.\"}]}}");
        p.accept("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"Tóm tắt\","
                + "\"total_cost_usd\":0.42,\"num_turns\":3,\"permission_denials\":[]}");

        ClaudeRunResult r = p.result(0);

        assertThat(r.failed()).isFalse();
        assertThat(r.sessionId()).isEqualTo("s-1");
        assertThat(r.resultText()).isEqualTo("Tóm tắt");
        assertThat(r.costUsd()).isEqualTo(0.42);
        assertThat(logs).anyMatch(l -> l.startsWith("→ Edit src"));
        assertThat(r.readPaths()).isEmpty();
        assertThat(logs).anyMatch(l -> l.contains("✗ boom"));
        assertThat(logs).contains("Claude: Xong.");
    }

    @Test
    void collectsReadFilePaths() {
        StreamJsonParser p = new StreamJsonParser(JsonMapper.builder().build(), workDir, l -> { });
        p.accept("{\"type\":\"assistant\",\"message\":{\"content\":["
                + "{\"type\":\"tool_use\",\"name\":\"Read\",\"input\":{\"file_path\":\"_jira\\\\01-loi.png\"}},"
                + "{\"type\":\"tool_use\",\"name\":\"Grep\",\"input\":{\"pattern\":\"x\",\"path\":\"be\"}},"
                + "{\"type\":\"tool_use\",\"name\":\"Read\",\"input\":{\"file_path\":\"C:/ws/K-1/_jira/02-man-hinh.jpg\"}}]}}");

        assertThat(p.result(0).readPaths()).containsExactly("_jira\\01-loi.png", "C:/ws/K-1/_jira/02-man-hinh.jpg");
    }

    @Test
    void missingResultEventIsFailureWithRawOutput() {
        List<String> logs = new ArrayList<>();
        StreamJsonParser p = new StreamJsonParser(JsonMapper.builder().build(), workDir, logs::add);

        p.accept("error: unknown option '--foo'");
        ClaudeRunResult r = p.result(1);

        assertThat(r.failed()).isTrue();
        assertThat(r.resultText()).contains("unknown option");
        assertThat(logs).containsExactly("error: unknown option '--foo'");
    }

    @Test
    void nonSuccessSubtypeIsError() {
        StreamJsonParser p = new StreamJsonParser(JsonMapper.builder().build(), workDir, l -> { });
        p.accept("{\"type\":\"result\",\"subtype\":\"error_max_budget_usd\",\"is_error\":false,"
                + "\"permission_denials\":[{\"tool_name\":\"Bash\",\"tool_input\":{\"command\":\"git push\"}}]}");

        ClaudeRunResult r = p.result(0);

        assertThat(r.failed()).isTrue();
        assertThat(r.denials()).singleElement().asString().startsWith("Bash");
    }
}
