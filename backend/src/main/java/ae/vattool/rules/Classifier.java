package ae.vattool.rules;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Decides a line's VAT treatment from the client's tax code mapping and the rule pack (TXC-01 to
 * 04). This is a pure function of its inputs. The client's mapping always stands: where the
 * rules disagree the line is flagged for a person, because nothing here should change tax without
 * someone confirming it.
 */
public final class Classifier {

    public static final String UNCLASSIFIED = "UNCLASSIFIED";

    public static final String FLAG_CONFLICT = "CLASSIFICATION_CONFLICT";
    public static final String FLAG_DRC_POSSIBLE = "DRC_POSSIBLE";
    public static final String FLAG_DRC_SELLER = "DRC_SELLER_BOX_UNCONFIRMED";
    public static final String FLAG_BLOCKED_DESCRIPTION = "BLOCKED_BY_DESCRIPTION";
    public static final String FLAG_VAT_RATE = "VAT_RATE_MISMATCH";

    private static final BigDecimal RATE_TOLERANCE_AED = BigDecimal.ONE;
    private static final Set<String> SELF_ASSESSED = Set.of("REVERSE_CHARGE", "DRC");
    private static final Set<String> NO_VAT_EXPECTED = Set.of("ZERO_RATED", "EXEMPT", "OUT_OF_SCOPE");
    private static final Map<String, Pattern> PATTERNS = new ConcurrentHashMap<>();

    public record Mapped(String treatment, String blockedCategory, String drcCategory) {
        public static final Mapped NONE = new Mapped(null, null, null);
    }

    public record Facts(String direction, String source, String goodsOrServices, String counterpartyCountry,
                        boolean counterpartyHasTrn, boolean counterpartyDesignatedZone,
                        boolean establishmentDesignatedZone, String description, BigDecimal netAed,
                        BigDecimal vatAed, Mapped mapped) {}

    public record Outcome(String treatment, String blockedCategory, String drcCategory, String placeOfSupply,
                          List<String> ruleIds, String classifiedBy, BigDecimal confidence, List<String> flags,
                          boolean memo) {}

    private Classifier() {}

    public static Outcome classify(RulePackDefinition pack, Facts f) {
        List<String> ruleIds = new ArrayList<>();
        List<String> flags = new ArrayList<>();
        String ruleTreatment = null;
        String placeOfSupply = null;

        RulePackDefinition.Classification c = pack.classification();
        if (c != null && c.rules() != null) {
            for (RulePackDefinition.DecisionRule rule : c.rules()) {
                if (!matches(rule.when(), f)) {
                    continue;
                }
                RulePackDefinition.Then then = rule.then();
                boolean used = false;
                if (then.treatment() != null && ruleTreatment == null) {
                    ruleTreatment = then.treatment();
                    used = true;
                }
                if (then.placeOfSupply() != null && placeOfSupply == null) {
                    placeOfSupply = then.placeOfSupply();
                    used = true;
                }
                if (then.flag() != null) {
                    flags.add(then.flag());
                    used = true;
                }
                if (used) {
                    ruleIds.add(rule.id());
                }
            }
        }

        Mapped mapped = f.mapped() == null ? Mapped.NONE : f.mapped();
        String mappedTreatment = mapped.treatment();
        if (mappedTreatment == null && mapped.drcCategory() != null) {
            mappedTreatment = "DRC";
        }

        String treatment;
        String classifiedBy;
        BigDecimal confidence;
        if (mappedTreatment != null) {
            treatment = mappedTreatment;
            classifiedBy = "MAPPING";
            confidence = BigDecimal.ONE;
            if (ruleTreatment != null && !ruleTreatment.equals(mappedTreatment)) {
                flags.add(FLAG_CONFLICT);
            }
        } else if (ruleTreatment != null) {
            treatment = ruleTreatment;
            classifiedBy = "RULE";
            confidence = new BigDecimal("0.800");
        } else {
            treatment = UNCLASSIFIED;
            classifiedBy = "NONE";
            confidence = BigDecimal.ZERO;
        }

        String drcCategory = mapped.drcCategory();
        if (c != null && !"DRC".equals(treatment)) {
            String detected = firstMatch(c.drcPatterns(), f.description(), f.direction(), ruleIds);
            if (detected != null) {
                drcCategory = detected;
                flags.add(FLAG_DRC_POSSIBLE);
            }
        }
        if ("OUT".equals(f.direction()) && "DRC".equals(treatment)) {
            flags.add(FLAG_DRC_SELLER);
        }

        String blocked = null;
        if ("IN".equals(f.direction())) {
            blocked = mapped.blockedCategory();
            if (blocked == null && c != null) {
                blocked = firstMatch(c.blockedPatterns(), f.description(), f.direction(), ruleIds);
                if (blocked != null) {
                    flags.add(FLAG_BLOCKED_DESCRIPTION);
                }
            }
        }

        // Customs is the source for import VAT. An import in AP is kept as evidence only, or the
        // same VAT would reach boxes 6 and 10 twice.
        boolean memo = !"CUSTOMS".equals(f.source())
                && ("IMPORT".equals(treatment) || "IMPORT_ADJUSTMENT".equals(treatment));

        if (!memo && rateLooksWrong(pack, treatment, f)) {
            flags.add(FLAG_VAT_RATE);
        }

        return new Outcome(treatment, blocked, drcCategory, placeOfSupply == null ? null : placeOfSupply,
                List.copyOf(ruleIds), classifiedBy, confidence, List.copyOf(flags), memo);
    }

    /**
     * VAT the recipient must account for. Reverse charge lines usually arrive with no VAT, because
     * the supplier did not charge any, so it is calculated at the standard rate.
     */
    public static BigDecimal vatToAccount(RulePackDefinition pack, String direction, String treatment,
                                          BigDecimal netAed, BigDecimal vatAed) {
        if ("IN".equals(direction) && SELF_ASSESSED.contains(treatment) && vatAed.signum() == 0) {
            return percent(netAed, pack.rates().standardPct());
        }
        return vatAed;
    }

    /** Input VAT the entity can claim on a line. Output lines and blocked lines recover nothing. */
    public static BigDecimal recoverable(String direction, String treatment, String blockedCategory, boolean memo,
                                         BigDecimal vatAed, BigDecimal recoveryPct) {
        if (!"IN".equals(direction) || memo || blockedCategory != null || UNCLASSIFIED.equals(treatment)
                || NO_VAT_EXPECTED.contains(treatment)) {
            return BigDecimal.ZERO.setScale(2);
        }
        return percent(vatAed, recoveryPct);
    }

    static BigDecimal percent(BigDecimal amount, BigDecimal pct) {
        return amount.multiply(pct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private static boolean rateLooksWrong(RulePackDefinition pack, String treatment, Facts f) {
        if (f.netAed() == null || f.vatAed() == null) {
            return false;
        }
        if ("STANDARD".equals(treatment)) {
            BigDecimal expected = percent(f.netAed(), pack.rates().standardPct());
            return expected.subtract(f.vatAed()).abs().compareTo(RATE_TOLERANCE_AED) > 0;
        }
        return NO_VAT_EXPECTED.contains(treatment) && f.vatAed().signum() != 0;
    }

    private static boolean matches(RulePackDefinition.When w, Facts f) {
        if (w == null) {
            return false;
        }
        if (w.direction() != null && !w.direction().equals(f.direction())) {
            return false;
        }
        if (w.source() != null && !w.source().equals(f.source())) {
            return false;
        }
        if (w.goodsOrServices() != null && !w.goodsOrServices().equals(f.goodsOrServices())) {
            return false;
        }
        if (w.counterpartyInUae() != null) {
            Boolean inUae = counterpartyInUae(f);
            // An unknown location satisfies neither side, so no rule assumes one.
            if (inUae == null || !w.counterpartyInUae().equals(inUae)) {
                return false;
            }
        }
        if (w.counterpartyHasTrn() != null && w.counterpartyHasTrn() != f.counterpartyHasTrn()) {
            return false;
        }
        if (w.counterpartyDesignatedZone() != null && w.counterpartyDesignatedZone() != f.counterpartyDesignatedZone()) {
            return false;
        }
        if (w.establishmentDesignatedZone() != null && w.establishmentDesignatedZone() != f.establishmentDesignatedZone()) {
            return false;
        }
        return w.mappedTreatmentIn() == null
                || (f.mapped() != null && w.mappedTreatmentIn().contains(f.mapped().treatment()));
    }

    private static Boolean counterpartyInUae(Facts f) {
        if (f.counterpartyCountry() != null && !f.counterpartyCountry().isBlank()) {
            return "AE".equalsIgnoreCase(f.counterpartyCountry().trim());
        }
        return f.counterpartyHasTrn() ? Boolean.TRUE : null;
    }

    private static String firstMatch(List<RulePackDefinition.PatternRule> rules, String text, String direction,
                                     List<String> ruleIds) {
        if (rules == null || text == null || text.isBlank()) {
            return null;
        }
        for (RulePackDefinition.PatternRule rule : rules) {
            if (rule.direction() != null && !rule.direction().equals(direction)) {
                continue;
            }
            for (String p : rule.patterns()) {
                Pattern compiled = PATTERNS.computeIfAbsent(p, Classifier::compile);
                if (compiled.matcher(text).find()) {
                    ruleIds.add(rule.id());
                    return rule.category();
                }
            }
        }
        return null;
    }

    private static Pattern compile(String p) {
        return Pattern.compile(p, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
}
