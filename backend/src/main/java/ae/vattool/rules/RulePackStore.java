package ae.vattool.rules;

import ae.vattool.platform.audit.AuditLog;
import ae.vattool.platform.tenancy.RequestContext;
import ae.vattool.platform.web.DomainException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rule packs per tenant. The pack shipped with the product is installed as DRAFT the first time a
 * tenant needs it. Only that tenant's tax team can sign it off, and every return pack produced
 * under an unsigned rule pack carries a not-for-filing watermark.
 */
@Service
public class RulePackStore {

    static final String BUNDLED_PACK = "rulepacks/ae-vat/v1/ae-vat-v1.yaml";

    public record StoredPack(UUID id, String version, LocalDate effectiveFrom, LocalDate effectiveTo, String status,
                             String contentHash, String loadedBy, OffsetDateTime loadedAt, String signedOffBy,
                             OffsetDateTime signedOffAt, String signOffNote) {
        public boolean signedOff() {
            return "SIGNED_OFF".equals(status);
        }
    }

    public record ActivePack(StoredPack meta, RulePackDefinition definition) {}

    private final JdbcClient jdbc;
    private final AuditLog audit;
    private final Map<String, RulePackDefinition> parsedByHash = new ConcurrentHashMap<>();

    public RulePackStore(JdbcClient jdbc, AuditLog audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** The pack in force on a date: the latest effective version, ignoring retired ones. */
    @Transactional
    public ActivePack forDate(LocalDate date) {
        ensureBundledInstalled();
        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT id, content_yaml, content_hash FROM rule_pack
                WHERE status <> 'RETIRED' AND effective_from <= :d AND (effective_to IS NULL OR effective_to > :d)
                ORDER BY effective_from DESC, loaded_at DESC LIMIT 1
                """).param("d", date).query().listOfRows();
        if (rows.isEmpty()) {
            throw DomainException.invalid("NO_RULE_PACK", "No rule pack is in force on " + date);
        }
        Map<String, Object> row = rows.getFirst();
        String hash = (String) row.get("content_hash");
        RulePackDefinition def = parsedByHash.computeIfAbsent(hash, h -> RulePackParser.parse((String) row.get("content_yaml")));
        return new ActivePack(find((UUID) row.get("id")), def);
    }

    @Transactional(readOnly = true)
    public List<StoredPack> list() {
        return jdbc.sql(SELECT + " ORDER BY effective_from DESC, loaded_at DESC").query(StoredPack.class).list();
    }

    @Transactional
    public StoredPack upload(String yaml) {
        RulePackDefinition def = RulePackParser.parse(yaml);
        return insert(def, yaml);
    }

    @Transactional
    public StoredPack signOff(UUID id, String note) {
        StoredPack before = find(id);
        if ("RETIRED".equals(before.status())) {
            throw DomainException.conflict("RULE_PACK_RETIRED", "A retired rule pack cannot be signed off");
        }
        String actor = RequestContext.requireActor().actorId();
        jdbc.sql("""
                UPDATE rule_pack SET status = 'SIGNED_OFF', signed_off_by = :by, signed_off_at = now(), sign_off_note = :note
                WHERE id = :id
                """).param("by", actor).param("note", note).param("id", id).update();
        audit.record("RULE_PACK_SIGNED_OFF", "RULE_PACK", id, null,
                Map.of("status", before.status()), Map.of("status", "SIGNED_OFF", "version", before.version()), note);
        return find(id);
    }

    private static final String SELECT = """
            SELECT id, version, effective_from, effective_to, status, content_hash, loaded_by, loaded_at,
                   signed_off_by, signed_off_at, sign_off_note
            FROM rule_pack
            """;

    private StoredPack find(UUID id) {
        return jdbc.sql(SELECT + " WHERE id = ?").param(id).query(StoredPack.class).optional()
                .orElseThrow(() -> DomainException.notFound("Rule pack"));
    }

    private void ensureBundledInstalled() {
        Integer count = jdbc.sql("SELECT count(*) FROM rule_pack").query(Integer.class).single();
        if (count > 0) {
            return;
        }
        String yaml = readBundled();
        try {
            insert(RulePackParser.parse(yaml), yaml);
        } catch (DuplicateKeyException raced) {
            // Another transaction installed it first, which is fine.
        }
    }

    private StoredPack insert(RulePackDefinition def, String yaml) {
        UUID id = UUID.randomUUID();
        UUID tenant = RequestContext.requireTenant();
        int inserted = jdbc.sql("""
                INSERT INTO rule_pack (id, tenant_id, jurisdiction, version, effective_from, effective_to,
                                       content_yaml, content_hash, loaded_by)
                VALUES (:id, :tenant, :jurisdiction, :version, :from, :to, :yaml, :hash, :by)
                ON CONFLICT (tenant_id, jurisdiction, version) DO NOTHING
                """)
                .param("id", id).param("tenant", tenant).param("jurisdiction", def.jurisdiction())
                .param("version", def.version()).param("from", def.effectiveFrom()).param("to", def.effectiveTo())
                .param("yaml", yaml).param("hash", sha256(yaml))
                .param("by", RequestContext.requireActor().actorId())
                .update();
        if (inserted == 0) {
            throw DomainException.conflict("RULE_PACK_VERSION_EXISTS",
                    "Rule pack version " + def.version() + " already exists. Versions cannot be overwritten.");
        }
        audit.record("RULE_PACK_LOADED", "RULE_PACK", id, null, null,
                Map.of("version", def.version(), "effectiveFrom", def.effectiveFrom().toString(), "hash", sha256(yaml)), null);
        return find(id);
    }

    private static String readBundled() {
        try (InputStream in = new ClassPathResource(BUNDLED_PACK).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Bundled rule pack is missing from the build", e);
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
