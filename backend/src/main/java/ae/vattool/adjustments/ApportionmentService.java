package ae.vattool.adjustments;

import ae.vattool.masterdata.LegalEntity;
import ae.vattool.masterdata.TaxPeriod;
import ae.vattool.platform.audit.AuditLog;
import ae.vattool.platform.tenancy.RequestContext;
import ae.vattool.platform.web.DomainException;
import ae.vattool.rules.RulePackDefinition;
import ae.vattool.rules.RulePackStore;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recovers residual input tax by the standard method (ADJ-03). The method comes from the rule pack by
 * the tax year it falls in, because it changes from input-based to output-based for tax years from
 * October 2027. Running twice changes nothing, since the delta is by then zero. A file marks a line as
 * direct with a recovery rate of 100 or 0, and everything else is residual.
 */
@Service
public class ApportionmentService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public record Result(String kind, String method, LocalDate from, LocalDate to, BigDecimal directRecoverableAed, BigDecimal directExemptAed, BigDecimal taxableSuppliesAed,
                         BigDecimal totalSuppliesAed, BigDecimal ratioPct, BigDecimal residualInputAed, BigDecimal targetRecoverableAed, BigDecimal alreadyRecoveredAed,
                         BigDecimal deltaAed, boolean furtherAdjustment, boolean posted) {}

    public record Run(UUID id, String kind, String method, LocalDate fromDate, LocalDate toDate, BigDecimal ratioPct, BigDecimal residualInputAed, BigDecimal targetRecoverableAed,
                      BigDecimal alreadyRecoveredAed, BigDecimal deltaAed, boolean furtherAdjustment, String createdBy, OffsetDateTime createdAt) {}

    private record Totals(BigDecimal directRecoverable, BigDecimal directExempt, BigDecimal taxable, BigDecimal total, BigDecimal residual, BigDecimal recovered) {
        static Totals zero() {
            return new Totals(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        Totals plus(Totals o) {
            return new Totals(directRecoverable.add(o.directRecoverable), directExempt.add(o.directExempt), taxable.add(o.taxable), total.add(o.total), residual.add(o.residual), recovered.add(o.recovered));
        }
    }

    private final JdbcClient jdbc;
    private final RulePackStore packs;
    private final AdjustmentService adjustments;
    private final AuditLog audit;

    public ApportionmentService(JdbcClient jdbc, RulePackStore packs, AdjustmentService adjustments, AuditLog audit) {
        this.jdbc = jdbc;
        this.packs = packs;
        this.adjustments = adjustments;
        this.audit = audit;
    }

    /** excludedSupplies is the value of capital asset disposals, which the output-based method leaves out. */
    @Transactional
    public Result runPeriod(TaxPeriod period, BigDecimal excludedSupplies, boolean post) {
        requireOpen(period);
        RulePackDefinition.Apportionment rule = rule(period);
        LegalEntity e = entity(period);
        String method = rule.methodFor(e.taxYearStart(period.periodStart()));
        Totals t = totals(period.entityId(), period.periodStart(), period.periodEnd());
        BigDecimal posted = jdbc.sql("""
                SELECT COALESCE(SUM(l.vat_aed), 0) FROM tax_line l
                WHERE l.period_id = ? AND l.superseded_at IS NULL AND l.line_kind = 'ADJUSTMENT' AND l.change_reason_code = 'APPORTIONMENT'
                """).param(period.id()).query(BigDecimal.class).single();
        return finish(period, rule, "PERIOD", "APPORTIONMENT", method, period.periodStart(), period.periodEnd(), t, posted, excludedSupplies, post);
    }

    /** Only in the first return after the tax year ends, over the whole year including anything before go-live. */
    @Transactional
    public Result runAnnual(TaxPeriod period, BigDecimal excludedSupplies, boolean post) {
        requireOpen(period);
        RulePackDefinition.Apportionment rule = rule(period);
        LegalEntity e = entity(period);
        LocalDate yearStart = e.taxYearStart(period.periodStart().minusDays(1));
        LocalDate yearEnd = yearStart.plusYears(1).minusDays(1);
        if (!period.periodEnd().isAfter(yearEnd)) {
            throw DomainException.conflict("APPORTIONMENT_WRONG_PERIOD", "The annual adjustment goes in the first return after the tax year ends on " + yearEnd);
        }
        Totals t = totals(period.entityId(), yearStart, yearEnd);
        record Opening(BigDecimal directRecoverableAed, BigDecimal directExemptAed, BigDecimal taxableSuppliesAed, BigDecimal totalSuppliesAed, BigDecimal residualInputAed, BigDecimal recoveredAed) {}
        Totals opening = jdbc.sql("""
                SELECT direct_recoverable_aed, direct_exempt_aed, taxable_supplies_aed, total_supplies_aed, residual_input_aed, recovered_aed
                FROM apportionment_opening WHERE entity_id = ? AND tax_year_start = ?
                """).params(period.entityId(), yearStart).query(Opening.class).optional()
                .map(o -> new Totals(o.directRecoverableAed(), o.directExemptAed(), o.taxableSuppliesAed(), o.totalSuppliesAed(), o.residualInputAed(), o.recoveredAed()))
                .orElse(Totals.zero());
        BigDecimal posted = jdbc.sql("""
                SELECT COALESCE(SUM(l.vat_aed), 0) FROM tax_line l JOIN tax_period p ON p.id = l.period_id
                WHERE p.entity_id = :entity AND l.superseded_at IS NULL AND l.line_kind = 'ADJUSTMENT'
                  AND ((l.change_reason_code = 'APPORTIONMENT' AND p.period_start >= :from AND p.period_end <= :to)
                       OR (l.change_reason_code = 'APPORTIONMENT_ANNUAL' AND p.id = :period))
                """).param("entity", period.entityId()).param("from", yearStart).param("to", yearEnd).param("period", period.id()).query(BigDecimal.class).single();
        return finish(period, rule, "ANNUAL", "APPORTIONMENT_ANNUAL", rule.methodFor(yearStart), yearStart, yearEnd, t.plus(opening), posted, excludedSupplies, post);
    }

    private Result finish(TaxPeriod period, RulePackDefinition.Apportionment rule, String kind, String type, String method, LocalDate from, LocalDate to, Totals t,
                          BigDecimal alreadyPosted, BigDecimal excludedSupplies, boolean post) {
        BigDecimal ratio = ratio(rule, method, t, excludedSupplies);
        BigDecimal target = t.residual().multiply(ratio).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        BigDecimal already = t.recovered().add(alreadyPosted);
        BigDecimal delta = target.subtract(already);
        boolean further = "ANNUAL".equals(kind) && rule.furtherAdjustmentThresholdAed() != null && delta.abs().compareTo(rule.furtherAdjustmentThresholdAed()) > 0;
        boolean posting = post && delta.signum() != 0;
        UUID adjustmentId = null;
        if (posting) {
            String description = ("ANNUAL".equals(kind) ? "Annual input tax apportionment " : "Input tax apportionment ") + from + " to " + to + " at " + ratio.stripTrailingZeros().toPlainString() + "%";
            adjustmentId = adjustments.postCalculated(period, type, rule.adjustmentBox(), delta, description, "Standard method: " + method, "apportionment");
        }
        if (post) {
            jdbc.sql("""
                    INSERT INTO apportionment_run (id, tenant_id, entity_id, period_id, kind, method, from_date, to_date, direct_recoverable_aed, direct_exempt_aed, taxable_supplies_aed,
                        total_supplies_aed, ratio_pct, residual_input_aed, target_recoverable_aed, already_recovered_aed, delta_aed, further_adjustment, adjustment_id, created_by)
                    VALUES (:id, :tenant, :entity, :period, :kind, :method, :from, :to, :recoverable, :exempt, :taxable, :total, :ratio, :residual, :target, :already, :delta, :further, :adjustment, :by)
                    """)
                    .param("id", UUID.randomUUID()).param("tenant", RequestContext.requireTenant()).param("entity", period.entityId()).param("period", period.id()).param("kind", kind)
                    .param("method", method).param("from", from).param("to", to).param("recoverable", t.directRecoverable()).param("exempt", t.directExempt()).param("taxable", t.taxable())
                    .param("total", t.total()).param("ratio", ratio).param("residual", t.residual()).param("target", target).param("already", already).param("delta", delta)
                    .param("further", further).param("adjustment", adjustmentId).param("by", RequestContext.requireActor().actorId())
                    .update();
            audit.record("APPORTIONMENT_RUN", "TAX_PERIOD", period.id(), period.entityId(), null,
                    Map.of("kind", kind, "method", method, "ratioPct", ratio.toPlainString(), "delta", delta.toPlainString(), "furtherAdjustment", further), null);
        }
        return new Result(kind, method, from, to, t.directRecoverable(), t.directExempt(), t.taxable(), t.total(), ratio, t.residual(), target, already, delta, further, posting);
    }

    static BigDecimal ratio(RulePackDefinition.Apportionment rule, String method, Totals t, BigDecimal excludedSupplies) {
        BigDecimal top;
        BigDecimal bottom;
        if ("OUTPUT_BASED".equals(method)) {
            BigDecimal excluded = excludedSupplies == null ? BigDecimal.ZERO : excludedSupplies;
            top = t.taxable().subtract(excluded);
            bottom = t.total().subtract(excluded);
        } else {
            top = t.directRecoverable();
            bottom = t.directRecoverable().add(t.directExempt());
        }
        if (bottom.signum() <= 0) {
            throw DomainException.conflict("APPORTIONMENT_NO_BASIS", "OUTPUT_BASED".equals(method)
                    ? "There are no supplies to work out a ratio from"
                    : "No input tax is marked as directly taxable or exempt. Give those lines a recovery rate of 100 or 0.");
        }
        BigDecimal ratio = top.multiply(HUNDRED).divide(bottom, MathContext.DECIMAL64);
        return ratio.setScale(rule.roundToWholePct() ? 0 : 4, RoundingMode.HALF_UP).setScale(4, RoundingMode.HALF_UP);
    }

    private Totals totals(UUID entityId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                SELECT COALESCE(SUM(l.vat_aed) FILTER (WHERE l.direction = 'IN' AND l.input_attribution = 'TAXABLE' AND l.blocked_category IS NULL), 0) AS direct_recoverable,
                       COALESCE(SUM(l.vat_aed) FILTER (WHERE l.direction = 'IN' AND l.input_attribution = 'EXEMPT' AND l.blocked_category IS NULL), 0) AS direct_exempt,
                       COALESCE(SUM(l.net_aed) FILTER (WHERE l.direction = 'OUT' AND l.treatment IN ('STANDARD','ZERO_RATED')), 0) AS taxable,
                       COALESCE(SUM(l.net_aed) FILTER (WHERE l.direction = 'OUT' AND l.treatment IN ('STANDARD','ZERO_RATED','EXEMPT')), 0) AS total,
                       COALESCE(SUM(l.vat_aed) FILTER (WHERE l.direction = 'IN' AND COALESCE(l.input_attribution, 'RESIDUAL') = 'RESIDUAL' AND l.blocked_category IS NULL), 0) AS residual,
                       COALESCE(SUM(l.recoverable_vat_aed) FILTER (WHERE l.direction = 'IN' AND COALESCE(l.input_attribution, 'RESIDUAL') = 'RESIDUAL' AND l.blocked_category IS NULL), 0) AS recovered
                FROM tax_line l JOIN tax_period p ON p.id = l.period_id
                WHERE p.entity_id = :entity AND p.period_start >= :from AND p.period_end <= :to
                  AND l.superseded_at IS NULL AND l.memo = false AND l.line_kind = 'NORMAL'
                """).param("entity", entityId).param("from", from).param("to", to).query(Totals.class).single();
    }

    @Transactional(readOnly = true)
    public List<Run> runs(UUID periodId) {
        return jdbc.sql("""
                SELECT id, kind, method, from_date, to_date, ratio_pct, residual_input_aed, target_recoverable_aed, already_recovered_aed, delta_aed, further_adjustment, created_by, created_at
                FROM apportionment_run WHERE period_id = ? ORDER BY created_at DESC
                """).param(periodId).query(Run.class).list();
    }

    private RulePackDefinition.Apportionment rule(TaxPeriod p) {
        return RulePackDefinition.require(packs.forDate(p.periodEnd()).definition().apportionment(), "apportionment");
    }

    private LegalEntity entity(TaxPeriod p) {
        return jdbc.sql(LegalEntity.SELECT + " WHERE id = ?").param(p.entityId()).query(LegalEntity.class).single();
    }

    private static void requireOpen(TaxPeriod p) {
        if (p.isLocked()) {
            throw DomainException.conflict("PERIOD_LOCKED", "This period is locked. Use a correction instead.");
        }
    }
}
