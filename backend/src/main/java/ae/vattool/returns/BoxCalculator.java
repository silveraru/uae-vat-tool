package ae.vattool.returns;

import ae.vattool.rules.RulePackDefinition;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluates the VAT201 form metadata over ledger aggregates (RET-01). There is no box logic in
 * code: a change to the form or its formulas is a change to the rule pack. A pure function, so
 * every path can be unit tested without a database.
 */
public final class BoxCalculator {

    /** Ledger lines summed by every field a box filter can use. */
    public record Aggregate(String direction, String treatment, String emirate, boolean blocked, String lineKind,
                            String adjBox, java.util.UUID memberEntityId, BigDecimal net, BigDecimal vat,
                            BigDecimal recoverable, long lines) {}

    public record BoxValue(BigDecimal amount, BigDecimal vat, BigDecimal adjustment) {
        static final BoxValue ZERO = new BoxValue(BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2));

        BoxValue plus(BoxValue o) {
            return new BoxValue(amount.add(o.amount), vat.add(o.vat), adjustment.add(o.adjustment));
        }

        BigDecimal column(String name) {
            return switch (name) {
                case "amount" -> amount;
                case "vat" -> vat;
                case "adjustment" -> adjustment;
                default -> throw new IllegalArgumentException("Unknown column " + name);
            };
        }
    }

    private BoxCalculator() {}

    public static Map<String, BoxValue> calculate(RulePackDefinition.Form form, List<Aggregate> rows) {
        Map<String, BoxValue> out = new LinkedHashMap<>();
        for (RulePackDefinition.Box box : form.boxes()) {
            BoxValue value;
            if (box.filter() != null) {
                value = fromLedger(box, rows);
            } else if (box.sumOf() != null) {
                value = BoxValue.ZERO;
                for (String ref : box.sumOf()) {
                    value = value.plus(out.get(ref));
                }
            } else {
                BigDecimal total = BigDecimal.ZERO.setScale(2);
                for (String ref : box.addOf()) {
                    total = total.add(read(out, ref));
                }
                if (box.subtractOf() != null) {
                    for (String ref : box.subtractOf()) {
                        total = total.subtract(read(out, ref));
                    }
                }
                value = new BoxValue(BigDecimal.ZERO.setScale(2), total, BigDecimal.ZERO.setScale(2));
            }
            out.put(box.code(), value);
        }
        return out;
    }

    /**
     * Applies VAT changes to ledger boxes and works out the calculated boxes again. Used by the
     * correction flow when an error is not represented by changed lines.
     */
    public static Map<String, BoxValue> withVatChanges(RulePackDefinition.Form form, Map<String, BoxValue> base,
                                                       Map<String, BigDecimal> vatChanges) {
        Map<String, BoxValue> out = new LinkedHashMap<>();
        for (RulePackDefinition.Box box : form.boxes()) {
            BoxValue value;
            if (box.filter() != null) {
                BoxValue b = base.getOrDefault(box.code(), BoxValue.ZERO);
                BigDecimal change = vatChanges.getOrDefault(box.code(), BigDecimal.ZERO);
                value = new BoxValue(b.amount(), b.vat().add(change), b.adjustment());
            } else if (box.sumOf() != null) {
                value = BoxValue.ZERO;
                for (String ref : box.sumOf()) {
                    value = value.plus(out.get(ref));
                }
            } else {
                BigDecimal total = BigDecimal.ZERO.setScale(2);
                for (String ref : box.addOf()) {
                    total = total.add(read(out, ref));
                }
                if (box.subtractOf() != null) {
                    for (String ref : box.subtractOf()) {
                        total = total.subtract(read(out, ref));
                    }
                }
                value = new BoxValue(BigDecimal.ZERO.setScale(2), total, BigDecimal.ZERO.setScale(2));
            }
            out.put(box.code(), value);
        }
        return out;
    }

    private static BigDecimal read(Map<String, BoxValue> values, String ref) {
        int dot = ref.indexOf('.');
        return values.get(ref.substring(0, dot)).column(ref.substring(dot + 1));
    }

    private static BoxValue fromLedger(RulePackDefinition.Box box, List<Aggregate> rows) {
        BigDecimal amount = BigDecimal.ZERO.setScale(2);
        BigDecimal vat = BigDecimal.ZERO.setScale(2);
        BigDecimal adjustment = BigDecimal.ZERO.setScale(2);
        boolean recoverable = "recoverable".equals(box.vatMeasure());
        BigDecimal sign = box.sign() != null && box.sign() < 0 ? BigDecimal.ONE.negate() : BigDecimal.ONE;
        for (Aggregate row : rows) {
            if ("ADJUSTMENT".equals(row.lineKind())) {
                if (box.code().equals(row.adjBox())) {
                    adjustment = adjustment.add(row.vat());
                }
                continue;
            }
            if (matches(box.filter(), row)) {
                amount = amount.add(row.net().multiply(sign));
                vat = vat.add((recoverable ? row.recoverable() : row.vat()).multiply(sign));
            }
        }
        return new BoxValue(amount, vat, adjustment);
    }

    /** Also used by drill-down, so a box and its detail lines can never disagree. */
    static boolean matches(RulePackDefinition.Filter f, Aggregate row) {
        return (f.direction() == null || f.direction().equals(row.direction()))
                && (f.treatments() == null || f.treatments().contains(row.treatment()))
                && (f.emirate() == null || f.emirate().equals(row.emirate()))
                && (f.blocked() == null || f.blocked() == row.blocked());
    }
}
