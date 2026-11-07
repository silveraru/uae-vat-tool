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
        Reconciliation reconciliation,
        Form form,
        List<PenaltySchedule> penalties,
        Disclosure disclosure,
        Refund refund,
        Apportionment apportionment,
        CapitalAssets capitalAssets,
        BadDebt badDebt,
        MarginScheme marginScheme,
        ZeroRating zeroRating,
        Registration registration,
        Analytics analytics,
        Suggestions suggestions,
        Faf faf) {

    /** Sections added after 1.0 are absent from older packs, so a flow that needs one asks for it here. */
    public static <T> T require(T section, String name) {
        if (section == null) {
            throw ae.vattool.platform.web.DomainException.conflict("RULE_PACK_SECTION_MISSING",
                    "The rule pack in force has no " + name + " section. Install rule pack AE-VAT 1.1 or later.");
        }
        return section;
    }

    public record PenaltySchedule(LocalDate effectiveFrom, String source, BigDecimal lateFilingFirstAed, BigDecimal lateFilingRepeatAed,
                                  int lateFilingRepeatWindowMonths, BigDecimal latePaymentAnnualPct, BigDecimal incorrectReturnAed,
                                  BigDecimal disclosureMonthlyPct, BigDecimal afterAuditFixedPct, BigDecimal afterAuditMonthlyPct,
                                  BigDecimal capPctOfTax) {}

    public record Disclosure(String form, int deadlineBusinessDays) {}

    public record Refund(String form, int timeLimitYears, LocalDate transitionalDeadline) {}

    public record Apportionment(List<ApportionmentMethod> methods, boolean roundToWholePct, BigDecimal furtherAdjustmentThresholdAed, String adjustmentBox) {
        /** The method in force for a tax year, by the day that year starts. */
        public String methodFor(LocalDate taxYearStart) {
            return methods.stream().filter(m -> !m.fromTaxYearStarting().isAfter(taxYearStart))
                    .max(java.util.Comparator.comparing(ApportionmentMethod::fromTaxYearStarting))
                    .map(ApportionmentMethod::method)
                    .orElseThrow(() -> ae.vattool.platform.web.DomainException.conflict("NO_APPORTIONMENT_METHOD",
                            "The rule pack has no apportionment method for a tax year starting " + taxYearStart));
        }
    }

    public record ApportionmentMethod(String method, LocalDate fromTaxYearStarting) {}

    public record CapitalAssets(BigDecimal thresholdAed, int realEstateYears, int otherYears, String adjustmentBox) {}

    public record BadDebt(int minMonthsSinceSupply) {}

    public record MarginScheme(List<String> eligibleCategories) {}

    public record ZeroRating(int exportDays) {}

    public record Registration(BigDecimal mandatoryAed, BigDecimal voluntaryAed, int rollingMonths, BigDecimal approachingPct) {}

    public record Analytics(BigDecimal newSupplierNetAed, BigDecimal zeroRatedGrowthMultiple, BigDecimal zeroRatedMinAed, int trailingPeriods) {}

    public record Suggestions(String engineVersion, int historyMinLines, List<SuggestionKeyword> keywords) {}

    public record SuggestionKeyword(String pattern, String treatment, BigDecimal confidence, String reason) {}

    public record Faf(String version, String dateFormat, Map<String, List<String>> markers, Map<String, String> taxCodes,
                      String outputAmendmentCode, String inputAmendmentCode) {}

    /** Tolerances, and which boxes each GL control account type is compared with (check R06). */
    public record Reconciliation(
            BigDecimal perInvoiceToleranceAed,
            BigDecimal glToleranceAed,
            BigDecimal boxSwingPct,
            int fuzzyDateWindowDays,
            Map<String, List<String>> glAccountBoxes) {}

    public record Filing(
            int dueDaysAfterPeriodEnd,
            boolean rollForwardNonWorkingDays,
            List<DayOfWeek> weekendDays,
            BigDecimal correctionThresholdAed,
            int unpaidSupplierMonths,
            String unpaidSupplierBox) {}

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
