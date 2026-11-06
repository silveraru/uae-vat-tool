package ae.vattool.masterdata;

import static org.assertj.core.api.Assertions.assertThat;

import ae.vattool.rules.RulePackDefinition;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class PeriodCalendarTest {

    private static LocalDate d(String s) {
        return LocalDate.parse(s);
    }

    private static RulePackDefinition.Filing filing(boolean rollForward) {
        return new RulePackDefinition.Filing(28, rollForward, List.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), new BigDecimal("10000"), 6);
    }

    @Test
    void monthlyPeriodCoversTheCalendarMonth() {
        LocalDate[] b = PeriodCalendar.boundsFor(d("2026-01-01"), "MONTHLY", d("2027-02-14")).orElseThrow();
        assertThat(b[0]).isEqualTo(d("2027-02-01"));
        assertThat(b[1]).isEqualTo(d("2027-02-28"));
    }

    @Test
    void quarterlyPeriodsFollowTheStaggerOfTheFirstPeriod() {
        // Stagger starting in January.
        LocalDate[] jan = PeriodCalendar.boundsFor(d("2026-01-01"), "QUARTERLY", d("2026-03-31")).orElseThrow();
        assertThat(jan[0]).isEqualTo(d("2026-01-01"));
        assertThat(jan[1]).isEqualTo(d("2026-03-31"));
        // Stagger starting in March: the 15 April date belongs to March to May.
        LocalDate[] mar = PeriodCalendar.boundsFor(d("2026-03-01"), "QUARTERLY", d("2026-04-15")).orElseThrow();
        assertThat(mar[0]).isEqualTo(d("2026-03-01"));
        assertThat(mar[1]).isEqualTo(d("2026-05-31"));
        // And a later quarter crossing the year end.
        LocalDate[] dec = PeriodCalendar.boundsFor(d("2026-03-01"), "QUARTERLY", d("2027-01-10")).orElseThrow();
        assertThat(dec[0]).isEqualTo(d("2026-12-01"));
        assertThat(dec[1]).isEqualTo(d("2027-02-28"));
    }

    @Test
    void aDayBeforeTheFirstPeriodHasNoPeriod() {
        assertThat(PeriodCalendar.boundsFor(d("2026-01-01"), "MONTHLY", d("2025-12-31"))).isEmpty();
    }

    @Test
    void firstReturnWithEinvoiceDataIsDueOnTheDateTheSpecStates() {
        assertThat(PeriodCalendar.dueDate(d("2027-01-31"), filing(false))).isEqualTo(d("2027-02-28"));
    }

    @Test
    void dueDateIsTheTwentyEighthDayAfterPeriodEndAcrossMonthLengths() {
        assertThat(PeriodCalendar.dueDate(d("2027-03-31"), filing(false))).isEqualTo(d("2027-04-28"));
        assertThat(PeriodCalendar.dueDate(d("2027-02-28"), filing(false))).isEqualTo(d("2027-03-28"));
        assertThat(PeriodCalendar.dueDate(d("2028-02-29"), filing(false))).isEqualTo(d("2028-03-28"));
    }

    @Test
    void weekendRolloverStaysOffUnlessThePackTurnsItOn() {
        // 28 Feb 2027 is a Sunday.
        assertThat(d("2027-02-28").getDayOfWeek()).isEqualTo(DayOfWeek.SUNDAY);
        assertThat(PeriodCalendar.dueDate(d("2027-01-31"), filing(false))).isEqualTo(d("2027-02-28"));
        assertThat(PeriodCalendar.dueDate(d("2027-01-31"), filing(true))).isEqualTo(d("2027-03-01"));
    }
}
