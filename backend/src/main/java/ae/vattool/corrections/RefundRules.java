package ae.vattool.corrections;

import ae.vattool.rules.RulePackDefinition;
import java.time.LocalDate;

/**
 * The refund time limit (COR-04). Excess recoverable tax can be carried for a limited number of years
 * from the end of the period it arose in. A transitional window lets a claim whose limit has passed,
 * or passes within it, still be made before the window closes.
 */
public final class RefundRules {

    public record Limit(LocalDate limitOn, boolean allowed, boolean transitional) {}

    private RefundRules() {}

    public static Limit check(RulePackDefinition.Refund rule, LocalDate periodEnd, LocalDate today) {
        LocalDate limit = periodEnd.plusYears(rule.timeLimitYears());
        if (!today.isAfter(limit)) {
            return new Limit(limit, true, false);
        }
        boolean window = rule.transitionalDeadline() != null && today.isBefore(rule.transitionalDeadline());
        return new Limit(limit, window, window);
    }
}
