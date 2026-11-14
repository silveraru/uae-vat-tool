package ae.vattool.rules;

import ae.vattool.platform.audit.AuditLog;
import ae.vattool.platform.tenancy.RequestContext;
import ae.vattool.platform.web.DomainException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rules for one client on top of the firm's pack. Each publish is a new version with an effective date
 * and a reason, like a tax code mapping. A new version is a draft until someone who manages rules signs
 * it off, and return packs computed under a draft carry the not-for-filing watermark, as for the pack.
 */
@Service
public class ClientRuleService {

    public record View(RulePackStore.StoredPack firmPack, ClientRuleVersion inForce, List<ClientRuleVersion> versions) {}

    public record Summary(UUID entityId, String legalName, int versionNo, LocalDate effectiveFrom, String status, int rules) {}

    private final JdbcClient jdbc;
    private final RulePackStore packs;
    private final AuditLog audit;

    public ClientRuleService(JdbcClient jdbc, RulePackStore packs, AuditLog audit) {
        this.jdbc = jdbc;
        this.packs = packs;
        this.audit = audit;
    }

    /** What applies to this client today, and every version it has had. */
    @Transactional
    public View view(UUID entityId) {
        LocalDate today = LocalDate.now();
        return new View(packs.forDate(today).meta(), packs.clientRulesFor(entityId, today).orElse(null), history(entityId));
    }

    @Transactional(readOnly = true)
    public List<ClientRuleVersion> history(UUID entityId) {
        return jdbc.sql(RulePackStore.CLIENT_SELECT + " WHERE v.entity_id = ? ORDER BY v.version_no DESC").param(entityId)
                .query(packs::clientRow).list();
    }

    @Transactional(readOnly = true)
    public ClientRuleVersion get(UUID entityId, UUID versionId) {
        return jdbc.sql(RulePackStore.CLIENT_SELECT + " WHERE v.id = ? AND v.entity_id = ?").params(versionId, entityId)
                .query(packs::clientRow).optional().orElseThrow(() -> DomainException.notFound("Client rules"));
    }

    /**
     * Publishes the complete set of client rules from a date. An empty set is allowed: it ends the client
     * rules from that date, and the history still shows what applied before.
     */
    @Transactional
    public ClientRuleVersion publish(UUID entityId, LocalDate effectiveFrom, String note, ClientRules content) {
        if (effectiveFrom == null) {
            throw DomainException.invalid("EFFECTIVE_DATE_REQUIRED", "Client rules need an effective date");
        }
        if (note == null || note.isBlank()) {
            throw DomainException.invalid("REASON_REQUIRED", "Say what the client rules change and why");
        }
        ClientRules rules = content == null ? ClientRules.NONE : content;
        // Serialises publishers for this entity so version numbers cannot collide.
        String kind = jdbc.sql("SELECT entity_kind FROM legal_entity WHERE id = ? FOR UPDATE").param(entityId).query(String.class).optional()
                .orElseThrow(() -> DomainException.notFound("Entity"));
        if ("GROUP_MEMBER".equals(kind)) {
            throw DomainException.invalid("LOAD_TO_FILER", "Client rules belong to the group representative, which files for the whole group");
        }
        List<String> problems = rules.validate(packs.forDate(effectiveFrom).definition());
        if (!problems.isEmpty()) {
            throw DomainException.invalid("CLIENT_RULES_INVALID", "The client rules are not valid: " + String.join("; ", problems) + ".");
        }
        int next = jdbc.sql("SELECT COALESCE(MAX(version_no), 0) + 1 FROM client_rule_version WHERE entity_id = ?").param(entityId)
                .query(Integer.class).single();
        UUID id = UUID.randomUUID();
        String json = RulePackStore.toJson(rules);
        jdbc.sql("""
                INSERT INTO client_rule_version (id, tenant_id, entity_id, version_no, effective_from, content, content_hash, note, created_by)
                VALUES (:id, :tenant, :entity, :no, :from, CAST(:content AS jsonb), :hash, :note, :by)
                """)
                .param("id", id).param("tenant", RequestContext.requireTenant()).param("entity", entityId).param("no", next)
                .param("from", effectiveFrom).param("content", json).param("hash", RulePackStore.sha256(json)).param("note", note)
                .param("by", RequestContext.requireActor().actorId())
                .update();
        audit.record("CLIENT_RULES_PUBLISHED", "CLIENT_RULE_VERSION", id, entityId, null,
                Map.of("versionNo", next, "effectiveFrom", effectiveFrom.toString(), "rules", rules.size(),
                        "switchedOff", rules.switchedOff().stream().map(ClientRules.SwitchedOff::id).toList()), note);
        return get(entityId, id);
    }

    @Transactional
    public ClientRuleVersion signOff(UUID entityId, UUID versionId, String note) {
        if (note == null || note.isBlank()) {
            throw DomainException.invalid("REASON_REQUIRED", "Say what was checked before signing off");
        }
        ClientRuleVersion v = get(entityId, versionId);
        if (v.signedOff()) {
            throw DomainException.conflict("CLIENT_RULES_SIGNED_OFF", "Version " + v.versionNo() + " of these client rules is already signed off");
        }
        jdbc.sql("""
                UPDATE client_rule_version SET status = 'SIGNED_OFF', signed_off_by = :by, signed_off_at = now(), sign_off_note = :note
                WHERE id = :id
                """).param("by", RequestContext.requireActor().actorId()).param("note", note).param("id", versionId).update();
        audit.record("CLIENT_RULES_SIGNED_OFF", "CLIENT_RULE_VERSION", versionId, entityId, Map.of("status", v.status()),
                Map.of("status", "SIGNED_OFF", "versionNo", v.versionNo()), note);
        return get(entityId, versionId);
    }

    /** The latest version for every client that has rules of its own, for the firm's rule pack page. */
    @Transactional(readOnly = true)
    public List<Summary> summaries() {
        return jdbc.sql("""
                SELECT DISTINCT ON (v.entity_id) v.entity_id, e.legal_name, v.version_no, v.effective_from, v.status,
                       v.content::text AS content, v.content_hash
                FROM client_rule_version v JOIN legal_entity e ON e.id = v.entity_id
                ORDER BY v.entity_id, v.version_no DESC
                """).query((rs, n) -> new Summary(rs.getObject("entity_id", UUID.class), rs.getString("legal_name"), rs.getInt("version_no"),
                        rs.getObject("effective_from", LocalDate.class), rs.getString("status"),
                        packs.parseClient(rs.getString("content_hash"), rs.getString("content")).size()))
                .list().stream()
                .sorted(java.util.Comparator.comparing(Summary::legalName))
                .toList();
    }
}
