package ae.vattool.corrections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ae.vattool.platform.documents.PdfRenderer;
import ae.vattool.support.IntegrationTest;
import ae.vattool.support.Scenario;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Spec journeys 3 and 5: an error found after filing, and a refund claim, through to their end. */
class CorrectionsJourneyTest extends IntegrationTest {

    Scenario s;
    UUID entity;
    UUID period;

    @BeforeEach
    void setUp() throws Exception {
        s = new Scenario(mvc, world);
        entity = s.cleanEntity();
        period = s.cleanJanuary(entity, "10000.00", "500.00");
    }

    private JsonNode largeCorrection() throws Exception {
        s.toFiled(entity, period);
        s.client.uploadCsv(entity, "AR", Scenario.AR_HEAD + "TAX_INVOICE,LATE-1,2027-01-15,300000.00,15000.00,S5,DXB-1," + Scenario.CUSTOMER + ",B2B\n");
        return s.client.post(s.reviewer, "/api/periods/" + period + "/corrections", Map.of("reason", "Missed a large invoice"));
    }

    private JsonNode disclosure(JsonNode correction) throws Exception {
        return s.client.post(s.reviewer, "/api/corrections/" + correction.get("id").asString() + "/disclosure",
                Map.of("discoveredOn", "2026-10-01", "natureOfError", "An invoice was left out of the January return"));
    }

    private byte[] document(JsonNode view, String kind) throws Exception {
        for (JsonNode d : view.get("documents")) {
            if (d.get("kind").asString().equals(kind)) {
                return mvc.perform(get("/api/documents/" + d.get("id").asString() + "/content").with(s.reviewer.auth()))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            }
        }
        throw new AssertionError("No document of kind " + kind + " in " + view.get("documents"));
    }

    private static String text(byte[] pdf) throws Exception {
        try (PDDocument d = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(d);
        }
    }

    // ---- Journey 3: voluntary disclosure ----

    @Test
    void aCorrectionThatStaysInTheNextReturnNeedsNoForm211() throws Exception {
        s.toFiled(entity, period);
        s.client.uploadCsv(entity, "AR", Scenario.AR_HEAD + "TAX_INVOICE,SMALL,2027-01-15,1000.00,50.00,S5,DXB-1," + Scenario.CUSTOMER + ",B2B\n");
        JsonNode c = s.client.post(s.reviewer, "/api/periods/" + period + "/corrections", Map.of("reason", "Small"));

        s.client.postRaw(s.reviewer, "/api/corrections/" + c.get("id").asString() + "/disclosure", Map.of("discoveredOn", "2026-10-01", "natureOfError", "x"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_A_DISCLOSURE"));
    }

    @Test
    void aDisclosureKeepsTheFiledAndCorrectedFiguresAndAddsTheDeadline() throws Exception {
        JsonNode view = disclosure(largeCorrection());

        assertThat(view.get("disclosure").get("status").asString()).isEqualTo("DRAFT");
        // 20 working days from Thursday 1 October 2026.
        assertThat(view.get("disclosure").get("dueBy").asString()).isEqualTo("2026-10-29");
        assertThat(view.get("form").asString()).isEqualTo("Form 211");
        assertThat(new BigDecimal(view.get("taxDifferenceAed").asString())).isEqualByComparingTo("15000.00");
        JsonNode box1b = null;
        for (JsonNode b : view.get("boxes")) {
            if (b.get("box").asString().equals("1b")) {
                box1b = b;
            }
        }
        assertThat(new BigDecimal(box1b.get("vatFrom").asString())).isEqualByComparingTo("500.00");
        assertThat(new BigDecimal(box1b.get("vatTo").asString())).isEqualByComparingTo("15500.00");
    }

    @Test
    void aFutureDiscoveryDateAndAMissingDescriptionAreRefused() throws Exception {
        JsonNode c = largeCorrection();
        String path = "/api/corrections/" + c.get("id").asString() + "/disclosure";

        s.client.postRaw(s.reviewer, path, Map.of("discoveredOn", LocalDate.now().plusDays(2).toString(), "natureOfError", "x"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("DISCOVERY_DATE_FUTURE"));
        s.client.postRaw(s.reviewer, path, Map.of("discoveredOn", "2026-10-01", "natureOfError", " "))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
    }

    @Test
    void aSecondDisclosureOfOneCorrectionIsRefused() throws Exception {
        JsonNode c = largeCorrection();
        disclosure(c);

        s.client.postRaw(s.reviewer, "/api/corrections/" + c.get("id").asString() + "/disclosure", Map.of("discoveredOn", "2026-10-01", "natureOfError", "again"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DISCLOSURE_EXISTS"));
    }

    @Test
    void theDrafterCannotApproveTheirOwnDisclosure() throws Exception {
        String id = disclosure(largeCorrection()).get("disclosure").get("id").asString();

        s.client.postRaw(s.reviewer, "/api/disclosures/" + id + "/approve", null)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MAKER_CHECKER"));
        assertThat(s.client.post(s.approver, "/api/disclosures/" + id + "/approve", null).get("disclosure").get("status").asString()).isEqualTo("APPROVED");
    }

    @Test
    void aDisclosureAboveTheRiskThresholdNeedsThePartner() throws Exception {
        putJson(s.admin, "/api/entities/" + entity + "/settings", Map.of("partnerThresholdAed", "1000.00")).andExpect(status().is2xxSuccessful());
        String id = disclosure(largeCorrection()).get("disclosure").get("id").asString();

        s.client.postRaw(s.approver, "/api/disclosures/" + id + "/approve", null)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PARTNER_REQUIRED"));
        assertThat(s.client.post(s.partner, "/api/disclosures/" + id + "/approve", null).get("disclosure").get("status").asString()).isEqualTo("APPROVED");
    }

    @Test
    void theFullJourneyEndsSettledWithTheSubmissionRecorded() throws Exception {
        String id = disclosure(largeCorrection()).get("disclosure").get("id").asString();

        s.client.postRaw(s.reviewer, "/api/disclosures/" + id + "/submission", Map.of("submissionRef", "VD-1", "submittedOn", "2026-10-05", "ackEvidenceId", s.evidence(entity, period, "ack.pdf")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WRONG_STATE"));
        s.client.post(s.approver, "/api/disclosures/" + id + "/approve", null);
        s.client.postRaw(s.reviewer, "/api/disclosures/" + id + "/submission", Map.of("submissionRef", "VD-1", "submittedOn", "2026-10-05"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("EVIDENCE_REQUIRED"));

        JsonNode submitted = s.client.post(s.reviewer, "/api/disclosures/" + id + "/submission",
                Map.of("submissionRef", "VD-1", "submittedOn", "2026-10-05", "ackEvidenceId", s.evidence(entity, period, "ack.pdf")));
        assertThat(submitted.get("disclosure").get("status").asString()).isEqualTo("SUBMITTED");
        assertThat(submitted.get("overdue").asBoolean()).isFalse();

        JsonNode settled = s.client.post(s.reviewer, "/api/disclosures/" + id + "/settlement", Map.of("settledOn", "2026-10-06", "amountAed", "15000.00", "gibanReference", "AE1"));
        assertThat(settled.get("disclosure").get("status").asString()).isEqualTo("SETTLED");
    }

    @Test
    void thePackHoldsTheFiguresAndIsWatermarkedInEveryFormatAndLanguage() throws Exception {
        JsonNode view = disclosure(largeCorrection());
        String id = view.get("disclosure").get("id").asString();

        JsonNode packed = s.client.post(s.reviewer, "/api/disclosures/" + id + "/pack", null);

        assertThat(packed.get("documents")).hasSize(3);
        String english = text(document(packed, "FORM_211_PDF"));
        assertThat(english).contains(PdfRenderer.WATERMARK, "Voluntary disclosure (Form 211) pack", "15,500.00", "15,000.00", "An invoice was left out");
        String arabic = text(document(packed, "FORM_211_PDF_AR"));
        assertThat(arabic).contains("15,500.00");
        assertThat(arabic).containsPattern("[\\u0600-\\u06FF\\uFB50-\\uFDFF\\uFE70-\\uFEFF]");
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(document(packed, "FORM_211_XLSX")))) {
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                assertThat(wb.getSheetAt(i).getRow(0).getCell(0).getStringCellValue()).isEqualTo(PdfRenderer.WATERMARK);
            }
        }
    }

    // ---- Penalty exposure ----

    @Test
    void anUnpaidFiledReturnShowsLatePaymentButNotLateFiling() throws Exception {
        s.toFiled(entity, period);

        JsonNode e = s.client.get(s.reviewer, "/api/periods/" + period + "/penalties?asOf=2027-08-01");

        assertThat(e.get("scheduleFound").asBoolean()).isTrue();
        assertThat(e.get("source").asString()).contains("129 of 2025");
        assertThat(e.get("items").toString()).contains("LATE_PAYMENT").doesNotContain("LATE_FILING");
        // AED 500 for the months starting 1 March to 1 August, at one twelfth of 14% each.
        assertThat(new BigDecimal(e.get("total").asString())).isEqualByComparingTo("35.00");
    }

    @Test
    void aSecondLateReturnWithinTwoYearsCostsTheRepeatAmount() throws Exception {
        s.toApproved(period);
        s.client.post(s.reviewer, "/api/periods/" + period + "/filing", Map.of("submissionRef", "E1", "submittedOn", "2027-03-05", "ackEvidenceId", s.evidence(entity, period, "a.pdf")));
        assertThat(s.client.get(s.reviewer, "/api/periods/" + period + "/penalties?asOf=2027-03-05").get("items").toString()).contains("\"LATE_FILING\"");

        s.client.uploadCsv(entity, "AR", Scenario.AR_HEAD + "TAX_INVOICE,FEB,2027-02-10,1000.00,50.00,S5,DXB-1," + Scenario.CUSTOMER + ",B2B\n");
        s.client.uploadCsv(entity, "GL", "account_code,account_type,period_end,balance_aed\n2201,OUTPUT,2027-02-28,50.00\n");
        UUID feb = s.client.periodId(entity, "2027-02-01");
        s.toApproved(feb);
        s.client.post(s.reviewer, "/api/periods/" + feb + "/filing", Map.of("submissionRef", "E2", "submittedOn", "2027-04-20", "ackEvidenceId", s.evidence(entity, feb, "b.pdf")));

        assertThat(s.client.get(s.reviewer, "/api/periods/" + feb + "/penalties?asOf=2027-04-20").get("items").toString()).contains("LATE_FILING_REPEAT");
    }

    @Test
    void aBankConfirmedPaymentStopsLatePaymentAccruing() throws Exception {
        s.toFiled(entity, period);
        s.client.post(s.reviewer, "/api/periods/" + period + "/payment", Map.of("amountPaidAed", "500.00", "gibanReference", "AE1", "paidOn", "2027-02-25",
                "bankReference", "AE1", "bankAmountAed", "500.00", "confirmationEvidenceId", s.evidence(entity, period, "bank.pdf")));

        assertThat(s.client.get(s.reviewer, "/api/periods/" + period + "/penalties?asOf=2027-08-01").get("items")).isEmpty();
    }

    @Test
    void aDisclosureAddsItsOwnPenaltyAndAnAuditNoticeRaisesIt() throws Exception {
        JsonNode c = largeCorrection();
        String path = "/api/corrections/" + c.get("id").asString() + "/penalties?asOf=2027-06-30&disclosedOn=2027-06-30";

        assertThat(s.client.get(s.reviewer, path).get("items").toString()).contains("VOLUNTARY_DISCLOSURE", "INCORRECT_RETURN").doesNotContain("UNDISCLOSED_BEFORE_AUDIT");

        s.client.post(s.reviewer, "/api/entities/" + entity + "/audit-cases", Map.of("ftaReference", "FTA-1", "notifiedOn", "2027-06-01", "periodFrom", "2027-01-01", "periodTo", "2027-01-31"));

        assertThat(s.client.get(s.reviewer, path).get("items").toString()).contains("UNDISCLOSED_BEFORE_AUDIT");
    }

    // ---- Journey 5: refund ----

    private UUID refundPeriod() throws Exception {
        UUID e = s.cleanEntity();
        entity = e;
        s.client.uploadCsv(e, "AP", Scenario.AP_HEAD + "TAX_INVOICE,B1,2027-01-05,1000.00,50.00,S5,DXB-1," + Scenario.SUPPLIER + ",true,2027-02-05,,,SERVICES\n");
        s.client.uploadCsv(e, "GL", "account_code,account_type,period_end,balance_aed\n1201,INPUT,2027-01-31,50.00\n");
        return s.client.periodId(e, "2027-01-01");
    }

    @Test
    void aRefundIsClaimedOnlyOnAFiledReturnWithAnExcess() throws Exception {
        s.client.postRaw(s.reviewer, "/api/periods/" + period + "/refund", null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PERIOD_NOT_FILED"));
        s.toFiled(entity, period);
        s.client.postRaw(s.reviewer, "/api/periods/" + period + "/refund", null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_REFUNDABLE"));
    }

    @Test
    void theRefundPositionIsFlaggedAndTheClaimRunsThroughToReceipt() throws Exception {
        UUID p = refundPeriod();
        s.toFiled(entity, p);

        JsonNode position = s.client.get(s.reviewer, "/api/periods/" + p + "/refund");
        assertThat(position.get("refundable").asBoolean()).isTrue();
        assertThat(new BigDecimal(position.get("excessAed").asString())).isEqualByComparingTo("50.00");
        assertThat(position.get("timeLimitOn").asString()).isEqualTo("2032-01-31");
        assertThat(position.get("withinLimit").asBoolean()).isTrue();

        JsonNode claimed = s.client.post(s.reviewer, "/api/periods/" + p + "/refund", null);
        String id = claimed.get("request").get("id").asString();
        assertThat(claimed.get("request").get("status").asString()).isEqualTo("DRAFT");

        s.client.postRaw(s.reviewer, "/api/periods/" + p + "/refund", null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REFUND_EXISTS"));

        JsonNode packed = s.client.post(s.reviewer, "/api/refunds/" + id + "/pack", null);
        assertThat(packed.get("documents")).hasSize(3);
        byte[] pdf = mvc.perform(get("/api/documents/" + packed.get("documents").get(2).get("id").asString() + "/content").with(s.reviewer.auth()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(pdf).isNotEmpty();

        s.client.post(s.reviewer, "/api/refunds/" + id + "/submission", Map.of("submissionRef", "VAT311-1", "submittedOn", "2027-03-01", "ackEvidenceId", s.evidence(entity, p, "ack.pdf")));
        s.client.post(s.reviewer, "/api/refunds/" + id + "/decision", Map.of("approved", true, "decidedOn", "2027-03-20"));
        JsonNode received = s.client.post(s.reviewer, "/api/refunds/" + id + "/receipt", Map.of("receivedOn", "2027-03-25", "receivedAmountAed", "50.00"));

        assertThat(received.get("request").get("status").asString()).isEqualTo("RECEIVED");
    }

    @Test
    void aClaimCannotExceedTheExcess() throws Exception {
        UUID p = refundPeriod();
        s.toFiled(entity, p);

        s.client.postRaw(s.reviewer, "/api/periods/" + p + "/refund", Map.of("amountAed", "50.01"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("AMOUNT_INVALID"));
    }

    @Test
    void aRejectionNeedsAReasonAndAllowsANewClaim() throws Exception {
        UUID p = refundPeriod();
        s.toFiled(entity, p);
        String id = s.client.post(s.reviewer, "/api/periods/" + p + "/refund", null).get("request").get("id").asString();
        s.client.post(s.reviewer, "/api/refunds/" + id + "/submission", Map.of("submissionRef", "R1", "submittedOn", "2027-03-01", "ackEvidenceId", s.evidence(entity, p, "ack.pdf")));

        s.client.postRaw(s.reviewer, "/api/refunds/" + id + "/decision", Map.of("approved", false, "decidedOn", "2027-03-20"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
        s.client.post(s.reviewer, "/api/refunds/" + id + "/decision", Map.of("approved", false, "decidedOn", "2027-03-20", "note", "Missing documents"));

        assertThat(s.client.post(s.reviewer, "/api/periods/" + p + "/refund", null).get("request").get("status").asString()).isEqualTo("DRAFT");
    }

    @Test
    void anExternalAuditorCannotSeeTheFirmsPenaltyOrDisclosureWorking() throws Exception {
        JsonNode c = largeCorrection();
        JsonNode packed = s.client.post(s.reviewer, "/api/disclosures/" + disclosure(c).get("disclosure").get("id").asString() + "/pack", null);
        var auditor = world.newUser(s.tenant, "EXTERNAL");
        world.grantOnEntity(auditor, entity, "AUDITOR");

        s.client.getRaw(auditor, "/api/periods/" + period + "/return").andExpect(status().isOk());
        s.client.getRaw(auditor, "/api/periods/" + period + "/penalties").andExpect(status().isForbidden());
        s.client.getRaw(auditor, "/api/corrections/" + c.get("id").asString() + "/penalties").andExpect(status().isForbidden());
        s.client.getRaw(auditor, "/api/corrections/" + c.get("id").asString() + "/disclosure").andExpect(status().isForbidden());
        s.client.getRaw(auditor, "/api/periods/" + period + "/refund").andExpect(status().isForbidden());
        s.client.getRaw(auditor, "/api/documents/" + packed.get("documents").get(0).get("id").asString() + "/content").andExpect(status().isForbidden());
    }

    @Test
    void anArabicNameInTheEnglishPackIsStillThere() throws Exception {
        JsonNode c = largeCorrection();
        String id = s.client.post(s.reviewer, "/api/corrections/" + c.get("id").asString() + "/disclosure",
                Map.of("discoveredOn", "2026-10-01", "natureOfError", "فاتورة ناقصة")).get("disclosure").get("id").asString();

        JsonNode packed = s.client.post(s.reviewer, "/api/disclosures/" + id + "/pack", null);

        assertThat(text(document(packed, "FORM_211_PDF"))).containsPattern("[\\u0600-\\u06FF\\uFB50-\\uFDFF\\uFE70-\\uFEFF]");
    }

    @Test
    void anotherEntitysUserCannotReachARefundOrDisclosure() throws Exception {
        JsonNode c = largeCorrection();
        Scenario other = new Scenario(mvc, world);

        other.client.getRaw(other.reviewer, "/api/corrections/" + c.get("id").asString() + "/disclosure").andExpect(status().isNotFound());
        other.client.getRaw(other.reviewer, "/api/periods/" + period + "/refund").andExpect(status().isNotFound());
        var scoped = world.newUser(s.tenant, "CLIENT");
        world.grantOnEntity(scoped, s.cleanEntity(), "PREPARER");
        s.client.getRaw(scoped, "/api/periods/" + period + "/penalties").andExpect(status().isForbidden());
        assertThat(List.of(1)).hasSize(1);
    }
}
