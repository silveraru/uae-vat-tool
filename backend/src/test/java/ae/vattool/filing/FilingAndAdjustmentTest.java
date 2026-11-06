package ae.vattool.filing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ae.vattool.platform.storage.VaultStorage;
import ae.vattool.support.IntegrationTest;
import ae.vattool.support.Scenario;
import ae.vattool.support.TestClient;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

class FilingAndAdjustmentTest extends IntegrationTest {

    @Autowired JdbcClient jdbc;
    @Autowired VaultStorage vault;

    Scenario s;
    UUID entity;
    UUID period;

    @BeforeEach
    void setUp() throws Exception {
        s = new Scenario(mvc, world);
        entity = s.cleanEntity();
        period = s.cleanJanuary(entity, "10000.00", "500.00");
    }

    private byte[] download(String format) throws Exception {
        return mvc.perform(get("/api/periods/" + period + "/pack/" + format).with(s.reviewer.auth()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }

    private String pdfText(byte[] pdf) throws Exception {
        try (PDDocument d = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(d);
        }
    }

    private int pdfPagesWith(byte[] pdf, String text) throws Exception {
        try (PDDocument d = Loader.loadPDF(pdf)) {
            int count = 0;
            for (int i = 1; i <= d.getNumberOfPages(); i++) {
                PDFTextStripper t = new PDFTextStripper();
                t.setStartPage(i);
                t.setEndPage(i);
                if (t.getText(d).contains(text)) {
                    count++;
                }
            }
            return count;
        }
    }

    private String box(UUID p, String code, String column) throws Exception {
        s.client.post(s.preparer, "/api/periods/" + p + "/recompute", null);
        JsonNode boxes = TestClient.JSON.readTree(s.client.get(s.preparer, "/api/periods/" + p + "/return").get("version").get("boxValues").asString());
        return boxes.get(code).get(column).asString();
    }

    private void signOffRulePack() throws Exception {
        // The pack is installed on first use, so it exists by now.
        for (JsonNode pack : s.client.get(s.admin, "/api/rule-packs")) {
            s.client.post(s.admin, "/api/rule-packs/" + pack.get("id").asString() + "/sign-off", Map.of("note", "Reviewed by the tax team"));
        }
    }

    // ---- Return pack ----

    @Test
    void aPackIsOnlyProducedFromALockedReturn() throws Exception {
        s.client.postRaw(s.reviewer, "/api/periods/" + period + "/pack", null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PERIOD_NOT_LOCKED"));
    }

    @Test
    void aPackUnderAnUnsignedRulePackIsWatermarkedInEveryFormatAndOnEveryPage() throws Exception {
        s.toApproved(period);

        JsonNode pack = s.client.post(s.reviewer, "/api/periods/" + period + "/pack", null);

        assertThat(pack.get("draftRules").asBoolean()).isTrue();
        byte[] pdf = download("pdf");
        try (PDDocument d = Loader.loadPDF(pdf)) {
            assertThat(pdfPagesWith(pdf, ReturnPackService.WATERMARK)).isEqualTo(d.getNumberOfPages());
        }
        assertThat(new String(download("json"))).contains(ReturnPackService.WATERMARK);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(download("xlsx")))) {
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                assertThat(wb.getSheetAt(i).getRow(0).getCell(0).getStringCellValue()).as(wb.getSheetName(i)).isEqualTo(ReturnPackService.WATERMARK);
            }
        }
    }

    @Test
    void signingOffTheRulePackRemovesTheWatermarkEvenForAReturnComputedEarlier() throws Exception {
        s.client.post(s.preparer, "/api/periods/" + period + "/recompute", null);
        assertThat(s.client.get(s.preparer, "/api/periods/" + period + "/return").get("ruleDraft").asBoolean()).isTrue();
        signOffRulePack();
        assertThat(s.client.get(s.preparer, "/api/periods/" + period + "/return").get("ruleDraft").asBoolean()).isFalse();
        s.toApproved(period);

        JsonNode pack = s.client.post(s.reviewer, "/api/periods/" + period + "/pack", null);

        assertThat(pack.get("draftRules").asBoolean()).isFalse();
        assertThat(pdfText(download("pdf"))).doesNotContain(ReturnPackService.WATERMARK);
        assertThat(new String(download("json"))).doesNotContain("watermark");
    }

    @Test
    void thePackHoldsTheFiguresEntryGuideCertificateAndApprovals() throws Exception {
        s.toApproved(period);
        s.client.post(s.reviewer, "/api/periods/" + period + "/pack", null);

        String text = pdfText(download("pdf"));

        assertThat(text).contains("VAT201 return pack", "Standard rated supplies in Dubai", "10,000.00", "500.00");
        assertThat(text).contains("Output section, row 1b");
        assertThat(text).contains("Reconciliation certificate", "RECONCILED");
        assertThat(text).contains("PREPARED", "REVIEWED", "APPROVED");
        JsonNode json = TestClient.JSON.readTree(download("json"));
        assertThat(json.get("boxes").toString()).contains("\"box\":\"14\"");
        assertThat(json.get("reconciliationCertificate").get("rules")).hasSizeGreaterThanOrEqualTo(14);
    }

    @Test
    void packFilesAreInLockedStorage() throws Exception {
        s.toApproved(period);

        JsonNode pack = s.client.post(s.reviewer, "/api/periods/" + period + "/pack", null);

        assertThat(vault.retention(pack.get("pdfKey").asString()).mode()).isEqualTo("GOVERNANCE");
    }

    // ---- Filing and payment ----

    @Test
    void filingNeedsAReferenceDateAndAcknowledgement() throws Exception {
        s.toApproved(period);
        String ack = s.evidence(entity, period, "ack.pdf");

        s.client.postRaw(s.reviewer, "/api/periods/" + period + "/filing", Map.of("submittedOn", "2027-02-20", "ackEvidenceId", ack))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("SUBMISSION_REF_REQUIRED"));
        s.client.postRaw(s.reviewer, "/api/periods/" + period + "/filing", Map.of("submissionRef", "EMT-1", "submittedOn", "2027-02-20"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("EVIDENCE_REQUIRED"));

        JsonNode position = s.client.post(s.reviewer, "/api/periods/" + period + "/filing", Map.of("submissionRef", "EMT-1", "submittedOn", "2027-02-20", "ackEvidenceId", ack));

        assertThat(position.get("filing").get("submissionRef").asString()).isEqualTo("EMT-1");
        assertThat(s.client.get(s.preparer, "/api/periods/" + period).get("state").asString()).isEqualTo("FILED");
    }

    @Test
    void onlyAnApprovedReturnCanBeFiled() throws Exception {
        String ack = s.evidence(entity, period, "ack.pdf");

        s.client.postRaw(s.reviewer, "/api/periods/" + period + "/filing", Map.of("submissionRef", "EMT-1", "submittedOn", "2027-02-20", "ackEvidenceId", ack))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WRONG_STATE"));
    }

    @Test
    void evidenceFromAnotherEntityIsNotAccepted() throws Exception {
        s.toApproved(period);
        UUID other = s.cleanEntity();
        String foreign = s.evidence(other, null, "other.pdf");

        s.client.postRaw(s.reviewer, "/api/periods/" + period + "/filing", Map.of("submissionRef", "EMT-1", "submittedOn", "2027-02-20", "ackEvidenceId", foreign))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("EVIDENCE_WRONG_ENTITY"));
    }

    @Test
    void aMatchedBankConfirmationMarksTheReturnPaid() throws Exception {
        s.toFiled(entity, period);
        String bank = s.evidence(entity, period, "bank.pdf");

        JsonNode p = s.client.post(s.reviewer, "/api/periods/" + period + "/payment", Map.of("amountPaidAed", "500.00", "gibanReference", "AE123", "paidOn", "2027-02-25",
                "bankReference", "ae123", "bankAmountAed", "500.00", "confirmationEvidenceId", bank));

        assertThat(p.get("payments").get(0).get("matchStatus").asString()).isEqualTo("MATCHED");
        assertThat(new BigDecimal(p.get("outstanding").asString())).isEqualByComparingTo("0");
        assertThat(s.client.get(s.preparer, "/api/periods/" + period).get("state").asString()).isEqualTo("PAID");
    }

    @Test
    void aPaymentWithoutBankConfirmationDoesNotMarkItPaid() throws Exception {
        s.toFiled(entity, period);

        JsonNode p = s.client.post(s.reviewer, "/api/periods/" + period + "/payment", Map.of("amountPaidAed", "500.00", "gibanReference", "AE123", "paidOn", "2027-02-25"));

        assertThat(p.get("payments").get(0).get("matchStatus").asString()).isEqualTo("UNCONFIRMED");
        assertThat(s.client.get(s.preparer, "/api/periods/" + period).get("state").asString()).isEqualTo("FILED");
    }

    @Test
    void aWrongBankReferenceOrAmountIsFlaggedAsAMismatch() throws Exception {
        s.toFiled(entity, period);
        String bank = s.evidence(entity, period, "bank.pdf");

        JsonNode wrongRef = s.client.post(s.reviewer, "/api/periods/" + period + "/payment", Map.of("amountPaidAed", "500.00", "gibanReference", "AE123", "paidOn", "2027-02-25",
                "bankReference", "AE999", "bankAmountAed", "500.00", "confirmationEvidenceId", bank));
        JsonNode wrongAmount = s.client.post(s.reviewer, "/api/periods/" + period + "/payment", Map.of("amountPaidAed", "500.00", "gibanReference", "AE123", "paidOn", "2027-02-25",
                "bankReference", "AE123", "bankAmountAed", "400.00", "confirmationEvidenceId", bank));

        assertThat(wrongRef.get("payments").get(0).get("matchStatus").asString()).isEqualTo("MISMATCHED");
        assertThat(wrongAmount.get("payments").get(1).get("matchStatus").asString()).isEqualTo("MISMATCHED");
        assertThat(s.client.get(s.preparer, "/api/periods/" + period).get("state").asString()).isEqualTo("FILED");
    }

    @Test
    void partPaymentsAddUpUntilTheReturnIsPaid() throws Exception {
        s.toFiled(entity, period);
        String bank = s.evidence(entity, period, "bank.pdf");

        JsonNode first = s.client.post(s.reviewer, "/api/periods/" + period + "/payment", Map.of("amountPaidAed", "200.00", "gibanReference", "AE1", "paidOn", "2027-02-25",
                "bankReference", "AE1", "bankAmountAed", "200.00", "confirmationEvidenceId", bank));
        assertThat(new BigDecimal(first.get("outstanding").asString())).isEqualByComparingTo("300.00");
        assertThat(s.client.get(s.preparer, "/api/periods/" + period).get("state").asString()).isEqualTo("FILED");

        s.client.post(s.reviewer, "/api/periods/" + period + "/payment", Map.of("amountPaidAed", "300.00", "gibanReference", "AE2", "paidOn", "2027-02-26",
                "bankReference", "AE2", "bankAmountAed", "300.00", "confirmationEvidenceId", bank));

        assertThat(s.client.get(s.preparer, "/api/periods/" + period).get("state").asString()).isEqualTo("PAID");
    }

    @Test
    void aRefundPositionHasNoPaymentStep() throws Exception {
        UUID refundEntity = s.cleanEntity();
        s.client.uploadCsv(refundEntity, "AP", Scenario.AP_HEAD + "TAX_INVOICE,B1,2027-01-05,1000.00,50.00,S5,DXB-1," + Scenario.SUPPLIER + ",true,2027-02-05,,,SERVICES\n");
        s.client.uploadCsv(refundEntity, "GL", "account_code,account_type,period_end,balance_aed\n1201,INPUT,2027-01-31,50.00\n");
        UUID p = s.client.periodId(refundEntity, "2027-01-01");
        s.toFiled(refundEntity, p);

        JsonNode position = s.client.get(s.reviewer, "/api/periods/" + p + "/filing");

        assertThat(position.get("refundable").asBoolean()).isTrue();
        assertThat(s.client.get(s.preparer, "/api/periods/" + p).get("state").asString()).isEqualTo("PAID");
    }

    // ---- Evidence vault ----

    @Test
    void evidenceIsWormCannotBeDeletedAndStaysIntact() throws Exception {
        String id = s.evidence(entity, period, "invoice.pdf");
        JsonNode e = s.client.get(s.reviewer, "/api/evidence/" + id);
        String key = e.get("storageKey").asString();
        String version = world.queryInTenant(s.tenant, () -> jdbc.sql("SELECT version_id FROM evidence WHERE id = ?").param(UUID.fromString(id)).query(String.class).single());

        assertThat(vault.retention(key).mode()).isEqualTo("GOVERNANCE");
        assertThatThrownBy(() -> vault.deleteVersion(key, version)).hasMessageContaining("WORM");
        mvc.perform(get("/api/evidence/" + id + "/content").with(s.reviewer.auth())).andExpect(status().isOk());
        assertThat(e.get("sha256").asString()).hasSize(64);
    }

    @Test
    void retentionIsFiveYearsOrFifteenForRealEstate() throws Exception {
        String standard = s.evidence(entity, period, "a.pdf");
        var file = new org.springframework.mock.web.MockMultipartFile("file", "lease.pdf", "application/pdf", "lease".getBytes());
        JsonNode realEstate = TestClient.read(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/entities/" + entity + "/evidence")
                .file(file).param("retentionClass", "REAL_ESTATE").with(s.reviewer.auth())));

        assertThat(s.client.get(s.reviewer, "/api/evidence/" + standard).get("retainUntil").asString()).startsWith(String.valueOf(java.time.LocalDate.now().getYear() + 5));
        assertThat(realEstate.get("retainUntil").asString()).startsWith(String.valueOf(java.time.LocalDate.now().getYear() + 15));
    }

    @Test
    void aLegalHoldIsSetInStorageAndNeedsAReason() throws Exception {
        String id = s.evidence(entity, period, "a.pdf");

        s.client.postRaw(s.admin, "/api/evidence/" + id + "/legal-hold", Map.of("on", true, "reason", " "))
                .andExpect(status().isUnprocessableContent());
        JsonNode held = s.client.post(s.admin, "/api/evidence/" + id + "/legal-hold", Map.of("on", true, "reason", "FTA audit"));

        assertThat(held.get("legalHold").asBoolean()).isTrue();
        assertThat(vault.retention(held.get("storageKey").asString()).legalHold()).isTrue();
    }

    @Test
    void evidenceLinksToLinesBoxesAndExceptions() throws Exception {
        String id = s.evidence(entity, period, "a.pdf");

        s.client.post(s.reviewer, "/api/evidence/" + id + "/links", Map.of("objectType", "BOX", "objectId", "1b"));

        JsonNode linked = s.client.get(s.reviewer, "/api/entities/" + entity + "/evidence?objectType=BOX&objectId=1b");
        assertThat(linked).hasSize(1);
        assertThat(linked.get(0).get("id").asString()).isEqualTo(id);
    }

    @Test
    void oneTenantCannotReadAnothersEvidence() throws Exception {
        String id = s.evidence(entity, period, "a.pdf");
        Scenario other = new Scenario(mvc, world);

        other.client.getRaw(other.reviewer, "/api/evidence/" + id).andExpect(status().isNotFound());
    }

    // ---- Six-month rule (ADJ-01) ----

    private UUID withUnpaidInvoice() throws Exception {
        UUID e = s.cleanEntity();
        s.client.uploadCsv(e, "AP", Scenario.AP_HEAD + "TAX_INVOICE,OLD,2027-01-05,1000.00,50.00,S5,DXB-1," + Scenario.SUPPLIER + ",true,2027-01-20,,,SERVICES\n");
        return e;
    }

    @Test
    void theReversalPostsToBoxNineAdjustmentAndClearsR11() throws Exception {
        UUID e = withUnpaidInvoice();
        UUID july = s.client.periodId(e, "2027-07-01");
        s.client.post(s.preparer, "/api/periods/" + july + "/reconcile", null);
        assertThat(s.client.get(s.preparer, "/api/periods/" + july + "/exceptions?status=OPEN").toString()).contains("R11");

        JsonNode run = s.client.post(s.reviewer, "/api/periods/" + july + "/adjustments/supplier-reversal", null);

        assertThat(run.get("reversed").asInt()).isEqualTo(1);
        assertThat(new BigDecimal(box(july, "9", "adjustment"))).isEqualByComparingTo("-50.00");
        s.client.post(s.preparer, "/api/periods/" + july + "/reconcile", null);
        assertThat(s.client.get(s.preparer, "/api/periods/" + july + "/exceptions?status=OPEN").toString()).doesNotContain("R11");
    }

    @Test
    void runningTheReversalTwiceDoesNotReverseTwice() throws Exception {
        UUID e = withUnpaidInvoice();
        UUID july = s.client.periodId(e, "2027-07-01");

        s.client.post(s.reviewer, "/api/periods/" + july + "/adjustments/supplier-reversal", null);
        JsonNode second = s.client.post(s.reviewer, "/api/periods/" + july + "/adjustments/supplier-reversal", null);

        assertThat(second.get("reversed").asInt()).isZero();
        assertThat(new BigDecimal(box(july, "9", "adjustment"))).isEqualByComparingTo("-50.00");
    }

    @Test
    void inputVatIsReinstatedWhenTheInvoiceIsLaterPaid() throws Exception {
        UUID e = withUnpaidInvoice();
        UUID july = s.client.periodId(e, "2027-07-01");
        s.client.post(s.reviewer, "/api/periods/" + july + "/adjustments/supplier-reversal", null);

        s.client.uploadCsv(e, "AP", Scenario.AP_HEAD + "TAX_INVOICE,OLD,2027-01-05,1000.00,50.00,S5,DXB-1," + Scenario.SUPPLIER + ",true,2027-01-20,2027-08-10,,SERVICES\n");
        UUID august = s.client.periodId(e, "2027-08-01");
        JsonNode run = s.client.post(s.reviewer, "/api/periods/" + august + "/adjustments/supplier-reversal", null);

        assertThat(run.get("reinstated").asInt()).isEqualTo(1);
        assertThat(new BigDecimal(box(august, "9", "adjustment"))).isEqualByComparingTo("50.00");
    }

    @Test
    void aBlockedInvoiceHasNothingToReverse() throws Exception {
        UUID e = s.cleanEntity();
        s.client.uploadCsv(e, "AP", "doc_type,doc_number,doc_date,net_amount,vat_amount,tax_code,gl_account,establishment_code,counterparty_trn,has_tax_invoice,agreed_payment_date\n"
                + "TAX_INVOICE,BLK,2027-01-05,1000.00,50.00,S5,6100,DXB-1," + Scenario.SUPPLIER + ",true,2027-01-20\n");
        UUID july = s.client.periodId(e, "2027-07-01");

        JsonNode run = s.client.post(s.reviewer, "/api/periods/" + july + "/adjustments/supplier-reversal", null);

        assertThat(run.get("reversed").asInt()).isZero();
    }

    // ---- Manual journals and deemed supplies (ADJ-05) ----

    private Map<String, Object> journal(String evidenceId) {
        return Map.of("type", "MANUAL_JOURNAL", "boxCode", "9", "vatAed", "-100.00", "description", "Year end fix", "reason", "Auditor request", "evidenceId", evidenceId);
    }

    @Test
    void aJournalNeedsEvidenceAndReachesTheLedgerOnlyAfterASecondPersonApproves() throws Exception {
        String ev = s.evidence(entity, period, "support.pdf");
        s.client.postRaw(s.reviewer, "/api/periods/" + period + "/adjustments", Map.of("type", "MANUAL_JOURNAL", "boxCode", "9", "vatAed", "-100.00", "description", "x", "reason", "y"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("EVIDENCE_REQUIRED"));

        JsonNode proposed = s.client.post(s.reviewer, "/api/periods/" + period + "/adjustments", journal(ev));
        assertThat(new BigDecimal(box(period, "9", "adjustment"))).isEqualByComparingTo("0");

        s.client.postRaw(s.reviewer, "/api/adjustments/" + proposed.get("id").asString() + "/decision", Map.of("approve", true))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MAKER_CHECKER"));

        s.client.post(s.approver, "/api/adjustments/" + proposed.get("id").asString() + "/decision", Map.of("approve", true, "note", "OK"));

        assertThat(new BigDecimal(box(period, "9", "adjustment"))).isEqualByComparingTo("-100.00");
    }

    @Test
    void aRejectedJournalNeverReachesTheLedger() throws Exception {
        String ev = s.evidence(entity, period, "support.pdf");
        JsonNode proposed = s.client.post(s.reviewer, "/api/periods/" + period + "/adjustments", journal(ev));

        s.client.post(s.approver, "/api/adjustments/" + proposed.get("id").asString() + "/decision", Map.of("approve", false, "note", "No basis"));

        assertThat(new BigDecimal(box(period, "9", "adjustment"))).isEqualByComparingTo("0");
        s.client.postRaw(s.approver, "/api/adjustments/" + proposed.get("id").asString() + "/decision", Map.of("approve", true))
                .andExpect(status().isConflict());
    }

    @Test
    void aJournalIsRefusedOnACalculatedBoxOrABoxWithNoAdjustmentColumn() throws Exception {
        String ev = s.evidence(entity, period, "support.pdf");

        s.client.postRaw(s.reviewer, "/api/periods/" + period + "/adjustments", Map.of("type", "MANUAL_JOURNAL", "boxCode", "14", "vatAed", "1", "description", "x", "reason", "y", "evidenceId", ev))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("BOX_IS_COMPUTED"));
        s.client.postRaw(s.reviewer, "/api/periods/" + period + "/adjustments", Map.of("type", "MANUAL_JOURNAL", "boxCode", "6", "vatAed", "1", "description", "x", "reason", "y", "evidenceId", ev))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("BOX_HAS_NO_ADJUSTMENT"));
    }

    @Test
    void aDeemedSupplyGoesIntoTheEmirateBoxAsANormalLine() throws Exception {
        String ev = s.evidence(entity, period, "support.pdf");
        JsonNode proposed = s.client.post(s.reviewer, "/api/periods/" + period + "/adjustments", Map.of("type", "DEEMED_SUPPLY", "boxCode", "1a", "netAed", "1000.00", "vatAed", "50.00",
                "description", "Goods given away", "reason", "Deemed supply", "evidenceId", ev));

        s.client.post(s.approver, "/api/adjustments/" + proposed.get("id").asString() + "/decision", Map.of("approve", true));

        assertThat(new BigDecimal(box(period, "1a", "amount"))).isEqualByComparingTo("1000.00");
        assertThat(new BigDecimal(box(period, "1a", "vat"))).isEqualByComparingTo("50.00");
    }

    @Test
    void adjustmentsAreRefusedOnALockedPeriod() throws Exception {
        String ev = s.evidence(entity, period, "support.pdf");
        s.toApproved(period);

        s.client.postRaw(s.reviewer, "/api/periods/" + period + "/adjustments", journal(ev))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PERIOD_LOCKED"));
    }

    // ---- Corrections after filing (COR-01) ----

    private void lateSale(UUID e, String net, String vat) throws Exception {
        s.client.uploadCsv(e, "AR", Scenario.AR_HEAD + "TAX_INVOICE,MISSED-" + UUID.randomUUID().toString().substring(0, 5) + ",2027-01-15," + net + "," + vat + ",S5,DXB-1," + Scenario.CUSTOMER + ",B2B\n");
    }

    @Test
    void aSmallErrorRoutesToTheNextReturnAndAdjustsIt() throws Exception {
        s.toFiled(entity, period);
        lateSale(entity, "20000.00", "1000.00");

        JsonNode preview = s.client.post(s.reviewer, "/api/periods/" + period + "/corrections/preview", Map.of());
        assertThat(preview.get("route").asString()).isEqualTo("NEXT_RETURN");
        assertThat(new BigDecimal(preview.get("taxDifference").asString())).isEqualByComparingTo("1000.00");

        JsonNode c = s.client.post(s.reviewer, "/api/periods/" + period + "/corrections", Map.of("reason", "Missed invoice found"));

        assertThat(c.get("route").asString()).isEqualTo("NEXT_RETURN");
        UUID target = UUID.fromString(c.get("targetPeriodId").asString());
        assertThat(new BigDecimal(box(target, "1b", "adjustment"))).isEqualByComparingTo("1000.00");
        assertThat(new BigDecimal(box(target, "14", "vat"))).isEqualByComparingTo("1000.00");
        assertThat(s.client.get(s.preparer, "/api/periods/" + period).get("state").asString()).isEqualTo("FILED");
    }

    @Test
    void exactlyTenThousandStillGoesToTheNextReturn() throws Exception {
        s.toFiled(entity, period);
        lateSale(entity, "200000.00", "10000.00");

        JsonNode c = s.client.post(s.reviewer, "/api/periods/" + period + "/corrections", Map.of("reason", "On the threshold"));

        assertThat(c.get("route").asString()).isEqualTo("NEXT_RETURN");
    }

    @Test
    void oneFilsOverTenThousandNeedsAVoluntaryDisclosure() throws Exception {
        s.toFiled(entity, period);
        lateSale(entity, "200000.20", "10000.01");

        JsonNode c = s.client.post(s.reviewer, "/api/periods/" + period + "/corrections", Map.of("reason", "Large miss"));

        assertThat(c.get("route").asString()).isEqualTo("FORM_211");
        assertThat(c.get("targetPeriodId").isNull()).isTrue();
        assertThat(s.client.get(s.preparer, "/api/periods/" + period).get("state").asString()).isEqualTo("AMENDED");
    }

    @Test
    void anOverstatementIsJudgedByItsSizeNotItsSign() throws Exception {
        UUID e = s.cleanEntity();
        UUID p = s.cleanJanuary(e, "400000.00", "20000.00");
        s.toFiled(e, p);
        String line = s.client.get(s.preparer, "/api/periods/" + p + "/lines").get("rows").get(0).get("id").asString();
        assertThat(line).isNotBlank();

        JsonNode c = s.client.post(s.reviewer, "/api/periods/" + p + "/corrections/preview", Map.of("manualChanges", List.of(Map.of("box", "1b", "vatChange", "-15000.00"))));

        assertThat(new BigDecimal(c.get("taxDifference").asString())).isEqualByComparingTo("-15000.00");
        assertThat(c.get("route").asString()).isEqualTo("FORM_211");
    }

    @Test
    void bothTheFiledVersionAndTheCorrectedVersionAreKept() throws Exception {
        s.toFiled(entity, period);
        String filedHash = s.client.get(s.preparer, "/api/periods/" + period + "/return").get("version").get("valuesHash").asString();
        lateSale(entity, "20000.00", "1000.00");

        JsonNode c = s.client.post(s.reviewer, "/api/periods/" + period + "/corrections", Map.of("reason", "Missed invoice"));

        JsonNode versions = s.client.get(s.preparer, "/api/periods/" + period + "/versions");
        assertThat(versions.toString()).contains("LOCKED", "CORRECTED", c.get("correctedVersionId").asString());
        JsonNode shown = s.client.get(s.preparer, "/api/periods/" + period + "/return").get("version");
        assertThat(shown.get("valuesHash").asString()).isEqualTo(filedHash);
        assertThat(shown.get("status").asString()).isEqualTo("LOCKED");
    }

    @Test
    void openingACorrectionClearsTheFlagOnTheNextPeriodUntilNewChangesArrive() throws Exception {
        s.toFiled(entity, period);
        lateSale(entity, "20000.00", "1000.00");
        UUID feb = s.client.periodId(entity, "2027-02-01");
        s.client.post(s.preparer, "/api/periods/" + feb + "/reconcile", null);
        assertThat(s.client.get(s.preparer, "/api/periods/" + feb + "/exceptions?status=OPEN").toString()).contains("D04");

        s.client.post(s.reviewer, "/api/periods/" + period + "/corrections", Map.of("reason", "Missed invoice"));
        s.client.post(s.preparer, "/api/periods/" + feb + "/reconcile", null);
        assertThat(s.client.get(s.preparer, "/api/periods/" + feb + "/exceptions?status=OPEN").toString()).doesNotContain("D04");

        lateSale(entity, "100.00", "5.00");
        s.client.post(s.preparer, "/api/periods/" + feb + "/reconcile", null);
        assertThat(s.client.get(s.preparer, "/api/periods/" + feb + "/exceptions?status=OPEN").toString()).contains("D04");
    }

    @Test
    void aCorrectionNeedsAReasonAndAnActualDifference() throws Exception {
        s.toFiled(entity, period);

        s.client.postRaw(s.reviewer, "/api/periods/" + period + "/corrections", Map.of("reason", " "))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
        s.client.postRaw(s.reviewer, "/api/periods/" + period + "/corrections", Map.of("reason", "Nothing wrong"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NO_DIFFERENCE"));
    }

    @Test
    void aFigureTypedByHandNeedsEvidence() throws Exception {
        s.toFiled(entity, period);

        s.client.postRaw(s.reviewer, "/api/periods/" + period + "/corrections", Map.of("reason", "Error", "manualChanges", List.of(Map.of("box", "9", "vatChange", "100.00"))))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("EVIDENCE_REQUIRED"));

        String ev = s.evidence(entity, period, "calc.pdf");
        JsonNode c = s.client.post(s.reviewer, "/api/periods/" + period + "/corrections", Map.of("reason", "Error", "evidenceId", ev, "manualChanges", List.of(Map.of("box", "9", "vatChange", "100.00"))));

        // More recoverable VAT lowers the amount payable.
        assertThat(new BigDecimal(c.get("taxDifferenceAed").asString())).isEqualByComparingTo("-100.00");
        UUID target = UUID.fromString(c.get("targetPeriodId").asString());
        assertThat(new BigDecimal(box(target, "9", "adjustment"))).isEqualByComparingTo("100.00");
    }

    @Test
    void aSecondCorrectionPostsOnlyTheNewDifferenceNotTheFirstOneAgain() throws Exception {
        s.toFiled(entity, period);
        lateSale(entity, "20000.00", "1000.00");
        JsonNode first = s.client.post(s.reviewer, "/api/periods/" + period + "/corrections", Map.of("reason", "First miss"));
        UUID target = UUID.fromString(first.get("targetPeriodId").asString());

        lateSale(entity, "2000.00", "100.00");
        JsonNode second = s.client.post(s.reviewer, "/api/periods/" + period + "/corrections", Map.of("reason", "Second miss"));

        assertThat(new BigDecimal(second.get("taxDifferenceAed").asString())).isEqualByComparingTo("100.00");
        // 1,000 and 100 in total. Measuring against the filed return again would give 2,100.
        assertThat(new BigDecimal(box(target, "1b", "adjustment"))).isEqualByComparingTo("1100.00");
        assertThat(new BigDecimal(box(target, "14", "vat"))).isEqualByComparingTo("1100.00");
    }

    @Test
    void aSecondCorrectionDoesNotReverseAFigureEnteredByHandInTheFirst() throws Exception {
        s.toFiled(entity, period);
        String ev = s.evidence(entity, period, "calc.pdf");
        JsonNode first = s.client.post(s.reviewer, "/api/periods/" + period + "/corrections", Map.of("reason", "Input missed", "evidenceId", ev,
                "manualChanges", List.of(Map.of("box", "9", "vatChange", "100.00"))));
        UUID target = UUID.fromString(first.get("targetPeriodId").asString());

        lateSale(entity, "2000.00", "100.00");
        JsonNode second = s.client.post(s.reviewer, "/api/periods/" + period + "/corrections", Map.of("reason", "Sale missed"));

        assertThat(new BigDecimal(second.get("taxDifferenceAed").asString())).isEqualByComparingTo("100.00");
        assertThat(new BigDecimal(box(target, "9", "adjustment"))).isEqualByComparingTo("100.00");
        assertThat(new BigDecimal(box(target, "1b", "adjustment"))).isEqualByComparingTo("100.00");
    }

    @Test
    void withNothingNewSinceTheLastCorrectionThereIsNothingToCorrect() throws Exception {
        s.toFiled(entity, period);
        lateSale(entity, "20000.00", "1000.00");
        s.client.post(s.reviewer, "/api/periods/" + period + "/corrections", Map.of("reason", "First miss"));

        s.client.postRaw(s.reviewer, "/api/periods/" + period + "/corrections", Map.of("reason", "Again"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NO_DIFFERENCE"));
    }

    @Test
    void aCorrectionOnABoxWithNoAdjustmentColumnPostsToAnAdjustableBox() throws Exception {
        s.toFiled(entity, period);
        String ev = s.evidence(entity, period, "calc.pdf");

        JsonNode c = s.client.post(s.reviewer, "/api/periods/" + period + "/corrections", Map.of("reason", "Customs correction", "evidenceId", ev, "manualChanges", List.of(Map.of("box", "6", "vatChange", "200.00"))));

        UUID target = UUID.fromString(c.get("targetPeriodId").asString());
        assertThat(new BigDecimal(box(target, "1a", "adjustment"))).isEqualByComparingTo("200.00");
        assertThat(new BigDecimal(box(target, "14", "vat"))).isEqualByComparingTo("200.00");
    }

    @Test
    void anUnfiledReturnIsNotCorrectedThisWay() throws Exception {
        s.toApproved(period);

        s.client.postRaw(s.reviewer, "/api/periods/" + period + "/corrections", Map.of("reason", "x"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PERIOD_NOT_FILED"));
    }

    @Test
    void aCorrectionIsOutOfAPreparersReach() throws Exception {
        s.toFiled(entity, period);

        s.client.postRaw(s.preparer, "/api/periods/" + period + "/corrections", Map.of("reason", "x")).andExpect(status().isForbidden());
    }

    // ---- Form changes through configuration alone (NFR-10) ----

    @Test
    void aNewRulePackChangesTheFormWithNoCodeRelease() throws Exception {
        String yaml = new org.springframework.core.io.ClassPathResource("rulepacks/ae-vat/v1/ae-vat-v1.yaml").getContentAsString(java.nio.charset.StandardCharsets.UTF_8)
                .replace("version: \"AE-VAT-1.0\"", "version: \"AE-VAT-2.0\"")
                .replace("effectiveFrom: 2018-01-01", "effectiveFrom: 2027-03-01")
                .replace("label: { en: \"Standard rated supplies in Dubai\"", "label: { en: \"Standard rated supplies, Dubai (new form)\"")
                // The new form moves exempt supplies out of the output total.
                .replace("sumOf: [\"1a\", \"1b\", \"1c\", \"1d\", \"1e\", \"1f\", \"1g\", \"2\", \"3\", \"4\", \"5\", \"6\", \"7\"]",
                        "sumOf: [\"1a\", \"1b\", \"1c\", \"1d\", \"1e\", \"1f\", \"1g\", \"2\", \"3\", \"4\", \"6\", \"7\"]");
        s.client.uploadCsv(entity, "AR", Scenario.AR_HEAD + "TAX_INVOICE,MAR-1,2027-03-05,100.00,5.00,S5,DXB-1," + Scenario.CUSTOMER + ",B2B\n"
                + "TAX_INVOICE,MAR-2,2027-03-06,50.00,0.00,EX,DXB-1," + Scenario.CUSTOMER + ",B2B\n"
                + "TAX_INVOICE,FEB-2,2027-02-06,50.00,0.00,EX,DXB-1," + Scenario.CUSTOMER + ",B2B\n");
        UUID feb = s.client.periodId(entity, "2027-02-01");
        UUID mar = s.client.periodId(entity, "2027-03-01");

        JsonNode uploaded = TestClient.read(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/rule-packs")
                .with(s.admin.auth()).contentType("text/plain").content(yaml)).andExpect(status().isOk()));
        assertThat(uploaded.get("version").asString()).isEqualTo("AE-VAT-2.0");
        assertThat(uploaded.get("status").asString()).isEqualTo("DRAFT");

        // March uses the new form; February, before its effective date, keeps the old one.
        assertThat(new BigDecimal(box(mar, "8", "amount"))).isEqualByComparingTo("100.00");
        assertThat(new BigDecimal(box(feb, "8", "amount"))).isEqualByComparingTo("50.00");
        JsonNode marchView = s.client.get(s.preparer, "/api/periods/" + mar + "/return");
        assertThat(marchView.toString()).contains("Standard rated supplies, Dubai (new form)");
        assertThat(marchView.get("version").get("rulePackVersion").asString()).isEqualTo("AE-VAT-2.0");
    }

    @Test
    void aRulePackVersionCannotBeOverwritten() throws Exception {
        s.client.post(s.preparer, "/api/periods/" + period + "/recompute", null);
        String yaml = new org.springframework.core.io.ClassPathResource("rulepacks/ae-vat/v1/ae-vat-v1.yaml").getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/rule-packs").with(s.admin.auth()).contentType("text/plain").content(yaml))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RULE_PACK_VERSION_EXISTS"));
    }

    @Test
    void onlyAFirmAdminManagesRulePacks() throws Exception {
        s.client.getRaw(s.preparer, "/api/rule-packs").andExpect(status().isForbidden());
    }

    @Test
    void theWholeAuditChainStillVerifiesAfterAFullClose() throws Exception {
        s.toFiled(entity, period);
        s.client.post(s.reviewer, "/api/periods/" + period + "/pack", null);

        JsonNode result = s.client.get(s.admin, "/api/audit/verify");

        assertThat(result.get("intact").asBoolean()).isTrue();
        assertThat(result.get("eventsChecked").asLong()).isGreaterThan(5);
    }
}
