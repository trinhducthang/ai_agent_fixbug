package vn.fis.jiraaifixer.claude;

import java.util.List;

/** @param readPaths đường dẫn file Claude đã mở bằng tool Read trong lượt chạy (nguyên văn Claude truyền vào) */
public record ClaudeRunResult(int exitCode, String sessionId, String resultText, boolean error, Double costUsd,
                              List<String> denials, List<String> readPaths) {

    public boolean failed() {
        return exitCode != 0 || error;
    }
}
