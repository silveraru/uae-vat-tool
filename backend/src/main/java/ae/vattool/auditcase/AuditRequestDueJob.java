package ae.vattool.auditcase;

import ae.vattool.platform.notify.Notifier;
import ae.vattool.platform.tenancy.RequestContext;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Reminds the people on an audit before an FTA deadline passes. Each request is reminded once a day at most. */
@Component
public class AuditRequestDueJob {

    private static final int DAYS_AHEAD = 5;
    private static final List<String> ROLES = List.of("TAX_REVIEWER", "ENGAGEMENT_PARTNER");

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Notifier notifier;
    private final boolean enabled;
    private final Clock clock = Clock.system(ZoneId.of("Asia/Dubai"));

    public AuditRequestDueJob(JdbcClient jdbc, TransactionTemplate tx, Notifier notifier, @Value("${app.worker.enabled}") boolean enabled) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.notifier = notifier;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${app.worker.deadline-cron:0 0 4 * * *}", zone = "Asia/Dubai")
    public void daily() {
        if (!enabled) {
            return;
        }
        LocalDate today = LocalDate.now(clock);
        for (UUID tenant : jdbc.sql("SELECT list_tenant_ids()").query(UUID.class).list()) {
            RequestContext.asSystem(tenant, "audit-request-alerts", () -> {
                run(today);
            });
        }
    }

    private record Due(UUID id, UUID entityId, String legalName, String ftaReference, String description, LocalDate dueOn) {}

    /** Sends what is due on the given day for the tenant in context. Returns how many requests were reminded. */
    public int run(LocalDate today) {
        return tx.execute(s -> {
            List<Due> due = jdbc.sql("""
                    SELECT r.id, c.entity_id, e.legal_name, c.fta_reference, r.description, r.due_on
                    FROM audit_request r JOIN audit_case c ON c.id = r.case_id JOIN legal_entity e ON e.id = c.entity_id
                    WHERE r.status = 'OPEN' AND c.status = 'OPEN' AND r.due_on <= :limit AND (r.alerted_on IS NULL OR r.alerted_on < :today)
                    """).param("limit", today.plusDays(DAYS_AHEAD)).param("today", today).query(Due.class).list();
            int reminded = 0;
            for (Due d : due) {
                // Claimed before sending, so a second worker on the same schedule finds nothing to send.
                int claimed = jdbc.sql("UPDATE audit_request SET alerted_on = :today WHERE id = :id AND (alerted_on IS NULL OR alerted_on < :today)")
                        .param("today", today).param("id", d.id()).update();
                if (claimed == 1) {
                    notifier.toRoles(d.entityId(), null, "AUDIT_REQUEST_DUE", ROLES, "notify.auditRequest.title", "notify.auditRequest.body",
                            "entity", d.legalName(), "due", d.dueOn(), "reference", d.ftaReference(), "description", d.description());
                    reminded++;
                }
            }
            return reminded;
        });
    }
}
