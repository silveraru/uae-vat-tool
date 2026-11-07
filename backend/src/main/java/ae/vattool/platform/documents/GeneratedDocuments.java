package ae.vattool.platform.documents;

import ae.vattool.platform.audit.AuditLog;
import ae.vattool.platform.storage.VaultStorage;
import ae.vattool.platform.tenancy.RequestContext;
import ae.vattool.platform.web.DomainException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Files the system produces for someone else: Form 211 and refund packs, FTA Audit Files, audit
 * bundles. Each goes to locked storage with a fingerprint, and a download proves it is unchanged.
 */
@Service
public class GeneratedDocuments {

    private static final int RETENTION_YEARS = 5;

    public record Doc(UUID id, UUID entityId, UUID periodId, String kind, String objectId, String fileName, String contentType,
                      String sha256, long sizeBytes, boolean draftRules, String createdBy, OffsetDateTime createdAt) {
        static final String SELECT = """
                SELECT id, entity_id, period_id, kind, object_id, file_name, content_type, sha256, size_bytes, draft_rules, created_by, created_at
                FROM generated_document
                """;
    }

    public record Content(String fileName, String contentType, byte[] bytes) {}

    private final JdbcClient jdbc;
    private final VaultStorage vault;
    private final AuditLog audit;

    public GeneratedDocuments(JdbcClient jdbc, VaultStorage vault, AuditLog audit) {
        this.jdbc = jdbc;
        this.vault = vault;
        this.audit = audit;
    }

    @Transactional
    public Doc store(UUID entityId, UUID periodId, String kind, Object objectId, String fileName, String contentType, byte[] content, boolean draft) {
        UUID id = UUID.randomUUID();
        String key = "t/" + RequestContext.requireTenant() + "/documents/" + entityId + "/" + kind.toLowerCase() + "/" + id + "/" + fileName.replaceAll("[^A-Za-z0-9._-]", "_");
        VaultStorage.Stored stored = vault.put(key, content, contentType, LocalDate.now().plusYears(RETENTION_YEARS));
        jdbc.sql("""
                INSERT INTO generated_document (id, tenant_id, entity_id, period_id, kind, object_id, file_name, content_type, storage_key, sha256, size_bytes, draft_rules, created_by)
                VALUES (:id, :tenant, :entity, :period, :kind, :object, :file, :type, :key, :sha, :size, :draft, :by)
                """)
                .param("id", id).param("tenant", RequestContext.requireTenant()).param("entity", entityId).param("period", periodId)
                .param("kind", kind).param("object", String.valueOf(objectId)).param("file", fileName).param("type", contentType)
                .param("key", key).param("sha", stored.sha256()).param("size", stored.size()).param("draft", draft)
                .param("by", RequestContext.requireActor().actorId())
                .update();
        audit.record("DOCUMENT_GENERATED", "GENERATED_DOCUMENT", id, entityId, null,
                Map.of("kind", kind, "file", fileName, "sha256", stored.sha256(), "draftRules", draft), null);
        return get(id);
    }

    @Transactional(readOnly = true)
    public Doc get(UUID id) {
        return jdbc.sql(Doc.SELECT + " WHERE id = ?").param(id).query(Doc.class).optional().orElseThrow(() -> DomainException.notFound("Document"));
    }

    @Transactional(readOnly = true)
    public List<Doc> forObject(String objectId) {
        return jdbc.sql(Doc.SELECT + " WHERE object_id = ? ORDER BY created_at DESC").param(objectId).query(Doc.class).list();
    }

    @Transactional(readOnly = true)
    public Content content(UUID id) {
        Doc d = get(id);
        String key = jdbc.sql("SELECT storage_key FROM generated_document WHERE id = ?").param(id).query(String.class).single();
        byte[] bytes = vault.get(key);
        if (!VaultStorage.sha256(bytes).equals(d.sha256())) {
            throw new IllegalStateException("Document " + id + " no longer matches its recorded fingerprint");
        }
        return new Content(d.fileName(), d.contentType(), bytes);
    }

    /** An external auditor receives what an audit hands over. A disclosure or refund pack holds the firm's own working, such as penalty exposure. */
    public boolean sharedWithAuditors(Doc d) {
        return List.of("FAF", "AUDIT_BUNDLE").contains(d.kind());
    }
}
