package ae.vattool.adjustments;

import ae.vattool.evidence.EvidenceService;
import ae.vattool.ledger.Ledger;
import ae.vattool.masterdata.PeriodCalendar;
import ae.vattool.masterdata.TaxPeriod;
import ae.vattool.platform.audit.AuditLog;
import ae.vattool.platform.tenancy.RequestContext;
import ae.vattool.platform.web.DomainException;
import ae.vattool.returns.BoxCalculator;
import ae.vattool.returns.BoxCalculator.BoxValue;
import ae.vattool.returns.ReturnService;
import ae.vattool.returns.ReturnVersionView;
import ae.vattool.rules.RulePackDefinition;
import ae.vattool.rules.RulePackStore;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
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
 * Errors found after filing (COR-01, spec journey 3). Works out the tax difference against the
 * locked return and routes it: up to the threshold goes into the next return; above that needs a
 * voluntary disclosure on Form 211. The filed version is never touched, and both are kept.
 */
@Service
public class CorrectionService {

    public record BoxChange(String box, BigDecimal vatChange) {}

    public record CaseInput(String reason, UUID evidenceId, List<BoxChange> manualChanges) {}

    public record Preview(BigDecimal taxDifference, BigDecimal threshold, String route, BigDecimal originalPayable, BigDecimal correctedPayable,
                          List<Map<String, String>> changes) {}

    public record CorrectionCase(UUID id, UUID entityId, UUID periodId, UUID originalVersionId, UUID correctedVersionId, BigDecimal taxDifferenceAed,
                                 BigDecimal thresholdAed, String route, UUID targetPeriodId, String reason, UUID evidenceId,
                                 OffsetDateTime handledThrough, String createdBy, OffsetDateTime createdAt) {
        static final String SELECT = """
                SELECT id, entity_id, period_id, original_version_id, corrected_version_id, tax_difference_aed, threshold_aed, route,
                       target_period_id, reason, evidence_id, handled_through, created_by, created_at
                FROM correction_case
                """;
    }

    private static final Set<String> FILED_STATES = Set.of("FILED", "PAID", "AMENDED");
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;
    private final ReturnService returns;
    private final RulePackStore packs;
    private final Ledger ledger;
    private final PeriodCalendar calendar;
    private final EvidenceService evidence;
    private final AuditLog audit;

    public CorrectionService(JdbcClient jdbc, ReturnService returns, RulePackStore packs, Ledger ledger, PeriodCalendar calendar,
                             EvidenceService evidence, AuditLog audit) {
        this.jdbc = jdbc;
        this.returns = returns;
        this.packs = packs;
        this.ledger = ledger;
        this.calendar = calendar;
        this.evidence = evidence;
        this.audit = audit;
    }

    /**
     * baseline is what the tax position stands at today: the filed return, or the last correction of
     * it. Measuring against the filed return every time would post earlier corrections again.
     */
    private record Worked(TaxPeriod period, ReturnVersionView original, ReturnVersionView baseline, RulePackStore.ActivePack pack,
                          Map<String, BoxValue> corrected, Map<UUID, Map<String, BoxValue>> perMember, BigDecimal difference,
                          BigDecimal threshold, String route, List<Map<String, String>> changes) {}

    /** Shows the outcome before anything is saved, so a reviewer can see the route first. */
    @Transactional(readOnly = true)
    public Preview preview(UUID periodId, List<BoxChange> manual) {
        Worked w = work(periodId, manual);
        return new Preview(w.difference(), w.threshold(), w.route(), payable(ReturnService.parseValues(w.baseline().boxValues())),
                payable(w.corrected()), w.changes());
    }

    @Transactional
    public CorrectionCase open(UUID periodId, CaseInput in) {
        if (in.reason() == null || in.reason().isBlank()) {
            throw DomainException.invalid("REASON_REQUIRED", "A correction needs a reason");
        }
        boolean hasManual = in.manualChanges() != null && !in.manualChanges().isEmpty();
        // Lines changing is its own evidence. A figure typed in by hand needs something behind it.
        if (hasManual) {
            UUID entity = calendar.get(periodId).entityId();
            evidence.requireForEntity(in.evidenceId(), entity);
        }
        Worked w = work(periodId, in.manualChanges());
        if (w.changes().isEmpty()) {
            throw DomainException.conflict("NO_DIFFERENCE", "The ledger matches the filed return, so there is nothing to correct");
        }
        ReturnVersionView corrected = returns.saveCorrected(w.period(), w.pack(), w.corrected(), w.baseline(), w.perMember());
        UUID target = null;
        if ("NEXT_RETURN".equals(w.route())) {
            target = postToNextReturn(w);
        } else {
            jdbc.sql("UPDATE tax_period SET state = 'AMENDED' WHERE id = ?").param(periodId).update();
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO correction_case (id, tenant_id, entity_id, period_id, original_version_id, corrected_version_id, tax_difference_aed,
                    threshold_aed, route, target_period_id, reason, evidence_id, handled_through, created_by, manual_changes)
                VALUES (:id, :tenant, :entity, :period, :original, :corrected, :difference, :threshold, :route, :target, :reason, :evidence, now(), :by,
                    CAST(:manual AS jsonb))
                """)
                .param("id", id).param("tenant", RequestContext.requireTenant()).param("entity", w.period().entityId()).param("period", periodId)
                .param("original", w.original().id()).param("corrected", corrected.id()).param("difference", w.difference())
                .param("threshold", w.threshold()).param("route", w.route()).param("target", target).param("reason", in.reason())
                .param("evidence", in.evidenceId()).param("by", RequestContext.requireActor().actorId())
                .param("manual", JSON.writeValueAsString(in.manualChanges() == null ? List.of() : in.manualChanges()))
                .update();
        if (target != null) {
            ledger.requestRecompute(target);
        }
        if (in.evidenceId() != null) {
            evidence.link(in.evidenceId(), "CORRECTION", id.toString());
        }
        audit.record("CORRECTION_OPENED", "CORRECTION_CASE", id, w.period().entityId(), Map.of("originalVersion", w.original().versionNo()),
                Map.of("route", w.route(), "taxDifference", w.difference().toPlainString(), "correctedVersion", corrected.versionNo()), in.reason());
        return get(id);
    }

    private Worked work(UUID periodId, List<BoxChange> manual) {
        TaxPeriod period = calendar.get(periodId);
        if (!FILED_STATES.contains(period.state())) {
            throw DomainException.conflict("PERIOD_NOT_FILED", "Corrections apply to filed returns. Withdraw the approval or edit the open return instead.");
        }
        ReturnVersionView original = returns.find(period.lockedVersionId()).orElseThrow();
        ReturnVersionView baseline = returns.latest(periodId).orElseThrow();
        RulePackStore.ActivePack pack = packs.forDate(period.periodEnd());
        RulePackDefinition.Form form = pack.definition().form();
        ReturnService.Computed now = returns.computeNow(period, form);
        Map<String, BigDecimal> vatChanges = new HashMap<>();
        // Figures entered by hand in earlier corrections are not in the ledger, so they carry forward.
        for (BoxChange earlier : earlierManualChanges(periodId)) {
            vatChanges.merge(earlier.box(), earlier.vatChange(), BigDecimal::add);
        }
        if (manual != null) {
            for (BoxChange c : manual) {
                RulePackDefinition.Box box = form.boxes().stream().filter(b -> b.code().equals(c.box())).findFirst()
                        .orElseThrow(() -> DomainException.invalid("BOX_UNKNOWN", "Unknown box " + c.box()));
                if (box.isComputed()) {
                    throw DomainException.invalid("BOX_IS_COMPUTED", "Box " + c.box() + " is calculated. Correct the boxes it is made from.");
                }
                vatChanges.merge(c.box(), c.vatChange(), BigDecimal::add);
            }
        }
        Map<String, BoxValue> corrected = BoxCalculator.withVatChanges(form, now.boxes(), vatChanges);
        Map<String, BoxValue> filed = ReturnService.parseValues(baseline.boxValues());
        BigDecimal difference = payable(corrected).subtract(payable(filed));
        // The threshold in force today applies, since the correction is being made today.
        BigDecimal threshold = packs.forDate(LocalDate.now()).definition().filing().correctionThresholdAed();
        String route = difference.abs().compareTo(threshold) <= 0 ? "NEXT_RETURN" : "FORM_211";
        List<Map<String, String>> changes = new ArrayList<>();
        for (RulePackDefinition.Box box : form.boxes()) {
            BoxValue before = filed.getOrDefault(box.code(), new BoxValue(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
            BoxValue after = corrected.get(box.code());
            if (before.vat().compareTo(after.vat()) != 0 || before.amount().compareTo(after.amount()) != 0 || before.adjustment().compareTo(after.adjustment()) != 0) {
                Map<String, String> change = new LinkedHashMap<>();
                change.put("box", box.code());
                change.put("section", String.valueOf(box.section()));
                change.put("amountFrom", before.amount().toPlainString());
                change.put("amountTo", after.amount().toPlainString());
                change.put("vatFrom", before.vat().toPlainString());
                change.put("vatTo", after.vat().toPlainString());
                changes.add(change);
            }
        }
        return new Worked(period, original, baseline, pack, corrected, now.perMember(), difference, threshold, route, changes);
    }

    private List<BoxChange> earlierManualChanges(UUID periodId) {
        List<BoxChange> out = new ArrayList<>();
        for (String json : jdbc.sql("SELECT manual_changes::text FROM correction_case WHERE period_id = ?").param(periodId).query(String.class).list()) {
            List<Map<String, Object>> rows = JSON.readValue(json, List.class);
            for (Map<String, Object> r : rows) {
                out.add(new BoxChange(String.valueOf(r.get("box")), new BigDecimal(String.valueOf(r.get("vatChange")))));
            }
        }
        return out;
    }

    /**
     * Posts each box's VAT change as an adjustment in the earliest open period. Not every box has an
     * adjustment column on the form, so such a change goes to the first output or input box that does.
     * That choice is question 11 for the tax team.
     */
    private UUID postToNextReturn(Worked w) {
        TaxPeriod target = jdbc.sql(TaxPeriod.SELECT + """
                 WHERE entity_id = :entity AND state = 'OPEN' AND period_start > :after ORDER BY period_start LIMIT 1
                """).param("entity", w.period().entityId()).param("after", w.period().periodEnd()).query(TaxPeriod.class).optional()
                .orElseGet(() -> calendar.periodFor(w.period().entityId(), w.period().periodEnd().plusDays(1)).orElseThrow());
        if (target.isLocked() || !"OPEN".equals(target.state())) {
            throw DomainException.conflict("NO_OPEN_PERIOD", "There is no open return to take this correction");
        }
        RulePackDefinition.Form form = w.pack().definition().form();
        Map<String, BoxValue> filed = ReturnService.parseValues(w.baseline().boxValues());
        String firstOutput = firstAdjustable(form, "OUTPUT");
        String firstInput = firstAdjustable(form, "INPUT");
        for (RulePackDefinition.Box box : form.boxes()) {
            if (box.isComputed()) {
                continue;
            }
            BoxValue before = filed.getOrDefault(box.code(), new BoxValue(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
            BigDecimal change = w.corrected().get(box.code()).vat().subtract(before.vat());
            if (change.signum() == 0) {
                continue;
            }
            String postBox = box.columns().contains("adjustment") ? box.code() : ("INPUT".equals(box.section()) ? firstInput : firstOutput);
            UUID id = UUID.randomUUID();
            String description = "Correction of box " + box.code() + " for the period ending " + w.period().periodEnd();
            jdbc.sql("""
                    INSERT INTO adjustment (id, tenant_id, entity_id, period_id, type, box_code, net_aed, vat_aed, description, reason, status,
                        created_by, decided_by, decided_at)
                    VALUES (:id, :tenant, :entity, :period, 'PRIOR_PERIOD_CORRECTION', :box, 0, :vat, :description, 'Correction', 'APPROVED', :by, :by, now())
                    """)
                    .param("id", id).param("tenant", RequestContext.requireTenant()).param("entity", target.entityId()).param("period", target.id())
                    .param("box", postBox).param("vat", change).param("description", description)
                    .param("by", RequestContext.requireActor().actorId())
                    .update();
            UUID line = ledger.writeAdjustment(target.entityId(), target.id(), target.periodEnd(), postBox, "INPUT".equals(box.section()) ? "IN" : "OUT",
                    BigDecimal.ZERO, change, id, null, description, "PRIOR_PERIOD_CORRECTION");
            jdbc.sql("UPDATE adjustment SET line_id = ? WHERE id = ?").params(line, id).update();
        }
        return target.id();
    }

    private static String firstAdjustable(RulePackDefinition.Form form, String section) {
        return form.boxes().stream().filter(b -> section.equals(b.section()) && !b.isComputed() && b.columns().contains("adjustment"))
                .map(RulePackDefinition.Box::code).findFirst()
                .orElseThrow(() -> new IllegalStateException("The form has no adjustable " + section + " box"));
    }

    private static BigDecimal payable(Map<String, BoxValue> boxes) {
        BoxValue net = boxes.get("14");
        return net == null ? BigDecimal.ZERO : net.vat();
    }

    @Transactional(readOnly = true)
    public CorrectionCase get(UUID id) {
        return jdbc.sql(CorrectionCase.SELECT + " WHERE id = ?").param(id).query(CorrectionCase.class).optional()
                .orElseThrow(() -> DomainException.notFound("Correction"));
    }

    @Transactional(readOnly = true)
    public List<CorrectionCase> list(UUID periodId) {
        return jdbc.sql(CorrectionCase.SELECT + " WHERE period_id = ? ORDER BY created_at DESC").param(periodId).query(CorrectionCase.class).list();
    }
}
