package ae.vattool.analytics;

import ae.vattool.platform.audit.AuditLog;
import ae.vattool.platform.notify.Notifier;
import ae.vattool.platform.tenancy.RequestContext;
import ae.vattool.platform.web.DomainException;
import ae.vattool.rules.RulePackDefinition;
import ae.vattool.rules.RulePackStore;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Warns before an unregistered client must register (ANL-03): rolling 12-month taxable supplies, plus
 * what is expected in the next 30 days. These clients have no TRN, so they are kept apart from legal
 * entities, and the firm is told once each time a client's status changes.
 */
@Service
public class RegistrationWatchService {

    public record WatchInput(String name, String notes) {}

    public record MonthInput(LocalDate monthStart, BigDecimal taxableSuppliesAed, BigDecimal zeroRatedAed, BigDecimal expectedNext30DaysAed) {}

    public record Month(LocalDate monthStart, BigDecimal taxableSuppliesAed, BigDecimal zeroRatedAed, BigDecimal expectedNext30DaysAed) {}

    public record Watch(UUID id, String name, String notes, String createdBy, OffsetDateTime createdAt) {}

    public record Position(Watch watch, BigDecimal rollingAed, BigDecimal expectedNext30DaysAed, BigDecimal mandatoryAed, BigDecimal voluntaryAed, BigDecimal pctOfMandatory, String status, LocalDate asOf, List<Month> months) {}

    private final JdbcClient jdbc;
    private final RulePackStore packs;
    private final Notifier notifier;
    private final AuditLog audit;

    public RegistrationWatchService(JdbcClient jdbc, RulePackStore packs, Notifier notifier, AuditLog audit) {
        this.jdbc = jdbc;
        this.packs = packs;
        this.notifier = notifier;
        this.audit = audit;
    }

    @Transactional
    public Position add(WatchInput in) {
        if (in.name() == null || in.name().isBlank()) {
            throw DomainException.invalid("NAME_REQUIRED", "A name is required");
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO registration_watch (id, tenant_id, name, notes, created_by) VALUES (?, ?, ?, ?, ?)")
                .params(id, RequestContext.requireTenant(), in.name(), in.notes(), RequestContext.requireActor().actorId()).update();
        audit.record("REGISTRATION_WATCH_ADDED", "REGISTRATION_WATCH", id, null, null, Map.of("name", in.name()), null);
        return position(id);
    }

    @Transactional
    public Position recordMonth(UUID watchId, MonthInput in) {
        Watch w = watch(watchId);
        if (in.monthStart() == null || in.monthStart().getDayOfMonth() != 1) {
            throw DomainException.invalid("MONTH_INVALID", "Give the first day of the month");
        }
        if (in.taxableSuppliesAed() == null || in.taxableSuppliesAed().signum() < 0) {
            throw DomainException.invalid("AMOUNT_INVALID", "Taxable supplies must be zero or more");
        }
        jdbc.sql("""
                INSERT INTO registration_watch_month (tenant_id, watch_id, month_start, taxable_supplies_aed, zero_rated_aed, expected_next_30_days_aed)
                VALUES (:tenant, :watch, :month, :taxable, :zero, :expected)
                ON CONFLICT (tenant_id, watch_id, month_start) DO UPDATE SET taxable_supplies_aed = EXCLUDED.taxable_supplies_aed, zero_rated_aed = EXCLUDED.zero_rated_aed,
                    expected_next_30_days_aed = EXCLUDED.expected_next_30_days_aed
                """)
                .param("tenant", RequestContext.requireTenant()).param("watch", watchId).param("month", in.monthStart()).param("taxable", in.taxableSuppliesAed())
                .param("zero", in.zeroRatedAed() == null ? BigDecimal.ZERO : in.zeroRatedAed()).param("expected", in.expectedNext30DaysAed())
                .update();
        Position p = position(watchId);
        String previous = jdbc.sql("SELECT alerted_status FROM registration_watch WHERE id = ?").param(watchId).query(String.class).optional().orElse(null);
        if (!p.status().equals(previous) && List.of("APPROACHING", "MUST_REGISTER").contains(p.status())) {
            notifier.toTenantRoles("REGISTRATION_THRESHOLD", List.of("FIRM_ADMIN", "ENGAGEMENT_PARTNER"), "notify.registration.title", "notify.registration.body",
                    "name", w.name(), "total", p.rollingAed().toPlainString(), "status", p.status());
        }
        jdbc.sql("UPDATE registration_watch SET alerted_status = ? WHERE id = ?").params(p.status(), watchId).update();
        return p;
    }

    @Transactional
    public Position position(UUID watchId) {
        Watch w = watch(watchId);
        List<Month> months = jdbc.sql("""
                SELECT month_start, taxable_supplies_aed, zero_rated_aed, expected_next_30_days_aed AS expected_next30_days_aed
                FROM registration_watch_month WHERE watch_id = ? ORDER BY month_start DESC LIMIT 24
                """).param(watchId).query(Month.class).list();
        LocalDate asOf = months.isEmpty() ? LocalDate.now() : months.getFirst().monthStart();
        RulePackDefinition.Registration rule = RulePackDefinition.require(packs.forDate(asOf).definition().registration(), "registration");
        LocalDate from = asOf.minusMonths(rule.rollingMonths() - 1L);
        BigDecimal rolling = months.stream().filter(m -> !m.monthStart().isBefore(from)).map(Month::taxableSuppliesAed).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal expected = months.isEmpty() || months.getFirst().expectedNext30DaysAed() == null ? BigDecimal.ZERO : months.getFirst().expectedNext30DaysAed();
        return new Position(w, rolling, expected, rule.mandatoryAed(), rule.voluntaryAed(),
                rolling.multiply(BigDecimal.valueOf(100)).divide(rule.mandatoryAed(), 2, RoundingMode.HALF_UP), status(rule, rolling, expected), asOf, new ArrayList<>(months));
    }

    /** Going over in the rolling year, or expecting to in the next 30 days, both mean registering. */
    static String status(RulePackDefinition.Registration rule, BigDecimal rolling, BigDecimal expected) {
        if (rolling.compareTo(rule.mandatoryAed()) > 0 || expected.compareTo(rule.mandatoryAed()) > 0) {
            return "MUST_REGISTER";
        }
        if (rolling.multiply(BigDecimal.valueOf(100)).compareTo(rule.mandatoryAed().multiply(rule.approachingPct())) >= 0) {
            return "APPROACHING";
        }
        return rolling.compareTo(rule.voluntaryAed()) > 0 ? "MAY_REGISTER" : "BELOW";
    }

    @Transactional
    public List<Position> list() {
        return jdbc.sql("SELECT id FROM registration_watch ORDER BY name").query(UUID.class).list().stream().map(this::position).toList();
    }

    private Watch watch(UUID id) {
        return jdbc.sql("SELECT id, name, notes, created_by, created_at FROM registration_watch WHERE id = ?").param(id).query(Watch.class).optional()
                .orElseThrow(() -> DomainException.notFound("Registration watch"));
    }
}
