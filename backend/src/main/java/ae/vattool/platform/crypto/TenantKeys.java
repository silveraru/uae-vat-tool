package ae.vattool.platform.crypto;

import ae.vattool.platform.tenancy.RequestContext;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Per-tenant envelope encryption (NFR-02, NFR-03). Each tenant has a data key, stored only as wrapped
 * by the key service, and files are encrypted with it using AES-256-GCM before they reach object
 * storage. Keys are read in their own transaction, because a data key used for a stored object must
 * survive even if the work that wrote the object rolls back.
 */
@Service
public class TenantKeys {

    /** Marks an encrypted object. Anything without it was stored before encryption existed. */
    private static final byte[] MAGIC = "VTE1".getBytes(StandardCharsets.US_ASCII);
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final Duration CACHE_FOR = Duration.ofMinutes(10);

    private record CachedKey(int version, byte[] key, Instant until) {}

    private record Stored(int keyVersion, String wrappedKey) {}

    private final JdbcClient jdbc;
    private final Kms kms;
    private final TransactionTemplate ownTx;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, CachedKey> cache = new ConcurrentHashMap<>();

    public TenantKeys(JdbcClient jdbc, Kms kms, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.kms = kms;
        this.ownTx = new TransactionTemplate(tm);
        this.ownTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public byte[] encrypt(byte[] plain) {
        CachedKey key = currentKey();
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.key(), "AES"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plain);
            return ByteBuffer.allocate(MAGIC.length + Integer.BYTES + iv.length + ct.length).put(MAGIC).putInt(key.version()).put(iv).put(ct).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt", e);
        }
    }

    /** Objects written before encryption was switched on come back unchanged. */
    public byte[] decrypt(byte[] stored) {
        if (!isEncrypted(stored)) {
            return stored;
        }
        ByteBuffer b = ByteBuffer.wrap(stored);
        b.position(MAGIC.length);
        int version = b.getInt();
        byte[] iv = new byte[IV_BYTES];
        b.get(iv);
        byte[] ct = new byte[b.remaining()];
        b.get(ct);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyVersion(version), "AES"), new GCMParameterSpec(TAG_BITS, iv));
            return c.doFinal(ct);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not decrypt: the object may have been altered, or belongs to another tenant", e);
        }
    }

    public static boolean isEncrypted(byte[] stored) {
        if (stored.length < MAGIC.length) {
            return false;
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (stored[i] != MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    /** For small secrets kept in the database, such as webhook signing keys and connector credentials. */
    public String encryptText(String plain) {
        return Base64.getEncoder().encodeToString(encrypt(plain.getBytes(StandardCharsets.UTF_8)));
    }

    public String decryptText(String stored) {
        return new String(decrypt(Base64.getDecoder().decode(stored)), StandardCharsets.UTF_8);
    }

    private CachedKey currentKey() {
        UUID tenant = RequestContext.requireTenant();
        CachedKey cached = cache.get(tenant + "|current");
        if (cached != null && cached.until().isAfter(Instant.now())) {
            return cached;
        }
        Stored stored = ownTx.execute(s -> {
            Optional<Stored> existing = currentRow();
            if (existing.isPresent()) {
                return existing.get();
            }
            byte[] fresh = new byte[32];
            random.nextBytes(fresh);
            jdbc.sql("""
                    INSERT INTO tenant_key (tenant_id, key_version, wrapped_key, kms_key_ref) VALUES (?, 1, ?, ?) ON CONFLICT DO NOTHING
                    """).params(tenant, Base64.getEncoder().encodeToString(kms.wrap(fresh, tenant.toString())), kms.keyRef()).update();
            return currentRow().orElseThrow();
        });
        CachedKey key = new CachedKey(stored.keyVersion(), kms.unwrap(Base64.getDecoder().decode(stored.wrappedKey()), tenant.toString()), Instant.now().plus(CACHE_FOR));
        cache.put(tenant + "|current", key);
        return key;
    }

    private Optional<Stored> currentRow() {
        return jdbc.sql("SELECT key_version, wrapped_key FROM tenant_key WHERE retired_at IS NULL ORDER BY key_version DESC LIMIT 1").query(Stored.class).optional();
    }

    private byte[] keyVersion(int version) {
        UUID tenant = RequestContext.requireTenant();
        CachedKey cached = cache.get(tenant + "|" + version);
        if (cached != null && cached.until().isAfter(Instant.now())) {
            return cached.key();
        }
        String wrapped = ownTx.execute(s -> jdbc.sql("SELECT wrapped_key FROM tenant_key WHERE key_version = ?").param(version).query(String.class).optional()
                .orElseThrow(() -> new IllegalStateException("This tenant has no key version " + version + ": the object belongs to another tenant")));
        byte[] key = kms.unwrap(Base64.getDecoder().decode(wrapped), tenant.toString());
        cache.put(tenant + "|" + version, new CachedKey(version, key, Instant.now().plus(CACHE_FOR)));
        return key;
    }

    /** Starts a new data key. Old objects keep working, since each one names the version it used. */
    public int rotate() {
        UUID tenant = RequestContext.requireTenant();
        cache.remove(tenant + "|current");
        return ownTx.execute(s -> {
            int next = jdbc.sql("SELECT COALESCE(MAX(key_version), 0) + 1 FROM tenant_key").query(Integer.class).single();
            jdbc.sql("UPDATE tenant_key SET retired_at = now() WHERE retired_at IS NULL").update();
            byte[] fresh = new byte[32];
            random.nextBytes(fresh);
            jdbc.sql("INSERT INTO tenant_key (tenant_id, key_version, wrapped_key, kms_key_ref) VALUES (?, ?, ?, ?)")
                    .params(tenant, next, Base64.getEncoder().encodeToString(kms.wrap(fresh, tenant.toString())), kms.keyRef()).update();
            return next;
        });
    }
}
