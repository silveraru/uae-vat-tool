package ae.vattool.reconciliation;

import ae.vattool.platform.tenancy.RequestContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps returns and reconciliation current (RET-03, REC-01). Waiting for a quiet moment means a file
 * with thousands of rows causes one recompute rather than thousands. A failed period stays queued
 * and is retried, and the failure shows in the vat.recon.failed metric (NFR-14).
 */
@Component
@ConditionalOnProperty(name = "app.worker.enabled", havingValue = "true")
public class PeriodRefreshJob {
    private static final Logger log = LoggerFactory.getLogger(PeriodRefreshJob.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ReconciliationService reconciliation;
    private final MeterRegistry metrics;
    private final long quietMillis;

    public PeriodRefreshJob(JdbcClient jdbc, TransactionTemplate tx, ReconciliationService reconciliation, MeterRegistry metrics,
                            @Value("${app.worker.quiet-ms:2000}") long quietMillis) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.reconciliation = reconciliation;
        this.metrics = metrics;
        this.quietMillis = quietMillis;
    }

    @Scheduled(fixedDelayString = "${app.worker.refresh-ms:3000}")
    public void tick() {
        // Rows are not locked here: the run takes the period lock first, in the same order as a data
        // load. Two workers picking one period just means the second finds nothing to change.
        for (UUID tenant : jdbc.sql("SELECT list_tenant_ids()").query(UUID.class).list()) {
            RequestContext.asSystem(tenant, "period-refresh", () -> refreshTenant(tenant));
        }
    }

    private void refreshTenant(UUID tenant) {
        List<UUID> due = tx.execute(s -> jdbc.sql("""
                SELECT period_id FROM period_recompute_request
                WHERE requested_at < now() - make_interval(secs => :quiet)
                ORDER BY requested_at LIMIT 20
                """).param("quiet", quietMillis / 1000.0).query(UUID.class).list());
        if (due == null) {
            return;
        }
        for (UUID period : due) {
            try {
                reconciliation.run(period, "DATA_CHANGE");
            } catch (RuntimeException e) {
                metrics.counter("vat.recon.failed").increment();
                log.error("Could not refresh period {} in tenant {}", period, tenant, e);
            }
        }
    }
}
