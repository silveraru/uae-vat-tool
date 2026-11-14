package ae.vattool.platform.observability;

import ae.vattool.platform.tenancy.RequestContext;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes vat_tenant_info{tenant, name} = 1 for each tenant, so a dashboard can offer firms by name
 * while every other metric carries only the tenant id (NFR-14). Each name is read inside its own tenant,
 * the only place row-level security lets the application see it.
 */
@Component
public class TenantInfoMetric {

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final MultiGauge gauge;

    public TenantInfoMetric(JdbcClient jdbc, TransactionTemplate tx, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.gauge = MultiGauge.builder("vat.tenant.info").description("One series per tenant, naming it").register(metrics);
    }

    @Scheduled(initialDelayString = "${app.metrics.tenant-info-initial-ms:15000}", fixedDelayString = "${app.metrics.tenant-info-ms:600000}")
    public void refresh() {
        List<MultiGauge.Row<?>> rows = new ArrayList<>();
        for (UUID tenant : jdbc.sql("SELECT list_tenant_ids()").query(UUID.class).list()) {
            String name = RequestContext.asSystem(tenant, "tenant-info", () -> tx.execute(s -> jdbc.sql("SELECT name FROM tenant WHERE id = ?")
                    .param(tenant).query(String.class).optional().orElse(null)));
            if (name != null) {
                rows.add(MultiGauge.Row.of(Tags.of("tenant", tenant.toString(), "name", name), 1));
            }
        }
        // Overwrites, so a tenant that has gone stops being offered.
        gauge.register(rows, true);
    }
}
