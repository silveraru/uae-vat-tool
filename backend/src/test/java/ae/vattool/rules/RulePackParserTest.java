package ae.vattool.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class RulePackParserTest {

    private static String bundled() throws Exception {
        return new ClassPathResource(RulePackStore.BUNDLED_PACK).getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void theBundledPackIsValidAndHasEveryVat201Box() throws Exception {
        RulePackDefinition pack = RulePackParser.parse(bundled());

        assertThat(pack.version()).isEqualTo("AE-VAT-1.0");
        assertThat(pack.form().boxes()).extracting(RulePackDefinition.Box::code).containsExactly(
                "1a", "1b", "1c", "1d", "1e", "1f", "1g", "2", "3", "4", "5", "6", "7", "8",
                "9", "10", "11", "12", "13", "14");
        assertThat(pack.filing().correctionThresholdAed()).isEqualByComparingTo("10000");
    }

    @Test
    void everyBoxHasEnglishAndArabicLabelsAndAnEntryGuide() throws Exception {
        RulePackDefinition pack = RulePackParser.parse(bundled());

        assertThat(pack.form().boxes()).allSatisfy(box -> {
            assertThat(box.label()).containsKeys("en", "ar");
            assertThat(box.entryGuide()).containsKeys("en", "ar");
        });
    }

    @Test
    void aTypoInAPackIsRefusedRatherThanIgnored() throws Exception {
        String typo = bundled().replace("dueDaysAfterPeriodEnd", "dueDaysAfterPeriodEndd");

        assertThatThrownBy(() -> RulePackParser.parse(typo)).hasMessageContaining("could not be read");
    }

    @Test
    void aFormulaUsingAnUndefinedBoxIsRefused() throws Exception {
        String bad = bundled().replace("addOf: [\"12.vat\"]", "addOf: [\"99.vat\"]");

        assertThatThrownBy(() -> RulePackParser.parse(bad)).hasMessageContaining("refers to 99.vat");
    }

    @Test
    void aFormulaUsingALaterBoxIsRefused() throws Exception {
        String bad = bundled().replace("addOf: [\"8.vat\", \"8.adjustment\"]", "addOf: [\"14.vat\"]");

        assertThatThrownBy(() -> RulePackParser.parse(bad)).hasMessageContaining("not defined before it");
    }

    @Test
    void anUnknownTreatmentIsRefused() throws Exception {
        String bad = bundled().replace("treatments: [ZERO_RATED]", "treatments: [ZERO_RATE]");

        assertThatThrownBy(() -> RulePackParser.parse(bad)).hasMessageContaining("unknown treatment ZERO_RATE");
    }
}
