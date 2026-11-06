package ae.vattool.masterdata;

import ae.vattool.platform.tenancy.RequestContext;
import ae.vattool.platform.web.DomainException;
import ae.vattool.rules.RulePackDefinition;
import ae.vattool.rules.RulePackStore;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The filing calendar (ONB-03). Periods are created on demand from the entity's first period and
 * frequency, so any date on or after the first period always resolves to exactly one period.
 */
@Service
public class PeriodCalendar {

    private final JdbcClient jdbc;
    private final RulePackStore packs;

    public PeriodCalendar(JdbcClient jdbc, RulePackStore packs) {
        this.jdbc = jdbc;
        this.packs = packs;
    }

    /** Period dates containing the given day, or empty if the day is before the first period. */
    public static Optional<LocalDate[]> boundsFor(LocalDate first, String frequency, LocalDate day) {
        if (day.isBefore(first)) {
            return Optional.empty();
        }
        int months = monthsIn(frequency);
        long monthsSinceFirst = java.time.temporal.ChronoUnit.MONTHS.between(first, day.withDayOfMonth(1));
        LocalDate start = first.plusMonths(monthsSinceFirst - (monthsSinceFirst % months));
        return Optional.of(new LocalDate[] {start, start.plusMonths(months).minusDays(1)});
    }

    private static int monthsIn(String frequency) {
        return "QUARTERLY".equals(frequency) ? 3 : 1;
    }

    /** Applies the rule pack: the 28th day after period end, optionally moved off non-working days. */
    public static LocalDate dueDate(LocalDate periodEnd, RulePackDefinition.Filing filing) {
        LocalDate due = periodEnd.plusDays(filing.dueDaysAfterPeriodEnd());
        if (filing.rollForwardNonWorkingDays() && filing.weekendDays() != null) {
            List<DayOfWeek> weekend = filing.weekendDays();
            while (weekend.contains(due.getDayOfWeek())) {
                due = due.plusDays(1);
            }
        }
        return due;
    }

    /** Finds or creates the period for a day. Empty if the day is before the entity's first period. */
    @Transactional
    public Optional<TaxPeriod> periodFor(UUID filerId, LocalDate day) {
        LegalEntity filer = jdbc.sql(LegalEntity.SELECT + " WHERE id = ?").param(filerId)
                .query(LegalEntity.class).optional()
                .orElseThrow(() -> DomainException.notFound("Entity"));
        if (!filer.isFiler()) {
            throw new IllegalArgumentException("Periods belong to the filer, not a group member");
        }
        Optional<LocalDate[]> bounds = boundsFor(filer.firstPeriodStart(), filer.filingFrequency(), day);
        if (bounds.isEmpty()) {
            return Optional.empty();
        }
        LocalDate start = bounds.get()[0];
        LocalDate end = bounds.get()[1];
        Optional<TaxPeriod> existing = find(filerId, start);
        if (existing.isPresent()) {
            return existing;
        }
        LocalDate due = dueDate(end, packs.forDate(end).definition().filing());
        jdbc.sql("""
                INSERT INTO tax_period (id, tenant_id, entity_id, period_start, period_end, due_date)
                VALUES (:id, :tenant, :entity, :start, :end, :due)
                ON CONFLICT (tenant_id, entity_id, period_start) DO NOTHING
                """)
                .param("id", UUID.randomUUID()).param("tenant", RequestContext.requireTenant())
                .param("entity", filerId).param("start", start).param("end", end).param("due", due)
                .update();
        return find(filerId, start);
    }

    /** Makes sure every period from the first one through the given day exists, with no gaps. */
    @Transactional
    public List<TaxPeriod> ensureThrough(UUID filerId, LocalDate through) {
        LegalEntity filer = jdbc.sql(LegalEntity.SELECT + " WHERE id = ?").param(filerId)
                .query(LegalEntity.class).single();
        LocalDate cursor = filer.firstPeriodStart();
        while (!cursor.isAfter(through)) {
            TaxPeriod p = periodFor(filerId, cursor).orElseThrow();
            cursor = p.periodEnd().plusDays(1);
        }
        return list(filerId);
    }

    @Transactional(readOnly = true)
    public List<TaxPeriod> list(UUID filerId) {
        return jdbc.sql(TaxPeriod.SELECT + " WHERE entity_id = ? ORDER BY period_start").param(filerId)
                .query(TaxPeriod.class).list();
    }

    private Optional<TaxPeriod> find(UUID filerId, LocalDate start) {
        return jdbc.sql(TaxPeriod.SELECT + " WHERE entity_id = ? AND period_start = ?").params(filerId, start)
                .query(TaxPeriod.class).optional();
    }
}
