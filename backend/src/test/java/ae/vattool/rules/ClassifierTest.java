package ae.vattool.rules;

import static org.assertj.core.api.Assertions.assertThat;

import ae.vattool.rules.Classifier.Facts;
import ae.vattool.rules.Classifier.Mapped;
import ae.vattool.rules.Classifier.Outcome;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** Runs against the bundled rule pack, so a pack edit that changes behaviour fails here. */
class ClassifierTest {

    static RulePackDefinition pack;

    @BeforeAll
    static void load() throws Exception {
        pack = RulePackParser.parse(new ClassPathResource(RulePackStore.BUNDLED_PACK).getContentAsString(StandardCharsets.UTF_8));
    }

    private static Facts facts(String direction, String gos, String country, boolean hasTrn, Mapped mapped) {
        return new Facts(direction, "ERP", gos, country, hasTrn, false, false, null, new BigDecimal("100.00"), new BigDecimal("5.00"), mapped);
    }

    private static Outcome run(Facts f) {
        return Classifier.classify(pack, f);
    }

    @Test
    void theClientsTaxCodeDecidesTheTreatment() {
        Outcome o = run(facts("OUT", "GOODS", "AE", true, new Mapped("STANDARD", null, null)));

        assertThat(o.treatment()).isEqualTo("STANDARD");
        assertThat(o.classifiedBy()).isEqualTo("MAPPING");
        assertThat(o.flags()).isEmpty();
    }

    @Test
    void aRuleThatDisagreesWithTheMappingFlagsItButDoesNotOverrideIt() {
        // An export mapped as standard-rated: the client's code stands and a person must decide.
        Outcome o = run(facts("OUT", "GOODS", "US", false, new Mapped("STANDARD", null, null)));

        assertThat(o.treatment()).isEqualTo("STANDARD");
        assertThat(o.flags()).contains(Classifier.FLAG_CONFLICT);
    }

    @Test
    void withNoMappingAnExportIsZeroRatedByTheRule() {
        Outcome o = run(new Facts("OUT", "ERP", "GOODS", "US", false, false, false, null, new BigDecimal("100.00"), BigDecimal.ZERO, Mapped.NONE));

        assertThat(o.treatment()).isEqualTo("ZERO_RATED");
        assertThat(o.classifiedBy()).isEqualTo("RULE");
        assertThat(o.confidence()).isLessThan(BigDecimal.ONE);
        assertThat(o.placeOfSupply()).isEqualTo("OUTSIDE_UAE");
        assertThat(o.ruleIds()).contains("POS-EXPORT-GOODS");
    }

    @Test
    void servicesFromAbroadAreReverseCharge() {
        Outcome o = run(new Facts("IN", "ERP", "SERVICES", "GB", false, false, false, null, new BigDecimal("100.00"), BigDecimal.ZERO, Mapped.NONE));

        assertThat(o.treatment()).isEqualTo("REVERSE_CHARGE");
        assertThat(o.placeOfSupply()).isEqualTo("UAE");
    }

    @Test
    void aDomesticLineWithNoMappingIsUnclassifiedNotGuessed() {
        Outcome o = run(facts("OUT", "GOODS", "AE", true, Mapped.NONE));

        assertThat(o.treatment()).isEqualTo(Classifier.UNCLASSIFIED);
        assertThat(o.classifiedBy()).isEqualTo("NONE");
    }

    @Test
    void anUnknownLocationSatisfiesNoLocationRule() {
        // No country and no TRN: the engine cannot tell, so it assumes neither domestic nor export.
        Outcome o = run(new Facts("OUT", "ERP", "GOODS", null, false, false, false, null, new BigDecimal("100.00"), BigDecimal.ZERO, Mapped.NONE));

        assertThat(o.treatment()).isEqualTo(Classifier.UNCLASSIFIED);
        assertThat(o.placeOfSupply()).isNull();
    }

    @Test
    void goodsBetweenDesignatedZonesAreOutOfScope() {
        Outcome o = run(new Facts("OUT", "ERP", "GOODS", "AE", true, true, true, null, new BigDecimal("100.00"), BigDecimal.ZERO, Mapped.NONE));

        assertThat(o.treatment()).isEqualTo("OUT_OF_SCOPE");
        assertThat(o.placeOfSupply()).isEqualTo("OUTSIDE_SCOPE_DESIGNATED_ZONE");
    }

    @Test
    void servicesInADesignatedZoneAreNotOutOfScope() {
        Outcome o = run(new Facts("OUT", "ERP", "SERVICES", "AE", true, true, true, null, new BigDecimal("100.00"), new BigDecimal("5.00"), new Mapped("STANDARD", null, null)));

        assertThat(o.treatment()).isEqualTo("STANDARD");
        assertThat(o.flags()).doesNotContain(Classifier.FLAG_CONFLICT);
    }

    @Test
    void goodsFromAZoneToTheMainlandAreFlagged() {
        Outcome o = run(new Facts("IN", "ERP", "GOODS", "AE", true, true, false, null, new BigDecimal("100.00"), new BigDecimal("5.00"), new Mapped("STANDARD", null, null)));

        assertThat(o.flags()).contains("DZ_GOODS_TO_MAINLAND");
    }

    @Test
    void aMappedDrcCategoryMeansDomesticReverseCharge() {
        Outcome o = run(facts("IN", "GOODS", "AE", true, new Mapped(null, null, "GOLD")));

        assertThat(o.treatment()).isEqualTo("DRC");
        assertThat(o.drcCategory()).isEqualTo("GOLD");
        assertThat(o.flags()).isEmpty();
    }

    @Test
    void aDescriptionThatLooksLikeDrcIsFlaggedButTheTreatmentIsNotChanged() {
        Outcome o = run(new Facts("IN", "ERP", "GOODS", "AE", true, false, false, "Mobile phones for resale", new BigDecimal("100.00"), new BigDecimal("5.00"), new Mapped("STANDARD", null, null)));

        assertThat(o.treatment()).isEqualTo("STANDARD");
        assertThat(o.flags()).contains(Classifier.FLAG_DRC_POSSIBLE);
        assertThat(o.drcCategory()).isEqualTo("ELECTRONIC_DEVICES");
    }

    @Test
    void aSellerOfDrcGoodsIsFlaggedBecauseTheBoxIsUnconfirmed() {
        Outcome o = run(facts("OUT", "GOODS", "AE", true, new Mapped("DRC", null, "GOLD")));

        assertThat(o.flags()).contains(Classifier.FLAG_DRC_SELLER);
    }

    @Test
    void aBlockedGlMappingBlocksWithoutAFlag() {
        Outcome o = run(facts("IN", "SERVICES", "AE", true, new Mapped("STANDARD", "ENTERTAINMENT", null)));

        assertThat(o.blockedCategory()).isEqualTo("ENTERTAINMENT");
        assertThat(o.flags()).doesNotContain(Classifier.FLAG_BLOCKED_DESCRIPTION);
    }

    @Test
    void aBlockedDescriptionBlocksAndFlagsForConfirmation() {
        Outcome o = run(new Facts("IN", "ERP", "SERVICES", "AE", true, false, false, "Client dinner at hotel", new BigDecimal("100.00"), new BigDecimal("5.00"), new Mapped("STANDARD", null, null)));

        assertThat(o.blockedCategory()).isEqualTo("ENTERTAINMENT");
        assertThat(o.flags()).contains(Classifier.FLAG_BLOCKED_DESCRIPTION);
    }

    @Test
    void outputLinesAreNeverBlocked() {
        Outcome o = run(new Facts("OUT", "ERP", "SERVICES", "AE", true, false, false, "Entertainment services sold", new BigDecimal("100.00"), new BigDecimal("5.00"), new Mapped("STANDARD", null, null)));

        assertThat(o.blockedCategory()).isNull();
    }

    @Test
    void anImportInApIsEvidenceOnlyAndACustomsImportCounts() {
        Outcome ap = run(facts("IN", "GOODS", "CN", false, new Mapped("IMPORT", null, null)));
        Outcome customs = run(new Facts("IN", "CUSTOMS", "GOODS", "CN", false, false, false, null, new BigDecimal("100.00"), new BigDecimal("5.00"), new Mapped("IMPORT", null, null)));

        assertThat(ap.memo()).isTrue();
        assertThat(customs.memo()).isFalse();
    }

    @Test
    void vatNotAtTheStandardRateIsFlaggedOutsideTheRoundingTolerance() {
        Outcome ok = run(new Facts("OUT", "ERP", "GOODS", "AE", true, false, false, null, new BigDecimal("100.00"), new BigDecimal("5.90"), new Mapped("STANDARD", null, null)));
        Outcome wrong = run(new Facts("OUT", "ERP", "GOODS", "AE", true, false, false, null, new BigDecimal("1000.00"), new BigDecimal("10.00"), new Mapped("STANDARD", null, null)));

        assertThat(ok.flags()).doesNotContain(Classifier.FLAG_VAT_RATE);
        assertThat(wrong.flags()).contains(Classifier.FLAG_VAT_RATE);
    }

    @Test
    void vatOnAZeroRatedLineIsFlagged() {
        Outcome o = run(new Facts("OUT", "ERP", "GOODS", "AE", true, false, false, null, new BigDecimal("100.00"), new BigDecimal("5.00"), new Mapped("ZERO_RATED", null, null)));

        assertThat(o.flags()).contains(Classifier.FLAG_VAT_RATE);
    }

    @Test
    void reverseChargeWithNoVatIsCalculatedAtTheStandardRate() {
        assertThat(Classifier.vatToAccount(pack, "IN", "REVERSE_CHARGE", new BigDecimal("20000.00"), BigDecimal.ZERO))
                .isEqualByComparingTo("1000.00");
        // VAT the ERP already calculated is kept.
        assertThat(Classifier.vatToAccount(pack, "IN", "DRC", new BigDecimal("100.00"), new BigDecimal("4.99"))).isEqualByComparingTo("4.99");
        // Other treatments and output lines are never self-assessed.
        assertThat(Classifier.vatToAccount(pack, "IN", "STANDARD", new BigDecimal("100.00"), BigDecimal.ZERO)).isEqualByComparingTo("0");
        assertThat(Classifier.vatToAccount(pack, "OUT", "DRC", new BigDecimal("100.00"), BigDecimal.ZERO)).isEqualByComparingTo("0");
    }

    @Test
    void recoverableVatFollowsTheRecoveryRateAndNothingIsRecoveredWhenBlocked() {
        BigDecimal vat = new BigDecimal("400.00");
        BigDecimal half = new BigDecimal("50");
        assertThat(Classifier.recoverable("IN", "STANDARD", null, false, vat, half)).isEqualByComparingTo("200.00");
        assertThat(Classifier.recoverable("IN", "STANDARD", "ENTERTAINMENT", false, vat, half)).isEqualByComparingTo("0");
        assertThat(Classifier.recoverable("IN", "IMPORT", null, true, vat, half)).isEqualByComparingTo("0");
        assertThat(Classifier.recoverable("OUT", "STANDARD", null, false, vat, half)).isEqualByComparingTo("0");
        assertThat(Classifier.recoverable("IN", Classifier.UNCLASSIFIED, null, false, vat, half)).isEqualByComparingTo("0");
    }
}
