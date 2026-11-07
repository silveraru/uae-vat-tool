package ae.vattool.corrections;

import ae.vattool.rules.RulePackDefinition.PenaltySchedule;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Penalty exposure from the effective-dated schedule (COR-03). A pure function, so every rule is unit
 * tested. The result is an estimate for a reviewer: the FTA decides what is actually charged.
 */
public final class PenaltyCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal MONTHS_IN_YEAR = BigDecimal.valueOf(12);

    public record Paid(LocalDate on, BigDecimal amount) {}

    /**
     * filedOn is null until filed. understatedTax is the tax short-declared, which only a disclosure has.
     * It is zero when the error went the other way, since overpaying carries no penalty on the difference.
     */
    public record Facts(LocalDate dueDate, LocalDate filedOn, BigDecimal payable, List<Paid> payments, boolean earlierLateFiling,
                        BigDecimal understatedTax, boolean disclosureRequired, LocalDate disclosedOn, LocalDate auditNotifiedOn) {}

    public record Item(String code, BigDecimal base, BigDecimal ratePct, long months, BigDecimal amount, boolean capped) {}

    public record Estimate(LocalDate asOf, boolean scheduleFound, String source, List<Item> items, BigDecimal total) {}

    private PenaltyCalculator() {}

    /** The schedule in force on the date of the violation, not today. */
    public static PenaltySchedule scheduleFor(List<PenaltySchedule> schedules, LocalDate violation) {
        if (schedules == null) {
            return null;
        }
        return schedules.stream().filter(s -> !s.effectiveFrom().isAfter(violation))
                .max(Comparator.comparing(PenaltySchedule::effectiveFrom)).orElse(null);
    }

    /** Whole months since a date, where any started month counts in full. */
    static long startedMonths(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            return 0;
        }
        long whole = ChronoUnit.MONTHS.between(from, to);
        return whole + 1;
    }

    public static Estimate estimate(PenaltySchedule s, Facts f, LocalDate asOf) {
        if (s == null) {
            return new Estimate(asOf, false, null, List.of(), BigDecimal.ZERO.setScale(2));
        }
        List<Item> items = new ArrayList<>();
        lateFiling(s, f, asOf, items);
        latePayment(s, f, asOf, items);
        disclosure(s, f, asOf, items);
        BigDecimal total = items.stream().map(Item::amount).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        return new Estimate(asOf, true, s.source(), List.copyOf(items), total);
    }

    private static void lateFiling(PenaltySchedule s, Facts f, LocalDate asOf, List<Item> items) {
        boolean late = f.filedOn() != null ? f.filedOn().isAfter(f.dueDate()) : asOf.isAfter(f.dueDate());
        if (late) {
            BigDecimal fixed = f.earlierLateFiling() ? s.lateFilingRepeatAed() : s.lateFilingFirstAed();
            items.add(new Item(f.earlierLateFiling() ? "LATE_FILING_REPEAT" : "LATE_FILING", null, null, 0, fixed.setScale(2, RoundingMode.HALF_UP), false));
        }
    }

    /**
     * One twelfth of the annual rate on what was still unpaid at the start of each month running from
     * the day after the due date. A payment reduces the balance from the next month on.
     */
    private static void latePayment(PenaltySchedule s, Facts f, LocalDate asOf, List<Item> items) {
        if (f.payable() == null || f.payable().signum() <= 0 || !asOf.isAfter(f.dueDate())) {
            return;
        }
        BigDecimal monthly = s.latePaymentAnnualPct().divide(MONTHS_IN_YEAR, MathContext.DECIMAL64);
        LocalDate start = f.dueDate().plusDays(1);
        BigDecimal accrued = BigDecimal.ZERO;
        long months = 0;
        for (long m = 0; !start.plusMonths(m).isAfter(asOf); m++) {
            LocalDate monthStart = start.plusMonths(m);
            BigDecimal paid = f.payments().stream().filter(p -> !p.on().isAfter(monthStart)).map(Paid::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal balance = f.payable().subtract(paid);
            if (balance.signum() <= 0) {
                break;
            }
            accrued = accrued.add(balance.multiply(monthly).divide(HUNDRED, MathContext.DECIMAL64));
            months++;
        }
        if (months > 0) {
            Capped c = cap(accrued, f.payable(), s);
            items.add(new Item("LATE_PAYMENT", f.payable(), monthly.setScale(4, RoundingMode.HALF_UP), months, c.amount(), c.capped()));
        }
    }

    /**
     * A voluntary disclosure accrues monthly from the original due date. If the FTA gave notice of an
     * audit before the disclosure was made, the fixed percentage applies as well.
     */
    private static void disclosure(PenaltySchedule s, Facts f, LocalDate asOf, List<Item> items) {
        if (!f.disclosureRequired() || f.understatedTax() == null || f.understatedTax().signum() <= 0) {
            return;
        }
        LocalDate until = f.disclosedOn() != null ? f.disclosedOn() : asOf;
        long months = startedMonths(f.dueDate().plusDays(1), until);
        items.add(new Item("INCORRECT_RETURN", null, null, 0, s.incorrectReturnAed().setScale(2, RoundingMode.HALF_UP), false));
        boolean afterAudit = f.auditNotifiedOn() != null && !f.auditNotifiedOn().isAfter(until);
        BigDecimal rate = afterAudit
                ? s.afterAuditFixedPct().add(s.afterAuditMonthlyPct().multiply(BigDecimal.valueOf(months)))
                : s.disclosureMonthlyPct().multiply(BigDecimal.valueOf(months));
        Capped c = cap(f.understatedTax().multiply(rate).divide(HUNDRED, MathContext.DECIMAL64), f.understatedTax(), s);
        items.add(new Item(afterAudit ? "UNDISCLOSED_BEFORE_AUDIT" : "VOLUNTARY_DISCLOSURE", f.understatedTax(), rate, months, c.amount(), c.capped()));
    }

    private record Capped(BigDecimal amount, boolean capped) {}

    private static Capped cap(BigDecimal amount, BigDecimal base, PenaltySchedule s) {
        if (s.capPctOfTax() != null) {
            BigDecimal limit = base.multiply(s.capPctOfTax()).divide(HUNDRED, MathContext.DECIMAL64);
            if (amount.compareTo(limit) > 0) {
                return new Capped(limit.setScale(2, RoundingMode.HALF_UP), true);
            }
        }
        return new Capped(amount.setScale(2, RoundingMode.HALF_UP), false);
    }

    /** Adds working days, skipping the weekend days in the rule pack. Public holidays are not modelled. */
    public static LocalDate addBusinessDays(LocalDate from, int days, List<java.time.DayOfWeek> weekend) {
        LocalDate d = from;
        int left = days;
        while (left > 0) {
            d = d.plusDays(1);
            if (weekend == null || !weekend.contains(d.getDayOfWeek())) {
                left--;
            }
        }
        return d;
    }
}
