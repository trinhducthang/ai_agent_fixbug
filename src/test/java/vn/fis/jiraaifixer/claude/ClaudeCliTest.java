package vn.fis.jiraaifixer.claude;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ClaudeCliTest {

    @Test
    void versionKeyOrdersExtensionFolders() {
        assertThat(ClaudeCli.versionKey("anthropic.claude-code-2.1.331-win32-x64"))
                .isGreaterThan(ClaudeCli.versionKey("anthropic.claude-code-2.1.330-win32-x64"))
                .isGreaterThan(ClaudeCli.versionKey("anthropic.claude-code-2.0.999-win32-x64"));
        assertThat(ClaudeCli.versionKey("no-version")).isEqualTo(-1);
    }

    @Test
    void compactListKeepsSpacesInsideRules() {
        assertThat(ClaudeCli.compactList("Read, Edit,\n  Bash(git diff:*), PowerShell(mvn:*) "))
                .isEqualTo("Read,Edit,Bash(git diff:*),PowerShell(mvn:*)");
    }

    @Test
    void validateModel() {
        assertThat(ClaudeCli.validateModel(" opus ")).isEqualTo("opus");
        assertThat(ClaudeCli.validateModel("")).isNull();
        assertThatThrownBy(() -> ClaudeCli.validateModel("opus --dangerously-skip-permissions"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
