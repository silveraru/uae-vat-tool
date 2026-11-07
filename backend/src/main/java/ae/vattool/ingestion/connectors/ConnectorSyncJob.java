package ae.vattool.ingestion.connectors;

import ae.vattool.platform.tenancy.RequestContext;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Pulls each enabled connection once a day, from where its last good run ended. One failure never stops the others. */
@Component
public class ConnectorSyncJob {
    private static final Logger log = LoggerFactory.getLogger(ConnectorSyncJob.class);

    private final JdbcClient jdbc;
    private final ConnectorService connectors;
    private final boolean enabled;
    private final Clock clock = Clock.system(ZoneId.of("Asia/Dubai"));

    public ConnectorSyncJob(JdbcClient jdbc, ConnectorService connectors, @Value("${app.worker.enabled}") boolean enabled) {
        this.jdbc = jdbc;
        this.connectors = connectors;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${app.worker.connector-cron:0 30 2 * * *}", zone = "Asia/Dubai")
    public void daily() {
        if (!enabled) {
            return;
        }
        LocalDate today = LocalDate.now(clock);
        for (UUID tenant : jdbc.sql("SELECT list_tenant_ids()").query(UUID.class).list()) {
            RequestContext.asSystem(tenant, "connector-sync", () -> {
                run(today);
            });
        }
    }

    /** One pass for the tenant in context. Returns how many pulls succeeded. */
    public int run(LocalDate today) {
        int ok = 0;
        for (ConnectorService.Connector c : connectors.enabled()) {
            for (String kind : c.kinds()) {
                LocalDate from = connectors.nextFrom(c.id(), kind, today);
                if (from.isAfter(today)) {
                    continue;
                }
                try {
                    connectors.sync(c.id(), kind, from, today);
                    ok++;
                } catch (RuntimeException e) {
                    log.warn("Connector {} could not pull {}: {}", c.id(), kind, e.getMessage());
                }
            }
        }
        return ok;
    }
}
