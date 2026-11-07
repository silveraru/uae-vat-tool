package ae.vattool.rules;

import ae.vattool.platform.web.DomainException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.dataformat.yaml.YAMLMapper;

/** Parses and validates a pack, so a bad pack is refused on upload instead of failing during a return. */
public final class RulePackParser {

    private static final YAMLMapper YAML = YAMLMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private static final Set<String> TREATMENTS = Set.of("STANDARD", "ZERO_RATED", "EXEMPT", "OUT_OF_SCOPE",
            "REVERSE_CHARGE", "IMPORT", "IMPORT_ADJUSTMENT", "DRC", "TOURIST_REFUND");

    private RulePackParser() {}

    public static RulePackDefinition parse(String yaml) {
        RulePackDefinition pack;
        try {
            pack = YAML.readValue(yaml, RulePackDefinition.class);
        } catch (JacksonException e) {
            throw DomainException.invalid("RULE_PACK_INVALID", "Rule pack could not be read: " + e.getOriginalMessage());
        }
        List<String> problems = validate(pack);
        if (!problems.isEmpty()) {
            throw DomainException.invalid("RULE_PACK_INVALID", "Rule pack is not valid: " + String.join("; ", problems));
        }
        return pack;
    }

    static List<String> validate(RulePackDefinition pack) {
        List<String> problems = new ArrayList<>();
        if (pack.jurisdiction() == null || pack.version() == null || pack.effectiveFrom() == null) {
            problems.add("jurisdiction, version and effectiveFrom are required");
        }
        if (pack.filing() == null) {
            problems.add("filing section is required");
        }
        if (pack.rates() == null || pack.rates().standardPct() == null) {
            problems.add("rates.standardPct is required");
        }
        if (pack.reconciliation() == null) {
            problems.add("reconciliation section is required");
        }
        if (pack.form() == null || pack.form().boxes() == null || pack.form().boxes().isEmpty()) {
            problems.add("form with boxes is required");
            return problems;
        }
        Set<String> seen = new HashSet<>();
        for (RulePackDefinition.Box box : pack.form().boxes()) {
            if (!seen.add(box.code())) {
                problems.add("box " + box.code() + " is defined twice");
            }
            boolean computed = box.sumOf() != null || box.addOf() != null;
            if (box.filter() != null && computed) {
                problems.add("box " + box.code() + " has both a filter and a formula");
            }
            if (box.filter() == null && !computed) {
                problems.add("box " + box.code() + " has neither a filter nor a formula");
            }
            for (List<String> refs : new List[] {box.sumOf(), box.addOf(), box.subtractOf()}) {
                if (refs != null) {
                    for (String ref : refs) {
                        // A reference is "8" or "8.vat". Formulas may only use boxes defined above
                        // them, which also rules out cycles.
                        String code = ref.contains(".") ? ref.substring(0, ref.indexOf('.')) : ref;
                        if (!seen.contains(code) || code.equals(box.code())) {
                            problems.add("box " + box.code() + " refers to " + ref + ", which is not defined before it");
                        }
                    }
                }
            }
            if (box.filter() != null && box.filter().treatments() != null) {
                box.filter().treatments().stream().filter(t -> !TREATMENTS.contains(t))
                        .forEach(t -> problems.add("box " + box.code() + " uses unknown treatment " + t));
            }
        }
        if (pack.classification() != null && pack.classification().rules() != null) {
            for (RulePackDefinition.DecisionRule rule : pack.classification().rules()) {
                if (rule.then() != null && rule.then().treatment() != null
                        && !TREATMENTS.contains(rule.then().treatment())) {
                    problems.add("rule " + rule.id() + " uses unknown treatment " + rule.then().treatment());
                }
            }
        }
        if (pack.penalties() != null) {
            for (RulePackDefinition.PenaltySchedule p : pack.penalties()) {
                if (p.effectiveFrom() == null || p.source() == null || p.latePaymentAnnualPct() == null || p.disclosureMonthlyPct() == null) {
                    problems.add("each penalty schedule needs effectiveFrom, source, latePaymentAnnualPct and disclosureMonthlyPct");
                }
            }
        }
        if (pack.apportionment() != null && (pack.apportionment().methods() == null || pack.apportionment().methods().stream()
                .anyMatch(m -> !Set.of("INPUT_BASED", "OUTPUT_BASED").contains(m.method()) || m.fromTaxYearStarting() == null))) {
            problems.add("apportionment methods must be INPUT_BASED or OUTPUT_BASED, each with fromTaxYearStarting");
        }
        if (pack.suggestions() != null && pack.suggestions().keywords() != null) {
            for (RulePackDefinition.SuggestionKeyword k : pack.suggestions().keywords()) {
                if (!TREATMENTS.contains(k.treatment())) {
                    problems.add("suggestion keyword " + k.pattern() + " uses unknown treatment " + k.treatment());
                }
                try {
                    java.util.regex.Pattern.compile(k.pattern());
                } catch (java.util.regex.PatternSyntaxException e) {
                    problems.add("suggestion keyword " + k.pattern() + " is not a valid pattern");
                }
            }
        }
        return problems;
    }
}
