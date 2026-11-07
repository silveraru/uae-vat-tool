package ae.vattool.corrections;

import static org.assertj.core.api.Assertions.assertThat;

import ae.vattool.corrections.PenaltyCalculator.Estimate;
import ae.vattool.corrections.PenaltyCalculator.Facts;
import ae.vattool.corrections.PenaltyCalculator.Item;
import ae.vattool.corrections.PenaltyCalculator.Paid;
import ae.vattool.rules.RulePackDefinition.PenaltySchedule;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class PenaltyCalculatorTest {

    private static final PenaltySchedule S = new PenaltySchedule(LocalDate.parse("2026-04-14"), "Cabinet Decision No. 129 of 2025",
            bd("1000"), bd("2000"), 24, bd("14"), bd("500"), bd("1"), bd("15"), bd("1"), bd("200"));
    private static final LocalDate DUE = LocalDate.parse("2027-01-28");

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    private static Facts onTime(BigDecimal payable, List<Paid> paid) {
        return new Facts(DUE, DUE, payable, paid, false, BigDecimal.ZERO, false, null, null);
    }

    private static Item item(Estimate e, String code) {
        return e.items().stream().filter(i -> i.code().equals(code)).findFirst().orElse(null);
    }

    @Test
    void aSourcesWorkedExampleOfLatePaymentComesOut() {
        // AED 100,000 delayed by 8 months at 14% a year is about AED 9,333 in the published comparison.
        Estimate e = PenaltyCalculator.estimate(S, onTime(bd("100000.00"), List.of()), LocalDate.parse("2027-09-28"));

        assertThat(item(e, "LATE_PAYMENT").amount()).isEqualByComparingTo("9333.33");
        assertThat(item(e, "LATE_PAYMENT").months()).isEqualTo(8);
    }

    @Test
    void nothingIsDueWhenFiledAndPaidOnTime() {
        Estimate e = PenaltyCalculator.estimate(S, onTime(bd("500.00"), List.of(new Paid(DUE, bd("500.00")))), LocalDate.parse("2027-06-01"));

        assertThat(e.items()).isEmpty();
        assertThat(e.total()).isEqualByComparingTo("0");
    }

    @Test
    void aPartMonthCountsAsAWholeMonth() {
        Estimate e = PenaltyCalculator.estimate(S, onTime(bd("1200.00"), List.of()), DUE.plusDays(1));

        assertThat(item(e, "LATE_PAYMENT").months()).isEqualTo(1);
        assertThat(item(e, "LATE_PAYMENT").amount()).isEqualByComparingTo("14.00");
    }

    @Test
    void aPartPaymentReducesTheBalanceFromTheNextMonth() {
        // Paid half on 15 March. Months start on the 29th: Jan, Feb and Mar on 1200, then Apr on 600? No: Mar 29 sees the payment.
        Estimate e = PenaltyCalculator.estimate(S, onTime(bd("1200.00"), List.of(new Paid(LocalDate.parse("2027-03-15"), bd("600.00")))), LocalDate.parse("2027-04-29"));

        // Jan 29 and Feb 29 (28th) on 1200 = 28.00. Mar 29 and Apr 29 on 600 = 14.00.
        assertThat(item(e, "LATE_PAYMENT").amount()).isEqualByComparingTo("42.00");
    }

    @Test
    void filingLateCostsTheFirstAmountThenTheRepeatAmount() {
        Facts first = new Facts(DUE, DUE.plusDays(3), bd("0"), List.of(), false, bd("0"), false, null, null);
        Facts repeat = new Facts(DUE, DUE.plusDays(3), bd("0"), List.of(), true, bd("0"), false, null, null);

        assertThat(item(PenaltyCalculator.estimate(S, first, DUE.plusDays(3)), "LATE_FILING").amount()).isEqualByComparingTo("1000");
        assertThat(item(PenaltyCalculator.estimate(S, repeat, DUE.plusDays(3)), "LATE_FILING_REPEAT").amount()).isEqualByComparingTo("2000");
    }

    @Test
    void anUnfiledReturnPastItsDueDateIsLate() {
        Facts unfiled = new Facts(DUE, null, bd("0"), List.of(), false, bd("0"), false, null, null);

        assertThat(item(PenaltyCalculator.estimate(S, unfiled, DUE), "LATE_FILING")).isNull();
        assertThat(item(PenaltyCalculator.estimate(S, unfiled, DUE.plusDays(1)), "LATE_FILING")).isNotNull();
    }

    @Test
    void aSourcesWorkedExampleOfDisclosureComesOut() {
        // AED 200,000 disclosed after 18 months at 1% a month is AED 36,000 in the published comparison.
        Facts f = new Facts(DUE, DUE, bd("0"), List.of(), false, bd("200000.00"), true, DUE.plusMonths(18), null);

        Estimate e = PenaltyCalculator.estimate(S, f, DUE.plusMonths(18));

        assertThat(item(e, "VOLUNTARY_DISCLOSURE").amount()).isEqualByComparingTo("36000.00");
        assertThat(item(e, "INCORRECT_RETURN").amount()).isEqualByComparingTo("500");
    }

    @Test
    void aSourcesWorkedExampleAfterAuditNoticeComesOut() {
        // AED 500,000 over 12 months at 15% plus 1% a month is AED 135,000 in the published comparison.
        LocalDate disclosed = DUE.plusMonths(12);
        Facts f = new Facts(DUE, DUE, bd("0"), List.of(), false, bd("500000.00"), true, disclosed, disclosed.minusDays(5));

        Estimate e = PenaltyCalculator.estimate(S, f, disclosed);

        assertThat(item(e, "UNDISCLOSED_BEFORE_AUDIT").amount()).isEqualByComparingTo("135000.00");
        assertThat(item(e, "VOLUNTARY_DISCLOSURE")).isNull();
    }

    @Test
    void anAuditNoticeAfterTheDisclosureDoesNotApply() {
        LocalDate disclosed = DUE.plusMonths(2);
        Facts f = new Facts(DUE, DUE, bd("0"), List.of(), false, bd("1000.00"), true, disclosed, disclosed.plusDays(1));

        assertThat(item(PenaltyCalculator.estimate(S, f, disclosed.plusDays(30)), "VOLUNTARY_DISCLOSURE")).isNotNull();
    }

    @Test
    void anOverstatementCarriesNoPenaltyOnTheDifference() {
        Facts f = new Facts(DUE, DUE, bd("0"), List.of(), false, bd("0"), true, DUE.plusMonths(3), null);

        assertThat(PenaltyCalculator.estimate(S, f, DUE.plusMonths(3)).items()).isEmpty();
    }

    @Test
    void aCorrectionInTheNextReturnHasNoDisclosurePenalty() {
        Facts f = new Facts(DUE, DUE, bd("0"), List.of(), false, bd("9000.00"), false, null, null);

        assertThat(PenaltyCalculator.estimate(S, f, DUE.plusMonths(3)).items()).isEmpty();
    }

    @Test
    void percentagePenaltiesStopAtTheCap() {
        Facts f = new Facts(DUE, DUE, bd("0"), List.of(), false, bd("1000.00"), true, DUE.plusYears(30), null);

        Item item = item(PenaltyCalculator.estimate(S, f, DUE.plusYears(30)), "VOLUNTARY_DISCLOSURE");

        assertThat(item.amount()).isEqualByComparingTo("2000.00");
        assertThat(item.capped()).isTrue();
    }

    @Test
    void theScheduleInForceOnTheViolationDateApplies() {
        PenaltySchedule later = new PenaltySchedule(LocalDate.parse("2030-01-01"), "Later", bd("1"), bd("2"), 24, bd("1"), bd("1"), bd("1"), bd("1"), bd("1"), null);

        assertThat(PenaltyCalculator.scheduleFor(List.of(S, later), LocalDate.parse("2029-12-31"))).isSameAs(S);
        assertThat(PenaltyCalculator.scheduleFor(List.of(S, later), LocalDate.parse("2030-01-01"))).isSameAs(later);
    }

    @Test
    void withNoScheduleItSaysSoAndDoesNotGuess() {
        assertThat(PenaltyCalculator.scheduleFor(List.of(S), LocalDate.parse("2026-04-13"))).isNull();

        Estimate e = PenaltyCalculator.estimate(null, onTime(bd("100"), List.of()), DUE.plusMonths(1));

        assertThat(e.scheduleFound()).isFalse();
        assertThat(e.items()).isEmpty();
    }

    @Test
    void businessDaysSkipTheWeekend() {
        // 2027-01-01 is a Friday.
        LocalDate d = PenaltyCalculator.addBusinessDays(LocalDate.parse("2027-01-01"), 1, List.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY));
        assertThat(d).isEqualTo(LocalDate.parse("2027-01-04"));
        assertThat(PenaltyCalculator.addBusinessDays(LocalDate.parse("2027-01-01"), 20, List.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))).isEqualTo(LocalDate.parse("2027-01-29"));
    }
}
