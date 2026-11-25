package vn.fis.jiraaifixer.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecretBoxTest {

    @TempDir
    Path dir;

    @Test
    void roundTripAndRandomIv() {
        SecretBox box = new SecretBox(dir.resolve("secret.key"));
        String a = box.encrypt("Mật@khẩu123");
        String b = box.encrypt("Mật@khẩu123");

        assertThat(a).startsWith("v1:").doesNotContain("khẩu").isNotEqualTo(b);
        assertThat(box.decrypt(a)).isEqualTo("Mật@khẩu123");
        assertThat(new SecretBox(dir.resolve("secret.key")).decrypt(b)).isEqualTo("Mật@khẩu123");
        assertThat(box.encrypt("")).isNull();
        assertThat(box.decrypt(null)).isNull();
    }

    @Test
    void otherKeyCannotDecrypt() {
        String enc = new SecretBox(dir.resolve("k1")).encrypt("secret");
        assertThatThrownBy(() -> new SecretBox(dir.resolve("k2")).decrypt(enc)).isInstanceOf(IllegalStateException.class);
    }
}
