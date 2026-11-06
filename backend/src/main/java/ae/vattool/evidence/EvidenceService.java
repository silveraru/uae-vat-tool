package ae.vattool.evidence;

import ae.vattool.platform.audit.AuditLog;
import ae.vattool.platform.storage.VaultStorage;
import ae.vattool.platform.tenancy.RequestContext;
import ae.vattool.platform.web.DomainException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The evidence vault (AUD-02, NFR-06). Files go to Object Lock storage, so they cannot be replaced
 * or removed before their retention date: at least 5 years, or 15 for real estate records.
 */
@Service
public class EvidenceService {

    public record Evidence(UUID id, UUID entityId, UUID periodId, String fileName, String contentType, String sha256, long sizeBytes,
                           String storageKey, String retentionClass, LocalDate retainUntil, boolean legalHold, String description,
                           String uploadedBy, OffsetDateTime uploadedAt) {
        static final String SELECT = """
                SELECT id, entity_id, period_id, file_name, content_type, sha256, size_bytes, storage_key, retention_class,
                       retain_until, legal_hold, description, uploaded_by, uploaded_at
                FROM evidence
                """;
    }

    public record Download(String fileName, String contentType, byte[] content) {}

    private static final int STANDARD_YEARS = 5;
    private static final int REAL_ESTATE_YEARS = 15;
    private static final Set<String> LINKABLE = Set.of("PERIOD", "BOX", "LINE", "EXCEPTION", "ADJUSTMENT", "CORRECTION", "FILING", "PAYMENT");

    private final JdbcClient jdbc;
    private final VaultStorage vault;
    private final AuditLog audit;

    public EvidenceService(JdbcClient jdbc, VaultStorage vault, AuditLog audit) {
        this.jdbc = jdbc;
        this.vault = vault;
        this.audit = audit;
    }

    @Transactional
    public Evidence upload(UUID entityId, UUID periodId, String fileName, String contentType, byte[] content, String description, String retentionClass) {
        if (content == null || content.length == 0) {
            throw DomainException.invalid("EVIDENCE_EMPTY", "The evidence file is empty");
        }
        String cls = retentionClass == null || retentionClass.isBlank() ? "STANDARD" : retentionClass;
        if (!Set.of("STANDARD", "REAL_ESTATE").contains(cls)) {
            throw DomainException.invalid("RETENTION_CLASS_INVALID", "Retention class must be STANDARD or REAL_ESTATE");
        }
        UUID id = UUID.randomUUID();
        LocalDate until = LocalDate.now().plusYears("REAL_ESTATE".equals(cls) ? REAL_ESTATE_YEARS : STANDARD_YEARS);
        String safeName = fileName == null ? "file" : fileName.replaceAll("[^A-Za-z0-9._-]", "_");
        String key = "t/" + RequestContext.requireTenant() + "/evidence/" + entityId + "/" + id + "/" + safeName;
        String type = contentType == null ? "application/octet-stream" : contentType;
        VaultStorage.Stored stored = vault.put(key, content, type, until);
        jdbc.sql("""
                INSERT INTO evidence (id, tenant_id, entity_id, period_id, file_name, content_type, sha256, size_bytes, storage_key,
                    version_id, retention_class, retain_until, description, uploaded_by)
                VALUES (:id, :tenant, :entity, :period, :file, :type, :sha, :size, :key, :version, :cls, :until, :description, :by)
                """)
                .param("id", id).param("tenant", RequestContext.requireTenant()).param("entity", entityId).param("period", periodId)
                .param("file", fileName == null ? "file" : fileName).param("type", type).param("sha", stored.sha256())
                .param("size", stored.size()).param("key", key).param("version", stored.versionId()).param("cls", cls)
                .param("until", until).param("description", description).param("by", RequestContext.requireActor().actorId())
                .update();
        audit.record("EVIDENCE_ADDED", "EVIDENCE", id, entityId, null, Map.of("file", String.valueOf(fileName), "sha256", stored.sha256(),
                "retainUntil", until.toString()), description);
        return get(id);
    }

    @Transactional(readOnly = true)
    public Evidence get(UUID id) {
        return jdbc.sql(Evidence.SELECT + " WHERE id = ?").param(id).query(Evidence.class).optional()
                .orElseThrow(() -> DomainException.notFound("Evidence"));
    }

    /** Checks the file is what was stored, so tampering outside the application cannot go unnoticed. */
    @Transactional(readOnly = true)
    public Download download(UUID id) {
        Evidence e = get(id);
        byte[] content = vault.get(e.storageKey());
        if (!VaultStorage.sha256(content).equals(e.sha256())) {
            throw new IllegalStateException("Evidence " + id + " no longer matches its recorded fingerprint");
        }
        return new Download(e.fileName(), e.contentType(), content);
    }

    @Transactional
    public void link(UUID evidenceId, String objectType, String objectId) {
        if (!LINKABLE.contains(objectType)) {
            throw DomainException.invalid("LINK_TYPE_INVALID", "Evidence can be linked to " + LINKABLE);
        }
        Evidence e = get(evidenceId);
        jdbc.sql("""
                INSERT INTO evidence_link (tenant_id, evidence_id, object_type, object_id, created_by)
                VALUES (:tenant, :evidence, :type, :object, :by) ON CONFLICT DO NOTHING
                """)
                .param("tenant", RequestContext.requireTenant()).param("evidence", evidenceId).param("type", objectType)
                .param("object", objectId).param("by", RequestContext.requireActor().actorId())
                .update();
        audit.record("EVIDENCE_LINKED", "EVIDENCE", evidenceId, e.entityId(), null, Map.of("objectType", objectType, "objectId", objectId), null);
    }

    @Transactional(readOnly = true)
    public List<Evidence> forObject(String objectType, String objectId) {
        return jdbc.sql(Evidence.SELECT + """
                 WHERE id IN (SELECT evidence_id FROM evidence_link WHERE object_type = ? AND object_id = ?) ORDER BY uploaded_at
                """).params(objectType, objectId).query(Evidence.class).list();
    }

    @Transactional(readOnly = true)
    public List<Evidence> forPeriod(UUID periodId) {
        return jdbc.sql(Evidence.SELECT + " WHERE period_id = ? ORDER BY uploaded_at DESC").param(periodId).query(Evidence.class).list();
    }

    @Transactional
    public Evidence setLegalHold(UUID id, boolean on, String reason) {
        if (reason == null || reason.isBlank()) {
            throw DomainException.invalid("REASON_REQUIRED", "A legal hold change needs a reason");
        }
        Evidence e = get(id);
        vault.setLegalHold(e.storageKey(), on);
        jdbc.sql("UPDATE evidence SET legal_hold = ? WHERE id = ?").params(on, id).update();
        audit.record(on ? "LEGAL_HOLD_ON" : "LEGAL_HOLD_OFF", "EVIDENCE", id, e.entityId(), null, null, reason);
        return get(id);
    }

    /** Evidence must belong to the entity it supports. Called wherever a flow requires evidence. */
    @Transactional(readOnly = true)
    public Evidence requireForEntity(UUID evidenceId, UUID entityId) {
        if (evidenceId == null) {
            throw DomainException.invalid("EVIDENCE_REQUIRED", "Attach evidence first");
        }
        Evidence e = get(evidenceId);
        if (!e.entityId().equals(entityId)) {
            throw DomainException.invalid("EVIDENCE_WRONG_ENTITY", "That evidence belongs to a different entity");
        }
        return e;
    }
}
