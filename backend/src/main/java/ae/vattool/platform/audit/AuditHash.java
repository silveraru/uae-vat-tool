package ae.vattool.platform.audit;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** The exact bytes that get hashed, kept in one place so writing and verifying cannot drift. */
public final class AuditHash {
    public static final String GENESIS = "GENESIS";

    public record Fields(UUID tenantId, long seq, Instant occurredAt, String actorType, String actorId,
                         String action, String objectType, String objectId, UUID entityId,
                         String beforeJson, String afterJson, String reason, String prevHash) {}

    private AuditHash() {}

    public static ObjectMapper canonicalMapper() {
        return JsonMapper.builder()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    public static String hash(Fields f) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            // Each field is length-prefixed, so moving text between adjacent fields changes the hash.
            for (String part : new String[] {
                    f.prevHash(), f.tenantId().toString(), Long.toString(f.seq()), f.occurredAt().toString(),
                    f.actorType(), f.actorId(), f.action(), f.objectType(), f.objectId(),
                    f.entityId() == null ? null : f.entityId().toString(),
                    f.beforeJson(), f.afterJson(), f.reason()}) {
                if (part == null) {
                    d.update(ByteBuffer.allocate(4).putInt(-1).array());
                } else {
                    byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                    d.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                    d.update(bytes);
                }
            }
            return HexFormat.of().formatHex(d.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
