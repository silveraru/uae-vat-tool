package ae.vattool.returns;

import ae.vattool.masterdata.TaxPeriod;
import ae.vattool.platform.storage.VaultStorage;
import ae.vattool.platform.tenancy.RequestContext;
import ae.vattool.platform.web.DomainException;
import ae.vattool.returns.BoxCalculator.Aggregate;
import ae.vattool.returns.BoxCalculator.BoxValue;
import ae.vattool.rules.RulePackDefinition;
import ae.vattool.rules.RulePackStore;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Computes returns from the ledger and keeps numbered versions (RET-01, 03, 06). A new version is
 * created only when the figures change, so the version list reads as the real history of the return.
 */
@Service
public class ReturnService {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;
    private final RulePackStore packs;
    private final ApplicationEventPublisher events;

    public ReturnService(JdbcClient jdbc, RulePackStore packs, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.packs = packs;
        this.events = events;
    }

    public record Computed(Map<String, BoxValue> boxes, Map<UUID, Map<String, BoxValue>> perMember, long lines) {}

    /** The pack in force at period end decides the form, so one period always uses one form. */
    @Transactional
    public RulePackStore.ActivePack packFor(TaxPeriod period) {
        return packs.forDate(period.periodEnd());
    }

    /** Figures from the ledger as it is now, without saving anything. */
    @Transactional(readOnly = true)
    public Computed computeNow(TaxPeriod period, RulePackDefinition.Form form) {
        List<Aggregate> rows = aggregates(period.id());
        Map<String, BoxValue> total = BoxCalculator.calculate(form, rows);
        Map<UUID, List<Aggregate>> byMember = rows.stream().collect(Collectors.groupingBy(Aggregate::memberEntityId));
        Map<UUID, Map<String, BoxValue>> perMember = new LinkedHashMap<>();
        byMember.forEach((member, memberRows) -> perMember.put(member, BoxCalculator.calculate(form, memberRows)));
        long lines = rows.stream().mapToLong(Aggregate::lines).sum();
        return new Computed(total, perMember, lines);
    }

    /**
     * Brings the stored return up to date with the ledger. The row lock stops two recomputes
     * racing to create the same version number. A locked period is never recomputed: its return is
     * the record of what was approved, and later data goes through the correction flow.
     */
    @Transactional
    public Optional<ReturnVersionView> recompute(UUID periodId, String trigger) {
        TaxPeriod period = jdbc.sql(TaxPeriod.SELECT + " WHERE id = ? FOR UPDATE").param(periodId)
                .query(TaxPeriod.class).optional()
                .orElseThrow(() -> DomainException.notFound("Tax period"));
        Optional<ReturnVersionView> latest = latest(periodId);
        if (period.isLocked()) {
            return latest;
        }
        RulePackStore.ActivePack pack = packFor(period);
        RulePackDefinition.Form form = pack.definition().form();
        Computed computed = computeNow(period, form);
        String fingerprint = fingerprint(periodId);
        String valuesJson = canonicalValues(computed.boxes());
        String hash = VaultStorage.sha256(valuesJson + "|" + form.version() + "|" + pack.meta().version());

        String previousFingerprint = jdbc.sql("SELECT data_fingerprint FROM tax_period WHERE id = ?").param(periodId)
                .query(String.class).optional().orElse(null);
        if (!fingerprint.equals(previousFingerprint)) {
            jdbc.sql("UPDATE tax_period SET data_fingerprint = ? WHERE id = ?").params(fingerprint, periodId).update();
            events.publishEvent(new PeriodDataChanged(periodId, fingerprint));
        }

        if (latest.isPresent() && latest.get().valuesHash().equals(hash)) {
            return latest;
        }
        UUID id = UUID.randomUUID();
        int number = latest.map(v -> v.versionNo() + 1).orElse(1);
        jdbc.sql("""
                INSERT INTO return_version (id, tenant_id, entity_id, period_id, version_no, form_code, form_version, rule_pack_id,
                    rule_pack_version, rule_pack_signed_off, box_values, member_values, values_hash, data_fingerprint, diff,
                    line_count, trigger, created_by)
                VALUES (:id, :tenant, :entity, :period, :no, :formCode, :formVersion, :packId, :packVersion, :signedOff,
                    CAST(:boxes AS jsonb), CAST(:members AS jsonb), :hash, :fingerprint, CAST(:diff AS jsonb), :lines, :trigger, :by)
                """)
                .param("id", id).param("tenant", RequestContext.requireTenant()).param("entity", period.entityId())
                .param("period", periodId).param("no", number).param("formCode", form.code()).param("formVersion", form.version())
                .param("packId", pack.meta().id()).param("packVersion", pack.meta().version())
                .param("signedOff", pack.meta().signedOff()).param("boxes", valuesJson)
                .param("members", membersJson(computed.perMember())).param("hash", hash).param("fingerprint", fingerprint)
                .param("diff", latest.map(v -> JSON.writeValueAsString(diff(v.boxValues(), computed.boxes()))).orElse(null))
                .param("lines", computed.lines()).param("trigger", trigger)
                .param("by", RequestContext.requireActor().actorId())
                .update();
        return find(id);
    }

    /**
     * Stores corrected figures as a new version beside the locked one, which is never altered. Both
     * stay available: what was filed, and what it should have been (spec journey 3).
     */
    @Transactional
    public ReturnVersionView saveCorrected(TaxPeriod period, RulePackStore.ActivePack pack, Map<String, BoxValue> boxes,
                                           ReturnVersionView original, Map<UUID, Map<String, BoxValue>> perMember) {
        UUID id = UUID.randomUUID();
        int number = latest(period.id()).map(v -> v.versionNo() + 1).orElse(1);
        String valuesJson = canonicalValues(boxes);
        RulePackDefinition.Form form = pack.definition().form();
        jdbc.sql("""
                INSERT INTO return_version (id, tenant_id, entity_id, period_id, version_no, status, form_code, form_version, rule_pack_id,
                    rule_pack_version, rule_pack_signed_off, box_values, member_values, values_hash, data_fingerprint, diff,
                    line_count, trigger, created_by)
                VALUES (:id, :tenant, :entity, :period, :no, 'CORRECTED', :formCode, :formVersion, :packId, :packVersion, :signedOff,
                    CAST(:boxes AS jsonb), CAST(:members AS jsonb), :hash, :fingerprint, CAST(:diff AS jsonb), 0, 'CORRECTION', :by)
                """)
                .param("id", id).param("tenant", RequestContext.requireTenant()).param("entity", period.entityId())
                .param("period", period.id()).param("no", number).param("formCode", form.code()).param("formVersion", form.version())
                .param("packId", pack.meta().id()).param("packVersion", pack.meta().version()).param("signedOff", pack.meta().signedOff())
                .param("boxes", valuesJson).param("members", membersJson(perMember))
                .param("hash", VaultStorage.sha256(valuesJson + "|" + form.version() + "|" + pack.meta().version()))
                .param("fingerprint", fingerprint(period.id()))
                .param("diff", JSON.writeValueAsString(diff(original.boxValues(), boxes)))
                .param("by", RequestContext.requireActor().actorId())
                .update();
        return find(id).orElseThrow();
    }

    /** Reads stored figures back, for comparing a locked return with its corrected form. */
    public static Map<String, BoxValue> parseValues(String json) {
        Map<String, Map<String, String>> raw = JSON.readValue(json, Map.class);
        Map<String, BoxValue> out = new LinkedHashMap<>();
        raw.forEach((code, v) -> out.put(code, new BoxValue(new BigDecimal(v.get("amount")), new BigDecimal(v.get("vat")), new BigDecimal(v.get("adjustment")))));
        return out;
    }

    /** Fast check of whether the ledger for a period has changed: new rows raise one of these values. */
    @Transactional(readOnly = true)
    public String fingerprint(UUID periodId) {
        return jdbc.sql("""
                SELECT count(*) FILTER (WHERE superseded_at IS NULL) || '|' || COALESCE(max(created_at)::text, '') || '|'
                       || COALESCE(max(superseded_at)::text, '')
                FROM tax_line WHERE period_id = ?
                """).param(periodId).query(String.class).single();
    }

    @Transactional(readOnly = true)
    public Optional<ReturnVersionView> latest(UUID periodId) {
        return jdbc.sql(ReturnVersionView.SELECT + " WHERE period_id = ? ORDER BY version_no DESC LIMIT 1")
                .param(periodId).query(ReturnVersionView.class).optional();
    }

    @Transactional(readOnly = true)
    public Optional<ReturnVersionView> find(UUID versionId) {
        return jdbc.sql(ReturnVersionView.SELECT + " WHERE id = ?").param(versionId).query(ReturnVersionView.class).optional();
    }

    @Transactional(readOnly = true)
    public List<ReturnVersionView> versions(UUID periodId) {
        return jdbc.sql(ReturnVersionView.SELECT + " WHERE period_id = ? ORDER BY version_no DESC")
                .param(periodId).query(ReturnVersionView.class).list();
    }

    private List<Aggregate> aggregates(UUID periodId) {
        return jdbc.sql("""
                SELECT direction, treatment, emirate, blocked_category IS NOT NULL AS blocked, line_kind, adj_box, member_entity_id,
                       SUM(net_aed) AS net, SUM(vat_aed) AS vat, SUM(recoverable_vat_aed) AS recoverable, COUNT(*) AS lines
                FROM tax_line
                WHERE period_id = ? AND superseded_at IS NULL AND memo = false
                GROUP BY direction, treatment, emirate, blocked_category IS NOT NULL, line_kind, adj_box, member_entity_id
                """).param(periodId).query(Aggregate.class).list();
    }

    /** Sorted, plain-string decimals, so the same figures always produce the same hash. */
    private static String canonicalValues(Map<String, BoxValue> boxes) {
        Map<String, Object> out = new LinkedHashMap<>();
        boxes.forEach((code, v) -> out.put(code, Map.of(
                "adjustment", v.adjustment().toPlainString(), "amount", v.amount().toPlainString(), "vat", v.vat().toPlainString())));
        return JsonMapper.builder().enable(tools.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build()
                .writeValueAsString(new TreeMap<>(out));
    }

    private static String membersJson(Map<UUID, Map<String, BoxValue>> perMember) {
        Map<String, Object> out = new TreeMap<>();
        perMember.forEach((member, boxes) -> {
            Map<String, Object> m = new TreeMap<>();
            boxes.forEach((code, v) -> {
                if (v.amount().signum() != 0 || v.vat().signum() != 0 || v.adjustment().signum() != 0) {
                    m.put(code, Map.of("adjustment", v.adjustment().toPlainString(), "amount", v.amount().toPlainString(), "vat", v.vat().toPlainString()));
                }
            });
            out.put(member.toString(), m);
        });
        return JSON.writeValueAsString(out);
    }

    /** Box by box changes against the previous version (RET-03). */
    private static List<Map<String, String>> diff(String previousJson, Map<String, BoxValue> now) {
        Map<String, Map<String, String>> previous = JSON.readValue(previousJson, Map.class);
        List<Map<String, String>> changes = new ArrayList<>();
        now.forEach((code, v) -> {
            Map<String, String> old = previous.getOrDefault(code, Map.of());
            for (String column : List.of("amount", "vat", "adjustment")) {
                BigDecimal before = new BigDecimal(old.getOrDefault(column, "0"));
                BigDecimal after = v.column(column);
                if (before.compareTo(after) != 0) {
                    changes.add(Map.of("box", code, "column", column, "from", before.toPlainString(), "to", after.toPlainString(),
                            "change", after.subtract(before).toPlainString()));
                }
            }
        });
        return changes;
    }
}
