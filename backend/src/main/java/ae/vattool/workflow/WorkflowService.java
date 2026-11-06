package ae.vattool.workflow;

import ae.vattool.masterdata.LegalEntity;
import ae.vattool.masterdata.PeriodCalendar;
import ae.vattool.masterdata.TaxPeriod;
import ae.vattool.platform.audit.AuditLog;
import ae.vattool.platform.storage.VaultStorage;
import ae.vattool.platform.tenancy.RequestContext;
import ae.vattool.platform.web.DomainException;
import ae.vattool.reconciliation.ReconciliationService;
import ae.vattool.returns.PeriodDataChanged;
import ae.vattool.returns.ReturnService;
import ae.vattool.returns.ReturnVersionView;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The route from draft to locked (WF-01, RET-04, REC-03). Every rule is enforced here on the server,
 * whatever the UI shows. A sign-off binds to the exact figures and ledger state it was given on, and
 * nobody can check their own work.
 */
@Service
public class WorkflowService {

    public record Signoff(UUID id, String step, UUID versionId, String valuesHash, UUID userId, String userName, String note,
                          OffsetDateTime signedAt, OffsetDateTime invalidatedAt, String invalidatedReason) {}

    public record Status(TaxPeriod period, List<Signoff> signoffs, List<String> requiredSteps, boolean partnerRequired,
                         int blockingExceptions, int warnings, List<String> blockers, boolean canApprove) {}

    private static final String SIGNOFF_SELECT = """
            SELECT s.id, s.step, s.version_id, s.values_hash, s.user_id, u.display_name AS user_name, s.note, s.signed_at,
                   s.invalidated_at, s.invalidated_reason
            FROM signoff s JOIN app_user u ON u.id = s.user_id
            """;

    private final JdbcClient jdbc;
    private final ReturnService returns;
    private final ReconciliationService recon;
    private final PeriodCalendar calendar;
    private final VaultStorage vault;
    private final AuditLog audit;

    public WorkflowService(JdbcClient jdbc, ReturnService returns, ReconciliationService recon, PeriodCalendar calendar,
                           VaultStorage vault, AuditLog audit) {
        this.jdbc = jdbc;
        this.returns = returns;
        this.recon = recon;
        this.calendar = calendar;
        this.vault = vault;
        this.audit = audit;
    }

    /**
     * Brings the period up to date in its own transaction before a sign-off is attempted. If the data
     * changed, the cancelled sign-offs stay cancelled even when the step that follows is refused.
     */
    @Transactional
    public void refresh(UUID periodId) {
        recon.run(periodId, "PRE_SIGNOFF");
    }

    /** Preparer hands the return over. Reconciliation runs first, so the sign-off covers current data. */
    @Transactional
    public Status submit(UUID periodId, String note) {
        recon.run(periodId, "SUBMIT");
        TaxPeriod p = lock(periodId);
        requireState(p, "OPEN");
        ReturnVersionView v = latest(p);
        sign(p, v, "PREPARED", note);
        setState(p.id(), "IN_REVIEW");
        audit.record("RETURN_SUBMITTED", "TAX_PERIOD", p.id(), p.entityId(), Map.of("state", "OPEN"),
                Map.of("state", "IN_REVIEW", "version", v.versionNo(), "valuesHash", v.valuesHash()), note);
        return status(periodId);
    }

    @Transactional
    public Status review(UUID periodId, String note) {
        return signStep(periodId, "REVIEWED", note);
    }

    @Transactional
    public Status partnerSignoff(UUID periodId, String note) {
        return signStep(periodId, "PARTNER", note);
    }

    private Status signStep(UUID periodId, String step, String note) {
        recon.run(periodId, "SIGNOFF");
        TaxPeriod p = lock(periodId);
        requireState(p, "IN_REVIEW");
        requireLiveSignoff(p, "PREPARED");
        ReturnVersionView v = latest(p);
        requireNotAlreadySigned(p.id());
        sign(p, v, step, note);
        audit.record("RETURN_SIGNED_OFF", "TAX_PERIOD", p.id(), p.entityId(), null, Map.of("step", step, "version", v.versionNo()), note);
        return status(periodId);
    }

    /**
     * Approves and locks. The return is recomputed and checked inside this transaction while the
     * period row is held, so nothing can change between the checks and the lock.
     */
    @Transactional
    public Status approve(UUID periodId, String note) {
        recon.run(periodId, "APPROVAL");
        TaxPeriod p = lock(periodId);
        requireState(p, "IN_REVIEW");
        requireNotAlreadySigned(p.id());
        List<String> blockers = blockers(p);
        if (!blockers.isEmpty()) {
            throw new DomainException(org.springframework.http.HttpStatus.CONFLICT, "APPROVAL_BLOCKED",
                    "This return cannot be approved yet: " + String.join("; ", blockers), Map.of("blockers", blockers));
        }
        ReturnVersionView v = latest(p);
        sign(p, v, "APPROVED", note);
        jdbc.sql("UPDATE return_version SET status = 'LOCKED' WHERE id = ?").param(v.id()).update();
        jdbc.sql("UPDATE tax_period SET state = 'APPROVED', locked_version_id = ?, locked_at = now() WHERE id = ?")
                .params(v.id(), p.id()).update();
        recon.lockWithPeriod(p);
        // Store the approved figures in locked storage, along with where the audit chain stood. That
        // anchors the chain outside the database that holds it.
        String head = jdbc.sql("SELECT last_hash FROM audit_chain_head WHERE tenant_id = ?").param(RequestContext.requireTenant())
                .query(String.class).optional().orElse("GENESIS");
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("periodId", p.id().toString());
        snapshot.put("versionId", v.id().toString());
        snapshot.put("versionNo", v.versionNo());
        snapshot.put("valuesHash", v.valuesHash());
        snapshot.put("boxValues", JsonMapper.builder().build().readTree(v.boxValues()));
        snapshot.put("auditHeadAtLock", head);
        snapshot.put("lockedBy", RequestContext.requireUserId().toString());
        vault.put("t/" + RequestContext.requireTenant() + "/snapshots/" + p.id() + "/v" + v.versionNo() + "-" + System.currentTimeMillis() + ".json",
                JsonMapper.builder().build().writeValueAsString(snapshot).getBytes(StandardCharsets.UTF_8), "application/json",
                LocalDate.now().plusYears(5));
        audit.record("RETURN_APPROVED_AND_LOCKED", "TAX_PERIOD", p.id(), p.entityId(), Map.of("state", "IN_REVIEW"),
                Map.of("state", "APPROVED", "version", v.versionNo(), "valuesHash", v.valuesHash()), note);
        return status(periodId);
    }

    /** Sends it back for changes. A person's reason is required, since this undoes others' sign-offs. */
    @Transactional
    public Status reject(UUID periodId, String reason) {
        requireReason(reason);
        TaxPeriod p = lock(periodId);
        requireState(p, "IN_REVIEW");
        invalidate(p.id(), "Sent back: " + reason);
        setState(p.id(), "OPEN");
        audit.record("RETURN_SENT_BACK", "TAX_PERIOD", p.id(), p.entityId(), Map.of("state", "IN_REVIEW"), Map.of("state", "OPEN"), reason);
        return status(periodId);
    }

    /** Before filing, an approval can be taken back. After filing, only the correction flow applies. */
    @Transactional
    public Status withdrawApproval(UUID periodId, String reason) {
        requireReason(reason);
        TaxPeriod p = lock(periodId);
        requireState(p, "APPROVED");
        invalidate(p.id(), "Approval withdrawn: " + reason);
        jdbc.sql("UPDATE return_version SET status = 'DRAFT' WHERE id = ?").param(p.lockedVersionId()).update();
        jdbc.sql("UPDATE tax_period SET state = 'OPEN', locked_version_id = NULL, locked_at = NULL WHERE id = ?").param(p.id()).update();
        recon.unlockWithPeriod(p.id());
        audit.record("APPROVAL_WITHDRAWN", "TAX_PERIOD", p.id(), p.entityId(), Map.of("state", "APPROVED"), Map.of("state", "OPEN"), reason);
        return status(periodId);
    }

    /** When ledger data changes, sign-offs on the old data stop counting and the return goes back to open. */
    @EventListener
    public void onDataChanged(PeriodDataChanged event) {
        int invalidated = invalidate(event.periodId(), "The data changed after sign-off");
        if (invalidated > 0) {
            jdbc.sql("UPDATE tax_period SET state = 'OPEN' WHERE id = ? AND state = 'IN_REVIEW'").param(event.periodId()).update();
        }
    }

    @Transactional(readOnly = true)
    public Status status(UUID periodId) {
        TaxPeriod p = calendar.get(periodId);
        List<Signoff> signoffs = jdbc.sql(SIGNOFF_SELECT + " WHERE s.period_id = ? ORDER BY s.signed_at").param(periodId).query(Signoff.class).list();
        boolean partner = partnerRequired(p);
        List<String> required = requiredSteps(p, partner);
        List<String> blockers = p.isLocked() || !"IN_REVIEW".equals(p.state()) ? List.of() : blockers(p);
        int warnings = jdbc.sql("""
                SELECT COUNT(*) FROM recon_exception WHERE period_id = ? AND severity = 'WARNING' AND status IN ('OPEN','ASSIGNED')
                """).param(periodId).query(Integer.class).single();
        return new Status(p, signoffs, required, partner, recon.blockingOpen(periodId), warnings, blockers,
                "IN_REVIEW".equals(p.state()) && blockers.isEmpty());
    }

    private List<String> requiredSteps(TaxPeriod p, boolean partner) {
        LegalEntity e = entity(p);
        List<String> steps = new ArrayList<>(List.of("PREPARED"));
        if (e.requireReviewer()) {
            steps.add("REVIEWED");
        }
        if (partner) {
            steps.add("PARTNER");
        }
        steps.add("APPROVED");
        return steps;
    }

    /** Everything stopping approval, in words a person can act on. REC-03 is the first of these. */
    private List<String> blockers(TaxPeriod p) {
        List<String> out = new ArrayList<>();
        int blocking = recon.blockingOpen(p.id());
        if (blocking > 0) {
            out.add(blocking + " blocking exception(s) are open: " + String.join(", ", recon.ruleIdsWithOpenBlocking(p.id())));
        }
        ReturnVersionView v = latest(p);
        String fingerprint = returns.fingerprint(p.id());
        for (String step : requiredSteps(p, partnerRequired(p))) {
            if ("APPROVED".equals(step)) {
                continue;
            }
            boolean ok = jdbc.sql("""
                    SELECT EXISTS (SELECT 1 FROM signoff WHERE period_id = :period AND step = :step AND invalidated_at IS NULL
                                   AND values_hash = :hash AND data_fingerprint = :fingerprint)
                    """).param("period", p.id()).param("step", step).param("hash", v.valuesHash()).param("fingerprint", fingerprint)
                    .query(Boolean.class).single();
            if (!ok) {
                out.add("Sign-off missing: " + step);
            }
        }
        return out;
    }

    /** Partner sign-off applies above the entity's threshold, or if a blocking exception was waived. */
    private boolean partnerRequired(TaxPeriod p) {
        LegalEntity e = entity(p);
        if (e.partnerThresholdAed() == null) {
            return false;
        }
        var version = returns.latest(p.id());
        BigDecimal net = version.map(v -> {
            Map<String, Map<String, String>> boxes = JsonMapper.builder().build().readValue(v.boxValues(), Map.class);
            Map<String, String> box = boxes.get("14");
            return box == null ? BigDecimal.ZERO : new BigDecimal(box.get("vat"));
        }).orElse(BigDecimal.ZERO);
        if (net.abs().compareTo(e.partnerThresholdAed()) >= 0) {
            return true;
        }
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM recon_exception WHERE period_id = ? AND severity = 'BLOCKING' AND status = 'WAIVED')
                """).param(p.id()).query(Boolean.class).single();
    }

    private void sign(TaxPeriod p, ReturnVersionView v, String step, String note) {
        jdbc.sql("""
                INSERT INTO signoff (id, tenant_id, entity_id, period_id, step, version_id, values_hash, data_fingerprint, user_id, note)
                VALUES (:id, :tenant, :entity, :period, :step, :version, :hash, :fingerprint, :user, :note)
                """)
                .param("id", UUID.randomUUID()).param("tenant", RequestContext.requireTenant()).param("entity", p.entityId())
                .param("period", p.id()).param("step", step).param("version", v.id()).param("hash", v.valuesHash())
                .param("fingerprint", returns.fingerprint(p.id())).param("user", RequestContext.requireUserId()).param("note", note)
                .update();
    }

    /** Maker-checker: the same person cannot sign two steps of one return. */
    private void requireNotAlreadySigned(UUID periodId) {
        Set<UUID> signers = new HashSet<>(jdbc.sql("SELECT user_id FROM signoff WHERE period_id = ? AND invalidated_at IS NULL")
                .param(periodId).query(UUID.class).list());
        if (signers.contains(RequestContext.requireUserId())) {
            throw new DomainException(org.springframework.http.HttpStatus.FORBIDDEN, "MAKER_CHECKER",
                    "You have already signed this return. A different person must take this step.");
        }
    }

    private void requireLiveSignoff(TaxPeriod p, String step) {
        boolean live = jdbc.sql("SELECT EXISTS (SELECT 1 FROM signoff WHERE period_id = ? AND step = ? AND invalidated_at IS NULL)")
                .params(p.id(), step).query(Boolean.class).single();
        if (!live) {
            throw DomainException.conflict("SIGNOFF_MISSING", "The " + step + " sign-off is missing");
        }
    }

    private int invalidate(UUID periodId, String reason) {
        return jdbc.sql("""
                UPDATE signoff SET invalidated_at = now(), invalidated_reason = ? WHERE period_id = ? AND invalidated_at IS NULL
                """).params(reason, periodId).update();
    }

    private void setState(UUID periodId, String state) {
        jdbc.sql("UPDATE tax_period SET state = ? WHERE id = ?").params(state, periodId).update();
    }

    private TaxPeriod lock(UUID periodId) {
        return jdbc.sql(TaxPeriod.SELECT + " WHERE id = ? FOR UPDATE").param(periodId).query(TaxPeriod.class).optional()
                .orElseThrow(() -> DomainException.notFound("Tax period"));
    }

    private static void requireState(TaxPeriod p, String state) {
        if ("IN_REVIEW".equals(state) && "OPEN".equals(p.state())) {
            throw DomainException.conflict("BACK_TO_OPEN",
                    "This return is open, so it needs to be submitted first. If it was in review, the data changed and the sign-offs were cancelled.");
        }
        if (!state.equals(p.state())) {
            throw DomainException.conflict("WRONG_STATE", "This needs the period to be " + state + ", but it is " + p.state());
        }
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw DomainException.invalid("REASON_REQUIRED", "A reason is required");
        }
    }

    private ReturnVersionView latest(TaxPeriod p) {
        return returns.latest(p.id()).orElseThrow(() -> DomainException.conflict("NO_RETURN", "This period has no return yet"));
    }

    private LegalEntity entity(TaxPeriod p) {
        return jdbc.sql(LegalEntity.SELECT + " WHERE id = ?").param(p.entityId()).query(LegalEntity.class).single();
    }
}
