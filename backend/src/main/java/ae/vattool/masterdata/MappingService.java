package ae.vattool.masterdata;

import ae.vattool.platform.audit.AuditLog;
import ae.vattool.platform.tenancy.RequestContext;
import ae.vattool.platform.web.DomainException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ERP tax code and GL mapping (ONB-04). Each publish creates a new immutable version with an
 * effective date. The version in force on a line's tax point is what classifies that line, and
 * the line records which version it used.
 */
@Service
public class MappingService {

    public record EntryInput(String matchType, String sourceCode, String treatment, String blockedCategory, String drcCategory) {}

    public record Entry(String matchType, String sourceCode, String treatment, String blockedCategory, String drcCategory) {}

    public record Version(UUID id, int versionNo, LocalDate effectiveFrom, String note, String createdBy, OffsetDateTime createdAt) {}

    public record VersionWithEntries(Version version, List<Entry> entries) {
        public Optional<Entry> lookup(String matchType, String code) {
            if (code == null || code.isBlank()) {
                return Optional.empty();
            }
            return entries.stream()
                    .filter(e -> e.matchType().equals(matchType) && e.sourceCode().equalsIgnoreCase(code.trim()))
                    .findFirst();
        }
    }

    private static final String VERSION_SELECT = """
            SELECT id, version_no, effective_from, note, created_by, created_at FROM mapping_version
            """;

    private final JdbcClient jdbc;
    private final AuditLog audit;

    public MappingService(JdbcClient jdbc, AuditLog audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional
    public VersionWithEntries publish(UUID entityId, LocalDate effectiveFrom, String note, List<EntryInput> entries) {
        if (effectiveFrom == null) {
            throw DomainException.invalid("EFFECTIVE_DATE_REQUIRED", "Mapping needs an effective date");
        }
        if (entries == null || entries.isEmpty()) {
            throw DomainException.invalid("MAPPING_EMPTY", "Mapping needs at least one entry");
        }
        Set<String> seen = new HashSet<>();
        for (EntryInput e : entries) {
            if (e.sourceCode() == null || e.sourceCode().isBlank()) {
                throw DomainException.invalid("MAPPING_INVALID", "Every mapping entry needs a source code");
            }
            if (!seen.add(e.matchType() + "|" + e.sourceCode().trim().toUpperCase())) {
                throw DomainException.invalid("MAPPING_DUPLICATE", "Code " + e.sourceCode() + " is mapped twice");
            }
        }
        UUID tenant = RequestContext.requireTenant();
        // Serialises publishers for this entity so version numbers cannot collide.
        jdbc.sql("SELECT id FROM legal_entity WHERE id = ? FOR UPDATE").param(entityId).query(UUID.class).optional()
                .orElseThrow(() -> DomainException.notFound("Entity"));
        int next = jdbc.sql("SELECT COALESCE(MAX(version_no), 0) + 1 FROM mapping_version WHERE entity_id = ?")
                .param(entityId).query(Integer.class).single();
        UUID versionId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO mapping_version (id, tenant_id, entity_id, version_no, effective_from, note, created_by)
                VALUES (:id, :tenant, :entity, :no, :from, :note, :by)
                """)
                .param("id", versionId).param("tenant", tenant).param("entity", entityId).param("no", next)
                .param("from", effectiveFrom).param("note", note)
                .param("by", RequestContext.requireActor().actorId())
                .update();
        for (EntryInput e : entries) {
            jdbc.sql("""
                    INSERT INTO mapping_entry (id, tenant_id, mapping_version_id, match_type, source_code, treatment,
                                               blocked_category, drc_category)
                    VALUES (:id, :tenant, :version, :type, :code, :treatment, :blocked, :drc)
                    """)
                    .param("id", UUID.randomUUID()).param("tenant", tenant).param("version", versionId)
                    .param("type", e.matchType()).param("code", e.sourceCode().trim())
                    .param("treatment", e.treatment()).param("blocked", e.blockedCategory()).param("drc", e.drcCategory())
                    .update();
        }
        audit.record("MAPPING_PUBLISHED", "MAPPING_VERSION", versionId, entityId, null,
                Map.of("versionNo", next, "effectiveFrom", effectiveFrom.toString(), "entries", entries.size()), note);
        return load(versionId);
    }

    /** The version in force on a date, or empty if the entity has no mapping for that date. */
    @Transactional(readOnly = true)
    public Optional<VersionWithEntries> forDate(UUID entityId, LocalDate date) {
        return jdbc.sql(VERSION_SELECT + """
                 WHERE entity_id = ? AND effective_from <= ?
                 ORDER BY effective_from DESC, version_no DESC LIMIT 1
                """).params(entityId, date).query(Version.class).optional().map(v -> withEntries(v));
    }

    @Transactional(readOnly = true)
    public List<Version> history(UUID entityId) {
        return jdbc.sql(VERSION_SELECT + " WHERE entity_id = ? ORDER BY version_no DESC").param(entityId)
                .query(Version.class).list();
    }

    @Transactional(readOnly = true)
    public VersionWithEntries load(UUID versionId) {
        Version v = jdbc.sql(VERSION_SELECT + " WHERE id = ?").param(versionId).query(Version.class).optional()
                .orElseThrow(() -> DomainException.notFound("Mapping version"));
        return withEntries(v);
    }

    private VersionWithEntries withEntries(Version v) {
        List<Entry> entries = jdbc.sql("""
                SELECT match_type, source_code, treatment, blocked_category, drc_category
                FROM mapping_entry WHERE mapping_version_id = ? ORDER BY match_type, source_code
                """).param(v.id()).query(Entry.class).list();
        return new VersionWithEntries(v, entries);
    }
}
