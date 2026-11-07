package ae.vattool.corrections;

import static ae.vattool.platform.documents.PdfRenderer.aed;
import static ae.vattool.platform.documents.PdfRenderer.esc;

import ae.vattool.adjustments.CorrectionService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Voluntary disclosure on Form 211 (COR-02, spec journey 3). A person proposes it from a correction
 * routed to Form 211, a different person approves it, and the agent submits it on EmaraTax and records
 * the reference. The pack shows the original and corrected figures, and the filed return is untouched.
 */
@Service
public class DisclosureService {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public record Disclosure(UUID id, UUID entityId, UUID periodId, UUID correctionId, LocalDate discoveredOn, LocalDate dueBy, String natureOfError,
                             String status, String penaltyEstimate, String createdBy, OffsetDateTime createdAt, String approvedBy,
                             OffsetDateTime approvedAt, String submissionRef, LocalDate submittedOn, UUID ackEvidenceId, LocalDate settledOn,
                             BigDecimal settledAmountAed, String gibanReference) {
        static final String SELECT = """
                SELECT id, entity_id, period_id, correction_id, discovered_on, due_by, nature_of_error, status, penalty_estimate::text AS penalty_estimate,
                       created_by, created_at, approved_by, approved_at, submission_ref, submitted_on, ack_evidence_id, settled_on, settled_amount_aed, giban_reference
                FROM voluntary_disclosure
                """;
    }

    public record CreateInput(LocalDate discoveredOn, String natureOfError) {}

    public record SubmissionInput(String submissionRef, LocalDate submittedOn, UUID ackEvidenceId) {}

    public record SettlementInput(LocalDate settledOn, BigDecimal amountAed, String gibanReference) {}

    public record BoxRow(String box, String label, BigDecimal amountFrom, BigDecimal amountTo, BigDecimal vatFrom, BigDecimal vatTo,
                         BigDecimal adjustmentFrom, BigDecimal adjustmentTo) {}

    public record View(Disclosure disclosure, boolean overdue, String form, List<BoxRow> boxes, BigDecimal taxDifferenceAed,
                       PenaltyCalculator.Estimate penalty, List<GeneratedDocuments.Doc> documents) {}

    private final JdbcClient jdbc;
    private final CorrectionService corrections;
    private final PeriodCalendar calendar;
    private final ReturnService returns;
    private final RulePackStore packs;
    private final PenaltyService penalties;
    private final EvidenceService evidence;
    private final GeneratedDocuments documents;
    private final AuditLog audit;
    private final Clock clock = Clock.system(ZoneId.of("Asia/Dubai"));

    public DisclosureService(JdbcClient jdbc, CorrectionService corrections, PeriodCalendar calendar, ReturnService returns, RulePackStore packs,
                             PenaltyService penalties, EvidenceService evidence, GeneratedDocuments documents, AuditLog audit) {
        this.jdbc = jdbc;
        this.corrections = corrections;
        this.calendar = calendar;
        this.returns = returns;
        this.packs = packs;
        this.penalties = penalties;
        this.evidence = evidence;
        this.documents = documents;
        this.audit = audit;
    }

    @Transactional
    public View create(UUID correctionId, CreateInput in) {
        CorrectionService.CorrectionCase c = corrections.get(correctionId);
        if (!"FORM_211".equals(c.route())) {
            throw DomainException.conflict("NOT_A_DISCLOSURE", "This correction goes in the next return, so it needs no Form 211");
        }
        if (in.discoveredOn() == null) {
            throw DomainException.invalid("DISCOVERY_DATE_REQUIRED", "The date the error was discovered is required");
        }
        if (in.discoveredOn().isAfter(LocalDate.now(clock))) {
            throw DomainException.invalid("DISCOVERY_DATE_FUTURE", "The discovery date cannot be in the future");
        }
        if (in.natureOfError() == null || in.natureOfError().isBlank()) {
            throw DomainException.invalid("REASON_REQUIRED", "Describe the nature of the error");
        }
        if (jdbc.sql("SELECT EXISTS (SELECT 1 FROM voluntary_disclosure WHERE correction_id = ?)").param(correctionId).query(Boolean.class).single()) {
            throw DomainException.conflict("DISCLOSURE_EXISTS", "This correction already has a voluntary disclosure");
        }
        TaxPeriod p = calendar.get(c.periodId());
        RulePackDefinition def = packs.forDate(LocalDate.now(clock)).definition();
        RulePackDefinition.Disclosure rule = RulePackDefinition.require(def.disclosure(), "disclosure");
        LocalDate dueBy = PenaltyCalculator.addBusinessDays(in.discoveredOn(), rule.deadlineBusinessDays(), def.filing().weekendDays());
        UUID id = UUID.randomUUID();
        PenaltyCalculator.Estimate estimate = penalties.forCorrection(c, null, null);
        jdbc.sql("""
                INSERT INTO voluntary_disclosure (id, tenant_id, entity_id, period_id, correction_id, discovered_on, due_by, nature_of_error, status, penalty_estimate, created_by)
                VALUES (:id, :tenant, :entity, :period, :correction, :discovered, :due, :nature, 'DRAFT', CAST(:estimate AS jsonb), :by)
                """)
                .param("id", id).param("tenant", RequestContext.requireTenant()).param("entity", p.entityId()).param("period", p.id())
                .param("correction", correctionId).param("discovered", in.discoveredOn()).param("due", dueBy).param("nature", in.natureOfError())
                .param("estimate", JSON.writeValueAsString(estimate)).param("by", RequestContext.requireActor().actorId())
                .update();
        audit.record("DISCLOSURE_DRAFTED", "VOLUNTARY_DISCLOSURE", id, p.entityId(), null,
                Map.of("correction", correctionId.toString(), "taxDifference", c.taxDifferenceAed().toPlainString(), "dueBy", dueBy.toString()), in.natureOfError());
        return view(id);
    }

    /** Whoever drafted it cannot approve it, and a high-exposure disclosure needs the partner. */
    @Transactional
    public View approve(UUID id, boolean partnerAllowed) {
        Disclosure d = lock(id);
        if (!"DRAFT".equals(d.status())) {
            throw DomainException.conflict("WRONG_STATE", "Only a draft disclosure can be approved. This one is " + d.status());
        }
        String me = RequestContext.requireActor().actorId();
        if (me.equals(d.createdBy())) {
            throw new DomainException(HttpStatus.FORBIDDEN, "MAKER_CHECKER", "You drafted this disclosure, so a different person must approve it");
        }
        CorrectionService.CorrectionCase c = corrections.get(d.correctionId());
        BigDecimal threshold = jdbc.sql("SELECT partner_threshold_aed FROM legal_entity WHERE id = ?").param(d.entityId())
                .query(BigDecimal.class).optional().orElse(null);
        if (threshold != null && c.taxDifferenceAed().abs().compareTo(threshold) >= 0 && !partnerAllowed) {
            throw new DomainException(HttpStatus.FORBIDDEN, "PARTNER_REQUIRED", "A disclosure of this size needs the engagement partner's approval");
        }
        jdbc.sql("UPDATE voluntary_disclosure SET status = 'APPROVED', approved_by = ?, approved_at = now() WHERE id = ?").params(me, id).update();
        audit.record("DISCLOSURE_APPROVED", "VOLUNTARY_DISCLOSURE", id, d.entityId(), Map.of("status", "DRAFT"), Map.of("status", "APPROVED"), null);
        return view(id);
    }

    @Transactional
    public View recordSubmission(UUID id, SubmissionInput in) {
        Disclosure d = lock(id);
        if (!"APPROVED".equals(d.status())) {
            throw DomainException.conflict("WRONG_STATE", "A disclosure must be approved before it is submitted. This one is " + d.status());
        }
        if (in.submissionRef() == null || in.submissionRef().isBlank()) {
            throw DomainException.invalid("SUBMISSION_REF_REQUIRED", "The EmaraTax submission reference is required");
        }
        if (in.submittedOn() == null) {
            throw DomainException.invalid("SUBMISSION_DATE_REQUIRED", "The submission date is required");
        }
        evidence.requireForEntity(in.ackEvidenceId(), d.entityId());
        // The exposure is worked out again now the real disclosure date is known.
        PenaltyCalculator.Estimate estimate = penalties.forCorrection(corrections.get(d.correctionId()), in.submittedOn(), in.submittedOn());
        jdbc.sql("""
                UPDATE voluntary_disclosure SET status = 'SUBMITTED', submission_ref = :ref, submitted_on = :on, ack_evidence_id = :ack,
                    penalty_estimate = CAST(:estimate AS jsonb) WHERE id = :id
                """).param("ref", in.submissionRef()).param("on", in.submittedOn()).param("ack", in.ackEvidenceId())
                .param("estimate", JSON.writeValueAsString(estimate)).param("id", id).update();
        evidence.link(in.ackEvidenceId(), "DISCLOSURE", id.toString());
        audit.record("DISCLOSURE_SUBMITTED", "VOLUNTARY_DISCLOSURE", id, d.entityId(), Map.of("status", "APPROVED"),
                Map.of("status", "SUBMITTED", "submissionRef", in.submissionRef(), "submittedOn", in.submittedOn().toString(),
                        "late", in.submittedOn().isAfter(d.dueBy())), null);
        return view(id);
    }

    @Transactional
    public View recordSettlement(UUID id, SettlementInput in) {
        Disclosure d = lock(id);
        if (!"SUBMITTED".equals(d.status())) {
            throw DomainException.conflict("WRONG_STATE", "Settlement follows submission. This disclosure is " + d.status());
        }
        if (in.settledOn() == null || in.amountAed() == null || in.amountAed().signum() < 0) {
            throw DomainException.invalid("AMOUNT_INVALID", "A settlement needs a date and an amount of zero or more");
        }
        jdbc.sql("""
                UPDATE voluntary_disclosure SET status = 'SETTLED', settled_on = :on, settled_amount_aed = :amount, giban_reference = :giban WHERE id = :id
                """).param("on", in.settledOn()).param("amount", in.amountAed()).param("giban", in.gibanReference()).param("id", id).update();
        audit.record("DISCLOSURE_SETTLED", "VOLUNTARY_DISCLOSURE", id, d.entityId(), Map.of("status", "SUBMITTED"),
                Map.of("status", "SETTLED", "amount", in.amountAed().toPlainString()), null);
        return view(id);
    }

    /** Generates the pack from the case as it stands. Every format carries the watermark while the rules are unsigned. */
    @Transactional
    public View generatePack(UUID id) {
        Disclosure d = get(id);
        CorrectionService.CorrectionCase c = corrections.get(d.correctionId());
        TaxPeriod p = calendar.get(d.periodId());
        ReturnVersionView original = returns.find(c.originalVersionId()).orElseThrow();
        boolean draft = !packs.isSignedOff(original.rulePackId());
        LegalEntity entity = jdbc.sql(LegalEntity.SELECT + " WHERE id = ?").param(d.entityId()).query(LegalEntity.class).single();
        List<BoxRow> boxes = boxes(c, original);
        PenaltyCalculator.Estimate penalty = currentPenalty(c, d);
        List<EvidenceService.Evidence> attached = new ArrayList<>(evidence.forObject("CORRECTION", c.id().toString()));
        attached.addAll(evidence.forObject("DISCLOSURE", id.toString()));
        for (String lang : List.of("en", "ar")) {
            byte[] pdf = pdf(lang, d, c, p, entity, boxes, penalty, attached, draft);
            documents.store(d.entityId(), d.periodId(), "ar".equals(lang) ? "FORM_211_PDF_AR" : "FORM_211_PDF", id, "form-211-" + lang + ".pdf", "application/pdf", pdf, draft);
        }
        documents.store(d.entityId(), d.periodId(), "FORM_211_XLSX", id, "form-211.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx(d, c, p, entity, boxes, penalty, draft), draft);
        return view(id);
    }

    @Transactional(readOnly = true)
    public Disclosure get(UUID id) {
        return jdbc.sql(Disclosure.SELECT + " WHERE id = ?").param(id).query(Disclosure.class).optional()
                .orElseThrow(() -> DomainException.notFound("Voluntary disclosure"));
    }

    @Transactional(readOnly = true)
    public java.util.Optional<Disclosure> forCorrection(UUID correctionId) {
        return jdbc.sql(Disclosure.SELECT + " WHERE correction_id = ?").param(correctionId).query(Disclosure.class).optional();
    }

    @Transactional
    public View view(UUID id) {
        Disclosure d = get(id);
        CorrectionService.CorrectionCase c = corrections.get(d.correctionId());
        ReturnVersionView original = returns.find(c.originalVersionId()).orElseThrow();
        String form = RulePackDefinition.require(packs.forDate(LocalDate.now(clock)).definition().disclosure(), "disclosure").form();
        boolean overdue = d.submittedOn() == null ? LocalDate.now(clock).isAfter(d.dueBy()) : d.submittedOn().isAfter(d.dueBy());
        return new View(d, overdue, form, boxes(c, original), c.taxDifferenceAed(), currentPenalty(c, d), documents.forObject(id.toString()));
    }

    private PenaltyCalculator.Estimate currentPenalty(CorrectionService.CorrectionCase c, Disclosure d) {
        return d.submittedOn() != null ? penalties.forCorrection(c, d.submittedOn(), d.submittedOn()) : penalties.forCorrection(c, null, null);
    }

    private List<BoxRow> boxes(CorrectionService.CorrectionCase c, ReturnVersionView original) {
        ReturnVersionView corrected = returns.find(c.correctedVersionId()).orElseThrow();
        RulePackDefinition def = packs.definition(original.rulePackId());
        Map<String, BoxValue> from = ReturnService.parseValues(original.boxValues());
        Map<String, BoxValue> to = ReturnService.parseValues(corrected.boxValues());
        List<BoxRow> rows = new ArrayList<>();
        for (RulePackDefinition.Box b : def.form().boxes()) {
            BoxValue x = from.get(b.code());
            BoxValue y = to.get(b.code());
            if (x == null || y == null) {
                continue;
            }
            rows.add(new BoxRow(b.code(), b.label().get("en"), x.amount(), y.amount(), x.vat(), y.vat(), x.adjustment(), y.adjustment()));
        }
        return rows;
    }

    private Disclosure lock(UUID id) {
        return jdbc.sql(Disclosure.SELECT + " WHERE id = ? FOR UPDATE").param(id).query(Disclosure.class).optional()
                .orElseThrow(() -> DomainException.notFound("Voluntary disclosure"));
    }

    private byte[] pdf(String lang, Disclosure d, CorrectionService.CorrectionCase c, TaxPeriod p, LegalEntity e, List<BoxRow> boxes,
                       PenaltyCalculator.Estimate penalty, List<EvidenceService.Evidence> attached, boolean draft) {
        DocText t = DocText.of(lang);
        RulePackDefinition def = packs.definition(returns.find(c.originalVersionId()).orElseThrow().rulePackId());
        StringBuilder h = new StringBuilder();
        h.append("<h1>").append(esc(t.t("form211.title"))).append("</h1><p class=\"meta\">").append(esc(e.legalName())).append(" &#183; ")
                .append(esc(t.t("doc.trn"))).append(" <span class=\"ltr\">").append(esc(e.trn())).append("</span><br/>")
                .append(esc(t.t("doc.period"))).append(" <span class=\"ltr\">").append(p.periodStart()).append(" / ").append(p.periodEnd()).append("</span> &#183; ")
                .append(esc(t.t("doc.due"))).append(" <span class=\"ltr\">").append(p.dueDate()).append("</span></p>");
        h.append("<h2>").append(esc(t.t("form211.error"))).append("</h2><p>").append(esc(d.natureOfError())).append("</p><p>")
                .append(esc(t.t("form211.discovered"))).append(": <span class=\"ltr\">").append(d.discoveredOn()).append("</span> &#183; ")
                .append(esc(t.t("form211.dueBy"))).append(": <span class=\"ltr\">").append(d.dueBy()).append("</span><br/>")
                .append(esc(t.t("form211.reason"))).append(": ").append(esc(c.reason())).append("</p>");
        h.append("<h2>").append(esc(t.t("form211.figures"))).append("</h2><table><tr><th>").append(esc(t.t("doc.box"))).append("</th><th>")
                .append(esc(t.t("doc.description"))).append("</th><th class=\"n\">").append(esc(t.t("form211.vatFiled"))).append("</th><th class=\"n\">")
                .append(esc(t.t("form211.vatCorrected"))).append("</th><th class=\"n\">").append(esc(t.t("form211.vatDifference"))).append("</th></tr>");
        for (BoxRow b : boxes) {
            RulePackDefinition.Box box = def.form().boxes().stream().filter(x -> x.code().equals(b.box())).findFirst().orElseThrow();
            h.append(box.isComputed() ? "<tr class=\"total\">" : "<tr>").append("<td>").append(esc(b.box())).append("</td><td>").append(esc(box.label().getOrDefault(lang, b.label())))
                    .append("</td><td class=\"n\">").append(aed(b.vatFrom())).append("</td><td class=\"n\">").append(aed(b.vatTo())).append("</td><td class=\"n\">")
                    .append(aed(b.vatTo().subtract(b.vatFrom()))).append("</td></tr>");
        }
        h.append("</table><p><strong>").append(esc(t.t("form211.taxDifference"))).append(": AED ").append(aed(c.taxDifferenceAed())).append("</strong></p>");
        h.append("<h2>").append(esc(t.t("penalty.title"))).append("</h2>");
        if (!penalty.scheduleFound()) {
            h.append("<p class=\"bad\">").append(esc(t.t("penalty.noSchedule"))).append("</p>");
        } else {
            h.append("<p class=\"meta\">").append(esc(t.t("penalty.estimateNote", "source", penalty.source(), "date", penalty.asOf()))).append("</p><table><tr><th>")
                    .append(esc(t.t("doc.description"))).append("</th><th class=\"n\">").append(esc(t.t("penalty.months"))).append("</th><th class=\"n\">").append(esc(t.t("doc.amount"))).append("</th></tr>");
            for (PenaltyCalculator.Item i : penalty.items()) {
                h.append("<tr><td>").append(esc(t.t("penalty." + i.code()))).append("</td><td class=\"n\">").append(i.months() > 0 ? i.months() : "").append("</td><td class=\"n\">").append(aed(i.amount())).append("</td></tr>");
            }
            h.append("<tr class=\"total\"><td>").append(esc(t.t("doc.total"))).append("</td><td></td><td class=\"n\">").append(aed(penalty.total())).append("</td></tr></table>");
        }
        h.append("<h2>").append(esc(t.t("doc.evidence"))).append("</h2>");
        if (attached.isEmpty()) {
            h.append("<p class=\"meta\">").append(esc(t.t("doc.noEvidence"))).append("</p>");
        } else {
            h.append("<table><tr><th>").append(esc(t.t("doc.file"))).append("</th><th>SHA-256</th></tr>");
            for (EvidenceService.Evidence ev : attached) {
                h.append("<tr><td>").append(esc(ev.fileName())).append("</td><td class=\"hash\">").append(esc(ev.sha256())).append("</td></tr>");
            }
            h.append("</table>");
        }
        h.append("<h2>").append(esc(t.t("form211.status"))).append("</h2><p>").append(esc(t.t("form211.state." + d.status()))).append("</p>");
        return PdfRenderer.render(t.t("form211.title"), h.toString(), lang, draft);
    }

    private byte[] xlsx(Disclosure d, CorrectionService.CorrectionCase c, TaxPeriod p, LegalEntity e, List<BoxRow> boxes,
                        PenaltyCalculator.Estimate penalty, boolean draft) {
        DocText t = DocText.of("en");
        XlsxBuilder x = new XlsxBuilder("en", draft).sheet("Form 211");
        x.row(e.legalName(), "TRN " + e.trn()).row("Period " + p.periodStart() + " to " + p.periodEnd(), "Discovered " + d.discoveredOn()).row(d.natureOfError()).blank();
        x.header("Box", "Description", "Amount filed", "Amount corrected", "VAT filed", "VAT corrected", "Adjustment filed", "Adjustment corrected", "VAT difference");
        for (BoxRow b : boxes) {
            x.row(b.box(), b.label(), b.amountFrom(), b.amountTo(), b.vatFrom(), b.vatTo(), b.adjustmentFrom(), b.adjustmentTo(), b.vatTo().subtract(b.vatFrom()));
        }
        x.widths(8, 50, 16, 16, 16, 16, 16, 16, 16);
        x.sheet("Penalty exposure");
        if (!penalty.scheduleFound()) {
            x.row(t.t("penalty.noSchedule"));
        } else {
            x.row(t.t("penalty.estimateNote", "source", penalty.source(), "date", penalty.asOf())).header("Item", "Months", "AED");
            penalty.items().forEach(i -> x.row(t.t("penalty." + i.code()), i.months(), i.amount()));
            x.row("Total", null, penalty.total());
        }
        return x.build();
    }
}
