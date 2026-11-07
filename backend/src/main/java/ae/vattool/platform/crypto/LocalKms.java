package ae.vattool.platform.crypto;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * A master key held in configuration, for development and tests only. It gives the same behaviour as
 * a real key service, and none of the protection. TenantKeysConfig refuses it in production.
 */
final class LocalKms implements Kms {

    private final SecretKeySpec master;
    private final SecureRandom random = new SecureRandom();

    LocalKms(String base64Key) {
        byte[] key = Base64.getDecoder().decode(base64Key);
        if (key.length != 32) {
            throw new IllegalArgumentException("The local master key must be 32 bytes, base64 encoded");
        }
        this.master = new SecretKeySpec(key, "AES");
    }

    @Override
    public String keyRef() {
        return "local";
    }

    @Override
    public byte[] wrap(byte[] dataKey, String tenantId) {
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, master, new GCMParameterSpec(128, iv));
            c.updateAAD(tenantId.getBytes(StandardCharsets.UTF_8));
            byte[] ct = c.doFinal(dataKey);
            return ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not wrap a data key", e);
        }
    }

    @Override
    public byte[] unwrap(byte[] wrapped, String tenantId) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, master, new GCMParameterSpec(128, wrapped, 0, 12));
            c.updateAAD(tenantId.getBytes(StandardCharsets.UTF_8));
            return c.doFinal(wrapped, 12, wrapped.length - 12);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not unwrap a data key", e);
        }
    }
}
