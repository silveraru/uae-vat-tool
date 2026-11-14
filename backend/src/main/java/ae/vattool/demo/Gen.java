package ae.vattool.demo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Seeded randomness, so the same client and month always produce the same documents. Amounts carry
 * cents, dates avoid the UAE weekend, and VAT is worked out the way an ERP rounds it.
 */
final class Gen {

    static final BigDecimal RATE = new BigDecimal("0.05");

    private final Random random;

    Gen(String seed) {
        this.random = new Random(seed.hashCode() * 2654435761L);
    }

    static BigDecimal vat(BigDecimal net) {
        return net.multiply(RATE).setScale(2, RoundingMode.HALF_UP);
    }

    static BigDecimal aed(BigDecimal amount, BigDecimal rate) {
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    static BigDecimal money(String amount) {
        return new BigDecimal(amount).setScale(2, RoundingMode.HALF_UP);
    }

    /** A name-based UUID, so a document keeps its e-invoice id however often the demo is built. */
    static UUID uuid(String... parts) {
        return UUID.nameUUIDFromBytes(String.join("|", parts).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    BigDecimal amount(double min, double max) {
        double v = min + random.nextDouble() * (max - min);
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    /** A round figure, as rents, retainers and fees usually are. */
    BigDecimal round(double min, double max, int to) {
        double v = min + random.nextDouble() * (max - min);
        return BigDecimal.valueOf(Math.round(v / to) * (long) to).setScale(2, RoundingMode.HALF_UP);
    }

    int between(int min, int max) {
        return min + random.nextInt(max - min + 1);
    }

    boolean chance(double p) {
        return random.nextDouble() < p;
    }

    double factor(double spread) {
        return 1 + (random.nextDouble() * 2 - 1) * spread;
    }

    <T> T pick(List<T> items) {
        return items.get(random.nextInt(items.size()));
    }

    /** A working day in the month. The UAE weekend is Saturday and Sunday. */
    LocalDate day(YearMonth month) {
        return workingDay(month.atDay(between(1, month.lengthOfMonth())));
    }

    LocalDate dayBetween(YearMonth month, int from, int to) {
        return workingDay(month.atDay(Math.min(between(from, to), month.lengthOfMonth())));
    }

    static LocalDate workingDay(LocalDate d) {
        LocalDate x = d;
        while (x.getDayOfWeek() == DayOfWeek.SATURDAY || x.getDayOfWeek() == DayOfWeek.SUNDAY) {
            x = x.minusDays(1);
        }
        return x.getMonthValue() == d.getMonthValue() ? x : nextWorkingDay(d);
    }

    /** The days a shop trades: every day but Sunday. */
    static List<LocalDate> tradingDays(YearMonth month) {
        List<LocalDate> out = new java.util.ArrayList<>();
        for (int d = 1; d <= month.lengthOfMonth(); d++) {
            LocalDate date = month.atDay(d);
            if (date.getDayOfWeek() != DayOfWeek.SUNDAY) {
                out.add(date);
            }
        }
        return out;
    }

    static LocalDate nextWorkingDay(LocalDate d) {
        LocalDate x = d;
        while (x.getDayOfWeek() == DayOfWeek.SATURDAY || x.getDayOfWeek() == DayOfWeek.SUNDAY) {
            x = x.plusDays(1);
        }
        return x;
    }

    String digits(int count) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < count; i++) {
            b.append(random.nextInt(10));
        }
        return b.toString();
    }
}
