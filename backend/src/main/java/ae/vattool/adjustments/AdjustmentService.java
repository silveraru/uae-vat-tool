package ae.vattool.adjustments;

import ae.vattool.evidence.EvidenceService;
import ae.vattool.ledger.Ledger;
import ae.vattool.ledger.LineInput;
import ae.vattool.ledger.UnpaidSuppliers;
import ae.vattool.masterdata.PeriodCalendar;
import ae.vattool.masterdata.TaxPeriod;
import ae.vattool.platform.audit.AuditLog;
import ae.vattool.platform.tenancy.RequestContext;
import ae.vattool.platform.web.DomainException;
import ae.vattool.returns.ReturnService;
import ae.vattool.rules.RulePackDefinition;
import ae.vattool.rules.RulePackStore;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adjustments to a return (ADJ-01, ADJ-05). The 6-month supplier rule is calculated, so it posts
 * directly and a person reviews it with the return. Journals and deemed supplies are a person's
 * judgement, so they need evidence and a second person's approval before they reach the ledger.
 */
@Service
public class AdjustmentService {

    public record Adjustment(UUID id, UUID entityId, UUID periodId, String type, String boxCode, String emirate, BigDecimal netAed,
                             BigDecimal vatAed, String description, String reason, UUID documentId, UUID evidenceId, String status,
                             String createdBy, OffsetDateTime createdAt, String decidedBy, OffsetDateTime decidedAt,
                             String decisionNote, UUID lineId) {
        static final String SELECT = """
                SELECT id, entity_id, period_id, type, box_code, emirate, net_aed, vat_aed, description, reason, document_id, evidence_id,
                       status, created_by, created_at, decided_by, decided_at, decision_note, line_id
                FROM adjustment
                """;
    }

    public record ManualInput(String type, String boxCode, BigDecimal netAed, BigDecimal vatAed, String description, String reason, UUID evidenceId) {}

    public record ReversalResult(int reversed, int reinstated, BigDecimal totalVatChange, List<Adjustment> adjustments) {}

    private static final Set<String> MANUAL_TYPES = Set.of("MANUAL_JOURNAL", "DEEMED_SUPPLY");

    private final JdbcClient jdbc;
    private final Ledger ledger;
    private final UnpaidSuppliers unpaid;
    private final ReturnService returns;
    private final EvidenceService evidence;
    private final PeriodCalendar calendar;
    private final AuditLog audit;

    public AdjustmentService(JdbcClient jdbc, Ledger ledger, UnpaidSuppliers unpaid, ReturnService returns, EvidenceService evidence,
                             PeriodCalendar calendar, AuditLog audit) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.unpaid = unpaid;
        this.returns = returns;
        this.evidence = evidence;
        this.calendar = calendar;
        this.audit = audit;
    }

    @Transactional
    public Adjustment propose(TaxPeriod period, ManualInput in) {
        requireOpen(period);
        if (in.type() == null || !MANUAL_TYPES.contains(in.type())) {
            throw DomainException.invalid("ADJUSTMENT_TYPE_INVALID", "Type must be one of " + MANUAL_TYPES);
        }
        if (in.description() == null || in.description().isBlank() || in.reason() == null || in.reason().isBlank()) {
            throw DomainException.invalid("REASON_REQUIRED", "A description and a reason are required");
        }
        if (in.vatAed() == null) {
            throw DomainException.invalid("VAT_REQUIRED", "A VAT amount is required");
        }
        evidence.requireForEntity(in.evidenceId(), period.entityId());
        RulePackDefinition.Box box = findBox(period, in.boxCode());
        String emirate = null;
        if ("DEEMED_SUPPLY".equals(in.type())) {
            if (box.filter().emirate() == null) {
                throw DomainException.invalid("DEEMED_SUPPLY_BOX", "A deemed supply goes in an emirate box, 1a to 1g");
            }
            emirate = box.filter().emirate();
        } else if (!box.columns().contains("adjustment")) {
            throw DomainException.invalid("BOX_HAS_NO_ADJUSTMENT", "Box " + box.code() + " has no adjustment column on the return");
        }
        UUID id = UUID.randomUUID();
        insert(id, period, in.type(), box.code(), emirate, in.netAed() == null ? BigDecimal.ZERO : in.netAed(), in.vatAed(),
                in.description(), in.reason(), null, in.evidenceId(), "PROPOSED");
        evidence.link(in.evidenceId(), "ADJUSTMENT", id.toString());
        audit.record("ADJUSTMENT_PROPOSED", "ADJUSTMENT", id, period.entityId(), null, Map.of("type", in.type(), "box", box.code(),
                "vat", in.vatAed().toPlainString()), in.reason());
        return get(id);
    }

    /** Approval posts the adjustment to the ledger. The creator cannot approve their own. */
    @Transactional
    public Adjustment decide(UUID id, boolean approve, String note) {
        Adjustment a = get(id);
        if (!"PROPOSED".equals(a.status())) {
            throw DomainException.conflict("ALREADY_DECIDED", "This adjustment has already been decided");
        }
        String me = RequestContext.requireActor().actorId();
        if (me.equals(a.createdBy())) {
            throw new DomainException(org.springframework.http.HttpStatus.FORBIDDEN, "MAKER_CHECKER",
                    "You proposed this adjustment, so a different person must decide it");
        }
        TaxPeriod period = calendar.get(a.periodId());
        requireOpen(period);
        UUID lineId = null;
        if (approve) {
            lineId = post(a, period);
        }
        jdbc.sql("""
                UPDATE adjustment SET status = :status, decided_by = :by, decided_at = now(), decision_note = :note, line_id = :line
                WHERE id = :id
                """).param("status", approve ? "APPROVED" : "REJECTED").param("by", me).param("note", note).param("line", lineId).param("id", id).update();
        audit.record(approve ? "ADJUSTMENT_APPROVED" : "ADJUSTMENT_REJECTED", "ADJUSTMENT", id, a.entityId(),
                Map.of("status", "PROPOSED"), Map.of("status", approve ? "APPROVED" : "REJECTED"), note);
        return get(id);
    }

    private UUID post(Adjustment a, TaxPeriod period) {
        if ("DEEMED_SUPPLY".equals(a.type())) {
            UUID doc = jdbc.sql("""
                    INSERT INTO source_document (id, tenant_id, filer_entity_id, member_entity_id, source_system, direction, doc_type,
                        doc_number, doc_date, natural_key, has_tax_invoice)
                    VALUES (:id, :tenant, :filer, :filer, 'MANUAL', 'OUT', 'DEEMED_SUPPLY', :number, :date, :key, true)
                    RETURNING id
                    """)
                    .param("id", UUID.randomUUID()).param("tenant", RequestContext.requireTenant()).param("filer", a.entityId())
                    .param("number", "DS-" + a.id().toString().substring(0, 8)).param("date", period.periodEnd()).param("key", a.id().toString())
                    .query(UUID.class).single();
            LineInput line = new LineInput(doc, null, a.entityId(), a.entityId(), null, a.emirate(), false, "MANUAL", "OUT", "DEEMED_SUPPLY", 1,
                    null, null, null, a.description(), null, null, period.periodEnd(), "AED", BigDecimal.ONE, a.netAed(), a.vatAed(), a.netAed(),
                    a.vatAed(), null, "STANDARD");
            return ledger.writeSourceLine(line).lineId();
        }
        RulePackDefinition.Box box = findBox(period, a.boxCode());
        return ledger.writeAdjustment(a.entityId(), a.periodId(), period.periodEnd(), a.boxCode(), "INPUT".equals(box.section()) ? "IN" : "OUT",
                a.netAed(), a.vatAed(), a.id(), a.documentId(), a.description(), a.type());
    }

    /**
     * Posts what the 6-month rule requires for this period: reversals for invoices still unpaid and
     * reinstatements for those since paid. Running it twice changes nothing, since the delta is by
     * then zero. This is what clears check R11.
     */
    @Transactional
    public ReversalResult runSupplierReversal(TaxPeriod period) {
        requireOpen(period);
        RulePackStore.ActivePack pack = returns.packFor(period);
        RulePackDefinition.Filing filing = pack.definition().filing();
        List<Adjustment> created = new ArrayList<>();
        int reversed = 0;
        int reinstated = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (UnpaidSuppliers.Position p : unpaid.outstanding(period.entityId(), period.periodEnd(), filing.unpaidSupplierMonths())) {
            boolean reversal = p.delta().signum() > 0;
            BigDecimal vat = p.delta().negate().setScale(2, RoundingMode.HALF_UP);
            UUID id = UUID.randomUUID();
            String description = (reversal ? "Input VAT reversed: " : "Input VAT reinstated: ") + p.docNumber()
                    + (reversal ? ", unpaid past " + p.paymentDueOn() : ", paid since it was reversed");
            insert(id, period, reversal ? "SUPPLIER_REVERSAL" : "SUPPLIER_REINSTATEMENT", filing.unpaidSupplierBox(), null, BigDecimal.ZERO, vat,
                    description, "Six-month rule", p.documentId(), null, "APPROVED");
            UUID line = ledger.writeAdjustment(period.entityId(), period.id(), period.periodEnd(), filing.unpaidSupplierBox(), "IN",
                    BigDecimal.ZERO, vat, id, p.documentId(), description, reversal ? "SUPPLIER_REVERSAL" : "SUPPLIER_REINSTATEMENT");
            jdbc.sql("UPDATE adjustment SET line_id = :line, decided_by = 'six-month-rule', decided_at = now() WHERE id = :id")
                    .param("line", line).param("id", id).update();
            jdbc.sql("""
                    INSERT INTO supplier_reversal (id, tenant_id, entity_id, document_id, period_id, adjustment_id, vat_aed)
                    VALUES (:id, :tenant, :entity, :doc, :period, :adjustment, :vat)
                    """)
                    .param("id", UUID.randomUUID()).param("tenant", RequestContext.requireTenant()).param("entity", period.entityId())
                    .param("doc", p.documentId()).param("period", period.id()).param("adjustment", id).param("vat", p.delta())
                    .update();
            created.add(get(id));
            total = total.add(vat);
            if (reversal) {
                reversed++;
            } else {
                reinstated++;
            }
        }
        if (!created.isEmpty()) {
            audit.record("SUPPLIER_REVERSAL_RUN", "TAX_PERIOD", period.id(), period.entityId(), null,
                    Map.of("reversed", reversed, "reinstated", reinstated, "vatChange", total.toPlainString()), "Six-month rule");
        }
        return new ReversalResult(reversed, reinstated, total, created);
    }

    @Transactional(readOnly = true)
    public Adjustment get(UUID id) {
        return jdbc.sql(Adjustment.SELECT + " WHERE id = ?").param(id).query(Adjustment.class).optional()
                .orElseThrow(() -> DomainException.notFound("Adjustment"));
    }

    @Transactional(readOnly = true)
    public List<Adjustment> list(UUID periodId) {
        return jdbc.sql(Adjustment.SELECT + " WHERE period_id = ? ORDER BY created_at").param(periodId).query(Adjustment.class).list();
    }

    private void insert(UUID id, TaxPeriod period, String type, String box, String emirate, BigDecimal net, BigDecimal vat, String description,
                        String reason, UUID document, UUID evidenceId, String status) {
        jdbc.sql("""
                INSERT INTO adjustment (id, tenant_id, entity_id, period_id, type, box_code, emirate, net_aed, vat_aed, description, reason,
                    document_id, evidence_id, status, created_by)
                VALUES (:id, :tenant, :entity, :period, :type, :box, :emirate, :net, :vat, :description, :reason, :document, :evidence, :status, :by)
                """)
                .param("id", id).param("tenant", RequestContext.requireTenant()).param("entity", period.entityId()).param("period", period.id())
                .param("type", type).param("box", box).param("emirate", emirate).param("net", net).param("vat", vat)
                .param("description", description).param("reason", reason).param("document", document).param("evidence", evidenceId)
                .param("status", status).param("by", RequestContext.requireActor().actorId())
                .update();
    }

    private RulePackDefinition.Box findBox(TaxPeriod period, String code) {
        RulePackDefinition.Box box = returns.packFor(period).definition().form().boxes().stream()
                .filter(b -> b.code().equals(code)).findFirst()
                .orElseThrow(() -> DomainException.invalid("BOX_UNKNOWN", "Unknown box " + code));
        if (box.isComputed()) {
            throw DomainException.invalid("BOX_IS_COMPUTED", "Box " + code + " is calculated and cannot be adjusted directly");
        }
        return box;
    }

    private static void requireOpen(TaxPeriod p) {
        if (p.isLocked()) {
            throw DomainException.conflict("PERIOD_LOCKED", "This period is locked. Use a correction instead.");
        }
    }
}
