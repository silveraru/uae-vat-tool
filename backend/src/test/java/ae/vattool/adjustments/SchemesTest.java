package ae.vattool.adjustments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ae.vattool.support.IntegrationTest;
import ae.vattool.support.Scenario;
import ae.vattool.support.TestClient;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** ADJ-02, ADJ-03, ADJ-04, RET-05 and ONB-06: the schemes that change input and output tax after the event. */
class SchemesTest extends IntegrationTest {

    private static final String AP = "doc_type,doc_number,doc_date,net_amount,vat_amount,tax_code,establishment_code,counterparty_trn,has_tax_invoice,recovery_pct\n";

    Scenario s;
    UUID entity;

    @BeforeEach
    void setUp() throws Exception {
        s = new Scenario(mvc, world);
        entity = s.cleanEntity();
    }

    private String box(UUID p, String code, String column) throws Exception {
        s.client.post(s.preparer, "/api/periods/" + p + "/recompute", null);
        JsonNode boxes = TestClient.JSON.readTree(s.client.get(s.preparer, "/api/periods/" + p + "/return").get("version").get("boxValues").asString());
        return boxes.get(code).get(column).asString();
    }

    // ---- Apportionment ----

    /** January: 4,000 VAT direct taxable, 1,000 direct exempt, 1,000 residual recovered in full so far. */
    private UUID partlyExempt() throws Exception {
        s.client.uploadCsv(entity, "AP", AP
                + "TAX_INVOICE,T-1,2027-01-05,80000.00,4000.00,S5,DXB-1," + Scenario.SUPPLIER + ",true,100\n"
                + "TAX_INVOICE,E-1,2027-01-06,20000.00,1000.00,S5,DXB-1," + Scenario.SUPPLIER + ",true,0\n"
                + "TAX_INVOICE,R-1,2027-01-07,20000.00,1000.00,S5,DXB-1," + Scenario.SUPPLIER + ",true,\n");
        s.client.uploadCsv(entity, "AR", Scenario.AR_HEAD + "TAX_INVOICE,S-1,2027-01-10,60000.00,3000.00,S5,DXB-1," + Scenario.CUSTOMER + ",B2B\n"
                + "TAX_INVOICE,X-1,2027-01-11,40000.00,0.00,EX,DXB-1," + Scenario.CUSTOMER + ",B2B\n");
        return s.client.periodId(entity, "2027-01-01");
    }

    private JsonNode apportion(UUID p, boolean post) throws Exception {
        return s.client.post(s.reviewer, "/api/periods/" + p + "/apportionment", Map.of("post", post));
    }

    @Test
    void theInputBasedMethodRecoversResidualTaxAtTheDirectRatio() throws Exception {
        UUID p = partlyExempt();

        JsonNode r = apportion(p, true);

        assertThat(r.get("method").asString()).isEqualTo("INPUT_BASED");
        assertThat(new BigDecimal(r.get("ratioPct").asString())).isEqualByComparingTo("80");
        assertThat(new BigDecimal(r.get("targetRecoverableAed").asString())).isEqualByComparingTo("800.00");
        assertThat(new BigDecimal(r.get("deltaAed").asString())).isEqualByComparingTo("-200.00");
        assertThat(new BigDecimal(box(p, "9", "adjustment"))).isEqualByComparingTo("-200.00");
    }

    @Test
    void aPreviewPostsNothing() throws Exception {
        UUID p = partlyExempt();

        JsonNode r = apportion(p, false);

        assertThat(r.get("posted").asBoolean()).isFalse();
        assertThat(new BigDecimal(box(p, "9", "adjustment"))).isEqualByComparingTo("0");
    }

    @Test
    void runningItTwiceDoesNotApportionTwice() throws Exception {
        UUID p = partlyExempt();
        apportion(p, true);

        JsonNode again = apportion(p, true);

        assertThat(new BigDecimal(again.get("deltaAed").asString())).isEqualByComparingTo("0");
        assertThat(new BigDecimal(box(p, "9", "adjustment"))).isEqualByComparingTo("-200.00");
    }

    @Test
    void withNothingMarkedDirectItAsksRatherThanGuessing() throws Exception {
        s.client.uploadCsv(entity, "AP", AP + "TAX_INVOICE,R-1,2027-01-07,20000.00,1000.00,S5,DXB-1," + Scenario.SUPPLIER + ",true,\n");
        UUID p = s.client.periodId(entity, "2027-01-01");

        s.client.postRaw(s.reviewer, "/api/periods/" + p + "/apportionment", Map.of("post", true))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("APPORTIONMENT_NO_BASIS"));
    }

    @Test
    void anExemptSupplyWithUnapportionedResidualTaxRaisesTheWarning() throws Exception {
        UUID p = partlyExempt();
        s.client.post(s.preparer, "/api/periods/" + p + "/reconcile", null);
        assertThat(s.client.get(s.preparer, "/api/periods/" + p + "/exceptions?status=OPEN").toString()).contains("\"ruleId\":\"T09\"");

        apportion(p, true);
        s.client.post(s.preparer, "/api/periods/" + p + "/reconcile", null);

        assertThat(s.client.get(s.preparer, "/api/periods/" + p + "/exceptions?status=OPEN").toString()).doesNotContain("\"ruleId\":\"T09\"");
    }

    @Test
    void theAnnualAdjustmentGoesInTheFirstReturnAfterTheYearAndTrueUpsTheYear() throws Exception {
        UUID jan = partlyExempt();
        apportion(jan, true);
        // February changes the annual ratio: only exempt-direct input this month.
        s.client.uploadCsv(entity, "AP", AP + "TAX_INVOICE,E-2,2027-02-06,60000.00,3000.00,S5,DXB-1," + Scenario.SUPPLIER + ",true,0\n");
        UUID nextYear = s.client.periodId(entity, "2028-01-01");
        UUID mid = s.client.periodId(entity, "2027-06-01");

        s.client.postRaw(s.reviewer, "/api/periods/" + mid + "/apportionment/annual", Map.of("post", true))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("APPORTIONMENT_WRONG_PERIOD"));

        JsonNode r = s.client.post(s.reviewer, "/api/periods/" + nextYear + "/apportionment/annual", Map.of("post", true));

        // Direct: 4,000 taxable, 4,000 exempt, so 50%. Residual 1,000, of which 800 is recovered so far.
        assertThat(new BigDecimal(r.get("ratioPct").asString())).isEqualByComparingTo("50");
        assertThat(new BigDecimal(r.get("deltaAed").asString())).isEqualByComparingTo("-300.00");
        assertThat(r.get("furtherAdjustment").asBoolean()).isFalse();
        assertThat(new BigDecimal(box(nextYear, "9", "adjustment"))).isEqualByComparingTo("-300.00");
    }

    @Test
    void openingFiguresCountTowardsTheAnnualAdjustment() throws Exception {
        UUID jan = partlyExempt();
        apportion(jan, true);
        s.client.uploadCsv(entity, "OPENING_APPORTIONMENT", "tax_year_start,direct_recoverable_aed,direct_exempt_aed,taxable_supplies_aed,total_supplies_aed,residual_input_aed,recovered_aed\n"
                + "2027-01-01,0.00,3000.00,0.00,0.00,0.00,0.00\n");
        UUID nextYear = s.client.periodId(entity, "2028-01-01");

        JsonNode r = s.client.post(s.reviewer, "/api/periods/" + nextYear + "/apportionment/annual", Map.of("post", false));

        // Direct: 4,000 taxable, 1,000 + 3,000 exempt.
        assertThat(new BigDecimal(r.get("ratioPct").asString())).isEqualByComparingTo("50");
    }

    @Test
    void theOutputBasedMethodAppliesFromTheTaxYearItStartsAndLeavesOutDisposals() throws Exception {
        // A tax year starting October 2027 falls under the new method.
        Map<String, Object> in = new HashMap<>();
        in.put("legalName", "Output based " + UUID.randomUUID());
        in.put("trn", TestClient.nextTrn());
        in.put("entityKind", "STANDALONE");
        in.put("filingFrequency", "MONTHLY");
        in.put("firstPeriodStart", "2027-10-01");
        UUID e = UUID.fromString(s.client.post(s.admin, "/api/entities", in).get("id").asString());
        s.client.post(s.admin, "/api/entities/" + e + "/establishments", Map.of("code", "DXB-1", "name", "Dubai", "emirate", "DXB", "designatedZone", false));
        s.client.post(s.admin, "/api/entities/" + e + "/mappings", Map.of("effectiveFrom", "2018-01-01", "entries", java.util.List.of(
                Map.of("matchType", "TAX_CODE", "sourceCode", "S5", "treatment", "STANDARD"), Map.of("matchType", "TAX_CODE", "sourceCode", "EX", "treatment", "EXEMPT"))));
        s.client.uploadCsv(e, "AR", Scenario.AR_HEAD + "TAX_INVOICE,S-1,2027-10-10,70000.00,3500.00,S5,DXB-1," + Scenario.CUSTOMER + ",B2B\n"
                + "TAX_INVOICE,X-1,2027-10-11,30000.00,0.00,EX,DXB-1," + Scenario.CUSTOMER + ",B2B\n");
        s.client.uploadCsv(e, "AP", AP + "TAX_INVOICE,R-1,2027-10-07,20000.00,1000.00,S5,DXB-1," + Scenario.SUPPLIER + ",true,\n");
        UUID p = s.client.periodId(e, "2027-10-01");

        JsonNode plain = s.client.post(s.reviewer, "/api/periods/" + p + "/apportionment", Map.of("post", false));
        JsonNode excluding = s.client.post(s.reviewer, "/api/periods/" + p + "/apportionment", Map.of("post", false, "excludedSuppliesAed", "60000.00"));

        assertThat(plain.get("method").asString()).isEqualTo("OUTPUT_BASED");
        assertThat(new BigDecimal(plain.get("ratioPct").asString())).isEqualByComparingTo("70");
        // (70,000 - 60,000) / (100,000 - 60,000) = 25%
        assertThat(new BigDecimal(excluding.get("ratioPct").asString())).isEqualByComparingTo("25");
    }

    // ---- Bad debt relief ----

    private UUID filedSale(String net, String vat) throws Exception {
        UUID p = s.cleanJanuary(entity, net, vat);
        s.toFiled(entity, p);
        return p;
    }

    private String doc() throws Exception {
        UUID p = s.client.periodId(entity, "2027-08-01");
        return s.client.get(s.reviewer, "/api/periods/" + p + "/bad-debt/candidates").get(0).get("documentId").asString();
    }

    private Map<String, Object> relief(String document, String amount) throws Exception {
        UUID period = s.client.periodId(entity, "2027-01-01");
        Map<String, Object> in = new HashMap<>();
        in.put("documentId", document);
        in.put("writtenOffAed", amount);
        in.put("writtenOffOn", "2027-08-01");
        in.put("notifiedOn", "2027-08-02");
        in.put("writeOffEvidenceId", s.evidence(entity, period, "writeoff.pdf"));
        in.put("noticeEvidenceId", s.evidence(entity, period, "notice.pdf"));
        in.put("reason", "Customer in liquidation");
        return in;
    }

    @Test
    void reliefReducesOutputTaxInProportionAfterASecondPersonApproves() throws Exception {
        filedSale("10000.00", "500.00");
        UUID aug = s.client.periodId(entity, "2027-08-01");
        String d = doc();

        // Half the gross amount written off, so half the VAT.
        JsonNode claim = s.client.post(s.reviewer, "/api/periods/" + aug + "/bad-debt", relief(d, "5250.00"));

        assertThat(new BigDecimal(claim.get("vatAed").asString())).isEqualByComparingTo("250.00");
        assertThat(claim.get("checklist").asString()).contains("customerNotified");
        assertThat(new BigDecimal(box(aug, "1b", "adjustment"))).isEqualByComparingTo("0");

        JsonNode adjustments = s.client.get(s.reviewer, "/api/periods/" + aug + "/adjustments");
        s.client.post(s.approver, "/api/adjustments/" + adjustments.get(0).get("id").asString() + "/decision", Map.of("approve", true));

        assertThat(new BigDecimal(box(aug, "1b", "adjustment"))).isEqualByComparingTo("-250.00");
    }

    @Test
    void reliefIsRefusedBeforeSixMonthsHavePassed() throws Exception {
        filedSale("10000.00", "500.00");
        UUID june = s.client.periodId(entity, "2027-06-01");
        String d = s.client.get(s.reviewer, "/api/periods/" + s.client.periodId(entity, "2027-08-01") + "/bad-debt/candidates").get(0).get("documentId").asString();

        Map<String, Object> in = relief(d, "100.00");
        in.put("writtenOffOn", "2027-06-01");
        in.put("notifiedOn", "2027-06-02");
        s.client.postRaw(s.reviewer, "/api/periods/" + june + "/bad-debt", in)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BAD_DEBT_TOO_EARLY"));
    }

    @Test
    void reliefNeedsTheVatToHaveBeenFiled() throws Exception {
        s.cleanJanuary(entity, "10000.00", "500.00");
        UUID aug = s.client.periodId(entity, "2027-08-01");
        String d = world.queryInTenant(s.tenant, () -> jdbc.sql("SELECT id::text FROM source_document WHERE filer_entity_id = ?").param(entity).query(String.class).single());

        s.client.postRaw(s.reviewer, "/api/periods/" + aug + "/bad-debt", relief(d, "100.00"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BAD_DEBT_NOT_ACCOUNTED"));
    }

    @Test
    void reliefNeedsBothPiecesOfEvidenceAndCannotExceedWhatIsUnpaid() throws Exception {
        filedSale("10000.00", "500.00");
        UUID aug = s.client.periodId(entity, "2027-08-01");
        String d = doc();

        Map<String, Object> noNotice = relief(d, "100.00");
        noNotice.remove("noticeEvidenceId");
        s.client.postRaw(s.reviewer, "/api/periods/" + aug + "/bad-debt", noNotice)
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("EVIDENCE_REQUIRED"));
        s.client.postRaw(s.reviewer, "/api/periods/" + aug + "/bad-debt", relief(d, "10500.01"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("BAD_DEBT_TOO_MUCH"));
    }

    @Test
    void aLaterRecoveryPaysTheReliefBack() throws Exception {
        filedSale("10000.00", "500.00");
        UUID aug = s.client.periodId(entity, "2027-08-01");
        String d = doc();
        s.client.post(s.reviewer, "/api/periods/" + aug + "/bad-debt", relief(d, "10500.00"));
        s.client.post(s.approver, "/api/adjustments/" + s.client.get(s.reviewer, "/api/periods/" + aug + "/adjustments").get(0).get("id").asString() + "/decision", Map.of("approve", true));

        UUID sep = s.client.periodId(entity, "2027-09-01");
        JsonNode claim = s.client.post(s.reviewer, "/api/periods/" + sep + "/bad-debt/recovery",
                Map.of("documentId", d, "recoveredAed", "2100.00", "evidenceId", s.evidence(entity, sep, "receipt.pdf"), "reason", "Dividend from the liquidator"));

        assertThat(new BigDecimal(claim.get("vatAed").asString())).isEqualByComparingTo("100.00");
        s.client.post(s.approver, "/api/adjustments/" + s.client.get(s.reviewer, "/api/periods/" + sep + "/adjustments").get(0).get("id").asString() + "/decision", Map.of("approve", true));
        assertThat(new BigDecimal(box(sep, "1b", "adjustment"))).isEqualByComparingTo("100.00");
    }

    @Test
    void theRecoveryFormOffersWhatIsStillRelievedInAnyLaterPeriod() throws Exception {
        filedSale("10000.00", "500.00");
        UUID aug = s.client.periodId(entity, "2027-08-01");
        UUID sep = s.client.periodId(entity, "2027-09-01");
        String d = doc();
        assertThat(s.client.get(s.reviewer, "/api/periods/" + sep + "/bad-debt/recoverable")).isEmpty();

        s.client.post(s.reviewer, "/api/periods/" + aug + "/bad-debt", relief(d, "10500.00"));
        s.client.post(s.approver, "/api/adjustments/" + s.client.get(s.reviewer, "/api/periods/" + aug + "/adjustments").get(0).get("id").asString() + "/decision", Map.of("approve", true));
        JsonNode open = s.client.get(s.reviewer, "/api/periods/" + sep + "/bad-debt/recoverable").get(0);
        assertThat(open.get("documentId").asString()).isEqualTo(d);
        assertThat(new BigDecimal(open.get("outstandingAed").asString())).isEqualByComparingTo("10500.00");

        s.client.post(s.reviewer, "/api/periods/" + sep + "/bad-debt/recovery",
                Map.of("documentId", d, "recoveredAed", "2100.00", "evidenceId", s.evidence(entity, sep, "receipt.pdf"), "reason", "Dividend from the liquidator"));
        JsonNode reduced = s.client.get(s.reviewer, "/api/periods/" + sep + "/bad-debt/recoverable").get(0);
        assertThat(new BigDecimal(reduced.get("outstandingAed").asString())).isEqualByComparingTo("8400.00");
        assertThat(new BigDecimal(reduced.get("recoveredAed").asString())).isEqualByComparingTo("2100.00");

        s.client.post(s.reviewer, "/api/periods/" + sep + "/bad-debt/recovery",
                Map.of("documentId", d, "recoveredAed", "8400.00", "evidenceId", s.evidence(entity, sep, "receipt2.pdf"), "reason", "Final dividend"));
        assertThat(s.client.get(s.reviewer, "/api/periods/" + sep + "/bad-debt/recoverable")).isEmpty();
    }

    // ---- Capital assets ----

    private JsonNode asset(String cost, String vat, String initial, String firstUsed) throws Exception {
        return s.client.post(s.reviewer, "/api/entities/" + entity + "/capital-assets", Map.of("reference", "A-" + UUID.randomUUID().toString().substring(0, 6), "description", "Tower", "category", "REAL_ESTATE",
                "costAed", cost, "inputVatAed", vat, "initialRecoveryPct", initial, "firstUsedOn", firstUsed));
    }

    @Test
    void anAssetBelowTheThresholdIsNotInTheScheme() throws Exception {
        s.client.postRaw(s.reviewer, "/api/entities/" + entity + "/capital-assets", Map.of("reference", "A", "description", "Van", "category", "OTHER",
                        "costAed", "4999999.99", "inputVatAed", "250000.00", "initialRecoveryPct", "100", "firstUsedOn", "2027-01-15"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("ASSET_BELOW_THRESHOLD"));
    }

    @Test
    void realEstateRunsTenYearsAndOtherAssetsFive() throws Exception {
        assertThat(asset("5000000.00", "250000.00", "100", "2027-01-15").get("asset").get("adjustmentYears").asInt()).isEqualTo(10);
        JsonNode other = s.client.post(s.reviewer, "/api/entities/" + entity + "/capital-assets", Map.of("reference", "O-1", "description", "Plant", "category", "OTHER",
                "costAed", "6000000.00", "inputVatAed", "300000.00", "initialRecoveryPct", "100", "firstUsedOn", "2027-01-15"));
        assertThat(other.get("asset").get("adjustmentYears").asInt()).isEqualTo(5);
    }

    @Test
    void oneIntervalAdjustsByTheChangeInUseOverTheYears() throws Exception {
        String id = asset("10000000.00", "500000.00", "100", "2027-01-15").get("asset").get("id").asString();
        // The first interval ends 14 January 2028, so January 2028 is too early: it ends in that month.
        UUID feb = s.client.periodId(entity, "2028-02-01");

        JsonNode r = s.client.post(s.reviewer, "/api/periods/" + s.client.periodId(entity, "2028-01-01") + "/capital-assets/" + id + "/adjust", Map.of("taxableUsePct", "80"));

        // 500,000 / 10 years x (80% - 100%) = -10,000
        assertThat(new BigDecimal(r.get("intervals").get(0).get("vatAdjustment").asString())).isEqualByComparingTo("-10000.00");
        assertThat(new BigDecimal(box(s.client.periodId(entity, "2028-01-01"), "9", "adjustment"))).isEqualByComparingTo("-10000.00");
        assertThat(r.get("nextIntervalNo").asInt()).isEqualTo(2);
        s.client.postRaw(s.reviewer, "/api/periods/" + feb + "/capital-assets/" + id + "/adjust", Map.of("taxableUsePct", "80"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ASSET_WRONG_PERIOD"));
    }

    @Test
    void aDueAdjustmentRaisesAWarningUntilItIsRecorded() throws Exception {
        String id = asset("10000000.00", "500000.00", "100", "2027-01-15").get("asset").get("id").asString();
        UUID jan = s.client.periodId(entity, "2028-01-01");

        assertThat(s.client.get(s.reviewer, "/api/periods/" + jan + "/capital-assets/due")).hasSize(1);
        s.client.post(s.preparer, "/api/periods/" + jan + "/reconcile", null);
        assertThat(s.client.get(s.preparer, "/api/periods/" + jan + "/exceptions?status=OPEN").toString()).contains("\"ruleId\":\"T10\"");

        s.client.post(s.reviewer, "/api/periods/" + jan + "/capital-assets/" + id + "/adjust", Map.of("taxableUsePct", "100"));
        s.client.post(s.preparer, "/api/periods/" + jan + "/reconcile", null);

        assertThat(s.client.get(s.preparer, "/api/periods/" + jan + "/exceptions?status=OPEN").toString()).doesNotContain("\"ruleId\":\"T10\"");
    }

    @Test
    void disposalSettlesTheRemainingIntervalsTogether() throws Exception {
        String id = asset("10000000.00", "500000.00", "100", "2027-01-15").get("asset").get("id").asString();
        UUID jun = s.client.periodId(entity, "2027-06-01");

        JsonNode r = s.client.post(s.reviewer, "/api/periods/" + jun + "/capital-assets/" + id + "/dispose", Map.of("disposedOn", "2027-06-10", "taxable", false));

        // Sold in an exempt sale: all ten intervals at 0% use against 100% recovered.
        assertThat(r.get("intervals")).hasSize(10);
        assertThat(new BigDecimal(box(jun, "9", "adjustment"))).isEqualByComparingTo("-500000.00");
        s.client.postRaw(s.reviewer, "/api/periods/" + jun + "/capital-assets/" + id + "/dispose", Map.of("disposedOn", "2027-06-10", "taxable", false))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ASSET_DISPOSED"));
    }

    @Test
    void openingAssetsLoadFromATemplateAndBelowThresholdRowsAreRejected() throws Exception {
        JsonNode batch = s.client.uploadCsv(entity, "OPENING_ASSETS", "reference,description,category,cost_aed,input_vat_aed,initial_recovery_pct,first_used_on\n"
                + "OLD-1,Warehouse,REAL_ESTATE,8000000.00,400000.00,100,2025-03-01\nOLD-2,Small,OTHER,100.00,5.00,100,2025-03-01\n").get("batch");

        assertThat(batch.get("rowsLoaded").asInt()).isEqualTo(1);
        assertThat(batch.get("rowsRejected").asInt()).isEqualTo(1);
        JsonNode assets = s.client.get(s.reviewer, "/api/entities/" + entity + "/capital-assets");
        assertThat(assets.get(0).get("asset").get("opening").asBoolean()).isTrue();
    }

    // ---- Profit margin scheme ----

    private static final String MARGIN = "doc_number,doc_date,category,purchase_price_aed,selling_price_aed,acquisition_costs_aed,establishment_code,description\n";

    @Test
    void vatIsTheTaxFractionOfTheMarginAndNothingOnALoss() throws Exception {
        JsonNode batch = s.client.uploadCsv(entity, "MARGIN", MARGIN + "M-1,2027-01-10,SECOND_HAND,800.00,1850.00,0.00,DXB-1,Used car\nM-2,2027-01-11,ANTIQUE,500.00,400.00,0.00,DXB-1,At a loss\n").get("batch");
        UUID p = s.client.periodId(entity, "2027-01-01");

        assertThat(batch.get("rowsRejected").asInt()).isZero();
        // Margin 1,050: VAT is 5/105 of it, 50, on a net margin of 1,000.
        assertThat(new BigDecimal(box(p, "1b", "vat"))).isEqualByComparingTo("50.00");
        assertThat(new BigDecimal(box(p, "1b", "amount"))).isEqualByComparingTo("1000.00");
    }

    @Test
    void acquisitionCostsReduceTheMargin() throws Exception {
        s.client.uploadCsv(entity, "MARGIN", MARGIN + "M-1,2027-01-10,SECOND_HAND,800.00,1850.00,840.00,DXB-1,Used car\n");
        UUID p = s.client.periodId(entity, "2027-01-01");

        // Margin 210: VAT 10.
        assertThat(new BigDecimal(box(p, "1b", "vat"))).isEqualByComparingTo("10.00");
    }

    @Test
    void aCategoryOutsideTheSchemeIsRejected() throws Exception {
        JsonNode batch = s.client.uploadCsv(entity, "MARGIN", MARGIN + "M-1,2027-01-10,NEW_GOODS,800.00,1850.00,0.00,DXB-1,New car\n").get("batch");

        assertThat(batch.get("rowsRejected").asInt()).isEqualTo(1);
    }

    @Test
    void thePackShowsTheIndicatorAndTheSchedule() throws Exception {
        s.client.uploadCsv(entity, "MARGIN", MARGIN + "M-1,2027-01-10,SECOND_HAND,800.00,1850.00,0.00,DXB-1,Used car\n");
        s.client.uploadCsv(entity, "GL", "account_code,account_type,period_end,balance_aed\n2201,OUTPUT,2027-01-31,50.00\n");
        UUID p = s.client.periodId(entity, "2027-01-01");
        s.toApproved(p);

        s.client.post(s.reviewer, "/api/periods/" + p + "/pack", null);

        byte[] pdf = mvc.perform(get("/api/periods/" + p + "/pack/pdf").with(s.reviewer.auth())).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (PDDocument d = Loader.loadPDF(pdf)) {
            assertThat(new PDFTextStripper().getText(d)).contains("Profit margin scheme applied in this period", "Profit margin scheme schedule", "M-1", "1,850.00");
        }
    }

    @org.springframework.beans.factory.annotation.Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;
}
