package ae.vattool.rules;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Rules for one client, laid over the firm's rule pack (NFR-10). They can add classification rules and
 * review patterns, switch off a firm rule that misfires for this client, and move review thresholds.
 * They cannot change the law: rates, the form, deadlines, penalties and the schemes come only from the
 * pack, so nothing here has a field for them.
 *
 * Client rows are tried before the firm's. A decision rule still never overrides the client's tax code
 * mapping: where they disagree the line is flagged, as with any rule.
 */
public record ClientRules(
        List<RulePackDefinition.DecisionRule> classificationRules,
        List<RulePackDefinition.PatternRule> drcPatterns,
        List<RulePackDefinition.PatternRule> blockedPatterns,
        List<SwitchedOff> switchedOff,
        Reconciliation reconciliation,
        Analytics analytics,
        List<RulePackDefinition.SuggestionKeyword> suggestionKeywords) {

    /** A firm rule or pattern, by id, that does not apply to this client, and why. */
    public record SwitchedOff(String id, String reason) {}

    /** Blank means the firm's figure applies. */
    public record Reconciliation(BigDecimal perInvoiceToleranceAed, BigDecimal glToleranceAed, BigDecimal boxSwingPct, Integer fuzzyDateWindowDays) {
        boolean isEmpty() {
            return perInvoiceToleranceAed == null && glToleranceAed == null && boxSwingPct == null && fuzzyDateWindowDays == null;
        }
    }

    /** Blank means the firm's figure applies. */
    public record Analytics(BigDecimal newSupplierNetAed, BigDecimal zeroRatedGrowthMultiple, BigDecimal zeroRatedMinAed, Integer trailingPeriods) {
        boolean isEmpty() {
            return newSupplierNetAed == null && zeroRatedGrowthMultiple == null && zeroRatedMinAed == null && trailingPeriods == null;
        }
    }

    public ClientRules {
        classificationRules = classificationRules == null ? List.of() : List.copyOf(classificationRules);
        drcPatterns = drcPatterns == null ? List.of() : List.copyOf(drcPatterns);
        blockedPatterns = blockedPatterns == null ? List.of() : List.copyOf(blockedPatterns);
        switchedOff = switchedOff == null ? List.of() : List.copyOf(switchedOff);
        suggestionKeywords = suggestionKeywords == null ? List.of() : List.copyOf(suggestionKeywords);
        if (reconciliation != null && reconciliation.isEmpty()) {
            reconciliation = null;
        }
        if (analytics != null && analytics.isEmpty()) {
            analytics = null;
        }
    }

    public static final ClientRules NONE = new ClientRules(null, null, null, null, null, null, null);

    @JsonIgnore
    public boolean isEmpty() {
        return classificationRules.isEmpty() && drcPatterns.isEmpty() && blockedPatterns.isEmpty() && switchedOff.isEmpty()
                && reconciliation == null && analytics == null && suggestionKeywords.isEmpty();
    }

    /** How many separate rules and settings this holds, for summaries. */
    public int size() {
        return classificationRules.size() + drcPatterns.size() + blockedPatterns.size() + switchedOff.size() + suggestionKeywords.size()
                + (reconciliation == null ? 0 : 1) + (analytics == null ? 0 : 1);
    }

    /** Checked against the pack in force when the rules start, so a switched-off id must exist there. */
    public List<String> validate(RulePackDefinition pack) {
        List<String> problems = new ArrayList<>(RulePackParser.validateClassification(classificationRules, drcPatterns, blockedPatterns, "client rule"));
        Set<String> firmIds = firmIds(pack);
        Stream.of(classificationRules.stream().map(RulePackDefinition.DecisionRule::id),
                        drcPatterns.stream().map(RulePackDefinition.PatternRule::id),
                        blockedPatterns.stream().map(RulePackDefinition.PatternRule::id))
                .flatMap(s -> s)
                .filter(id -> id != null && firmIds.contains(id))
                .forEach(id -> problems.add("client rule " + id + " has the same id as a rule in the firm's pack: give it its own"));
        blockedPatterns.stream().filter(p -> p.direction() != null && !"IN".equals(p.direction()))
                .forEach(p -> problems.add("blocked pattern " + p.id() + " can only apply to purchases (direction IN)"));
        Set<String> off = new HashSet<>();
        for (SwitchedOff s : switchedOff) {
            if (s.id() == null || !firmIds.contains(s.id())) {
                problems.add("there is no firm rule or pattern " + s.id() + " to switch off");
            } else if (!off.add(s.id())) {
                problems.add("firm rule " + s.id() + " is switched off twice");
            }
            if (s.reason() == null || s.reason().isBlank()) {
                problems.add("say why firm rule " + s.id() + " does not apply to this client");
            }
        }
        if (reconciliation != null) {
            nonNegative(problems, "per-invoice tolerance", reconciliation.perInvoiceToleranceAed());
            nonNegative(problems, "GL tolerance", reconciliation.glToleranceAed());
            if (reconciliation.boxSwingPct() != null && reconciliation.boxSwingPct().signum() <= 0) {
                problems.add("the box swing must be above 0%");
            }
            if (reconciliation.fuzzyDateWindowDays() != null && (reconciliation.fuzzyDateWindowDays() < 0 || reconciliation.fuzzyDateWindowDays() > 31)) {
                problems.add("the date window for matching must be 0 to 31 days");
            }
        }
        if (analytics != null) {
            if (pack.analytics() == null) {
                problems.add("the firm's pack has no analytics section to adjust");
            }
            nonNegative(problems, "new supplier threshold", analytics.newSupplierNetAed());
            nonNegative(problems, "zero-rated minimum", analytics.zeroRatedMinAed());
            if (analytics.zeroRatedGrowthMultiple() != null && analytics.zeroRatedGrowthMultiple().compareTo(BigDecimal.ONE) <= 0) {
                problems.add("the zero-rated growth multiple must be above 1");
            }
            if (analytics.trailingPeriods() != null && (analytics.trailingPeriods() < 1 || analytics.trailingPeriods() > 12)) {
                problems.add("the trailing periods must be 1 to 12");
            }
        }
        if (!suggestionKeywords.isEmpty() && pack.suggestions() == null) {
            problems.add("the firm's pack has no suggestions section to add keywords to");
        }
        for (RulePackDefinition.SuggestionKeyword k : suggestionKeywords) {
            if (k.pattern() == null || !RulePackParser.compiles(k.pattern())) {
                problems.add("suggestion keyword " + k.pattern() + " is not a valid regular expression");
            }
            if (k.treatment() == null || !RulePackParser.TREATMENTS.contains(k.treatment())) {
                problems.add("suggestion keyword " + k.pattern() + " uses unknown treatment " + k.treatment());
            }
            if (k.confidence() == null || k.confidence().signum() <= 0 || k.confidence().compareTo(BigDecimal.ONE) > 0) {
                problems.add("suggestion keyword " + k.pattern() + " needs a confidence above 0 and at most 1");
            }
            if (k.reason() == null || k.reason().isBlank()) {
                problems.add("suggestion keyword " + k.pattern() + " needs a reason a person can read");
            }
        }
        return problems;
    }

    /** The pack as it applies to this client. Sections client rules cannot touch are passed through unchanged. */
    public RulePackDefinition applyTo(RulePackDefinition pack) {
        if (isEmpty()) {
            return pack;
        }
        Set<String> off = new HashSet<>();
        switchedOff.forEach(s -> off.add(s.id()));
        RulePackDefinition.Classification c = pack.classification();
        RulePackDefinition.Classification classification = new RulePackDefinition.Classification(
                concat(classificationRules, c == null ? null : c.rules(), r -> !off.contains(r.id())),
                concat(drcPatterns, c == null ? null : c.drcPatterns(), p -> !off.contains(p.id())),
                concat(blockedPatterns, c == null ? null : c.blockedPatterns(), p -> !off.contains(p.id())));

        RulePackDefinition.Reconciliation r = pack.reconciliation();
        RulePackDefinition.Reconciliation recon = reconciliation == null ? r : new RulePackDefinition.Reconciliation(
                or(reconciliation.perInvoiceToleranceAed(), r.perInvoiceToleranceAed()),
                or(reconciliation.glToleranceAed(), r.glToleranceAed()),
                or(reconciliation.boxSwingPct(), r.boxSwingPct()),
                reconciliation.fuzzyDateWindowDays() == null ? r.fuzzyDateWindowDays() : reconciliation.fuzzyDateWindowDays(),
                r.glAccountBoxes());

        RulePackDefinition.Analytics a = pack.analytics();
        RulePackDefinition.Analytics analyticsMerged = analytics == null || a == null ? a : new RulePackDefinition.Analytics(
                or(analytics.newSupplierNetAed(), a.newSupplierNetAed()),
                or(analytics.zeroRatedGrowthMultiple(), a.zeroRatedGrowthMultiple()),
                or(analytics.zeroRatedMinAed(), a.zeroRatedMinAed()),
                analytics.trailingPeriods() == null ? a.trailingPeriods() : analytics.trailingPeriods());

        RulePackDefinition.Suggestions s = pack.suggestions();
        RulePackDefinition.Suggestions suggestions = s == null || suggestionKeywords.isEmpty() ? s
                : new RulePackDefinition.Suggestions(s.engineVersion(), s.historyMinLines(), concat(suggestionKeywords, s.keywords(), k -> true));

        return new RulePackDefinition(pack.jurisdiction(), pack.version(), pack.effectiveFrom(), pack.effectiveTo(), pack.filing(), pack.rates(),
                classification, recon, pack.form(), pack.penalties(), pack.disclosure(), pack.refund(), pack.apportionment(),
                pack.capitalAssets(), pack.badDebt(), pack.marginScheme(), pack.zeroRating(), pack.registration(), analyticsMerged,
                suggestions, pack.faf());
    }

    /** Every id in the firm's classification section, which client rules may switch off. */
    static Set<String> firmIds(RulePackDefinition pack) {
        Set<String> ids = new HashSet<>();
        RulePackDefinition.Classification c = pack.classification();
        if (c != null) {
            if (c.rules() != null) {
                c.rules().forEach(r -> ids.add(r.id()));
            }
            if (c.drcPatterns() != null) {
                c.drcPatterns().forEach(p -> ids.add(p.id()));
            }
            if (c.blockedPatterns() != null) {
                c.blockedPatterns().forEach(p -> ids.add(p.id()));
            }
        }
        return ids;
    }

    private static <T> List<T> concat(List<T> first, List<T> then, Predicate<T> keep) {
        List<T> out = new ArrayList<>(first);
        if (then != null) {
            then.stream().filter(keep).forEach(out::add);
        }
        return List.copyOf(out);
    }

    private static BigDecimal or(BigDecimal value, BigDecimal fallback) {
        return value == null ? fallback : value;
    }

    private static void nonNegative(List<String> problems, String what, BigDecimal value) {
        if (value != null && value.signum() < 0) {
            problems.add("the " + what + " cannot be negative");
        }
    }
}
