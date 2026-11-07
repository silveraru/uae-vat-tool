package ae.vattool.corrections;

import ae.vattool.adjustments.CorrectionService;
import ae.vattool.masterdata.PeriodCalendar;
import ae.vattool.masterdata.TaxPeriod;
import ae.vattool.returns.BoxCalculator.BoxValue;
import ae.vattool.returns.ReturnService;
import ae.vattool.rules.RulePackDefinition.PenaltySchedule;
import ae.vattool.rules.RulePackStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Gathers the facts about a period and prices them against the schedule in force at the time. */
@Service
public class PenaltyService {

    private final JdbcClient jdbc;
    private final PeriodCalendar calendar;
    private final ReturnService returns;
    private final RulePackStore packs;
    private final Clock clock = Clock.system(ZoneId.of("Asia/Dubai"));

    public PenaltyService(JdbcClient jdbc, PeriodCalendar calendar, ReturnService returns, RulePackStore packs) {
        this.jdbc = jdbc;
        this.calendar = calendar;
        this.returns = returns;
        this.packs = packs;
    }

    /** Exposure on the return itself: late filing and late payment. */
    @Transactional
    public PenaltyCalculator.Estimate forPeriod(UUID periodId, LocalDate asOf) {
        TaxPeriod p = calendar.get(periodId);
        return estimate(p, asOf, BigDecimal.ZERO, false, null);
    }

    /** Exposure on the return, plus what a disclosure of this correction adds. */
    @Transactional
    public PenaltyCalculator.Estimate forCorrection(CorrectionService.CorrectionCase c, LocalDate asOf, LocalDate disclosedOn) {
        TaxPeriod p = calendar.get(c.periodId());
        boolean required = "FORM_211".equals(c.route());
        return estimate(p, asOf, c.taxDifferenceAed().max(BigDecimal.ZERO), required, disclosedOn);
    }

    private PenaltyCalculator.Estimate estimate(TaxPeriod p, LocalDate asOf, BigDecimal understated, boolean disclosureRequired, LocalDate disclosedOn) {
        LocalDate on = asOf != null ? asOf : LocalDate.now(clock);
        PenaltySchedule schedule = PenaltyCalculator.scheduleFor(packs.forDate(p.dueDate()).definition().penalties(), p.dueDate());
        LocalDate filedOn = jdbc.sql("SELECT submitted_on FROM filing WHERE period_id = ?").param(p.id()).query(LocalDate.class).optional().orElse(null);
        BigDecimal payable = p.lockedVersionId() == null ? BigDecimal.ZERO : returns.find(p.lockedVersionId())
                .map(v -> ReturnService.parseValues(v.boxValues()).get("14")).map(BoxValue::vat).orElse(BigDecimal.ZERO);
        // Only money a bank confirmed counts here, since an unconfirmed payment may not have happened.
        record PaidRow(LocalDate paidOn, BigDecimal amountPaidAed) {}
        List<PenaltyCalculator.Paid> payments = jdbc.sql("""
                SELECT paid_on, amount_paid_aed FROM payment WHERE period_id = ? AND match_status = 'MATCHED'
                """).param(p.id()).query(PaidRow.class).list().stream()
                .map(r -> new PenaltyCalculator.Paid(r.paidOn(), r.amountPaidAed())).toList();
        int window = schedule == null ? 0 : schedule.lateFilingRepeatWindowMonths();
        boolean repeat = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM filing f JOIN tax_period tp ON tp.id = f.period_id
                               WHERE tp.entity_id = :entity AND tp.id <> :period AND f.submitted_on > tp.due_date
                                 AND tp.due_date < :due AND tp.due_date >= :from)
                """).param("entity", p.entityId()).param("period", p.id()).param("due", p.dueDate()).param("from", p.dueDate().minusMonths(window))
                .query(Boolean.class).single();
        LocalDate notified = jdbc.sql("""
                SELECT MIN(notified_on) FROM audit_case
                WHERE entity_id = :entity AND period_from <= :end AND period_to >= :start
                """).param("entity", p.entityId()).param("end", p.periodEnd()).param("start", p.periodStart())
                .query(LocalDate.class).optional().orElse(null);
        return PenaltyCalculator.estimate(schedule, new PenaltyCalculator.Facts(p.dueDate(), filedOn, payable, payments, repeat, understated,
                disclosureRequired, disclosedOn, notified), on);
    }
}
