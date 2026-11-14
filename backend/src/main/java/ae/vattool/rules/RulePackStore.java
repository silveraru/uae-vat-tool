package ae.vattool.rules;

import ae.vattool.platform.audit.AuditLog;
import ae.vattool.platform.tenancy.RequestContext;
import ae.vattool.platform.web.DomainException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Rule packs per tenant. The pack shipped with the product is installed as DRAFT the first time a
 * tenant needs it. Only that tenant's tax team can sign it off, and every return pack produced
 * under an unsigned rule pack carries a not-for-filing watermark.
 *
 * A firm edits its pack by saving an edited copy as a new version: a version is never changed once
 * loaded. Client rules sit on top of the pack for one filer (see {@link ClientRules}).
 */
@Service
public class RulePackStore {

    static final String BUNDLED_PACK = "rulepacks/ae-vat/v1/ae-vat-v1.yaml";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Fields that identify a pack rather than say anything about tax, so changing only these changes no rule. */
    private static final Set<String> IDENTITY = Set.of("jurisdiction", "version");

    public record StoredPack(UUID id, String version, LocalDate effectiveFrom, LocalDate effectiveTo, String status,
                             String contentHash, String loadedBy, String loadedByName, OffsetDateTime loadedAt,
                             String signedOffBy, String signedOffByName, OffsetDateTime signedOffAt, String signOffNote,
                             UUID basedOn, String basedOnVersion, String editNote, String retiredBy, OffsetDateTime retiredAt,
                             String retireNote, boolean inForceToday) {
        public boolean signedOff() {
            return "SIGNED_OFF".equals(status);
        }
    }

    /** The pack in force, as it applies to one client when that client has rules of its own. */
    public record ActivePack(StoredPack meta, RulePackDefinition definition, ClientRuleVersion clientRules) {
        public ActivePack(StoredPack meta, RulePackDefinition definition) {
            this(meta, definition, null);
        }

        /** Whether everything behind a result is signed off: the firm's pack, and the client's rules if any. */
        public boolean signedOff() {
            return meta.signedOff() && (clientRules == null || clientRules.signedOff());
        }

        public UUID clientRuleVersionId() {
            return clientRules == null ? null : clientRules.id();
        }
    }

    public record PackView(StoredPack pack, RulePackDefinition definition, String yaml) {}

    public record Copy(String yaml, String suggestedVersion) {}

    /** One difference between two packs. A path names list items by their id or code, such as classification.rules[POS-DOMESTIC].then.treatment. */
    public record Change(String path, String before, String after) {}

    public record CheckResult(boolean valid, List<String> problems, String version, LocalDate effectiveFrom, List<Change> changes) {}

    public record Bundled(String version, boolean installed) {}

    private final JdbcClient jdbc;
    private final AuditLog audit;
    private final Map<String, RulePackDefinition> parsedByHash = new ConcurrentHashMap<>();
    private final Map<String, ClientRules> clientByHash = new ConcurrentHashMap<>();
    private final Map<String, RulePackDefinition> mergedByKey = new ConcurrentHashMap<>();

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

    /**
     * The pack in force on a date with this client's rules laid over it. Use it wherever the client's
     * own rules matter: classification, reconciliation tolerances, suggestions and analytics. What the
     * law fixes reads the same either way.
     */
    @Transactional
    public ActivePack forEntity(UUID entityId, LocalDate date) {
        ActivePack base = forDate(date);
        Optional<ClientRuleVersion> client = clientRulesFor(entityId, date);
        if (client.isEmpty()) {
            return base;
        }
        ClientRuleVersion v = client.get();
        RulePackDefinition merged = mergedByKey.computeIfAbsent(base.meta().contentHash() + "|" + v.contentHash(),
                k -> v.content().applyTo(base.definition()));
        return new ActivePack(base.meta(), merged, v);
    }

    /** Whether a pack is signed off now. A stored return's pack can be signed off after it was computed. */
    @Transactional(readOnly = true)
    public boolean isSignedOff(UUID packId) {
        return "SIGNED_OFF".equals(jdbc.sql("SELECT status FROM rule_pack WHERE id = ?").param(packId).query(String.class).optional().orElse(null));
    }

    /** Whether the client rules a return used are signed off now. No client rules count as nothing to sign. */
    @Transactional(readOnly = true)
    public boolean isClientRulesSignedOff(UUID clientRuleVersionId) {
        if (clientRuleVersionId == null) {
            return true;
        }
        return "SIGNED_OFF".equals(jdbc.sql("SELECT status FROM client_rule_version WHERE id = ?").param(clientRuleVersionId)
                .query(String.class).optional().orElse(null));
    }

    /** The version number of a set of client rules, for documents that name it; null when there were none. */
    @Transactional(readOnly = true)
    public Integer clientRulesVersionNo(UUID clientRuleVersionId) {
        if (clientRuleVersionId == null) {
            return null;
        }
        return jdbc.sql("SELECT version_no FROM client_rule_version WHERE id = ?").param(clientRuleVersionId).query(Integer.class).optional().orElse(null);
    }

    /** The exact pack a stored return was computed with, whatever is current today. */
    @Transactional(readOnly = true)
    public RulePackDefinition definition(UUID packId) {
        record Row(String contentYaml, String contentHash) {}
        Row row = jdbc.sql("SELECT content_yaml, content_hash FROM rule_pack WHERE id = ?").param(packId)
                .query(Row.class).optional().orElseThrow(() -> DomainException.notFound("Rule pack"));
        return parsedByHash.computeIfAbsent(row.contentHash(), h -> RulePackParser.parse(row.contentYaml()));
    }

    @Transactional
    public List<StoredPack> list() {
        ensureBundledInstalled();
        return jdbc.sql(SELECT + " ORDER BY r.effective_from DESC, r.loaded_at DESC").query(StoredPack.class).list();
    }

    /** A pack as stored, with its rules read out and its source, comments included. */
    @Transactional(readOnly = true)
    public PackView view(UUID id) {
        return new PackView(find(id), definition(id), yaml(id));
    }

    @Transactional
    public StoredPack upload(String yaml) {
        RulePackDefinition def = RulePackParser.parse(yaml);
        return insert(def, yaml, null, null);
    }

    /** The source of a pack, ready to edit as a copy: only the version is changed, to one not yet taken. */
    @Transactional(readOnly = true)
    public Copy copy(UUID id) {
        StoredPack p = find(id);
        String base = p.version().replaceFirst("-firm-\\d+$", "");
        Set<String> taken = new HashSet<>(jdbc.sql("SELECT version FROM rule_pack").query(String.class).list());
        int n = 1;
        while (taken.contains(base + "-firm-" + n)) {
            n++;
        }
        String suggested = base + "-firm-" + n;
        String yaml = yaml(id).replaceFirst("(?m)^version:.*$", Matcher.quoteReplacement("version: \"" + suggested + "\""));
        return new Copy(yaml, suggested);
    }

    /** Reads an edited copy without saving it: what is wrong with it, and what it changes from the pack it came from. */
    @Transactional(readOnly = true)
    public CheckResult check(String yaml, UUID basedOn) {
        RulePackDefinition def;
        try {
            def = RulePackParser.parse(yaml);
        } catch (DomainException e) {
            return new CheckResult(false, List.of(e.getMessage()), null, null, List.of());
        }
        List<String> problems = new ArrayList<>();
        if (jdbc.sql("SELECT EXISTS (SELECT 1 FROM rule_pack WHERE jurisdiction = ? AND version = ?)").params(def.jurisdiction(), def.version())
                .query(Boolean.class).single()) {
            problems.add("Version " + def.version() + " already exists. Give the copy a version of its own.");
        }
        List<Change> changes = List.of();
        if (basedOn != null) {
            RulePackDefinition base = definition(basedOn);
            if (!Objects.equals(base.jurisdiction(), def.jurisdiction())) {
                problems.add("A copy keeps the jurisdiction of the pack it came from (" + base.jurisdiction() + ").");
            }
            changes = diff(base, def);
            if (changes.stream().allMatch(c -> IDENTITY.contains(c.path()))) {
                problems.add("The copy does not change any rule yet.");
            }
        }
        return new CheckResult(problems.isEmpty(), problems, def.version(), def.effectiveFrom(), changes);
    }

    /**
     * Saves an edited copy as a new DRAFT version. Like an installed pack it is in force from its
     * effective date at once, and returns from it carry the watermark until the tax team signs it off.
     */
    @Transactional
    public StoredPack saveEdit(String yaml, UUID basedOn, String note) {
        if (note == null || note.isBlank()) {
            throw DomainException.invalid("REASON_REQUIRED", "Say what the edit changes and why");
        }
        StoredPack base = find(basedOn);
        CheckResult check = check(yaml, basedOn);
        if (!check.valid()) {
            throw DomainException.invalid("RULE_PACK_INVALID", String.join(" ", check.problems()));
        }
        StoredPack saved = insert(RulePackParser.parse(yaml), yaml, basedOn, note);
        audit.record("RULE_PACK_EDITED", "RULE_PACK", saved.id(), null, Map.of("version", base.version()),
                Map.of("version", saved.version(), "effectiveFrom", saved.effectiveFrom().toString(), "changes", check.changes().size(),
                        "paths", check.changes().stream().limit(40).map(Change::path).toList()), note);
        return saved;
    }

    /** What ships with the product, and whether this tenant has it yet. */
    @Transactional(readOnly = true)
    public Bundled bundled() {
        RulePackDefinition def = RulePackParser.parse(readBundled());
        boolean installed = jdbc.sql("SELECT EXISTS (SELECT 1 FROM rule_pack WHERE jurisdiction = ? AND version = ?)").params(def.jurisdiction(), def.version()).query(Boolean.class).single();
        return new Bundled(def.version(), installed);
    }

    /**
     * Installs a newer bundled pack on a person's say-so, as a draft. It takes effect at once, and it
     * does not inherit any sign-off, so the watermark returns until the tax team signs the new version.
     */
    @Transactional
    public StoredPack installBundled() {
        String yaml = readBundled();
        return insert(RulePackParser.parse(yaml), yaml, null, null);
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

    /**
     * Takes a pack out of use, for a version loaded by mistake. Returns already computed with it keep
     * pointing at it. Some other pack must still be in force from its first day and today.
     */
    @Transactional
    public StoredPack retire(UUID id, String note) {
        if (note == null || note.isBlank()) {
            throw DomainException.invalid("REASON_REQUIRED", "Say why the pack is retired");
        }
        StoredPack p = find(id);
        if ("RETIRED".equals(p.status())) {
            throw DomainException.conflict("RULE_PACK_RETIRED", "This rule pack is already retired");
        }
        for (LocalDate d : List.of(p.effectiveFrom(), LocalDate.now())) {
            boolean covered = jdbc.sql("""
                    SELECT EXISTS (SELECT 1 FROM rule_pack WHERE id <> :id AND status <> 'RETIRED'
                                   AND effective_from <= :d AND (effective_to IS NULL OR effective_to > :d))
                    """).param("id", id).param("d", d).query(Boolean.class).single();
            if (!covered && !d.isBefore(p.effectiveFrom()) && (p.effectiveTo() == null || d.isBefore(p.effectiveTo()))) {
                throw DomainException.conflict("RULE_PACK_LAST",
                        "Retiring " + p.version() + " would leave no rule pack in force on " + d + ". Load or edit another pack first.");
            }
        }
        jdbc.sql("""
                UPDATE rule_pack SET status = 'RETIRED', retired_by = :by, retired_at = now(), retire_note = :note WHERE id = :id
                """).param("by", RequestContext.requireActor().actorId()).param("note", note).param("id", id).update();
        audit.record("RULE_PACK_RETIRED", "RULE_PACK", id, null, Map.of("status", p.status()),
                Map.of("status", "RETIRED", "version", p.version()), note);
        return find(id);
    }

    // ---- client rules, read here so the pack and the rules on top of it resolve together ----

    static final String CLIENT_SELECT = """
            SELECT v.id, v.entity_id, v.version_no, v.effective_from, v.content::text AS content, v.content_hash, v.note, v.status,
                   v.created_by, cu.display_name AS created_by_name, v.created_at, v.signed_off_by, su.display_name AS signed_off_by_name,
                   v.signed_off_at, v.sign_off_note
            FROM client_rule_version v
            LEFT JOIN app_user cu ON cu.id::text = v.created_by
            LEFT JOIN app_user su ON su.id::text = v.signed_off_by
            """;

    /** The client rules in force for a filer on a date. A version with no rules in it means none. */
    @Transactional(readOnly = true)
    public Optional<ClientRuleVersion> clientRulesFor(UUID entityId, LocalDate date) {
        return jdbc.sql(CLIENT_SELECT + " WHERE v.entity_id = ? AND v.effective_from <= ? ORDER BY v.effective_from DESC, v.version_no DESC LIMIT 1")
                .params(entityId, date).query(this::clientRow).optional()
                .filter(v -> !v.content().isEmpty());
    }

    ClientRules parseClient(String hash, String content) {
        return clientByHash.computeIfAbsent(hash, h -> JSON.readValue(content, ClientRules.class));
    }

    ClientRuleVersion clientRow(ResultSet rs, int rowNum) throws SQLException {
        String hash = rs.getString("content_hash");
        ClientRules rules = parseClient(hash, rs.getString("content"));
        return new ClientRuleVersion(rs.getObject("id", UUID.class), rs.getObject("entity_id", UUID.class), rs.getInt("version_no"),
                rs.getObject("effective_from", LocalDate.class), rules, hash, rs.getString("note"), rs.getString("status"),
                rs.getString("created_by"), rs.getString("created_by_name"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getString("signed_off_by"), rs.getString("signed_off_by_name"), rs.getObject("signed_off_at", OffsetDateTime.class),
                rs.getString("sign_off_note"));
    }

    static String toJson(ClientRules rules) {
        return JSON.writeValueAsString(rules);
    }

    // ---- differences, for a person to read before saving an edit ----

    static List<Change> diff(RulePackDefinition before, RulePackDefinition after) {
        Map<String, String> was = new LinkedHashMap<>();
        Map<String, String> now = new LinkedHashMap<>();
        flatten("", JSON.valueToTree(before), was);
        flatten("", JSON.valueToTree(after), now);
        List<Change> changes = new ArrayList<>();
        now.forEach((path, value) -> {
            String old = was.get(path);
            if (!Objects.equals(old, value)) {
                changes.add(new Change(path, old, value));
            }
        });
        was.forEach((path, value) -> {
            if (!now.containsKey(path)) {
                changes.add(new Change(path, value, null));
            }
        });
        return changes;
    }

    private static final List<String> ITEM_KEYS = List.of("id", "code", "pattern", "effectiveFrom", "fromTaxYearStarting");

    private static void flatten(String path, JsonNode node, Map<String, String> out) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                flatten(path.isEmpty() ? e.getKey() : path + "." + e.getKey(), e.getValue(), out);
            }
        } else if (node.isArray()) {
            String key = itemKey(node);
            if (key != null) {
                for (JsonNode item : node) {
                    flatten(path + "[" + item.get(key).asString() + "]", item, out);
                }
            } else if (allValues(node)) {
                List<String> values = new ArrayList<>();
                node.forEach(v -> values.add(scalar(v)));
                out.put(path, String.join(", ", values));
            } else {
                int i = 0;
                for (JsonNode item : node) {
                    flatten(path + "[" + i++ + "]", item, out);
                }
            }
        } else {
            out.put(path, scalar(node));
        }
    }

    private static String itemKey(JsonNode array) {
        if (array.isEmpty()) {
            return null;
        }
        for (String key : ITEM_KEYS) {
            boolean all = true;
            Set<String> seen = new HashSet<>();
            for (JsonNode item : array) {
                if (!item.isObject() || !item.hasNonNull(key) || !seen.add(item.get(key).asString())) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return key;
            }
        }
        return null;
    }

    private static boolean allValues(JsonNode array) {
        for (JsonNode item : array) {
            if (item.isObject() || item.isArray()) {
                return false;
            }
        }
        return true;
    }

    /** 100 and 100.00 are the same figure, so numbers compare by value. */
    private static String scalar(JsonNode v) {
        return v.isNumber() ? v.decimalValue().stripTrailingZeros().toPlainString() : v.asString();
    }

    // ---- storage ----

    private static final String SELECT = """
            SELECT r.id, r.version, r.effective_from, r.effective_to, r.status, r.content_hash, r.loaded_by, lu.display_name AS loaded_by_name,
                   r.loaded_at, r.signed_off_by, su.display_name AS signed_off_by_name, r.signed_off_at, r.sign_off_note,
                   r.based_on, b.version AS based_on_version, r.edit_note, r.retired_by, r.retired_at, r.retire_note,
                   r.id = (SELECT f.id FROM rule_pack f
                           WHERE f.status <> 'RETIRED' AND f.effective_from <= CURRENT_DATE AND (f.effective_to IS NULL OR f.effective_to > CURRENT_DATE)
                           ORDER BY f.effective_from DESC, f.loaded_at DESC LIMIT 1) AS in_force_today
            FROM rule_pack r
            LEFT JOIN rule_pack b ON b.id = r.based_on
            LEFT JOIN app_user lu ON lu.id::text = r.loaded_by
            LEFT JOIN app_user su ON su.id::text = r.signed_off_by
            """;

    private StoredPack find(UUID id) {
        return jdbc.sql(SELECT + " WHERE r.id = ?").param(id).query(StoredPack.class).optional()
                .orElseThrow(() -> DomainException.notFound("Rule pack"));
    }

    private String yaml(UUID id) {
        return jdbc.sql("SELECT content_yaml FROM rule_pack WHERE id = ?").param(id).query(String.class).optional()
                .orElseThrow(() -> DomainException.notFound("Rule pack"));
    }

    private void ensureBundledInstalled() {
        Integer count = jdbc.sql("SELECT count(*) FROM rule_pack").query(Integer.class).single();
        if (count > 0) {
            return;
        }
        String yaml = readBundled();
        try {
            insert(RulePackParser.parse(yaml), yaml, null, null);
        } catch (DuplicateKeyException raced) {
            // Another transaction installed it first, which is fine.
        }
    }

    private StoredPack insert(RulePackDefinition def, String yaml, UUID basedOn, String note) {
        UUID id = UUID.randomUUID();
        UUID tenant = RequestContext.requireTenant();
        int inserted = jdbc.sql("""
                INSERT INTO rule_pack (id, tenant_id, jurisdiction, version, effective_from, effective_to,
                                       content_yaml, content_hash, loaded_by, based_on, edit_note)
                VALUES (:id, :tenant, :jurisdiction, :version, :from, :to, :yaml, :hash, :by, :basedOn, :note)
                ON CONFLICT (tenant_id, jurisdiction, version) DO NOTHING
                """)
                .param("id", id).param("tenant", tenant).param("jurisdiction", def.jurisdiction())
                .param("version", def.version()).param("from", def.effectiveFrom()).param("to", def.effectiveTo())
                .param("yaml", yaml).param("hash", sha256(yaml))
                .param("by", RequestContext.requireActor().actorId()).param("basedOn", basedOn).param("note", note)
                .update();
        if (inserted == 0) {
            throw DomainException.conflict("RULE_PACK_VERSION_EXISTS",
                    "Rule pack version " + def.version() + " already exists. Versions cannot be overwritten.");
        }
        if (basedOn == null) {
            audit.record("RULE_PACK_LOADED", "RULE_PACK", id, null, null,
                    Map.of("version", def.version(), "effectiveFrom", def.effectiveFrom().toString(), "hash", sha256(yaml)), null);
        }
        return find(id);
    }

    private static String readBundled() {
        try (InputStream in = new ClassPathResource(BUNDLED_PACK).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Bundled rule pack is missing from the build", e);
        }
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
