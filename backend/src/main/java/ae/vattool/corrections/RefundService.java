package ae.vattool.corrections;

import static ae.vattool.platform.documents.PdfRenderer.aed;
import static ae.vattool.platform.documents.PdfRenderer.esc;

import ae.vattool.evidence.EvidenceService;
import ae.vattool.masterdata.LegalEntity;
import ae.vattool.masterdata.PeriodCalendar;
import ae.vattool.masterdata.TaxPeriod;
import ae.vattool.platform.audit.AuditLog;
import ae.vattool.platform.documents.DocText;
import ae.vattool.platform.documents.GeneratedDocuments;
import ae.vattool.platform.documents.PdfRenderer;
import ae.vattool.platform.documents.XlsxBuilder;
import ae.vattool.platform.tenancy.RequestContext;
import ae.vattool.platform.web.DomainException;
import ae.vattool.returns.BoxCalculator.BoxValue;
import ae.vattool.returns.ReturnService;
import ae.vattool.returns.ReturnVersionView;
import ae.vattool.rules.RulePackDefinition;
import ae.vattool.rules.RulePackStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A claim for excess recoverable tax (COR-04, spec journey 5): flagged from a negative box 14, checked
 * against the time limit, prepared as a pack and followed until the money arrives.
 */
@Service
public class RefundService {

    private static final Set<String> FILED_STATES = Set.of("FILED", "PAID", "AMENDED");
    private static final int SUPPORTING_LINES = 50;

    public record Request(UUID id, UUID entityId, UUID periodId, UUID versionId, BigDecimal amountAed, LocalDate timeLimitOn, boolean transitional,
                          String status, String createdBy, OffsetDateTime createdAt, String submissionRef, LocalDate submittedOn, UUID ackEvidenceId,
                          LocalDate decidedOn, BigDecimal approvedAmountAed, String decisionNote, LocalDate receivedOn, BigDecimal receivedAmountAed,
                          UUID bankTxnId) {
        static final String SELECT = """
                SELECT id, entity_id, period_id, version_id, amount_aed, time_limit_on, transitional, status, created_by, created_at, submission_ref,
                       submitted_on, ack_evidence_id, decided_on, approved_amount_aed, decision_note, received_on, received_amount_aed, bank_txn_id
                FROM refund_request
                """;
    }

    public record Position(boolean refundable, boolean filed, BigDecimal excessAed, LocalDate timeLimitOn, boolean withinLimit, boolean transitional,
                           String form, Request request, List<GeneratedDocuments.Doc> documents) {}

    public record CreateInput(BigDecimal amountAed) {}

    public record SubmissionInput(String submissionRef, LocalDate submittedOn, UUID ackEvidenceId) {}

    public record DecisionInput(boolean approved, BigDecimal approvedAmountAed, LocalDate decidedOn, String note) {}

    public record ReceiptInput(LocalDate receivedOn, BigDecimal receivedAmountAed, UUID bankTxnId) {}

    private record SupportingLine(String docNumber, java.time.LocalDate docDate, String counterpartyName, String counterpartyTrn, BigDecimal netAed, BigDecimal recoverableAed) {}

    private final JdbcClient jdbc;
    private final PeriodCalendar calendar;
    private final ReturnService returns;
    private final RulePackStore packs;
    private final EvidenceService evidence;
    private final GeneratedDocuments documents;
    private final AuditLog audit;
    private final Clock clock = Clock.system(ZoneId.of("Asia/Dubai"));

    public RefundService(JdbcClient jdbc, PeriodCalendar calendar, ReturnService returns, RulePackStore packs, EvidenceService evidence,
                         GeneratedDocuments documents, AuditLog audit) {
        this.jdbc = jdbc;
        this.calendar = calendar;
        this.returns = returns;
        this.packs = packs;
        this.evidence = evidence;
        this.documents = documents;
        this.audit = audit;
    }

    /** The latest figures count, so a correction that changed the excess changes what can be claimed. */
    private BigDecimal excess(TaxPeriod p) {
        return returns.latest(p.id()).map(v -> ReturnService.parseValues(v.boxValues()).get("14")).map(BoxValue::vat)
                .map(BigDecimal::negate).orElse(BigDecimal.ZERO).max(BigDecimal.ZERO);
    }

    private RulePackDefinition.Refund rule() {
        return RulePackDefinition.require(packs.forDate(LocalDate.now(clock)).definition().refund(), "refund");
    }

    @Transactional
    public Position position(UUID periodId) {
        TaxPeriod p = calendar.get(periodId);
        RulePackDefinition.Refund rule = rule();
        BigDecimal excess = excess(p);
        RefundRules.Limit limit = RefundRules.check(rule, p.periodEnd(), LocalDate.now(clock));
        Optional<Request> request = current(periodId);
        return new Position(excess.signum() > 0, FILED_STATES.contains(p.state()), excess, limit.limitOn(), limit.allowed(), limit.transitional(), rule.form(),
                request.orElse(null), request.map(r -> documents.forObject(r.id().toString())).orElse(List.of()));
    }

    @Transactional
    public Position create(UUID periodId, CreateInput in) {
        TaxPeriod p = jdbc.sql(TaxPeriod.SELECT + " WHERE id = ? FOR UPDATE").param(periodId).query(TaxPeriod.class).optional()
                .orElseThrow(() -> DomainException.notFound("Tax period"));
        if (!FILED_STATES.contains(p.state())) {
            throw DomainException.conflict("PERIOD_NOT_FILED", "A refund is claimed on a filed return");
        }
        BigDecimal excess = excess(p);
        if (excess.signum() <= 0) {
            throw DomainException.conflict("NOT_REFUNDABLE", "This return has no excess recoverable tax to claim");
        }
        if (current(periodId).isPresent()) {
            throw DomainException.conflict("REFUND_EXISTS", "This period already has a refund request");
        }
        RefundRules.Limit limit = RefundRules.check(rule(), p.periodEnd(), LocalDate.now(clock));
        if (!limit.allowed()) {
            throw DomainException.conflict("REFUND_TIME_LIMIT_PASSED", "The time limit for claiming this refund passed on " + limit.limitOn());
        }
        BigDecimal amount = in == null || in.amountAed() == null ? excess : in.amountAed();
        if (amount.signum() <= 0 || amount.compareTo(excess) > 0) {
            throw DomainException.invalid("AMOUNT_INVALID", "The refund must be more than zero and no more than the excess of AED " + excess);
        }
        UUID id = UUID.randomUUID();
        ReturnVersionView version = returns.latest(periodId).orElseThrow();
        jdbc.sql("""
                INSERT INTO refund_request (id, tenant_id, entity_id, period_id, version_id, amount_aed, time_limit_on, transitional, status, created_by)
                VALUES (:id, :tenant, :entity, :period, :version, :amount, :limit, :transitional, 'DRAFT', :by)
                """)
                .param("id", id).param("tenant", RequestContext.requireTenant()).param("entity", p.entityId()).param("period", periodId)
                .param("version", version.id()).param("amount", amount).param("limit", limit.limitOn()).param("transitional", limit.transitional())
                .param("by", RequestContext.requireActor().actorId())
                .update();
        audit.record("REFUND_DRAFTED", "REFUND_REQUEST", id, p.entityId(), null, Map.of("amount", amount.toPlainString(), "timeLimitOn", limit.limitOn().toString()), null);
        return position(periodId);
    }

    @Transactional
    public Position generatePack(UUID id) {
        Request r = get(id);
        TaxPeriod p = calendar.get(r.periodId());
        ReturnVersionView v = returns.find(r.versionId()).orElseThrow();
        boolean draft = !packs.isSignedOff(v.rulePackId());
        LegalEntity e = jdbc.sql(LegalEntity.SELECT + " WHERE id = ?").param(r.entityId()).query(LegalEntity.class).single();
        Map<String, BoxValue> boxes = ReturnService.parseValues(v.boxValues());
        List<SupportingLine> lines = jdbc.sql("""
                SELECT d.doc_number, d.doc_date, d.counterparty_name, d.counterparty_trn, SUM(l.net_aed) AS net_aed, SUM(l.recoverable_vat_aed) AS recoverable_aed
                FROM tax_line l JOIN source_document d ON d.id = l.document_id
                WHERE l.period_id = :period AND l.superseded_at IS NULL AND l.direction = 'IN' AND l.recoverable_vat_aed <> 0
                GROUP BY d.id ORDER BY SUM(l.recoverable_vat_aed) DESC LIMIT :limit
                """).param("period", p.id()).param("limit", SUPPORTING_LINES).query(SupportingLine.class).list();
        for (String lang : List.of("en", "ar")) {
            documents.store(r.entityId(), r.periodId(), "ar".equals(lang) ? "REFUND_PDF_AR" : "REFUND_PDF", id, "refund-request-" + lang + ".pdf", "application/pdf",
                    pdf(lang, r, p, e, boxes, lines, draft), draft);
        }
        XlsxBuilder x = new XlsxBuilder("en", draft).sheet("Refund request");
        x.row(e.legalName(), "TRN " + e.trn()).row("Period " + p.periodStart() + " to " + p.periodEnd()).row("Amount claimed", null, r.amountAed())
                .row("Time limit", r.timeLimitOn().toString()).blank().header("Document", "Date", "Supplier", "TRN", "Net (AED)", "Recoverable VAT (AED)");
        lines.forEach(l -> x.row(l.docNumber(), String.valueOf(l.docDate()), l.counterpartyName(), l.counterpartyTrn(), l.netAed(), l.recoverableAed()));
        x.widths(22, 14, 40, 18, 16, 20);
        documents.store(r.entityId(), r.periodId(), "REFUND_XLSX", id, "refund-request.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", x.build(), draft);
        return position(r.periodId());
    }

    @Transactional
    public Position recordSubmission(UUID id, SubmissionInput in) {
        Request r = lock(id);
        requireStatus(r, "DRAFT");
        if (in.submissionRef() == null || in.submissionRef().isBlank()) {
            throw DomainException.invalid("SUBMISSION_REF_REQUIRED", "The EmaraTax submission reference is required");
        }
        if (in.submittedOn() == null) {
            throw DomainException.invalid("SUBMISSION_DATE_REQUIRED", "The submission date is required");
        }
        evidence.requireForEntity(in.ackEvidenceId(), r.entityId());
        jdbc.sql("UPDATE refund_request SET status = 'SUBMITTED', submission_ref = :ref, submitted_on = :on, ack_evidence_id = :ack WHERE id = :id")
                .param("ref", in.submissionRef()).param("on", in.submittedOn()).param("ack", in.ackEvidenceId()).param("id", id).update();
        evidence.link(in.ackEvidenceId(), "REFUND", id.toString());
        audit.record("REFUND_SUBMITTED", "REFUND_REQUEST", id, r.entityId(), Map.of("status", "DRAFT"),
                Map.of("status", "SUBMITTED", "submissionRef", in.submissionRef()), null);
        return position(r.periodId());
    }

    @Transactional
    public Position recordDecision(UUID id, DecisionInput in) {
        Request r = lock(id);
        requireStatus(r, "SUBMITTED");
        if (in.decidedOn() == null) {
            throw DomainException.invalid("DECISION_DATE_REQUIRED", "The decision date is required");
        }
        BigDecimal approved = in.approved() ? (in.approvedAmountAed() == null ? r.amountAed() : in.approvedAmountAed()) : null;
        if (approved != null && (approved.signum() <= 0 || approved.compareTo(r.amountAed()) > 0)) {
            throw DomainException.invalid("AMOUNT_INVALID", "The approved amount must be more than zero and no more than the amount claimed");
        }
        if (!in.approved() && (in.note() == null || in.note().isBlank())) {
            throw DomainException.invalid("REASON_REQUIRED", "Record why the FTA rejected the refund");
        }
        String status = in.approved() ? "APPROVED" : "REJECTED";
        jdbc.sql("UPDATE refund_request SET status = :status, decided_on = :on, approved_amount_aed = :amount, decision_note = :note WHERE id = :id")
                .param("status", status).param("on", in.decidedOn()).param("amount", approved).param("note", in.note()).param("id", id).update();
        audit.record("REFUND_DECIDED", "REFUND_REQUEST", id, r.entityId(), Map.of("status", "SUBMITTED"), Map.of("status", status), in.note());
        return position(r.periodId());
    }

    @Transactional
    public Position recordReceipt(UUID id, ReceiptInput in) {
        Request r = lock(id);
        requireStatus(r, "APPROVED");
        if (in.receivedOn() == null || in.receivedAmountAed() == null || in.receivedAmountAed().signum() <= 0) {
            throw DomainException.invalid("AMOUNT_INVALID", "A receipt needs a date and an amount of more than zero");
        }
        if (in.bankTxnId() != null) {
            record Txn(BigDecimal amount, String direction) {}
            Txn txn = jdbc.sql("SELECT amount, direction FROM bank_txn WHERE id = ? AND entity_id = ?").params(in.bankTxnId(), r.entityId()).query(Txn.class).optional()
                    .orElseThrow(() -> DomainException.invalid("BANK_TXN_UNKNOWN", "That bank transaction is not on this entity's statements"));
            if (!"CREDIT".equals(txn.direction()) || txn.amount().compareTo(in.receivedAmountAed()) != 0) {
                throw DomainException.invalid("BANK_TXN_MISMATCH", "That bank transaction is not a credit of AED " + in.receivedAmountAed());
            }
        }
        jdbc.sql("UPDATE refund_request SET status = 'RECEIVED', received_on = :on, received_amount_aed = :amount, bank_txn_id = :txn WHERE id = :id")
                .param("on", in.receivedOn()).param("amount", in.receivedAmountAed()).param("txn", in.bankTxnId()).param("id", id).update();
        audit.record("REFUND_RECEIVED", "REFUND_REQUEST", id, r.entityId(), Map.of("status", "APPROVED"),
                Map.of("status", "RECEIVED", "amount", in.receivedAmountAed().toPlainString(), "shortBy", r.approvedAmountAed().subtract(in.receivedAmountAed()).toPlainString()), null);
        return position(r.periodId());
    }

    @Transactional(readOnly = true)
    public Request get(UUID id) {
        return jdbc.sql(Request.SELECT + " WHERE id = ?").param(id).query(Request.class).optional().orElseThrow(() -> DomainException.notFound("Refund request"));
    }

    private Optional<Request> current(UUID periodId) {
        return jdbc.sql(Request.SELECT + " WHERE period_id = ? AND status <> 'REJECTED'").param(periodId).query(Request.class).optional();
    }

    private Request lock(UUID id) {
        return jdbc.sql(Request.SELECT + " WHERE id = ? FOR UPDATE").param(id).query(Request.class).optional().orElseThrow(() -> DomainException.notFound("Refund request"));
    }

    private static void requireStatus(Request r, String status) {
        if (!status.equals(r.status())) {
            throw DomainException.conflict("WRONG_STATE", "This needs the refund to be " + status + ", but it is " + r.status());
        }
    }

    private byte[] pdf(String lang, Request r, TaxPeriod p, LegalEntity e, Map<String, BoxValue> boxes, List<SupportingLine> lines, boolean draft) {
        DocText t = DocText.of(lang);
        StringBuilder h = new StringBuilder();
        h.append("<h1>").append(esc(t.t("refund.title"))).append("</h1><p class=\"meta\">").append(esc(e.legalName())).append(" &#183; ").append(esc(t.t("doc.trn")))
                .append(" <span class=\"ltr\">").append(esc(e.trn())).append("</span><br/>").append(esc(t.t("doc.period"))).append(" <span class=\"ltr\">")
                .append(p.periodStart()).append(" / ").append(p.periodEnd()).append("</span></p>");
        h.append("<h2>").append(esc(t.t("refund.position"))).append("</h2><table>");
        for (String code : List.of("12", "13", "14")) {
            BoxValue b = boxes.get(code);
            h.append("<tr><td>").append(esc(t.t("doc.box"))).append(" ").append(code).append("</td><td class=\"n\">").append(aed(b == null ? BigDecimal.ZERO : b.vat())).append("</td></tr>");
        }
        h.append("<tr class=\"total\"><td>").append(esc(t.t("refund.claimed"))).append("</td><td class=\"n\">").append(aed(r.amountAed())).append("</td></tr></table>");
        h.append("<p>").append(esc(t.t("refund.timeLimit"))).append(": <span class=\"ltr\">").append(r.timeLimitOn()).append("</span>");
        if (r.transitional()) {
            h.append(" &#183; ").append(esc(t.t("refund.transitional")));
        }
        h.append("</p><h2>").append(esc(t.t("refund.supporting", "count", lines.size()))).append("</h2><table><tr><th>").append(esc(t.t("doc.document"))).append("</th><th>")
                .append(esc(t.t("doc.date"))).append("</th><th>").append(esc(t.t("doc.supplier"))).append("</th><th class=\"n\">").append(esc(t.t("refund.recoverable"))).append("</th></tr>");
        for (SupportingLine l : lines) {
            h.append("<tr><td>").append(esc(l.docNumber())).append("</td><td class=\"ltr\">").append(l.docDate()).append("</td><td>").append(esc(l.counterpartyName() == null ? "" : l.counterpartyName()))
                    .append("</td><td class=\"n\">").append(aed(l.recoverableAed())).append("</td></tr>");
        }
        h.append("</table><p class=\"meta\">").append(esc(t.t("refund.entry"))).append("</p>");
        return PdfRenderer.render(t.t("refund.title"), h.toString(), lang, draft);
    }
}
