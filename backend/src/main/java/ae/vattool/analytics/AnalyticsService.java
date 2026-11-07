package ae.vattool.analytics;

import ae.vattool.masterdata.TaxPeriod;
import ae.vattool.returns.BoxCalculator.BoxValue;
import ae.vattool.returns.ReturnService;
import ae.vattool.rules.RulePackDefinition;
import ae.vattool.rules.RulePackStore;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Trends and anomaly flags over stored returns (ANL-01, ANL-02). Each period reads the locked figures
 * if there are any, and the latest draft if not. PostgreSQL serves this: per-entity series are small,
 * so a columnar store would add operations work with nothing to show for it yet.
 */
@Service
public class AnalyticsService {

    public record Point(UUID periodId, LocalDate periodStart, LocalDate periodEnd, String state, boolean locked, BigDecimal outputTax, BigDecimal recoverableTax, BigDecimal netVat,
                        BigDecimal inputTaxIncurred, BigDecimal recoveryRatePct, BigDecimal totalSupplies, BigDecimal effectiveRatePct, long openExceptions, long resolvedExceptions, long waivedExceptions) {}

    /** code and params let the screen word this in either language. */
    public record Anomaly(String code, String severity, String box, BigDecimal value, BigDecimal baseline, BigDecimal changePct, String counterparty) {}

    private record Row(UUID periodId, LocalDate periodStart, LocalDate periodEnd, String state, UUID lockedVersionId, String boxValues, BigDecimal inputIncurred, long openCount, long resolvedCount, long waivedCount) {}

    private final JdbcClient jdbc;
    private final RulePackStore packs;

    public AnalyticsService(JdbcClient jdbc, RulePackStore packs) {
        this.jdbc = jdbc;
        this.packs = packs;
    }

    @Transactional(readOnly = true)
    public List<Point> trends(UUID entityId) {
        List<Row> rows = jdbc.sql("""
                SELECT p.id AS period_id, p.period_start, p.period_end, p.state, p.locked_version_id,
                       (SELECT v.box_values::text FROM return_version v WHERE v.period_id = p.id
                        ORDER BY (v.id = p.locked_version_id) DESC NULLS LAST, v.version_no DESC LIMIT 1) AS box_values,
                       (SELECT COALESCE(SUM(l.vat_aed), 0) FROM tax_line l WHERE l.period_id = p.id AND l.superseded_at IS NULL AND l.memo = false AND l.direction = 'IN' AND l.line_kind = 'NORMAL') AS input_incurred,
                       (SELECT COUNT(*) FROM recon_exception x WHERE x.period_id = p.id AND x.status IN ('OPEN','ASSIGNED')) AS open_count,
                       (SELECT COUNT(*) FROM recon_exception x WHERE x.period_id = p.id AND x.status = 'RESOLVED') AS resolved_count,
                       (SELECT COUNT(*) FROM recon_exception x WHERE x.period_id = p.id AND x.status = 'WAIVED') AS waived_count
                FROM tax_period p WHERE p.entity_id = ? ORDER BY p.period_start
                """).param(entityId).query(Row.class).list();
        List<Point> out = new ArrayList<>();
        for (Row r : rows) {
            if (r.boxValues() == null) {
                continue;
            }
            Map<String, BoxValue> b = ReturnService.parseValues(r.boxValues());
            BigDecimal output = vat(b, "12");
            BigDecimal recoverable = vat(b, "13");
            BigDecimal supplies = standardSupplies(b).add(amount(b, "4")).add(amount(b, "5"));
            out.add(new Point(r.periodId(), r.periodStart(), r.periodEnd(), r.state(), r.lockedVersionId() != null, output, recoverable, vat(b, "14"), r.inputIncurred(),
                    pct(recoverable, r.inputIncurred()), supplies, pct(output, supplies), r.openCount(), r.resolvedCount(), r.waivedCount()));
        }
        return out;
    }

    /** Compares a period with the one before it, and with the trailing average, for what a reviewer should look at. */
    @Transactional
    public List<Anomaly> anomalies(TaxPeriod p) {
        RulePackDefinition def = packs.forDate(p.periodEnd()).definition();
        RulePackDefinition.Analytics a = RulePackDefinition.require(def.analytics(), "analytics");
        List<Point> series = trends(p.entityId());
        int at = -1;
        for (int i = 0; i < series.size(); i++) {
            if (series.get(i).periodId().equals(p.id())) {
                at = i;
            }
        }
        List<Anomaly> out = new ArrayList<>();
        if (at >= 0) {
            Map<String, BoxValue> current = boxes(series.get(at).periodId(), p.lockedVersionId());
            if (at > 0) {
                Map<String, BoxValue> previous = boxes(series.get(at - 1).periodId(), null);
                for (RulePackDefinition.Box box : def.form().boxes()) {
                    if (box.isComputed()) {
                        continue;
                    }
                    BoxValue now = current.get(box.code());
                    BoxValue before = previous.get(box.code());
                    if (now == null || before == null) {
                        continue;
                    }
                    BigDecimal x = box.columns().contains("vat") ? now.vat() : now.amount();
                    BigDecimal y = box.columns().contains("vat") ? before.vat() : before.amount();
                    if (y.signum() != 0 && pct(x.subtract(y).abs(), y.abs()).compareTo(def.reconciliation().boxSwingPct()) > 0) {
                        out.add(new Anomaly("BOX_SWING", "WARNING", box.code(), x, y, pct(x.subtract(y), y.abs()), null));
                    }
                }
            }
            int from = Math.max(0, at - a.trailingPeriods());
            if (at > from) {
                BigDecimal sum = BigDecimal.ZERO;
                for (int i = from; i < at; i++) {
                    sum = sum.add(amount(boxes(series.get(i).periodId(), null), "4"));
                }
                BigDecimal average = sum.divide(BigDecimal.valueOf(at - from), 2, RoundingMode.HALF_UP);
                BigDecimal zero = amount(current, "4");
                if (zero.compareTo(a.zeroRatedMinAed()) >= 0 && zero.compareTo(average.multiply(a.zeroRatedGrowthMultiple())) > 0) {
                    out.add(new Anomaly("ZERO_RATED_GROWTH", "WARNING", "4", zero, average, average.signum() == 0 ? null : pct(zero.subtract(average), average), null));
                }
            }
        }
        record NewSupplier(String counterpartyTrn, BigDecimal net) {}
        jdbc.sql("""
                SELECT l.counterparty_trn, SUM(l.net_aed) AS net FROM tax_line l
                WHERE l.period_id = :period AND l.superseded_at IS NULL AND l.direction = 'IN' AND l.line_kind = 'NORMAL' AND l.counterparty_trn IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM tax_line o JOIN tax_period q ON q.id = o.period_id
                                  WHERE o.counterparty_trn = l.counterparty_trn AND o.filer_entity_id = l.filer_entity_id AND o.superseded_at IS NULL AND q.period_start < :start)
                GROUP BY l.counterparty_trn HAVING SUM(l.net_aed) >= :threshold ORDER BY SUM(l.net_aed) DESC
                """).param("period", p.id()).param("start", p.periodStart()).param("threshold", a.newSupplierNetAed()).query(NewSupplier.class).list()
                .forEach(n -> out.add(new Anomaly("NEW_HIGH_VALUE_SUPPLIER", "WARNING", null, n.net(), null, null, n.counterpartyTrn())));
        return out;
    }

    private Map<String, BoxValue> boxes(UUID periodId, UUID locked) {
        String json = jdbc.sql("""
                SELECT v.box_values::text FROM return_version v JOIN tax_period p ON p.id = v.period_id WHERE v.period_id = ?
                ORDER BY (v.id = p.locked_version_id) DESC NULLS LAST, v.version_no DESC LIMIT 1
                """).param(periodId).query(String.class).optional().orElse(null);
        return json == null ? Map.of() : ReturnService.parseValues(json);
    }

    private static BigDecimal standardSupplies(Map<String, BoxValue> b) {
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<String, BoxValue> e : b.entrySet()) {
            if (e.getKey().matches("1[a-g]")) {
                total = total.add(e.getValue().amount());
            }
        }
        return total;
    }

    private static BigDecimal vat(Map<String, BoxValue> b, String code) {
        BoxValue v = b.get(code);
        return v == null ? BigDecimal.ZERO : v.vat();
    }

    private static BigDecimal amount(Map<String, BoxValue> b, String code) {
        BoxValue v = b.get(code);
        return v == null ? BigDecimal.ZERO : v.amount();
    }

    private static BigDecimal pct(BigDecimal part, BigDecimal whole) {
        return whole.signum() == 0 ? BigDecimal.ZERO.setScale(2) : part.multiply(BigDecimal.valueOf(100)).divide(whole, 2, RoundingMode.HALF_UP);
    }
}
