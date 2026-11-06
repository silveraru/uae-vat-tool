package ae.vattool.filing;

import ae.vattool.evidence.EvidenceService;
import ae.vattool.masterdata.PeriodCalendar;
import ae.vattool.masterdata.TaxPeriod;
import ae.vattool.platform.audit.AuditLog;
import ae.vattool.platform.tenancy.RequestContext;
import ae.vattool.platform.web.DomainException;
import ae.vattool.returns.BoxCalculator.BoxValue;
import ae.vattool.returns.ReturnService;
import ae.vattool.returns.ReturnVersionView;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records what happened outside the tool (FIL-02, FIL-03). In v1 a person submits on EmaraTax and
 * pays by bank transfer, so this captures the references and evidence. A filing is recorded even
 * under an unsigned rule pack, because it states what was actually submitted; the pack's watermark
 * is the control on that, and the audit event notes it. Payment counts only when a bank confirmation
 * agrees on reference and amount: statement import comes in Phase 2.
 */
@Service
public class FilingService {

    public record Filing(UUID id, UUID periodId, UUID versionId, String submissionRef, LocalDate submittedOn, UUID ackEvidenceId,
                         String recordedBy, OffsetDateTime recordedAt) {}

    public record Payment(UUID id, UUID periodId, BigDecimal amountDueAed, BigDecimal amountPaidAed, String gibanReference, LocalDate paidOn,
                          String bankReference, BigDecimal bankAmountAed, UUID confirmationEvidenceId, String matchStatus, String matchDetail,
                          String recordedBy, OffsetDateTime recordedAt) {}

    public record FilingInput(String submissionRef, LocalDate submittedOn, UUID ackEvidenceId) {}

    public record PaymentInput(BigDecimal amountPaidAed, String gibanReference, LocalDate paidOn, String bankReference, BigDecimal bankAmountAed,
                               UUID confirmationEvidenceId) {}

    public record Position(BigDecimal payable, boolean refundable, BigDecimal paidAndMatched, BigDecimal outstanding, Filing filing, List<Payment> payments) {}

    private final JdbcClient jdbc;
    private final PeriodCalendar calendar;
    private final ReturnService returns;
    private final EvidenceService evidence;
    private final AuditLog audit;

    public FilingService(JdbcClient jdbc, PeriodCalendar calendar, ReturnService returns, EvidenceService evidence, AuditLog audit) {
        this.jdbc = jdbc;
        this.calendar = calendar;
        this.returns = returns;
        this.evidence = evidence;
        this.audit = audit;
    }

    @Transactional
    public Position recordFiling(UUID periodId, FilingInput in) {
        TaxPeriod p = lock(periodId);
        if (!"APPROVED".equals(p.state())) {
            throw DomainException.conflict("WRONG_STATE", "Only an approved return can be recorded as filed. This one is " + p.state());
        }
        if (in.submissionRef() == null || in.submissionRef().isBlank()) {
            throw DomainException.invalid("SUBMISSION_REF_REQUIRED", "The EmaraTax submission reference is required");
        }
        if (in.submittedOn() == null) {
            throw DomainException.invalid("SUBMISSION_DATE_REQUIRED", "The submission date is required");
        }
        evidence.requireForEntity(in.ackEvidenceId(), p.entityId());
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO filing (id, tenant_id, entity_id, period_id, version_id, submission_ref, submitted_on, ack_evidence_id, recorded_by)
                VALUES (:id, :tenant, :entity, :period, :version, :ref, :on, :ack, :by)
                """)
                .param("id", id).param("tenant", RequestContext.requireTenant()).param("entity", p.entityId()).param("period", periodId)
                .param("version", p.lockedVersionId()).param("ref", in.submissionRef()).param("on", in.submittedOn())
                .param("ack", in.ackEvidenceId()).param("by", RequestContext.requireActor().actorId())
                .update();
        evidence.link(in.ackEvidenceId(), "FILING", id.toString());
        ReturnVersionView v = returns.find(p.lockedVersionId()).orElseThrow();
        // A return with nothing to pay has no payment step, so it is finished once filed.
        boolean nothingToPay = payable(v).signum() <= 0;
        jdbc.sql("UPDATE tax_period SET state = ? WHERE id = ?").params(nothingToPay ? "PAID" : "FILED", periodId).update();
        audit.record("RETURN_FILED", "TAX_PERIOD", periodId, p.entityId(), Map.of("state", "APPROVED"),
                Map.of("state", nothingToPay ? "PAID" : "FILED", "submissionRef", in.submissionRef(), "submittedOn", in.submittedOn().toString(),
                        "late", in.submittedOn().isAfter(p.dueDate())), null);
        return position(periodId);
    }

    @Transactional
    public Position recordPayment(UUID periodId, PaymentInput in) {
        TaxPeriod p = lock(periodId);
        if (!"FILED".equals(p.state())) {
            throw DomainException.conflict("WRONG_STATE", "A payment is recorded against a filed return. This one is " + p.state());
        }
        if (in.gibanReference() == null || in.gibanReference().isBlank()) {
            throw DomainException.invalid("GIBAN_REQUIRED", "The GIBAN payment reference is required");
        }
        if (in.amountPaidAed() == null || in.amountPaidAed().signum() <= 0) {
            throw DomainException.invalid("AMOUNT_INVALID", "The amount paid must be more than zero");
        }
        if (in.paidOn() == null) {
            throw DomainException.invalid("PAID_ON_REQUIRED", "The payment date is required");
        }
        if (in.confirmationEvidenceId() != null) {
            evidence.requireForEntity(in.confirmationEvidenceId(), p.entityId());
        }
        ReturnVersionView v = returns.find(p.lockedVersionId()).orElseThrow();
        BigDecimal due = payable(v);
        String status;
        String detail;
        if (in.confirmationEvidenceId() == null || in.bankReference() == null || in.bankAmountAed() == null) {
            status = "UNCONFIRMED";
            detail = "No bank confirmation yet, so the return stays unpaid";
        } else if (!in.bankReference().trim().equalsIgnoreCase(in.gibanReference().trim())) {
            status = "MISMATCHED";
            detail = "The bank reference " + in.bankReference() + " differs from the GIBAN reference " + in.gibanReference();
        } else if (in.bankAmountAed().compareTo(in.amountPaidAed()) != 0) {
            status = "MISMATCHED";
            detail = "The bank shows AED " + in.bankAmountAed() + " but AED " + in.amountPaidAed() + " was entered";
        } else {
            status = "MATCHED";
            detail = null;
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO payment (id, tenant_id, entity_id, period_id, amount_due_aed, amount_paid_aed, giban_reference, paid_on, bank_reference,
                    bank_amount_aed, confirmation_evidence_id, match_status, match_detail, recorded_by)
                VALUES (:id, :tenant, :entity, :period, :due, :paid, :giban, :on, :bankRef, :bankAmount, :evidence, :status, :detail, :by)
                """)
                .param("id", id).param("tenant", RequestContext.requireTenant()).param("entity", p.entityId()).param("period", periodId)
                .param("due", due).param("paid", in.amountPaidAed()).param("giban", in.gibanReference()).param("on", in.paidOn())
                .param("bankRef", in.bankReference()).param("bankAmount", in.bankAmountAed()).param("evidence", in.confirmationEvidenceId())
                .param("status", status).param("detail", detail).param("by", RequestContext.requireActor().actorId())
                .update();
        if (in.confirmationEvidenceId() != null) {
            evidence.link(in.confirmationEvidenceId(), "PAYMENT", id.toString());
        }
        BigDecimal matched = matchedTotal(periodId);
        boolean settled = matched.compareTo(due) >= 0;
        if (settled) {
            jdbc.sql("UPDATE tax_period SET state = 'PAID' WHERE id = ?").param(periodId).update();
        }
        audit.record("PAYMENT_RECORDED", "PAYMENT", id, p.entityId(), Map.of("state", "FILED"), Map.of("state", settled ? "PAID" : "FILED",
                "matchStatus", status, "amount", in.amountPaidAed().toPlainString(), "late", in.paidOn().isAfter(p.dueDate())), detail);
        return position(periodId);
    }

    @Transactional(readOnly = true)
    public Position position(UUID periodId) {
        TaxPeriod p = calendar.get(periodId);
        Optional<ReturnVersionView> version = p.lockedVersionId() == null ? Optional.empty() : returns.find(p.lockedVersionId());
        BigDecimal net = version.map(FilingService::payable).orElse(BigDecimal.ZERO);
        BigDecimal payable = net.max(BigDecimal.ZERO);
        BigDecimal matched = matchedTotal(periodId);
        Filing filing = jdbc.sql("""
                SELECT id, period_id, version_id, submission_ref, submitted_on, ack_evidence_id, recorded_by, recorded_at
                FROM filing WHERE period_id = ?
                """).param(periodId).query(Filing.class).optional().orElse(null);
        List<Payment> payments = jdbc.sql("""
                SELECT id, period_id, amount_due_aed, amount_paid_aed, giban_reference, paid_on, bank_reference, bank_amount_aed,
                       confirmation_evidence_id, match_status, match_detail, recorded_by, recorded_at
                FROM payment WHERE period_id = ? ORDER BY recorded_at
                """).param(periodId).query(Payment.class).list();
        return new Position(payable, net.signum() < 0, matched, payable.subtract(matched).max(BigDecimal.ZERO), filing, payments);
    }

    private BigDecimal matchedTotal(UUID periodId) {
        return jdbc.sql("SELECT COALESCE(SUM(amount_paid_aed), 0) FROM payment WHERE period_id = ? AND match_status = 'MATCHED'")
                .param(periodId).query(BigDecimal.class).single();
    }

    private static BigDecimal payable(ReturnVersionView v) {
        BoxValue box = ReturnService.parseValues(v.boxValues()).get("14");
        return box == null ? BigDecimal.ZERO : box.vat();
    }

    private TaxPeriod lock(UUID periodId) {
        return jdbc.sql(TaxPeriod.SELECT + " WHERE id = ? FOR UPDATE").param(periodId).query(TaxPeriod.class).optional()
                .orElseThrow(() -> DomainException.notFound("Tax period"));
    }
}
