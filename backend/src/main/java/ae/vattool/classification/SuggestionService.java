package ae.vattool.classification;

import ae.vattool.ledger.Ledger;
import ae.vattool.ledger.TaxLine;
import ae.vattool.masterdata.PeriodCalendar;
import ae.vattool.masterdata.TaxPeriod;
import ae.vattool.platform.audit.AuditLog;
import ae.vattool.platform.tenancy.RequestContext;
import ae.vattool.platform.web.DomainException;
import ae.vattool.rules.RulePackDefinition;
import ae.vattool.rules.RulePackStore;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Treatment suggestions with a confidence score (TXC-06, NFR-15). They come from this entity's own
 * confirmed history and the rule pack, and each is logged with its inputs and engine version. A
 * suggestion changes nothing: only a person accepting it reclassifies the line, through the ledger.
 */
@Service
public class SuggestionService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_LINES = 2000;

    public record Suggestion(UUID id, UUID periodId, UUID lineId, String currentTreatment, String treatment, BigDecimal confidence, String method, String reason, String inputs,
                             String engineVersion, String status, OffsetDateTime createdAt, String decidedBy, OffsetDateTime decidedAt, String docNumber, String counterpartyTrn, String description,
                             BigDecimal netAed, BigDecimal vatAed) {
        static final String SELECT = """
                SELECT s.id, s.period_id, s.line_id, s.current_treatment, s.treatment, s.confidence, s.method, s.reason, s.inputs::text AS inputs, s.engine_version, s.status, s.created_at, s.decided_by, s.decided_at,
                       d.doc_number, l.counterparty_trn, l.description, l.net_aed, l.vat_aed
                FROM classification_suggestion s
                JOIN tax_line l ON l.id = s.line_id
                LEFT JOIN source_document d ON d.id = l.document_id
                """;
    }

    public record Generated(int linesExamined, int suggestionsAdded, int noSuggestion) {}

    public record DecisionResult(int changed, List<UUID> newLineIds) {}

    private record Candidate(String treatment, BigDecimal confidence, String method, String reason, Map<String, Object> evidence) {}

    private record Line(UUID id, UUID logicalLineId, String direction, String treatment, String classifiedBy, String counterpartyTrn, String glAccount, String taxCode, String description) {}

    private record Tally(String treatment, long lines) {}

    private final JdbcClient jdbc;
    private final Ledger ledger;
    private final RulePackStore packs;
    private final PeriodCalendar calendar;
    private final AuditLog audit;

    public SuggestionService(JdbcClient jdbc, Ledger ledger, RulePackStore packs, PeriodCalendar calendar, AuditLog audit) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.packs = packs;
        this.calendar = calendar;
        this.audit = audit;
    }

    /** Looks at lines no person or mapping has settled. Running twice adds nothing new. */
    @Transactional
    public Generated generate(UUID periodId) {
        TaxPeriod p = calendar.get(periodId);
        requireOpen(p);
        RulePackDefinition.Suggestions rule = RulePackDefinition.require(packs.forDate(p.periodEnd()).definition().suggestions(), "suggestions");
        // A suggestion for a line that has since been revised no longer applies.
        jdbc.sql("""
                UPDATE classification_suggestion s SET status = 'STALE'
                WHERE s.period_id = ? AND s.status = 'PROPOSED'
                  AND NOT EXISTS (SELECT 1 FROM tax_line l WHERE l.id = s.line_id AND l.superseded_at IS NULL)
                """).param(periodId).update();
        List<Line> lines = jdbc.sql("""
                SELECT id, logical_line_id, direction, treatment, classified_by, counterparty_trn, gl_account, tax_code, description
                FROM tax_line
                WHERE period_id = ? AND superseded_at IS NULL AND line_kind = 'NORMAL' AND classified_by IN ('NONE','RULE') LIMIT ?
                """).params(periodId, MAX_LINES).query(Line.class).list();
        int added = 0;
        int none = 0;
        for (Line l : lines) {
            Optional<Candidate> best = best(p, l, rule);
            if (best.isEmpty()) {
                none++;
                continue;
            }
            Candidate c = best.get();
            Map<String, Object> inputs = new LinkedHashMap<>();
            inputs.put("counterpartyTrn", l.counterpartyTrn());
            inputs.put("glAccount", l.glAccount());
            inputs.put("taxCode", l.taxCode());
            inputs.put("description", l.description());
            inputs.putAll(c.evidence());
            added += jdbc.sql("""
                    INSERT INTO classification_suggestion (id, tenant_id, entity_id, period_id, line_id, logical_line_id, current_treatment, treatment, confidence, method, reason, inputs, engine_version, status)
                    VALUES (:id, :tenant, :entity, :period, :line, :logical, :current, :treatment, :confidence, :method, :reason, CAST(:inputs AS jsonb), :engine, 'PROPOSED')
                    ON CONFLICT (tenant_id, line_id, treatment) DO NOTHING
                    """)
                    .param("id", UUID.randomUUID()).param("tenant", RequestContext.requireTenant()).param("entity", p.entityId()).param("period", periodId).param("line", l.id())
                    .param("logical", l.logicalLineId()).param("current", l.treatment()).param("treatment", c.treatment()).param("confidence", c.confidence()).param("method", c.method())
                    .param("reason", c.reason()).param("inputs", JSON.writeValueAsString(inputs)).param("engine", rule.engineVersion())
                    .update();
        }
        return new Generated(lines.size(), added, none);
    }

    private Optional<Candidate> best(TaxPeriod p, Line l, RulePackDefinition.Suggestions rule) {
        List<Candidate> found = new ArrayList<>();
        fromHistory(p, l, rule, "counterparty_trn", l.counterpartyTrn(), "HISTORY_COUNTERPARTY", "this supplier or customer", new BigDecimal("0.95")).ifPresent(found::add);
        fromHistory(p, l, rule, "gl_account", l.glAccount(), "HISTORY_GL", "this GL account", new BigDecimal("0.85")).ifPresent(found::add);
        if (l.description() != null && rule.keywords() != null) {
            for (RulePackDefinition.SuggestionKeyword k : rule.keywords()) {
                if (Pattern.compile(k.pattern(), Pattern.CASE_INSENSITIVE).matcher(l.description()).find()) {
                    found.add(new Candidate(k.treatment(), k.confidence(), "KEYWORD", k.reason(), Map.of("pattern", k.pattern())));
                    break;
                }
            }
        }
        if ("RULE".equals(l.classifiedBy())) {
            found.add(new Candidate(l.treatment(), new BigDecimal("0.800"), "RULE_CONFIRM", "The place-of-supply rules chose this. Confirm or change it.", Map.of()));
        }
        return found.stream().max(java.util.Comparator.comparing(Candidate::confidence));
    }

    /** What this entity already settled for the same counterparty or account, weighted by how consistent it was. */
    private Optional<Candidate> fromHistory(TaxPeriod p, Line l, RulePackDefinition.Suggestions rule, String column, String value, String method, String what, BigDecimal ceiling) {
        if (value == null) {
            return Optional.empty();
        }
        List<Tally> tallies = jdbc.sql("""
                SELECT treatment, COUNT(*) AS lines FROM tax_line
                WHERE filer_entity_id = :filer AND superseded_at IS NULL AND direction = :direction AND line_kind = 'NORMAL' AND classified_by IN ('MAPPING','USER') AND %s = :value
                GROUP BY treatment ORDER BY COUNT(*) DESC
                """.formatted(column)).param("filer", p.entityId()).param("direction", l.direction()).param("value", value).query(Tally.class).list();
        long total = tallies.stream().mapToLong(Tally::lines).sum();
        if (total < rule.historyMinLines()) {
            return Optional.empty();
        }
        Tally top = tallies.getFirst();
        BigDecimal share = BigDecimal.valueOf(top.lines()).divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
        BigDecimal confidence = new BigDecimal("0.50").add(share.multiply(new BigDecimal("0.45"))).min(ceiling).setScale(3, RoundingMode.HALF_UP);
        return Optional.of(new Candidate(top.treatment(), confidence, method, top.lines() + " of " + total + " earlier lines for " + what + " were treated this way",
                Map.of("matchingLines", top.lines(), "totalLines", total)));
    }

    /** Applies suggestions a person accepted. A person decides every one, and the ledger keeps the old revision. */
    @Transactional
    public DecisionResult accept(List<UUID> ids) {
        List<UUID> newLines = new ArrayList<>();
        for (UUID id : ids) {
            record Row(UUID lineId, UUID entityId, UUID periodId, String treatment, String method, String status) {}
            Row s = jdbc.sql("SELECT line_id, entity_id, period_id, treatment, method, status FROM classification_suggestion WHERE id = ? FOR UPDATE").param(id).query(Row.class).optional()
                    .orElseThrow(() -> DomainException.notFound("Suggestion"));
            if (!"PROPOSED".equals(s.status())) {
                throw DomainException.conflict("ALREADY_DECIDED", "This suggestion has already been decided");
            }
            requireOpen(calendar.get(s.periodId()));
            TaxLine line = ledger.currentById(s.lineId()).orElseThrow(() -> DomainException.conflict("SUGGESTION_STALE", "This line has changed since the suggestion was made"));
            UUID revised = ledger.reclassify(line, s.treatment(), line.blockedCategory(), null, "SUGGESTION_ACCEPTED", "Accepted suggestion: " + s.method());
            jdbc.sql("UPDATE classification_suggestion SET status = 'ACCEPTED', decided_by = ?, decided_at = now() WHERE id = ?").params(RequestContext.requireActor().actorId(), id).update();
            audit.record("SUGGESTION_ACCEPTED", "CLASSIFICATION_SUGGESTION", id, s.entityId(), Map.of("treatment", line.treatment()), Map.of("treatment", s.treatment(), "line", revised.toString()), null);
            newLines.add(revised);
        }
        return new DecisionResult(newLines.size(), newLines);
    }

    @Transactional
    public void reject(UUID id) {
        record Row(UUID entityId, String status) {}
        Row s = jdbc.sql("SELECT entity_id, status FROM classification_suggestion WHERE id = ? FOR UPDATE").param(id).query(Row.class).optional()
                .orElseThrow(() -> DomainException.notFound("Suggestion"));
        if (!"PROPOSED".equals(s.status())) {
            throw DomainException.conflict("ALREADY_DECIDED", "This suggestion has already been decided");
        }
        jdbc.sql("UPDATE classification_suggestion SET status = 'REJECTED', decided_by = ?, decided_at = now() WHERE id = ?").params(RequestContext.requireActor().actorId(), id).update();
        audit.record("SUGGESTION_REJECTED", "CLASSIFICATION_SUGGESTION", id, s.entityId(), null, null, null);
    }

    @Transactional(readOnly = true)
    public List<Suggestion> list(UUID periodId, String status) {
        return jdbc.sql(Suggestion.SELECT + " WHERE s.period_id = :period AND (CAST(:status AS text) IS NULL OR s.status = :status) ORDER BY s.confidence DESC, s.created_at LIMIT 500")
                .param("period", periodId).param("status", status).query(Suggestion.class).list();
    }

    @Transactional(readOnly = true)
    public UUID entityOf(UUID suggestionId) {
        return jdbc.sql("SELECT entity_id FROM classification_suggestion WHERE id = ?").param(suggestionId).query(UUID.class).optional()
                .orElseThrow(() -> DomainException.notFound("Suggestion"));
    }

    private static void requireOpen(TaxPeriod p) {
        if (p.isLocked()) {
            throw DomainException.conflict("PERIOD_LOCKED", "This period is locked. Use a correction instead.");
        }
    }
}
