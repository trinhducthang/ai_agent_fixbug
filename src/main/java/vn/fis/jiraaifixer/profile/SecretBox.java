package vn.fis.jiraaifixer.profile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Mã hóa mật khẩu lưu trong file profile bằng AES-256-GCM. Khóa nằm trong file riêng cạnh dữ liệu.
 * Mục đích: không để mật khẩu dạng chữ thường trong file cấu hình (lỡ copy/chia sẻ/commit file profile
 * cũng không lộ). Không chống được người đã truy cập được vào tài khoản Windows của bạn.
 */
public final class SecretBox {

    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SecretBox(Path keyFile) {
        this.key = new SecretKeySpec(loadOrCreate(keyFile), "AES");
    }

    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array();
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Không mã hóa được mật khẩu", e);
        }
    }

    /** @return mật khẩu gốc; null nếu rỗng. Lỗi giải mã (đổi file khóa...) → IllegalStateException. */
    public String decrypt(String enc) {
        if (enc == null || enc.isEmpty()) {
            return null;
        }
        if (!enc.startsWith(PREFIX)) {
            throw new IllegalStateException("Mật khẩu trong profile không đúng định dạng mã hóa");
        }
        try {
            byte[] all = Base64.getDecoder().decode(enc.substring(PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return new String(c.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Không giải mã được mật khẩu (file khóa đã thay đổi?) – nhập lại mật khẩu cho profile", e);
        }
    }

    private static byte[] loadOrCreate(Path keyFile) {
        try {
            if (Files.exists(keyFile)) {
                return Base64.getDecoder().decode(Files.readString(keyFile).trim());
            }
            Files.createDirectories(keyFile.getParent());
            byte[] k = new byte[32];
            new SecureRandom().nextBytes(k);
            try {
                Files.writeString(keyFile, Base64.getEncoder().encodeToString(k), StandardOpenOption.CREATE_NEW);
            } catch (FileAlreadyExistsException race) {
                return Base64.getDecoder().decode(Files.readString(keyFile).trim());
            }
            try {
                Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException windows) {
                // Windows: file nằm trong thư mục người dùng, quyền mặc định chỉ chủ tài khoản đọc được
            }
            return k;
        } catch (IOException e) {
            throw new IllegalStateException("Không đọc/tạo được file khóa " + keyFile + ": " + e.getMessage(), e);
        }
    }
}
