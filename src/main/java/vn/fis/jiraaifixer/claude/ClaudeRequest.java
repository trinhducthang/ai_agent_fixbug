package vn.fis.jiraaifixer.claude;

import java.nio.file.Path;
import java.util.List;

/**
 * @param workDir            thư mục Claude làm việc (thư mục phiên chứa các worktree)
 * @param prompt             prompt gửi qua stdin
 * @param resumeSessionId    session cũ để tiếp tục hội thoại; null = phiên mới
 * @param model              model; null = theo cấu hình
 * @param addDirs            thư mục bổ sung Claude được đọc (vd: tài liệu tham khảo)
 * @param extraAllowedTools  quy tắc allow bổ sung theo phiên (vd: Bash(git -C be diff:*))
 */
public record ClaudeRequest(Path workDir, String prompt, String resumeSessionId, String model, List<Path> addDirs,
                            List<String> extraAllowedTools) {

    public ClaudeRequest {
        addDirs = addDirs == null ? List.of() : List.copyOf(addDirs);
        extraAllowedTools = extraAllowedTools == null ? List.of() : List.copyOf(extraAllowedTools);
    }
}
