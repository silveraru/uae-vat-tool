package ae.vattool.workflow;

import ae.vattool.platform.tenancy.RequestContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
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
    private static final Logger log = LoggerFactory.getLogger(DeadlineAlertJob.class);

    /** Tier in days before the due date, most urgent first, with the roles that hear about it. */
    private static final Map<Integer, List<String>> RECIPIENTS = Map.of(
            0, List.of("PREPARER", "TAX_REVIEWER", "ENGAGEMENT_PARTNER", "APPROVER", "FIRM_ADMIN"),
            2, List.of("PREPARER", "TAX_REVIEWER", "ENGAGEMENT_PARTNER", "APPROVER"),
            5, List.of("PREPARER", "TAX_REVIEWER"),
            10, List.of("PREPARER"));
    private static final List<Integer> TIERS = List.of(0, 2, 5, 10);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final JavaMailSender mail;
    private final MeterRegistry metrics;
    // Deadlines are UAE calendar days, wherever the server runs.
    private final Clock clock = Clock.system(ZoneId.of("Asia/Dubai"));
    private final boolean enabled;

    public DeadlineAlertJob(JdbcClient jdbc, TransactionTemplate tx, JavaMailSender mail, MeterRegistry metrics,
                            @Value("${app.worker.enabled}") boolean enabled) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.mail = mail;
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

    private record Recipient(UUID id, String email, String displayName) {}

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
        String task = "FILED".equals(p.state()) ? "payment" : "return";
        String title = daysLeft < 0
                ? p.legalName() + ": VAT " + task + " is overdue"
                : p.legalName() + ": VAT " + task + " due in " + daysLeft + " days";
        String body = "VAT " + task + " for the period ending " + p.periodEnd() + " is due on " + p.dueDate()
                + ". Current state: " + p.state() + ".";
        List<Recipient> people = jdbc.sql("""
                SELECT DISTINCT u.id, u.email, u.display_name FROM app_user u
                JOIN entity_grant g ON g.user_id = u.id
                WHERE u.active AND (g.entity_id = :entity OR g.entity_id IS NULL) AND g.role IN (:roles)
                  AND g.valid_from <= now() AND (g.valid_to IS NULL OR g.valid_to > now())
                """).param("entity", p.entityId()).param("roles", RECIPIENTS.get(tier)).query(Recipient.class).list();
        for (Recipient r : people) {
            jdbc.sql("""
                    INSERT INTO notification (id, tenant_id, user_id, entity_id, period_id, kind, title, body)
                    VALUES (:id, :tenant, :user, :entity, :period, :kind, :title, :body)
                    """)
                    .param("id", UUID.randomUUID()).param("tenant", RequestContext.requireTenant()).param("user", r.id())
                    .param("entity", p.entityId()).param("period", p.id()).param("kind", "DEADLINE_T" + tier)
                    .param("title", title).param("body", body)
                    .update();
            sendMail(r, title, body);
        }
        metrics.counter("vat.deadline.alerts", "tier", String.valueOf(tier)).increment(people.size());
        return people.size();
    }

    private void sendMail(Recipient r, String title, String body) {
        try {
            SimpleMailMessage m = new SimpleMailMessage();
            m.setTo(r.email());
            m.setFrom("no-reply@vat-tool.local");
            m.setSubject(title);
            m.setText(body);
            mail.send(m);
        } catch (RuntimeException e) {
            metrics.counter("vat.deadline.mail_failed").increment();
            log.warn("Could not send a deadline email to {}: {}", r.id(), e.getMessage());
        }
    }
}
