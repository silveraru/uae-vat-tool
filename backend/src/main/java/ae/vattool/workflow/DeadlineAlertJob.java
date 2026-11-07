package ae.vattool.workflow;

import ae.vattool.platform.notify.Notifier;
import ae.vattool.platform.tenancy.RequestContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deadline escalation at T-10, T-5 and T-2 (WF-03), plus one notice once a return is overdue. Each
 * tier goes to more senior people. If the job misses a day it sends only the most urgent tier due,
 * so a late run does not send three alerts at once. A mail failure never loses the in-app notice.
 */
@Component
public class DeadlineAlertJob {
    /** Tier in days before the due date, most urgent first, with the roles that hear about it. */
    private static final Map<Integer, List<String>> RECIPIENTS = Map.of(
            0, List.of("PREPARER", "TAX_REVIEWER", "ENGAGEMENT_PARTNER", "APPROVER", "FIRM_ADMIN"),
            2, List.of("PREPARER", "TAX_REVIEWER", "ENGAGEMENT_PARTNER", "APPROVER"),
            5, List.of("PREPARER", "TAX_REVIEWER"),
            10, List.of("PREPARER"));
    private static final List<Integer> TIERS = List.of(0, 2, 5, 10);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Notifier notifier;
    private final MeterRegistry metrics;
    // Deadlines are UAE calendar days, wherever the server runs.
    private final Clock clock = Clock.system(ZoneId.of("Asia/Dubai"));
    private final boolean enabled;

    public DeadlineAlertJob(JdbcClient jdbc, TransactionTemplate tx, Notifier notifier, MeterRegistry metrics,
                            @Value("${app.worker.enabled}") boolean enabled) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.notifier = notifier;
        this.metrics = metrics;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${app.worker.deadline-cron:0 0 4 * * *}", zone = "Asia/Dubai")
    public void daily() {
        if (!enabled) {
            return;
        }
        LocalDate today = LocalDate.now(clock);
        for (UUID tenant : jdbc.sql("SELECT list_tenant_ids()").query(UUID.class).list()) {
            RequestContext.asSystem(tenant, "deadline-alerts", () -> {
                run(today);
            });
        }
    }

    private record Due(UUID id, UUID entityId, String legalName, LocalDate periodEnd, LocalDate dueDate, String state) {}

    /** Sends what is due on the given day for the tenant in context. Returns how many notices went out. */
    public int run(LocalDate today) {
        List<Due> periods = tx.execute(s -> jdbc.sql("""
                SELECT p.id, p.entity_id, e.legal_name, p.period_end, p.due_date, p.state
                FROM tax_period p JOIN legal_entity e ON e.id = p.entity_id
                WHERE p.state IN ('OPEN', 'IN_REVIEW', 'APPROVED', 'FILED') AND p.due_date <= :limit AND p.period_end < :today
                """).param("limit", today.plusDays(10)).param("today", today).query(Due.class).list());
        int sent = 0;
        for (Due p : periods) {
            long daysLeft = ChronoUnit.DAYS.between(today, p.dueDate());
            Integer tier = tierFor(daysLeft);
            if (tier != null) {
                sent += tx.execute(s -> sendTier(p, tier, daysLeft));
            }
        }
        return sent;
    }

    private static Integer tierFor(long daysLeft) {
        if (daysLeft < 0) {
            return 0;
        }
        for (int tier : List.of(2, 5, 10)) {
            if (daysLeft <= tier) {
                return tier;
            }
        }
        return null;
    }

    private int sendTier(Due p, int tier, long daysLeft) {
        int inserted = jdbc.sql("""
                INSERT INTO deadline_alert (tenant_id, period_id, tier) VALUES (?, ?, ?) ON CONFLICT DO NOTHING
                """).params(RequestContext.requireTenant(), p.id(), tier).update();
        if (inserted == 0) {
            return 0;
        }
        // Later tiers are spent too, so a skipped day does not fire them again once this one is sent.
        for (int later : TIERS) {
            if (later > tier) {
                jdbc.sql("INSERT INTO deadline_alert (tenant_id, period_id, tier) VALUES (?, ?, ?) ON CONFLICT DO NOTHING")
                        .params(RequestContext.requireTenant(), p.id(), later).update();
            }
        }
        String task = "notify.task." + ("FILED".equals(p.state()) ? "payment" : "return");
        // Each person reads this in their own language, so the task word is resolved per language below.
        int people = notifier.toRoles(p.entityId(), p.id(), "DEADLINE_T" + tier, RECIPIENTS.get(tier),
                daysLeft < 0 ? "notify.deadline.overdue" : "notify.deadline.title", "notify.deadline.body",
                "entity", p.legalName(), "task", new ae.vattool.platform.notify.Notifier.Text(task), "days", daysLeft, "end", p.periodEnd(), "due", p.dueDate(), "state", p.state());
        metrics.counter("vat.deadline.alerts", "tier", String.valueOf(tier)).increment(people);
        return people;
    }
}
