package ae.vattool.auditcase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ae.vattool.platform.storage.VaultStorage;
import ae.vattool.support.IntegrationTest;
import ae.vattool.support.Scenario;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** Spec journey 4: an FTA audit request, from notice to bundle and close. */
class FafAndAuditCaseTest extends IntegrationTest {

    @Autowired AuditRequestDueJob dueJob;
    @Autowired JdbcClient jdbc;
    @Autowired VaultStorage vault;

    Scenario s;
    UUID entity;
    UUID period;

    @BeforeEach
    void setUp() throws Exception {
        s = new Scenario(mvc, world);
        entity = s.cleanEntity();
        s.client.uploadCsv(entity, "AR", "doc_type,doc_number,doc_date,net_amount,vat_amount,tax_code,establishment_code,counterparty_trn,counterparty_name,customer_type,description\n"
                + "TAX_INVOICE,S-1,2027-01-10,10000.00,500.00,S5,DXB-1," + Scenario.CUSTOMER + ",\"Acme, LLC\",B2B,\"Widgets, large\"\n");
        s.client.uploadCsv(entity, "AP", Scenario.AP_HEAD + "TAX_INVOICE,B-1,2027-01-05,1000.00,50.00,S5,DXB-1," + Scenario.SUPPLIER + ",true,2027-02-05,,,SERVICES\n");
        s.client.uploadCsv(entity, "GL", "account_code,account_type,period_end,balance_aed\n2201,OUTPUT,2027-01-31,500.00\n1201,INPUT,2027-01-31,50.00\n");
        period = s.client.periodId(entity, "2027-01-01");
    }

    private void journal() throws Exception {
        s.client.uploadCsv(entity, "GL_JOURNAL", "transaction_date,account_id,account_name,description,name,transaction_id,line_no,source_document_id,source_type,debit,credit,balance\n"
                + "2027-01-10,1100,Receivables,Sale,Acme,TX-1,1,S-1,AR,10500.00,0.00,10500.00\n"
                + "2027-01-10,4000,Revenue,Sale,Acme,TX-1,2,S-1,AR,0.00,10000.00,-10000.00\n"
                + "2027-01-10,2201,VAT output,Sale,Acme,TX-1,3,S-1,AR,0.00,500.00,-500.00\n");
    }

    private String fileOf(JsonNode doc) throws Exception {
        return new String(mvc.perform(get("/api/documents/" + doc.get("id").asString() + "/content").with(s.reviewer.auth()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private JsonNode faf() throws Exception {
        return s.client.post(s.reviewer, "/api/entities/" + entity + "/faf?from=2027-01-01&to=2027-01-31", null);
    }

    // ---- FTA Audit File ----

    @Test
    void theFileFollowsTheLayoutInTheFtaDocument() throws Exception {
        journal();
        s.client.post(s.preparer, "/api/periods/" + period + "/recompute", null);

        JsonNode result = faf();
        String[] lines = fileOf(result.get("document")).split("\r\n");

        assertThat(lines[0]).isEqualTo("CompInfoStart");
        assertThat(lines[1]).isEqualTo("TaxablePersonNameEn,TaxablePersonNameAr,TRN,TaxAgencyName,TAN,TaxAgentName,TAAN,PeriodStart,PeriodEnd,FAFCreationDate,ProductVersion,FAFVersion");
        assertThat(lines[2]).contains("01-01-2027,31-01-2027").endsWith("FAFv1.0.0");
        assertThat(lines[3]).isEqualTo("CompInfoEnd");
        assertThat(lines[4]).isEqualTo("PurcDataStart");
        assertThat(lines[5]).isEqualTo("SupplierName,SupplierCountry,SupplierTRN,InvoiceDate,InvoiceNo,PermitNo,TransactionID,LineNo,ProductDescription,PurchaseValueAED,VATValueAED,TaxCode,FCYCode,PurchaseFCY,VATFCY");
        assertThat(lines[6]).contains(Scenario.SUPPLIER, "05-01-2027", "B-1", "1000.00", "50.00", ",SR,XXX,0.00,0.00");
        assertThat(lines[7]).isEqualTo("PurchaseTotalAED,VATTotalAED,TransactionCountTotal");
        assertThat(lines[8]).isEqualTo("1000.00,50.00,1");
        assertThat(lines[9]).isEqualTo("PurcDataEnd");
        assertThat(lines[10]).isEqualTo("SuppDataStart");
        assertThat(lines[11]).startsWith("CustomerName,CustomerCountry,CustomerTRN,InvoiceDate,InvoiceNo,TransactionID,LineNo,ProductDescription,SupplyValueAED");
        assertThat(lines[13]).isEqualTo("SupplyTotalAED,VATTotalAED,TransactionCountTotal");
        assertThat(lines[14]).isEqualTo("10000.00,500.00,1");
        assertThat(lines[17]).isEqualTo("TransactionDate,AccountID,AccountName,TransactionDescription,Name,TransactionID,SourceDocumentID,SourceType,Debit,Credit,Balance");
        assertThat(lines[21]).isEqualTo("TotalDebit,TotalCredit,TransactionCountTotal,GLTCurrency");
        assertThat(lines[22]).isEqualTo("10500.00,10500.00,3,AED");
        assertThat(lines[23]).isEqualTo("GLDataEnd");
        assertThat(result.get("warnings")).isEmpty();
    }

    @Test
    void aCommaInADataValueCannotShiftTheColumns() throws Exception {
        String[] lines = fileOf(faf().get("document")).split("\r\n");
        String supplyRow = lines[12];

        assertThat(supplyRow).startsWith("Acme; LLC,").contains("Widgets; large");
        assertThat(supplyRow.split(",", -1)).hasSize(15);
    }

    @Test
    void aMissingLedgerIsReportedRatherThanHidden() throws Exception {
        assertThat(faf().get("warnings").toString()).contains("NO_GENERAL_LEDGER");
    }

    @Test
    void anUnbalancedLedgerIsReported() throws Exception {
        s.client.uploadCsv(entity, "GL_JOURNAL", "transaction_date,account_id,transaction_id,debit,credit\n2027-01-10,1100,TX-9,100.00,0.00\n");

        assertThat(faf().get("warnings").toString()).contains("LEDGER_NOT_BALANCED");
    }

    @Test
    void aFileThatDisagreesWithTheFiledReturnSaysSo() throws Exception {
        s.toFiled(entity, period);
        s.client.uploadCsv(entity, "AR", Scenario.AR_HEAD + "TAX_INVOICE,LATE,2027-01-20,2000.00,100.00,S5,DXB-1," + Scenario.CUSTOMER + ",B2B\n");

        assertThat(faf().get("warnings").toString()).contains("SUPPLY_VAT_DIFFERS_FROM_RETURNS");
    }

    @Test
    void aBadRangeIsRefused() throws Exception {
        s.client.postRaw(s.reviewer, "/api/entities/" + entity + "/faf?from=2027-02-01&to=2027-01-01", null)
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("RANGE_INVALID"));
    }

    @Test
    void aPreparerCannotGenerateAnAuditFile() throws Exception {
        s.client.postRaw(s.preparer, "/api/entities/" + entity + "/faf?from=2027-01-01&to=2027-01-31", null).andExpect(status().isForbidden());
    }

    // ---- Audit case ----

    private JsonNode openCase() throws Exception {
        return s.client.post(s.reviewer, "/api/entities/" + entity + "/audit-cases",
                Map.of("ftaReference", "FTA/2027/1", "notifiedOn", "2027-06-01", "periodFrom", "2027-01-01", "periodTo", "2027-01-31", "description", "Routine audit"));
    }

    @Test
    void openingACasePutsThePeriodsEvidenceOnLegalHold() throws Exception {
        String ev = s.evidence(entity, period, "invoice.pdf");

        JsonNode view = openCase();

        assertThat(view.get("heldPeriods").asInt()).isEqualTo(1);
        JsonNode held = s.client.get(s.reviewer, "/api/evidence/" + ev);
        assertThat(held.get("legalHold").asBoolean()).isTrue();
        assertThat(vault.retention(held.get("storageKey").asString()).legalHold()).isTrue();
    }

    @Test
    void requestsAreTrackedAndAnsweredWithEvidence() throws Exception {
        String id = openCase().get("auditCase").get("id").asString();

        JsonNode added = s.client.post(s.reviewer, "/api/audit-cases/" + id + "/requests", Map.of("description", "Sales invoices for January", "requestedOn", "2027-06-02", "dueOn", "2027-06-20"));
        String request = added.get("requests").get(0).get("id").asString();
        s.client.postRaw(s.reviewer, "/api/audit-requests/" + request + "/response", Map.of("note", " ")).andExpect(status().isUnprocessableContent());

        JsonNode answered = s.client.post(s.reviewer, "/api/audit-requests/" + request + "/response",
                Map.of("note", "Sent the listing", "respondedOn", "2027-06-10", "evidenceIds", List.of(s.evidence(entity, period, "listing.pdf"))));

        assertThat(answered.get("requests").get(0).get("status").asString()).isEqualTo("RESPONDED");
        assertThat(answered.get("requestEvidence")).hasSize(1);
    }

    @Test
    void aCaseCannotCloseWithAnUnansweredRequest() throws Exception {
        String id = openCase().get("auditCase").get("id").asString();
        s.client.post(s.reviewer, "/api/audit-cases/" + id + "/requests", Map.of("description", "More", "dueOn", "2027-06-20"));

        s.client.postRaw(s.partner, "/api/audit-cases/" + id + "/close", Map.of("outcome", "Done", "releaseHolds", false))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AUDIT_REQUESTS_OPEN"));
    }

    @Test
    void closingCanReleaseTheHoldsAndAClosedCaseTakesNoMoreRequests() throws Exception {
        String ev = s.evidence(entity, period, "invoice.pdf");
        String id = openCase().get("auditCase").get("id").asString();

        JsonNode closed = s.client.post(s.partner, "/api/audit-cases/" + id + "/close", Map.of("outcome", "No findings", "releaseHolds", true));

        assertThat(closed.get("auditCase").get("status").asString()).isEqualTo("CLOSED");
        assertThat(closed.get("heldPeriods").asInt()).isZero();
        assertThat(s.client.get(s.reviewer, "/api/evidence/" + ev).get("legalHold").asBoolean()).isFalse();
        s.client.postRaw(s.reviewer, "/api/audit-cases/" + id + "/requests", Map.of("description", "Late", "dueOn", "2027-07-01"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AUDIT_CASE_CLOSED"));
    }

    @Test
    void theBundleHoldsTheFafPacksAndEvidenceWithAManifestThatChecksOut() throws Exception {
        journal();
        s.toFiled(entity, period);
        s.client.post(s.reviewer, "/api/periods/" + period + "/pack", null);
        s.evidence(entity, period, "invoice.pdf");
        String id = openCase().get("auditCase").get("id").asString();

        JsonNode doc = s.client.post(s.reviewer, "/api/audit-cases/" + id + "/bundle", null);

        byte[] zip = mvc.perform(get("/api/documents/" + doc.get("id").asString() + "/content").with(s.reviewer.auth()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(VaultStorage.sha256(zip)).isEqualTo(doc.get("sha256").asString());
        Map<String, byte[]> files = new HashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                files.put(e.getName(), z.readAllBytes());
            }
        }
        assertThat(files.keySet()).anyMatch(n -> n.startsWith("faf/FAF_"));
        assertThat(files.keySet()).anyMatch(n -> n.startsWith("return-packs/2027-01-31/"));
        assertThat(files.keySet()).anyMatch(n -> n.startsWith("evidence/") && n.endsWith("invoice.pdf"));
        JsonNode manifest = JSON.readTree(files.get("manifest.json"));
        assertThat(manifest.get("ftaReference").asString()).isEqualTo("FTA/2027/1");
        for (JsonNode f : manifest.get("files")) {
            assertThat(VaultStorage.sha256(files.get(f.get("file").asString()))).isEqualTo(f.get("sha256").asString());
        }
    }

    @Test
    void anAuditorWithLockedOnlyAccessCanFetchTheFafButNotOpenCases() throws Exception {
        var auditor = world.newUser(s.tenant, "EXTERNAL");
        world.grantOnEntity(auditor, entity, "AUDITOR");
        JsonNode doc = faf().get("document");

        mvc.perform(get("/api/documents/" + doc.get("id").asString() + "/content").with(auditor.auth())).andExpect(status().isOk());
        s.client.getRaw(auditor, "/api/entities/" + entity + "/audit-cases").andExpect(status().isForbidden());
    }

    @Test
    void aRequestDueSoonRemindsTheTeamOnce() throws Exception {
        String id = openCase().get("auditCase").get("id").asString();
        s.client.post(s.reviewer, "/api/audit-cases/" + id + "/requests", Map.of("description", "Invoices", "dueOn", LocalDate.now().plusDays(3).toString()));

        int first = world.queryInTenant(s.tenant, () -> dueJob.run(LocalDate.now()));
        int again = world.queryInTenant(s.tenant, () -> dueJob.run(LocalDate.now()));

        assertThat(first).isEqualTo(1);
        assertThat(again).isZero();
        long notices = world.queryInTenant(s.tenant, () -> jdbc.sql("SELECT count(*) FROM notification WHERE kind = 'AUDIT_REQUEST_DUE'").query(Long.class).single());
        assertThat(notices).isGreaterThanOrEqualTo(1);
    }
}
