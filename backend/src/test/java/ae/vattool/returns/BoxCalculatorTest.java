package ae.vattool.returns;

import static org.assertj.core.api.Assertions.assertThat;

import ae.vattool.returns.BoxCalculator.Aggregate;
import ae.vattool.returns.BoxCalculator.BoxValue;
import ae.vattool.rules.RulePackDefinition;
import ae.vattool.rules.RulePackParser;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class BoxCalculatorTest {

    static RulePackDefinition.Form form;
    static final UUID MEMBER = UUID.randomUUID();

    @BeforeAll
    static void load() throws Exception {
        form = RulePackParser.parse(new ClassPathResource("rulepacks/ae-vat/v1/ae-vat-v1.yaml")
                .getContentAsString(StandardCharsets.UTF_8)).form();
    }

    private static Aggregate row(String direction, String treatment, String emirate, boolean blocked, String net, String vat, String recoverable) {
        return new Aggregate(direction, treatment, emirate, blocked, "NORMAL", null, MEMBER, new BigDecimal(net), new BigDecimal(vat), new BigDecimal(recoverable), 1);
    }

    private static Aggregate adjustment(String box, String vat) {
        return new Aggregate("IN", "OUT_OF_SCOPE", null, false, "ADJUSTMENT", box, MEMBER, BigDecimal.ZERO, new BigDecimal(vat), BigDecimal.ZERO, 1);
    }

    private static void assertBox(Map<String, BoxValue> boxes, String code, String amount, String vat, String adjustment) {
        assertThat(boxes.get(code).amount()).as(code + " amount").isEqualByComparingTo(amount);
        assertThat(boxes.get(code).vat()).as(code + " vat").isEqualByComparingTo(vat);
        assertThat(boxes.get(code).adjustment()).as(code + " adjustment").isEqualByComparingTo(adjustment);
    }

    @Test
    void anEmptyPeriodIsAllZeros() {
        Map<String, BoxValue> boxes = BoxCalculator.calculate(form, List.of());

        assertThat(boxes).hasSize(20);
        boxes.values().forEach(v -> assertThat(v.vat()).isEqualByComparingTo("0"));
    }

    @Test
    void standardSuppliesGoToTheirEmirate() {
        Map<String, BoxValue> b = BoxCalculator.calculate(form, List.of(
                row("OUT", "STANDARD", "AUH", false, "100.00", "5.00", "0"),
                row("OUT", "STANDARD", "FUJ", false, "200.00", "10.00", "0")));

        assertBox(b, "1a", "100.00", "5.00", "0");
        assertBox(b, "1g", "200.00", "10.00", "0");
        assertBox(b, "1b", "0", "0", "0");
        assertBox(b, "8", "300.00", "15.00", "0");
    }

    @Test
    void standardSuppliesWithNoEmirateFallOutOfEveryEmirateBoxWhichIsWhatR13Catches() {
        Map<String, BoxValue> b = BoxCalculator.calculate(form, List.of(row("OUT", "STANDARD", null, false, "100.00", "5.00", "0")));

        assertBox(b, "8", "0", "0", "0");
    }

    @Test
    void touristRefundsAreNegative() {
        Map<String, BoxValue> b = BoxCalculator.calculate(form, List.of(row("OUT", "TOURIST_REFUND", "DXB", false, "100.00", "5.00", "0")));

        assertBox(b, "2", "-100.00", "-5.00", "0");
        assertBox(b, "8", "-100.00", "-5.00", "0");
    }

    @Test
    void reverseChargeAppearsInBoxThreeInFullAndBoxTenOnlyAsRecoverable() {
        Map<String, BoxValue> b = BoxCalculator.calculate(form, List.of(row("IN", "REVERSE_CHARGE", "DXB", false, "1000.00", "50.00", "30.00")));

        assertBox(b, "3", "1000.00", "50.00", "0");
        assertBox(b, "10", "1000.00", "30.00", "0");
    }

    @Test
    void aBlockedReverseChargeIsStillDueButNotInBoxTen() {
        Map<String, BoxValue> b = BoxCalculator.calculate(form, List.of(row("IN", "REVERSE_CHARGE", "DXB", true, "1000.00", "50.00", "0")));

        assertBox(b, "3", "1000.00", "50.00", "0");
        assertBox(b, "10", "0", "0", "0");
    }

    @Test
    void blockedExpensesAreLeftOutOfBoxNine() {
        Map<String, BoxValue> b = BoxCalculator.calculate(form, List.of(
                row("IN", "STANDARD", "DXB", false, "1000.00", "50.00", "50.00"),
                row("IN", "STANDARD", "DXB", true, "500.00", "25.00", "0")));

        assertBox(b, "9", "1000.00", "50.00", "0");
    }

    @Test
    void anAdjustmentLineAffectsOnlyTheAdjustmentColumnOfItsBox() {
        Map<String, BoxValue> b = BoxCalculator.calculate(form, List.of(
                row("IN", "STANDARD", "DXB", false, "1000.00", "50.00", "50.00"),
                adjustment("9", "-20.00")));

        assertBox(b, "9", "1000.00", "50.00", "-20.00");
        assertBox(b, "11", "1000.00", "50.00", "-20.00");
        assertBox(b, "13", "0", "30.00", "0");
    }

    @Test
    void thePayableBoxIsDueLessRecoverableAndGoesNegativeForARefund() {
        Map<String, BoxValue> payable = BoxCalculator.calculate(form, List.of(
                row("OUT", "STANDARD", "DXB", false, "1000.00", "50.00", "0"),
                row("IN", "STANDARD", "DXB", false, "400.00", "20.00", "20.00")));
        Map<String, BoxValue> refund = BoxCalculator.calculate(form, List.of(
                row("OUT", "STANDARD", "DXB", false, "100.00", "5.00", "0"),
                row("IN", "STANDARD", "DXB", false, "1000.00", "50.00", "50.00")));

        assertBox(payable, "14", "0", "30.00", "0");
        assertBox(refund, "14", "0", "-45.00", "0");
    }

    @Test
    void outOfScopeLinesAreInNoBox() {
        Map<String, BoxValue> b = BoxCalculator.calculate(form, List.of(row("OUT", "OUT_OF_SCOPE", "DXB", false, "1000.00", "0", "0")));

        b.values().forEach(v -> assertThat(v.amount()).isEqualByComparingTo("0"));
    }

    @Test
    void unclassifiedLinesAreInNoBox() {
        Map<String, BoxValue> b = BoxCalculator.calculate(form, List.of(row("OUT", "UNCLASSIFIED", "DXB", false, "1000.00", "50.00", "0")));

        b.values().forEach(v -> assertThat(v.amount()).isEqualByComparingTo("0"));
    }
}
