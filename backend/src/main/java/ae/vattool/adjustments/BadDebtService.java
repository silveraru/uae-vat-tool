package ae.vattool.adjustments;

import ae.vattool.evidence.EvidenceService;
import ae.vattool.masterdata.TaxPeriod;
import ae.vattool.platform.audit.AuditLog;
import ae.vattool.platform.tenancy.RequestContext;
import ae.vattool.platform.web.DomainException;
import ae.vattool.rules.RulePackDefinition;
import ae.vattool.rules.RulePackStore;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bad debt relief on output tax (ADJ-02, Article 64). A claim must meet four conditions: the VAT was
 * accounted for, the debt is written off in the accounts, more than six months passed since the supply,
 * and the customer was told. The checklist is kept with the claim, and a second person approves the
 * adjustment that results. A later recovery of the debt pays the relief back.
 */
@Service
public class BadDebtService {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public record ReliefInput(UUID documentId, BigDecimal writtenOffAed, LocalDate writtenOffOn, LocalDate notifiedOn, UUID writeOffEvidenceId, UUID noticeEvidenceId, String reason) {}

    public record RecoveryInput(UUID documentId, BigDecimal recoveredAed, UUID evidenceId, String reason) {}

    public record Claim(UUID id, UUID periodId, UUID documentId, String kind, BigDecimal amountAed, BigDecimal vatAed, LocalDate writtenOffOn, LocalDate notifiedOn, String checklist,
                        String docNumber, String createdBy, OffsetDateTime createdAt) {}

    public record Candidate(UUID documentId, String docNumber, LocalDate docDate, String counterpartyName, String counterpartyTrn, BigDecimal grossAed, BigDecimal paidAed, BigDecimal vatAed,
                            BigDecimal alreadyRelievedAed) {}

    public record Relieved(UUID documentId, String docNumber, LocalDate docDate, BigDecimal reliefAed, BigDecimal recoveredAed, BigDecimal outstandingAed) {}

    private record Doc(UUID id, String docNumber, LocalDate docDate, String direction, String docType, LocalDate paidDate, BigDecimal paidAmountAed, java.time.OffsetDateTime voidedAt) {}

    private record EmirateVat(String emirate, BigDecimal net, BigDecimal vat, LocalDate taxPoint, boolean filed) {}

    private final JdbcClient jdbc;
    private final RulePackStore packs;
    private final AdjustmentService adjustments;
    private final EvidenceService evidence;
    private final AuditLog audit;

    public BadDebtService(JdbcClient jdbc, RulePackStore packs, AdjustmentService adjustments, EvidenceService evidence, AuditLog audit) {
        this.jdbc = jdbc;
        this.packs = packs;
        this.adjustments = adjustments;
        this.evidence = evidence;
        this.audit = audit;
    }

    @Transactional
    public Claim claimRelief(TaxPeriod period, ReliefInput in) {
        RulePackDefinition def = packs.forDate(period.periodEnd()).definition();
        RulePackDefinition.BadDebt rule = RulePackDefinition.require(def.badDebt(), "bad debt");
        Doc doc = document(in.documentId());
        if (in.reason() == null || in.reason().isBlank()) {
            throw DomainException.invalid("REASON_REQUIRED", "A reason is required");
        }
        if (in.writtenOffAed() == null || in.writtenOffAed().signum() <= 0 || in.writtenOffOn() == null || in.notifiedOn() == null) {
            throw DomainException.invalid("BAD_DEBT_INCOMPLETE", "Give the amount and date written off and the date the customer was told");
        }
        evidence.requireForEntity(in.writeOffEvidenceId(), period.entityId());
        evidence.requireForEntity(in.noticeEvidenceId(), period.entityId());
        List<EmirateVat> split = vatByEmirate(in.documentId());
        BigDecimal net = split.stream().map(EmirateVat::net).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal vat = split.stream().map(EmirateVat::vat).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal gross = net.add(vat);
        if (vat.signum() <= 0) {
            throw DomainException.conflict("BAD_DEBT_NO_VAT", "This invoice has no output VAT to relieve");
        }
        boolean accounted = split.stream().allMatch(EmirateVat::filed);
        LocalDate supply = split.stream().map(EmirateVat::taxPoint).min(LocalDate::compareTo).orElse(doc.docDate());
        LocalDate earliest = supply.plusMonths(rule.minMonthsSinceSupply());
        if (!accounted) {
            throw DomainException.conflict("BAD_DEBT_NOT_ACCOUNTED", "The VAT on this invoice has not been filed yet, so there is nothing to relieve");
        }
        if (!period.periodEnd().isAfter(earliest)) {
            throw DomainException.conflict("BAD_DEBT_TOO_EARLY", "Relief needs more than " + rule.minMonthsSinceSupply() + " months since the supply, so not before " + earliest.plusDays(1));
        }
        if (in.writtenOffOn().isAfter(period.periodEnd()) || in.notifiedOn().isAfter(period.periodEnd())) {
            throw DomainException.invalid("BAD_DEBT_DATES", "The write-off and the notice must fall on or before the end of this period");
        }
        BigDecimal paid = doc.paidDate() == null ? BigDecimal.ZERO : doc.paidAmountAed() != null ? doc.paidAmountAed() : gross;
        BigDecimal relievedGross = jdbc.sql("""
                SELECT COALESCE(SUM(CASE WHEN c.kind = 'RELIEF' THEN c.amount_aed ELSE -c.amount_aed END), 0) FROM bad_debt_claim c
                WHERE c.document_id = ? AND EXISTS (SELECT 1 FROM adjustment a WHERE a.id = ANY(c.adjustment_ids) AND a.status <> 'REJECTED')
                """).param(in.documentId()).query(BigDecimal.class).single();
        BigDecimal available = gross.subtract(paid).subtract(relievedGross);
        if (in.writtenOffAed().compareTo(available) > 0) {
            throw DomainException.invalid("BAD_DEBT_TOO_MUCH", "Only AED " + available + " of this invoice is unpaid and not yet relieved");
        }
        Map<String, Object> checklist = new LinkedHashMap<>();
        checklist.put("vatAccountedFor", true);
        checklist.put("writtenOffInAccounts", in.writtenOffOn().toString());
        checklist.put("moreThanSixMonthsSinceSupply", supply.toString());
        checklist.put("customerNotified", in.notifiedOn().toString());
        return post(period, def, doc, "RELIEF", "BAD_DEBT_RELIEF", in.writtenOffAed(), gross, split, -1, in.writtenOffOn(), in.notifiedOn(), in.writeOffEvidenceId(), in.noticeEvidenceId(), checklist, in.reason());
    }

    /** Money later received on a relieved debt means the VAT is due again, in proportion. */
    @Transactional
    public Claim recordRecovery(TaxPeriod period, RecoveryInput in) {
        RulePackDefinition def = packs.forDate(period.periodEnd()).definition();
        RulePackDefinition.require(def.badDebt(), "bad debt");
        Doc doc = document(in.documentId());
        if (in.recoveredAed() == null || in.recoveredAed().signum() <= 0) {
            throw DomainException.invalid("AMOUNT_INVALID", "The amount recovered must be more than zero");
        }
        if (in.reason() == null || in.reason().isBlank()) {
            throw DomainException.invalid("REASON_REQUIRED", "A reason is required");
        }
        evidence.requireForEntity(in.evidenceId(), period.entityId());
        BigDecimal relieved = jdbc.sql("""
                SELECT COALESCE(SUM(CASE WHEN c.kind = 'RELIEF' THEN c.amount_aed ELSE -c.amount_aed END), 0) FROM bad_debt_claim c
                WHERE c.document_id = ? AND EXISTS (SELECT 1 FROM adjustment a WHERE a.id = ANY(c.adjustment_ids) AND a.status <> 'REJECTED')
                """).param(in.documentId()).query(BigDecimal.class).single();
        if (in.recoveredAed().compareTo(relieved) > 0) {
            throw DomainException.invalid("BAD_DEBT_TOO_MUCH", "Only AED " + relieved + " of this invoice is relieved");
        }
        List<EmirateVat> split = vatByEmirate(in.documentId());
        BigDecimal gross = split.stream().map(x -> x.net().add(x.vat())).reduce(BigDecimal.ZERO, BigDecimal::add);
        return post(period, def, doc, "RECOVERY", "BAD_DEBT_RECOVERY", in.recoveredAed(), gross, split, 1, null, null, in.evidenceId(), null, Map.of("recovered", in.recoveredAed().toPlainString()), in.reason());
    }

    private Claim post(TaxPeriod period, RulePackDefinition def, Doc doc, String kind, String type, BigDecimal amount, BigDecimal gross, List<EmirateVat> split, int sign, LocalDate writtenOffOn, LocalDate notifiedOn,
                       UUID writeOffEvidence, UUID noticeEvidence, Map<String, Object> checklist, String reason) {
        List<UUID> ids = new ArrayList<>();
        BigDecimal totalVat = BigDecimal.ZERO;
        for (EmirateVat e : split) {
            RulePackDefinition.Box box = def.form().boxes().stream().filter(b -> b.filter() != null && e.emirate() != null && e.emirate().equals(b.filter().emirate())).findFirst()
                    .orElseThrow(() -> DomainException.conflict("BAD_DEBT_NO_EMIRATE", "A line of this invoice has no emirate, so no box can take the adjustment"));
            BigDecimal vat = e.vat().multiply(amount).divide(gross, 2, RoundingMode.HALF_UP);
            if (vat.signum() == 0) {
                continue;
            }
            String description = ("RELIEF".equals(kind) ? "Bad debt relief on " : "Bad debt recovered on ") + doc.docNumber();
            ids.add(adjustments.proposeCalculated(period, type, box.code(), vat.multiply(BigDecimal.valueOf(sign)), description, reason, doc.id(), writeOffEvidence));
            totalVat = totalVat.add(vat);
        }
        if (ids.isEmpty()) {
            throw DomainException.conflict("BAD_DEBT_NO_VAT", "This amount is too small to carry any VAT");
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO bad_debt_claim (id, tenant_id, entity_id, period_id, document_id, kind, amount_aed, vat_aed, written_off_on, notified_on, writeoff_evidence_id, notice_evidence_id,
                    checklist, adjustment_ids, created_by)
                VALUES (:id, :tenant, :entity, :period, :document, :kind, :amount, :vat, :writtenOff, :notified, :evidence, :notice, CAST(:checklist AS jsonb), CAST(:ids AS uuid[]), :by)
                """)
                .param("id", id).param("tenant", RequestContext.requireTenant()).param("entity", period.entityId()).param("period", period.id()).param("document", doc.id())
                .param("kind", kind).param("amount", amount).param("vat", totalVat).param("writtenOff", writtenOffOn).param("notified", notifiedOn).param("evidence", writeOffEvidence)
                .param("notice", noticeEvidence).param("checklist", JSON.writeValueAsString(checklist)).param("ids", "{" + String.join(",", ids.stream().map(UUID::toString).toList()) + "}")
                .param("by", RequestContext.requireActor().actorId())
                .update();
        evidence.link(writeOffEvidence, "BAD_DEBT", id.toString());
        if (noticeEvidence != null) {
            evidence.link(noticeEvidence, "BAD_DEBT", id.toString());
        }
        audit.record("BAD_DEBT_CLAIMED", "BAD_DEBT_CLAIM", id, period.entityId(), null, Map.of("kind", kind, "document", doc.docNumber(), "amount", amount.toPlainString(), "vat", totalVat.toPlainString()), reason);
        return list(period.id()).stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    /** Sales invoices old enough to qualify, with money still owed, for a person to choose from. */
    @Transactional(readOnly = true)
    public List<Candidate> candidates(TaxPeriod period) {
        int months = RulePackDefinition.require(packs.forDate(period.periodEnd()).definition().badDebt(), "bad debt").minMonthsSinceSupply();
        return jdbc.sql("""
                SELECT d.id AS document_id, d.doc_number, d.doc_date, d.counterparty_name, d.counterparty_trn, SUM(l.net_aed + l.vat_aed) AS gross_aed,
                       COALESCE(d.paid_amount_aed, CASE WHEN d.paid_date IS NOT NULL THEN SUM(l.net_aed + l.vat_aed) ELSE 0 END) AS paid_aed, SUM(l.vat_aed) AS vat_aed,
                       COALESCE((SELECT SUM(CASE WHEN c.kind = 'RELIEF' THEN c.amount_aed ELSE -c.amount_aed END) FROM bad_debt_claim c WHERE c.document_id = d.id
                           AND EXISTS (SELECT 1 FROM adjustment a WHERE a.id = ANY(c.adjustment_ids) AND a.status <> 'REJECTED')), 0) AS already_relieved_aed
                FROM source_document d
                JOIN tax_line l ON l.document_id = d.id AND l.superseded_at IS NULL AND l.line_kind = 'NORMAL' AND l.treatment = 'STANDARD'
                JOIN tax_period p ON p.id = l.period_id AND p.state IN ('FILED','PAID','AMENDED')
                WHERE d.filer_entity_id = :filer AND d.direction = 'OUT' AND d.voided_at IS NULL AND d.doc_type <> 'CREDIT_NOTE' AND d.doc_date < :cutoff
                GROUP BY d.id
                HAVING SUM(l.vat_aed) > 0
                   AND COALESCE(d.paid_amount_aed, CASE WHEN d.paid_date IS NOT NULL THEN SUM(l.net_aed + l.vat_aed) ELSE 0 END) < SUM(l.net_aed + l.vat_aed)
                ORDER BY d.doc_date LIMIT 200
                """).param("filer", period.entityId()).param("cutoff", period.periodEnd().minusMonths(months)).query(Candidate.class).list();
    }

    /** Invoices with relief still standing, across all periods, since money usually arrives in a later return than the claim. */
    @Transactional(readOnly = true)
    public List<Relieved> recoverable(UUID entityId) {
        return jdbc.sql("""
                SELECT c.document_id, d.doc_number, d.doc_date,
                       COALESCE(SUM(c.amount_aed) FILTER (WHERE c.kind = 'RELIEF'), 0) AS relief_aed,
                       COALESCE(SUM(c.amount_aed) FILTER (WHERE c.kind = 'RECOVERY'), 0) AS recovered_aed,
                       SUM(CASE WHEN c.kind = 'RELIEF' THEN c.amount_aed ELSE -c.amount_aed END) AS outstanding_aed
                FROM bad_debt_claim c JOIN source_document d ON d.id = c.document_id
                WHERE c.entity_id = ? AND EXISTS (SELECT 1 FROM adjustment a WHERE a.id = ANY(c.adjustment_ids) AND a.status <> 'REJECTED')
                GROUP BY c.document_id, d.doc_number, d.doc_date
                HAVING SUM(CASE WHEN c.kind = 'RELIEF' THEN c.amount_aed ELSE -c.amount_aed END) > 0
                ORDER BY d.doc_date LIMIT 200
                """).param(entityId).query(Relieved.class).list();
    }

    @Transactional(readOnly = true)
    public List<Claim> list(UUID periodId) {
        return jdbc.sql("""
                SELECT c.id, c.period_id, c.document_id, c.kind, c.amount_aed, c.vat_aed, c.written_off_on, c.notified_on, c.checklist::text AS checklist, d.doc_number, c.created_by, c.created_at
                FROM bad_debt_claim c JOIN source_document d ON d.id = c.document_id WHERE c.period_id = ? ORDER BY c.created_at DESC
                """).param(periodId).query(Claim.class).list();
    }

    private Doc document(UUID id) {
        if (id == null) {
            throw DomainException.invalid("DOCUMENT_REQUIRED", "Choose the sales invoice");
        }
        Doc doc = jdbc.sql("SELECT id, doc_number, doc_date, direction, doc_type, paid_date, paid_amount_aed, voided_at FROM source_document WHERE id = ?").param(id).query(Doc.class).optional()
                .orElseThrow(() -> DomainException.notFound("Document"));
        if (!"OUT".equals(doc.direction()) || doc.voidedAt() != null || "CREDIT_NOTE".equals(doc.docType())) {
            throw DomainException.conflict("BAD_DEBT_NOT_A_SALE", "Relief applies to a live sales invoice");
        }
        return doc;
    }

    private List<EmirateVat> vatByEmirate(UUID documentId) {
        return jdbc.sql("""
                SELECT l.emirate, SUM(l.net_aed) AS net, SUM(l.vat_aed) AS vat, MIN(l.tax_point) AS tax_point, BOOL_AND(p.state IN ('FILED','PAID','AMENDED')) AS filed
                FROM tax_line l JOIN tax_period p ON p.id = l.period_id
                WHERE l.document_id = ? AND l.superseded_at IS NULL AND l.line_kind = 'NORMAL' AND l.treatment = 'STANDARD'
                GROUP BY l.emirate
                """).param(documentId).query(EmirateVat.class).list();
    }
}
