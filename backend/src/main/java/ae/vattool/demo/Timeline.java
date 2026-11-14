package ae.vattool.demo;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;

/**
 * Dates relative to the day the demo is built, so its history always ends last month: twelve filed
 * months behind, and last month's return being worked on now, due on the 28th of this month.
 */
final class Timeline {

    final LocalDate today;
    final YearMonth current;

    Timeline(LocalDate today) {
        this.today = today;
        this.current = YearMonth.from(today);
    }

    static Timeline now() {
        return new Timeline(LocalDate.now(ZoneId.of("Asia/Dubai")));
    }

    /** Offset 0 is this month, -1 is the return being closed now, -12 is a year back. */
    YearMonth month(int offset) {
        return current.plusMonths(offset);
    }

    LocalDate day(int offset, int day) {
        YearMonth m = month(offset);
        return m.atDay(Math.min(day, m.lengthOfMonth()));
    }

    /** Nothing in the demo happens today or later. */
    LocalDate past(LocalDate d) {
        return d.isBefore(today) ? d : today.minusDays(1);
    }
}
