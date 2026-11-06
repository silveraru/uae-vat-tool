package ae.vattool.reconciliation;

import ae.vattool.masterdata.LegalEntity;
import ae.vattool.masterdata.PeriodCalendar;
import ae.vattool.masterdata.TaxPeriod;
import ae.vattool.platform.audit.AuditLog;
import ae.vattool.platform.tenancy.RequestContext;
import ae.vattool.platform.web.DomainException;
import ae.vattool.returns.ReturnService;
import ae.vattool.returns.ReturnVersionView;
import ae.vattool.rules.RulePackStore;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the checks and keeps exceptions current (REC-01, REC-02). A problem keeps the same exception
 * across runs: it closes when the check passes, reopens if it comes back, and a waiver lapses if
 * the figures behind it move, since the waiver only ever covered those figures.
 */
@Service
public class ReconciliationService {

    public record Exception(UUID id, UUID entityId, UUID periodId, String ruleId, String fingerprint, String severity,
                            String status, String message, String details, BigDecimal amountAed, UUID ownerId,
                            LocalDate dueDate, String resolutionCode, String resolutionNote, String resolvedBy,
                            OffsetDateTime resolvedAt, String waiverReason, UUID waiverApprover, OffsetDateTime waivedAt,
                            OffsetDateTime lockedAt, UUID carriedFromId, OffsetDateTime firstOpenedAt, OffsetDateTime lastSeenAt) {
        static final String SELECT = """
                SELECT id, entity_id, period_id, rule_id, fingerprint, severity, status, message, details::text AS details,
                       amount_aed, owner_id, due_date, resolution_code, resolution_note, resolved_by, resolved_at, waiver_reason,
                       waiver_approver, waived_at, locked_at, carried_from_id, first_opened_at, last_seen_at
                FROM recon_exception
                """;
    }

    public record Summary(UUID runId, int findings, int blockingOpen, int warningsOpen, int resolvedByRun, int reopened) {}

    private record Existing(UUID id, String ruleId, String fingerprint, String status, String dataHash, UUID carriedFromId) {}

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final Set<String> RESOLUTION_CODES = Set.of("FIXED_AT_SOURCE", "RECLASSIFIED", "ADJUSTED");

    private final JdbcClient jdbc;
    private final ReturnService returns;
    private final ReconChecks checks;
    private final DocumentMatcher matcher;
    private final PeriodCalendar calendar;
    private final AuditLog audit;
    private final io.micrometer.core.instrument.MeterRegistry metrics;

    public ReconciliationService(JdbcClient jdbc, ReturnService returns, ReconChecks checks, DocumentMatcher matcher,
                                 PeriodCalendar calendar, AuditLog audit, io.micrometer.core.instrument.MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.returns = returns;
        this.checks = checks;
        this.matcher = matcher;
        this.calendar = calendar;
        this.audit = audit;
        this.metrics = metrics;
    }

    /** Recomputes the return, then checks it. A locked period is frozen, so nothing runs against it. */
    @Transactional
    public Summary run(UUID periodId, String trigger) {
        OffsetDateTime started = OffsetDateTime.now();
        ReturnVersionView version = returns.recompute(periodId, trigger).orElseThrow();
        TaxPeriod period = jdbc.sql(TaxPeriod.SELECT + " WHERE id = ?").param(periodId).query(TaxPeriod.class).single();
        jdbc.sql("DELETE FROM period_recompute_request WHERE period_id = ?").param(periodId).update();
        if (period.isLocked()) {
            return new Summary(null, 0, 0, 0, 0, 0);
        }
        LegalEntity entity = jdbc.sql(LegalEntity.SELECT + " WHERE id = ?").param(period.entityId()).query(LegalEntity.class).single();
        RulePackStore.ActivePack pack = returns.packFor(period);
        matcher.match(period, pack.definition().reconciliation().perInvoiceToleranceAed(),
                pack.definition().reconciliation().fuzzyDateWindowDays());

        boolean earliest = jdbc.sql("""
                SELECT NOT EXISTS (SELECT 1 FROM tax_period p WHERE p.entity_id = :entity AND p.period_start < :start
                                   AND p.state IN ('OPEN', 'IN_REVIEW'))
                """).param("entity", period.entityId()).param("start", period.periodStart()).query(Boolean.class).single();
        Map<String, Map<String, String>> boxes = JSON.readValue(version.boxValues(), Map.class);
        List<Finding> findings = checks.runAll(new ReconChecks.Context(period, entity, pack.definition(), boxes, earliest));

        int[] counts = upsert(period, findings);
        UUID runId = UUID.randomUUID();
        Map<String, Long> byRule = new LinkedHashMap<>();
        findings.forEach(f -> byRule.merge(f.ruleId(), 1L, Long::sum));
        int blocking = openCount(periodId, "BLOCKING");
        int warnings = openCount(periodId, "WARNING");
        jdbc.sql("""
                INSERT INTO recon_run (id, tenant_id, entity_id, period_id, trigger, return_version_id, rule_pack_version, summary, started_at, finished_at)
                VALUES (:id, :tenant, :entity, :period, :trigger, :version, :pack, CAST(:summary AS jsonb), :started, now())
                """)
                .param("id", runId).param("tenant", RequestContext.requireTenant()).param("entity", period.entityId())
                .param("period", periodId).param("trigger", trigger).param("version", version.id())
                .param("pack", pack.meta().version()).param("summary", JSON.writeValueAsString(byRule)).param("started", started)
                .update();
        metrics.counter("vat.recon.runs", "trigger", trigger).increment();
        audit.record("RECONCILIATION_RUN", "RECON_RUN", runId, period.entityId(), null,
                Map.of("findings", findings.size(), "blockingOpen", blocking, "warningsOpen", warnings, "reopened", counts[1],
                        "closedByRun", counts[0]), trigger);
        return new Summary(runId, findings.size(), blocking, warnings, counts[0], counts[1]);
    }

    /** Returns {closed by this run, reopened by this run}. */
    private int[] upsert(TaxPeriod period, List<Finding> findings) {
        Map<String, Existing> existing = new HashMap<>();
        jdbc.sql("SELECT id, rule_id, fingerprint, status, data_hash, carried_from_id FROM recon_exception WHERE period_id = ?")
                .param(period.id()).query(Existing.class).list()
                .forEach(e -> existing.put(e.ruleId() + "|" + e.fingerprint(), e));
        Set<String> seen = new HashSet<>();
        int reopened = 0;
        for (Finding f : findings) {
            String key = f.ruleId() + "|" + f.fingerprint();
            seen.add(key);
            Existing e = existing.get(key);
            if (e == null) {
                insert(period, f);
                continue;
            }
            boolean reopen = "RESOLVED".equals(e.status())
                    || ("WAIVED".equals(e.status()) && !e.dataHash().equals(f.dataHash()));
            if (reopen) {
                reopened++;
            }
            jdbc.sql("""
                    UPDATE recon_exception SET message = :message, details = CAST(:details AS jsonb), amount_aed = :amount,
                        data_hash = :hash, last_seen_at = now(),
                        status = CASE WHEN :reopen THEN 'OPEN' ELSE status END,
                        resolution_code = CASE WHEN :reopen THEN NULL ELSE resolution_code END,
                        resolution_note = CASE WHEN :reopen THEN NULL ELSE resolution_note END,
                        resolved_by = CASE WHEN :reopen THEN NULL ELSE resolved_by END,
                        resolved_at = CASE WHEN :reopen THEN NULL ELSE resolved_at END,
                        waiver_reason = CASE WHEN :reopen THEN NULL ELSE waiver_reason END,
                        waiver_approver = CASE WHEN :reopen THEN NULL ELSE waiver_approver END,
                        waived_at = CASE WHEN :reopen THEN NULL ELSE waived_at END
                    WHERE id = :id
                    """)
                    .param("message", f.message()).param("details", JSON.writeValueAsString(f.details()))
                    .param("amount", f.amountAed()).param("hash", f.dataHash()).param("reopen", reopen).param("id", e.id())
                    .update();
        }
        int closed = 0;
        for (Map.Entry<String, Existing> entry : existing.entrySet()) {
            Existing e = entry.getValue();
            // A warning carried from an earlier period is about that period's data, so this
            // period's check cannot see it. It stays open until a person closes it.
            boolean active = "OPEN".equals(e.status()) || "ASSIGNED".equals(e.status()) || "WAIVED".equals(e.status());
            if (!seen.contains(entry.getKey()) && active && e.carriedFromId() == null) {
                jdbc.sql("""
                        UPDATE recon_exception SET status = 'RESOLVED', resolution_code = 'FIXED_AT_SOURCE',
                            resolution_note = 'The check now passes', resolved_by = 'system', resolved_at = now()
                        WHERE id = ?
                        """).param(e.id()).update();
                closed++;
            }
        }
        return new int[] {closed, reopened};
    }

    private void insert(TaxPeriod period, Finding f) {
        RuleCatalog.Rule rule = RuleCatalog.BY_ID.get(f.ruleId());
        jdbc.sql("""
                INSERT INTO recon_exception (id, tenant_id, entity_id, period_id, rule_id, fingerprint, severity, status, message,
                    details, amount_aed, data_hash, due_date)
                VALUES (:id, :tenant, :entity, :period, :rule, :fingerprint, :severity, 'OPEN', :message, CAST(:details AS jsonb),
                    :amount, :hash, :due)
                """)
                .param("id", UUID.randomUUID()).param("tenant", RequestContext.requireTenant()).param("entity", period.entityId())
                .param("period", period.id()).param("rule", f.ruleId()).param("fingerprint", f.fingerprint())
                .param("severity", rule.severity()).param("message", f.message()).param("details", JSON.writeValueAsString(f.details()))
                .param("amount", f.amountAed()).param("hash", f.dataHash()).param("due", period.dueDate().minusDays(7))
                .update();
    }

    private int openCount(UUID periodId, String severity) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM recon_exception WHERE period_id = ? AND severity = ? AND status IN ('OPEN', 'ASSIGNED')
                """).params(periodId, severity).query(Integer.class).single();
    }

    /** REC-03: the number that decides whether a return may be approved. */
    @Transactional(readOnly = true)
    public int blockingOpen(UUID periodId) {
        return openCount(periodId, "BLOCKING");
    }

    @Transactional(readOnly = true)
    public List<Exception> list(UUID periodId, String status, String severity) {
        return jdbc.sql(Exception.SELECT + """
                 WHERE period_id = :period
                   AND (CAST(:status AS text) IS NULL OR status = :status)
                   AND (CAST(:severity AS text) IS NULL OR severity = :severity)
                 ORDER BY CASE severity WHEN 'BLOCKING' THEN 0 ELSE 1 END,
                          CASE WHEN status IN ('OPEN', 'ASSIGNED') THEN 0 ELSE 1 END, rule_id, first_opened_at
                """).param("period", periodId).param("status", status).param("severity", severity)
                .query(Exception.class).list();
    }

    @Transactional(readOnly = true)
    public Exception get(UUID id) {
        return jdbc.sql(Exception.SELECT + " WHERE id = ?").param(id).query(Exception.class).optional()
                .orElseThrow(() -> DomainException.notFound("Exception"));
    }

    @Transactional
    public Exception assign(UUID id, UUID ownerId, LocalDate dueDate) {
        Exception before = requireChangeable(id);
        jdbc.sql("""
                UPDATE recon_exception SET owner_id = :owner, due_date = COALESCE(:due, due_date),
                    status = CASE WHEN status = 'OPEN' THEN 'ASSIGNED' ELSE status END
                WHERE id = :id
                """).param("owner", ownerId).param("due", dueDate).param("id", id).update();
        audit.record("EXCEPTION_ASSIGNED", "RECON_EXCEPTION", id, before.entityId(), Map.of("owner", String.valueOf(before.ownerId())),
                Map.of("owner", String.valueOf(ownerId)), null);
        return get(id);
    }

    /** A person marks a problem as dealt with. If the data still fails the check, the next run reopens it. */
    @Transactional
    public Exception resolve(UUID id, String code, String note) {
        if (code == null || !RESOLUTION_CODES.contains(code)) {
            throw DomainException.invalid("RESOLUTION_CODE_INVALID", "Resolution code must be one of " + RESOLUTION_CODES);
        }
        Exception before = requireChangeable(id);
        jdbc.sql("""
                UPDATE recon_exception SET status = 'RESOLVED', resolution_code = :code, resolution_note = :note,
                    resolved_by = :by, resolved_at = now()
                WHERE id = :id
                """).param("code", code).param("note", note).param("by", RequestContext.requireActor().actorId()).param("id", id).update();
        audit.record("EXCEPTION_RESOLVED", "RECON_EXCEPTION", id, before.entityId(), Map.of("status", before.status()),
                Map.of("status", "RESOLVED", "code", code), note);
        return get(id);
    }

    /** A waiver needs a reason and an approver who is not the person assigned to fix the problem. */
    @Transactional
    public Exception waive(UUID id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw DomainException.invalid("WAIVER_REASON_REQUIRED", "A waiver needs a reason");
        }
        Exception before = requireChangeable(id);
        UUID approver = RequestContext.requireUserId();
        if (approver.equals(before.ownerId())) {
            throw DomainException.forbidden("The person assigned to an exception cannot waive it");
        }
        jdbc.sql("""
                UPDATE recon_exception SET status = 'WAIVED', waiver_reason = :reason, waiver_approver = :approver, waived_at = now()
                WHERE id = :id
                """).param("reason", reason).param("approver", approver).param("id", id).update();
        audit.record("EXCEPTION_WAIVED", "RECON_EXCEPTION", id, before.entityId(), Map.of("status", before.status(), "rule", before.ruleId()),
                Map.of("status", "WAIVED"), reason);
        return get(id);
    }

    private Exception requireChangeable(UUID id) {
        Exception e = get(id);
        if (e.lockedAt() != null) {
            throw DomainException.conflict("EXCEPTION_LOCKED", "This exception is locked with its period");
        }
        return e;
    }

    /** At approval: exceptions freeze with the period, and open warnings carry forward with their age. */
    @Transactional
    public void lockWithPeriod(TaxPeriod period) {
        jdbc.sql("UPDATE recon_exception SET locked_at = now() WHERE period_id = ?").param(period.id()).update();
        TaxPeriod next = calendar.periodFor(period.entityId(), period.periodEnd().plusDays(1)).orElseThrow();
        jdbc.sql("""
                INSERT INTO recon_exception (id, tenant_id, entity_id, period_id, rule_id, fingerprint, severity, status, message,
                    details, amount_aed, data_hash, due_date, carried_from_id, first_opened_at)
                SELECT gen_random_uuid(), tenant_id, entity_id, :next, rule_id, fingerprint, severity, 'OPEN', message, details,
                    amount_aed, data_hash, :due, id, first_opened_at
                FROM recon_exception
                WHERE period_id = :period AND severity = 'WARNING' AND status IN ('OPEN', 'ASSIGNED')
                ON CONFLICT (tenant_id, period_id, rule_id, fingerprint) DO NOTHING
                """).param("next", next.id()).param("due", next.dueDate().minusDays(7)).param("period", period.id()).update();
    }

    /** When an approval is withdrawn before filing, the period's exceptions can be worked again. */
    @Transactional
    public void unlockWithPeriod(UUID periodId) {
        jdbc.sql("UPDATE recon_exception SET locked_at = NULL WHERE period_id = ?").param(periodId).update();
    }

    @Transactional
    public UUID addTimingItem(TaxPeriod period, String accountType, BigDecimal amount, String description) {
        if (description == null || description.isBlank()) {
            throw DomainException.invalid("TIMING_DESCRIPTION_REQUIRED", "A timing item needs a description");
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO recon_timing_item (id, tenant_id, period_id, account_type, amount_aed, description, created_by)
                VALUES (:id, :tenant, :period, :type, :amount, :description, :by)
                """).param("id", id).param("tenant", RequestContext.requireTenant()).param("period", period.id())
                .param("type", accountType).param("amount", amount).param("description", description)
                .param("by", RequestContext.requireActor().actorId()).update();
        audit.record("TIMING_ITEM_ADDED", "RECON_TIMING_ITEM", id, period.entityId(), null,
                Map.of("accountType", accountType, "amount", amount.toPlainString()), description);
        return id;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> timingItems(UUID periodId) {
        return jdbc.sql("SELECT id, account_type, amount_aed, description, created_by, created_at FROM recon_timing_item WHERE period_id = ? ORDER BY created_at")
                .param(periodId).query().listOfRows();
    }

    @Transactional(readOnly = true)
    public List<String> ruleIdsWithOpenBlocking(UUID periodId) {
        return new ArrayList<>(jdbc.sql("""
                SELECT DISTINCT rule_id FROM recon_exception WHERE period_id = ? AND severity = 'BLOCKING' AND status IN ('OPEN','ASSIGNED')
                """).param(periodId).query(String.class).list());
    }
}
