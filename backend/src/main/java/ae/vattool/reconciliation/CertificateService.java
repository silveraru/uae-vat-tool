package ae.vattool.reconciliation;

import ae.vattool.masterdata.TaxPeriod;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reconciliation certificate for the return pack (REC-04). It states what was checked and what
 * was found, including waivers, so nobody can read it as a clean result when it is not one.
 */
@Service
public class CertificateService {

    public record RuleResult(String ruleId, String name, String sets, String severity, long open, long resolved, long waived) {}

    public record Waiver(String ruleId, String message, String reason, UUID approver, OffsetDateTime waivedAt) {}

    /** Compares what the FTA should see from Corner 5 with what the books and return contain. */
    public record Corner5View(long issuedInvoices, long reportedToFta, long notReported, long rejected,
                              BigDecimal einvoiceNet, BigDecimal einvoiceVat, BigDecimal matchedErpNet,
                              BigDecimal matchedErpVat, BigDecimal vatVariance) {}

    public record Certificate(UUID periodId, String conclusion, UUID lastRunId, OffsetDateTime lastRunAt, List<RuleResult> rules,
                              List<Waiver> waivers, Corner5View corner5) {}

    private final JdbcClient jdbc;

    public CertificateService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Certificate build(TaxPeriod period) {
        record Count(String ruleId, String status, long n) {}
        List<Count> counts = jdbc.sql("SELECT rule_id, status, COUNT(*) AS n FROM recon_exception WHERE period_id = ? GROUP BY rule_id, status")
                .param(period.id()).query(Count.class).list();
        List<RuleResult> rules = new ArrayList<>();
        long blockingOpen = 0;
        long anyWaived = 0;
        for (RuleCatalog.Rule r : RuleCatalog.RULES) {
            long open = 0;
            long resolved = 0;
            long waived = 0;
            for (Count c : counts) {
                if (c.ruleId().equals(r.id())) {
                    switch (c.status()) {
                        case "OPEN", "ASSIGNED" -> open += c.n();
                        case "RESOLVED" -> resolved += c.n();
                        case "WAIVED" -> waived += c.n();
                        default -> { }
                    }
                }
            }
            if ("BLOCKING".equals(r.severity())) {
                blockingOpen += open;
            }
            anyWaived += waived;
            rules.add(new RuleResult(r.id(), r.name(), r.sets(), r.severity(), open, resolved, waived));
        }
        List<Waiver> waivers = jdbc.sql("""
                SELECT rule_id, message, waiver_reason AS reason, waiver_approver AS approver, waived_at
                FROM recon_exception WHERE period_id = ? AND status = 'WAIVED' ORDER BY rule_id, waived_at
                """).param(period.id()).query(Waiver.class).list();
        Map<String, Object> run = jdbc.sql("""
                SELECT id, finished_at FROM recon_run WHERE period_id = ? ORDER BY finished_at DESC LIMIT 1
                """).param(period.id()).query().listOfRows().stream().findFirst().orElse(Map.of());
        String conclusion = blockingOpen > 0 ? "NOT_RECONCILED"
                : anyWaived > 0 ? "RECONCILED_WITH_WAIVERS" : "RECONCILED";
        return new Certificate(period.id(), conclusion, (UUID) run.get("id"),
                run.get("finished_at") == null ? null : ((java.sql.Timestamp) run.get("finished_at")).toInstant().atOffset(java.time.ZoneOffset.UTC),
                rules, waivers, corner5(period));
    }

    private Corner5View corner5(TaxPeriod period) {
        record Totals(long issued, long reported, long notReported, long rejected, BigDecimal net, BigDecimal vat) {}
        Totals t = jdbc.sql("""
                SELECT COUNT(*) FILTER (WHERE lifecycle_status = 'ISSUED') AS issued,
                       COUNT(*) FILTER (WHERE lifecycle_status = 'ISSUED' AND corner5_status = 'REPORTED') AS reported,
                       COUNT(*) FILTER (WHERE lifecycle_status = 'ISSUED' AND corner5_status <> 'REPORTED') AS not_reported,
                       COUNT(*) FILTER (WHERE lifecycle_status = 'REJECTED') AS rejected,
                       COALESCE(SUM(net_aed) FILTER (WHERE lifecycle_status = 'ISSUED' AND corner5_status = 'REPORTED'), 0) AS net,
                       COALESCE(SUM(vat_aed) FILTER (WHERE lifecycle_status = 'ISSUED' AND corner5_status = 'REPORTED'), 0) AS vat
                FROM einvoice
                WHERE filer_entity_id = :filer AND direction = 'ISSUED' AND issue_date BETWEEN :from AND :to
                """).param("filer", period.entityId()).param("from", period.periodStart()).param("to", period.periodEnd())
                .query(Totals.class).single();
        record Erp(BigDecimal net, BigDecimal vat) {}
        Erp erp = jdbc.sql("""
                SELECT COALESCE(SUM(l.net_aed), 0) AS net, COALESCE(SUM(ROUND(l.vat_doc * l.fx_rate, 2)), 0) AS vat
                FROM tax_line l
                JOIN doc_match m ON m.document_id = l.document_id AND m.status = 'CONFIRMED'
                JOIN einvoice e ON e.invoice_uuid = m.invoice_uuid
                WHERE l.period_id = ? AND l.superseded_at IS NULL AND l.direction = 'OUT'
                  AND e.lifecycle_status = 'ISSUED' AND e.corner5_status = 'REPORTED'
                """).param(period.id()).query(Erp.class).single();
        return new Corner5View(t.issued(), t.reported(), t.notReported(), t.rejected(), t.net(), t.vat(), erp.net(), erp.vat(),
                erp.vat().subtract(t.vat()));
    }
}
