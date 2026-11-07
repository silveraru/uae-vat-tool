package ae.vattool.corrections;

import static org.assertj.core.api.Assertions.assertThat;

import ae.vattool.rules.RulePackDefinition;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class RefundRulesTest {

    private static final RulePackDefinition.Refund RULE = new RulePackDefinition.Refund("VAT311", 5, LocalDate.parse("2027-01-01"));
    private static final LocalDate PERIOD_END = LocalDate.parse("2020-01-31");

    @Test
    void aClaimWithinFiveYearsOfThePeriodEndIsAllowed() {
        RefundRules.Limit limit = RefundRules.check(RULE, PERIOD_END, LocalDate.parse("2025-01-31"));

        assertThat(limit.limitOn()).isEqualTo(LocalDate.parse("2025-01-31"));
        assertThat(limit.allowed()).isTrue();
        assertThat(limit.transitional()).isFalse();
    }

    @Test
    void aClaimPastTheLimitIsAllowedOnlyBeforeTheTransitionalWindowCloses() {
        RefundRules.Limit inWindow = RefundRules.check(RULE, PERIOD_END, LocalDate.parse("2026-12-31"));
        RefundRules.Limit closed = RefundRules.check(RULE, PERIOD_END, LocalDate.parse("2027-01-01"));

        assertThat(inWindow.allowed()).isTrue();
        assertThat(inWindow.transitional()).isTrue();
        assertThat(closed.allowed()).isFalse();
    }
}
