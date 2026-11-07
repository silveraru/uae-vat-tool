package ae.vattool.adjustments;

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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The capital assets scheme (ADJ-04): an asset of AED 5M or more is adjusted each year for how far its
 * real taxable use differs from what was first recovered, over 10 years for real estate and 5 for
 * others. One interval's adjustment is the input VAT divided by the years, times the change in use.
 */
@Service
public class CapitalAssetService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public record AssetInput(String reference, String description, String category, BigDecimal costAed, BigDecimal inputVatAed, BigDecimal initialRecoveryPct, LocalDate firstUsedOn) {}

    public record Asset(UUID id, UUID entityId, String reference, String description, String category, BigDecimal costAed, BigDecimal inputVatAed, BigDecimal initialRecoveryPct,
                        LocalDate firstUsedOn, int adjustmentYears, LocalDate disposedOn, Boolean disposalTaxable, boolean opening, String createdBy, OffsetDateTime createdAt) {
        static final String SELECT = """
                SELECT id, entity_id, reference, description, category, cost_aed, input_vat_aed, initial_recovery_pct, first_used_on, adjustment_years, disposed_on, disposal_taxable, opening, created_by, created_at
                FROM capital_asset
                """;
    }

    public record Interval(UUID id, UUID assetId, int intervalNo, LocalDate intervalEnd, BigDecimal taxableUsePct, BigDecimal vatAdjustment, UUID periodId, String recordedBy, OffsetDateTime recordedAt) {
        static final String SELECT = "SELECT id, asset_id, interval_no, interval_end, taxable_use_pct, vat_adjustment, period_id, recorded_by, recorded_at FROM capital_asset_interval";
    }

    public record AssetView(Asset asset, List<Interval> intervals, Integer nextIntervalNo, LocalDate nextIntervalEnd) {}

    public record Due(Asset asset, int intervalNo, LocalDate intervalEnd) {}

    private final JdbcClient jdbc;
    private final RulePackStore packs;
    private final AdjustmentService adjustments;
    private final AuditLog audit;

    public CapitalAssetService(JdbcClient jdbc, RulePackStore packs, AdjustmentService adjustments, AuditLog audit) {
        this.jdbc = jdbc;
        this.packs = packs;
        this.adjustments = adjustments;
        this.audit = audit;
    }

    @Transactional
    public AssetView register(UUID entityId, AssetInput in) {
        if (!jdbc.sql("SELECT EXISTS (SELECT 1 FROM legal_entity WHERE id = ?)").param(entityId).query(Boolean.class).single()) {
            throw DomainException.notFound("Entity");
        }
        if (in.reference() == null || in.reference().isBlank() || in.description() == null || in.description().isBlank()) {
            throw DomainException.invalid("ASSET_INCOMPLETE", "An asset needs a reference and a description");
        }
        if (in.category() == null || !Set.of("REAL_ESTATE", "OTHER").contains(in.category())) {
            throw DomainException.invalid("CATEGORY_INVALID", "The category must be REAL_ESTATE or OTHER");
        }
        if (in.costAed() == null || in.inputVatAed() == null || in.firstUsedOn() == null || in.initialRecoveryPct() == null
                || in.initialRecoveryPct().signum() < 0 || in.initialRecoveryPct().compareTo(HUNDRED) > 0) {
            throw DomainException.invalid("ASSET_INCOMPLETE", "Give the cost, the input VAT, the first use date and the recovery rate from 0 to 100");
        }
        RulePackDefinition.CapitalAssets rule = rule(in.firstUsedOn());
        if (in.costAed().compareTo(rule.thresholdAed()) < 0) {
            throw DomainException.invalid("ASSET_BELOW_THRESHOLD", "The scheme applies to assets from AED " + rule.thresholdAed() + " excluding VAT");
        }
        UUID id = UUID.randomUUID();
        int inserted = jdbc.sql("""
                INSERT INTO capital_asset (id, tenant_id, entity_id, reference, description, category, cost_aed, input_vat_aed, initial_recovery_pct, first_used_on, adjustment_years, created_by)
                VALUES (:id, :tenant, :entity, :reference, :description, :category, :cost, :vat, :initial, :firstUsed, :years, :by)
                ON CONFLICT (tenant_id, entity_id, reference) DO NOTHING
                """)
                .param("id", id).param("tenant", RequestContext.requireTenant()).param("entity", entityId).param("reference", in.reference()).param("description", in.description())
                .param("category", in.category()).param("cost", in.costAed()).param("vat", in.inputVatAed()).param("initial", in.initialRecoveryPct()).param("firstUsed", in.firstUsedOn())
                .param("years", "REAL_ESTATE".equals(in.category()) ? rule.realEstateYears() : rule.otherYears()).param("by", RequestContext.requireActor().actorId())
                .update();
        if (inserted == 0) {
            throw DomainException.conflict("ASSET_EXISTS", "An asset with this reference already exists");
        }
        audit.record("CAPITAL_ASSET_REGISTERED", "CAPITAL_ASSET", id, entityId, null, Map.of("reference", in.reference(), "cost", in.costAed().toPlainString()), null);
        return view(id);
    }

    /**
     * Records one interval's taxable use, in the first return after the interval ends. Intervals run in
     * order, and the database refuses a repeat, so one interval cannot be adjusted twice.
     */
    @Transactional
    public AssetView adjust(TaxPeriod period, UUID assetId, BigDecimal taxableUsePct) {
        Asset a = requireOnEntity(assetId, period.entityId());
        if (taxableUsePct == null || taxableUsePct.signum() < 0 || taxableUsePct.compareTo(HUNDRED) > 0) {
            throw DomainException.invalid("USE_PCT_INVALID", "Taxable use must be from 0 to 100");
        }
        if (a.disposedOn() != null) {
            throw DomainException.conflict("ASSET_DISPOSED", "This asset was disposed of, so its remaining intervals are settled together");
        }
        int next = nextInterval(a);
        if (next > a.adjustmentYears()) {
            throw DomainException.conflict("ASSET_SCHEME_COMPLETE", "This asset has finished its adjustment period");
        }
        LocalDate end = intervalEnd(a, next);
        if (!isFirstReturnAfter(period, end)) {
            throw DomainException.conflict("ASSET_WRONG_PERIOD", "Interval " + next + " ends on " + end + ", so it goes in the first return after that date");
        }
        BigDecimal vat = oneInterval(a, taxableUsePct);
        record(period, a, next, end, taxableUsePct, vat, "Capital asset " + a.reference() + ", interval " + next + " at " + taxableUsePct.stripTrailingZeros().toPlainString() + "% taxable use");
        return view(assetId);
    }

    /** On disposal, every remaining interval is treated as all taxable or all exempt, and settled in one adjustment. */
    @Transactional
    public AssetView dispose(TaxPeriod period, UUID assetId, LocalDate disposedOn, boolean taxable) {
        Asset a = requireOnEntity(assetId, period.entityId());
        if (a.disposedOn() != null) {
            throw DomainException.conflict("ASSET_DISPOSED", "This asset has already been disposed of");
        }
        if (disposedOn == null || !period.contains(disposedOn)) {
            throw DomainException.invalid("ASSET_WRONG_PERIOD", "The disposal date must fall within this period");
        }
        jdbc.sql("UPDATE capital_asset SET disposed_on = ?, disposal_taxable = ? WHERE id = ?").params(disposedOn, taxable, assetId).update();
        BigDecimal use = taxable ? HUNDRED : BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        int next = nextInterval(a);
        for (int n = next; n <= a.adjustmentYears(); n++) {
            total = total.add(oneInterval(a, use));
        }
        if (next > a.adjustmentYears() || total.signum() == 0) {
            return view(assetId);
        }
        UUID adjustment = adjustments.postCalculated(period, "CAPITAL_ASSET", rule(period.periodEnd()).adjustmentBox(), total,
                "Capital asset " + a.reference() + " disposed of: " + (a.adjustmentYears() - next + 1) + " remaining interval(s)", "Disposal", "capital-assets");
        for (int n = next; n <= a.adjustmentYears(); n++) {
            insertInterval(period, a, n, intervalEnd(a, n), use, oneInterval(a, use), adjustment);
        }
        audit.record("CAPITAL_ASSET_DISPOSED", "CAPITAL_ASSET", assetId, a.entityId(), null, Map.of("disposedOn", disposedOn.toString(), "taxable", taxable, "vat", total.toPlainString()), null);
        return view(assetId);
    }

    /** Assets with an interval that belongs in this return and has not been recorded. Check T10 reads this. */
    @Transactional(readOnly = true)
    public List<Due> dueIn(TaxPeriod period) {
        return list(period.entityId()).stream().filter(v -> v.asset().disposedOn() == null && v.nextIntervalNo() != null && v.nextIntervalEnd() != null
                        && isFirstReturnAfter(period, v.nextIntervalEnd()))
                .map(v -> new Due(v.asset(), v.nextIntervalNo(), v.nextIntervalEnd())).toList();
    }

    @Transactional(readOnly = true)
    public List<AssetView> list(UUID entityId) {
        return jdbc.sql(Asset.SELECT + " WHERE entity_id = ? ORDER BY first_used_on, reference").param(entityId).query(Asset.class).list().stream().map(this::viewOf).toList();
    }

    @Transactional(readOnly = true)
    public AssetView view(UUID id) {
        return viewOf(get(id));
    }

    @Transactional(readOnly = true)
    public Asset get(UUID id) {
        return jdbc.sql(Asset.SELECT + " WHERE id = ?").param(id).query(Asset.class).optional().orElseThrow(() -> DomainException.notFound("Capital asset"));
    }

    private AssetView viewOf(Asset a) {
        List<Interval> intervals = jdbc.sql(Interval.SELECT + " WHERE asset_id = ? ORDER BY interval_no").param(a.id()).query(Interval.class).list();
        int next = intervals.size() + 1;
        boolean more = next <= a.adjustmentYears() && a.disposedOn() == null;
        return new AssetView(a, intervals, more ? next : null, more ? intervalEnd(a, next) : null);
    }

    private void record(TaxPeriod period, Asset a, int n, LocalDate end, BigDecimal use, BigDecimal vat, String description) {
        UUID adjustment = vat.signum() == 0 ? null : adjustments.postCalculated(period, "CAPITAL_ASSET", rule(period.periodEnd()).adjustmentBox(), vat, description, "Annual capital asset adjustment", "capital-assets");
        insertInterval(period, a, n, end, use, vat, adjustment);
        audit.record("CAPITAL_ASSET_ADJUSTED", "CAPITAL_ASSET", a.id(), a.entityId(), null, Map.of("interval", n, "taxableUsePct", use.toPlainString(), "vat", vat.toPlainString()), null);
    }

    private void insertInterval(TaxPeriod period, Asset a, int n, LocalDate end, BigDecimal use, BigDecimal vat, UUID adjustment) {
        jdbc.sql("""
                INSERT INTO capital_asset_interval (id, tenant_id, asset_id, interval_no, interval_end, taxable_use_pct, vat_adjustment, period_id, adjustment_id, recorded_by)
                VALUES (:id, :tenant, :asset, :n, :end, :use, :vat, :period, :adjustment, :by)
                """)
                .param("id", UUID.randomUUID()).param("tenant", RequestContext.requireTenant()).param("asset", a.id()).param("n", n).param("end", end).param("use", use).param("vat", vat)
                .param("period", period.id()).param("adjustment", adjustment).param("by", RequestContext.requireActor().actorId())
                .update();
    }

    private static BigDecimal oneInterval(Asset a, BigDecimal use) {
        return a.inputVatAed().multiply(use.subtract(a.initialRecoveryPct())).divide(HUNDRED.multiply(BigDecimal.valueOf(a.adjustmentYears())), 2, RoundingMode.HALF_UP);
    }

    private int nextInterval(Asset a) {
        return jdbc.sql("SELECT COALESCE(MAX(interval_no), 0) + 1 FROM capital_asset_interval WHERE asset_id = ?").param(a.id()).query(Integer.class).single();
    }

    private static LocalDate intervalEnd(Asset a, int n) {
        return a.firstUsedOn().plusYears(n).minusDays(1);
    }

    private static boolean isFirstReturnAfter(TaxPeriod period, LocalDate end) {
        return period.periodEnd().isAfter(end) && !period.periodStart().minusDays(1).isAfter(end);
    }

    private Asset requireOnEntity(UUID id, UUID entityId) {
        Asset a = get(id);
        if (!a.entityId().equals(entityId)) {
            throw DomainException.notFound("Capital asset");
        }
        return a;
    }

    private RulePackDefinition.CapitalAssets rule(LocalDate date) {
        return RulePackDefinition.require(packs.forDate(date).definition().capitalAssets(), "capital assets");
    }
}
