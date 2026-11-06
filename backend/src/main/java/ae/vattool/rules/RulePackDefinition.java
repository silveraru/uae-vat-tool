package ae.vattool.rules;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * A rule pack as written in YAML. Tax logic and the VAT201 form live here as data, selected by
 * date, so a regulatory change ships as a new pack rather than a code release (NFR-10).
 */
public record RulePackDefinition(
        String jurisdiction,
        String version,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        Filing filing,
        Rates rates,
        Classification classification,
        Form form) {

    public record Filing(
            int dueDaysAfterPeriodEnd,
            boolean rollForwardNonWorkingDays,
            List<DayOfWeek> weekendDays,
            BigDecimal correctionThresholdAed,
            int unpaidSupplierMonths) {}

    public record Rates(BigDecimal standardPct) {}

    public record Classification(
            List<DecisionRule> rules,
            List<PatternRule> drcPatterns,
            List<PatternRule> blockedPatterns) {}

    /** One row of the decision table. All conditions present must hold; absent ones are ignored. */
    public record DecisionRule(String id, String description, When when, Then then) {}

    public record When(
            String direction,
            String goodsOrServices,
            Boolean counterpartyInUae,
            Boolean counterpartyHasTrn,
            Boolean counterpartyDesignatedZone,
            Boolean establishmentDesignatedZone,
            List<String> mappedTreatmentIn,
            String source) {}

    public record Then(String treatment, String placeOfSupply, String flag) {}

    public record PatternRule(String id, String category, List<String> patterns, String direction) {}

    public record Form(String code, String version, List<Box> boxes) {}

    public record Box(
            String code,
            Map<String, String> label,
            String section,
            Map<String, String> entryGuide,
            Filter filter,
            String vatMeasure,
            Integer sign,
            List<String> columns,
            List<String> sumOf,
            List<String> addOf,
            List<String> subtractOf) {

        public boolean isComputed() {
            return filter == null;
        }
    }

    public record Filter(String direction, List<String> treatments, String emirate, Boolean blocked) {}
}
