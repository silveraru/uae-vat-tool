package ae.vattool.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ae.vattool.platform.storage.VaultStorage;
import ae.vattool.support.IntegrationTest;
import ae.vattool.support.TestClient;
import ae.vattool.support.TestWorld.TestUser;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

class IngestionTest extends IntegrationTest {

    private static final String HEAD = "doc_type,doc_number,doc_date,net_amount,vat_amount,tax_code,establishment_code,counterparty_trn,customer_type,currency,line_no,paid_date,description\n";

    @Autowired JdbcClient jdbc;
    @Autowired VaultStorage vault;

    UUID tenant;
    TestUser admin;
    TestUser preparer;
    TestClient client;
    UUID entity;

    @BeforeEach
    void setUp() throws Exception {
        tenant = world.newTenant("Ingestion firm " + UUID.randomUUID());
        admin = world.newUser(tenant, "FIRM", "FIRM_ADMIN");
        preparer = world.newUser(tenant, "CLIENT", "PREPARER");
        client = new TestClient(mvc, admin, preparer);
        entity = client.simpleEntity();
    }

    private long currentLines() {
        return world.queryInTenant(tenant, () -> jdbc.sql("SELECT COUNT(*) FROM tax_line WHERE filer_entity_id = ? AND superseded_at IS NULL")
                .param(entity).query(Long.class).single());
    }

    private long allLineRevisions() {
        return world.queryInTenant(tenant, () -> jdbc.sql("SELECT COUNT(*) FROM tax_line WHERE filer_entity_id = ?")
                .param(entity).query(Long.class).single());
    }

    @Test
    void aGoodFileLoadsAndEveryRowIsCounted() throws Exception {
        JsonNode r = client.uploadCsv(entity, "AR", HEAD
                + "TAX_INVOICE,A1,2027-01-05,100.00,5.00,S5,DXB-1,100000000000901,B2B,,1,,\n"
                + "TAX_INVOICE,A2,2027-01-06,200.00,10.00,S5,DXB-1,100000000000901,B2B,,1,,\n");

        assertThat(r.get("batch").get("rowsLoaded").asInt()).isEqualTo(2);
        assertThat(r.get("batch").get("rowsRejected").asInt()).isZero();
        assertThat(currentLines()).isEqualTo(2);
    }

    @Test
    void aMissingRequiredColumnRefusesTheFile() throws Exception {
        client.uploadRaw(entity, "AR", "bad.csv", "doc_type,doc_number\nTAX_INVOICE,A1\n".getBytes())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("TEMPLATE_COLUMNS_MISSING"));
    }

    @Test
    void anUnknownColumnRefusesTheFileBecauseItMayBeATypo() throws Exception {
        client.uploadRaw(entity, "AR", "bad.csv", "doc_type,doc_number,doc_date,net_amount,vat_amount,vat_amont\nTAX_INVOICE,A1,2027-01-01,1,1,1\n".getBytes())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("TEMPLATE_COLUMNS_UNKNOWN"));
    }

    @Test
    void anUnsupportedFileTypeIsRefused() throws Exception {
        client.uploadRaw(entity, "AR", "data.pdf", "x".getBytes())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("FILE_TYPE_UNSUPPORTED"));
    }

    @Test
    void badRowsAreRejectedWithReasonsAndGoodRowsStillLoad() throws Exception {
        JsonNode r = client.uploadCsv(entity, "AR", HEAD
                + "TAX_INVOICE,GOOD,2027-01-05,100.00,5.00,S5,DXB-1,100000000000901,B2B,,1,,\n"
                + "TAX_INVOICE,BADAMT,2027-01-05,abc,5.00,S5,DXB-1,100000000000901,B2B,,1,,\n"
                + "TAX_INVOICE,BADDATE,05/01/2027,100.00,5.00,S5,DXB-1,100000000000901,B2B,,1,,\n"
                + "TAX_INVOICE,BADTRN,2027-01-05,100.00,5.00,S5,DXB-1,123,B2B,,1,,\n"
                + "NONSENSE,BADTYPE,2027-01-05,100.00,5.00,S5,DXB-1,100000000000901,B2B,,1,,\n"
                + "TAX_INVOICE,BADEST,2027-01-05,100.00,5.00,S5,NOPE,100000000000901,B2B,,1,,\n"
                + "TAX_INVOICE,TOOPRECISE,2027-01-05,100.001,5.00,S5,DXB-1,100000000000901,B2B,,1,,\n");

        assertThat(r.get("batch").get("rowsLoaded").asInt()).isEqualTo(1);
        assertThat(r.get("batch").get("rowsRejected").asInt()).isEqualTo(6);
        JsonNode rejected = client.get(preparer, "/api/entities/" + entity + "/ingest/batches/" + r.get("batch").get("id").asString() + "/rows?status=REJECTED");
        assertThat(rejected.toString()).contains("NET_AMOUNT_INVALID", "DOC_DATE_INVALID", "COUNTERPARTY_TRN_INVALID",
                "DOC_TYPE_INVALID", "ESTABLISHMENT_UNKNOWN", "NET_AMOUNT_TOO_PRECISE");
    }

    @Test
    void aTaxPointBeforeTheFirstPeriodIsRejected() throws Exception {
        JsonNode r = client.uploadCsv(entity, "AR", HEAD + "TAX_INVOICE,OLD,2026-12-31,100.00,5.00,S5,DXB-1,100000000000901,B2B,,1,,\n");

        assertThat(r.get("batch").get("rowsRejected").asInt()).isEqualTo(1);
    }

    @Test
    void reloadingAnIdenticalFileChangesNothing() throws Exception {
        byte[] file = (HEAD + "TAX_INVOICE,A1,2027-01-05,100.00,5.00,S5,DXB-1,100000000000901,B2B,,1,,\n").getBytes();
        client.upload(entity, "AR", "a.csv", file);
        long before = allLineRevisions();

        JsonNode again = client.upload(entity, "AR", "a-renamed.csv", file);

        assertThat(again.get("alreadyProcessed").asBoolean()).isTrue();
        assertThat(allLineRevisions()).isEqualTo(before);
    }

    @Test
    void aNewFileWithAnIdenticalRowLeavesThatLineAlone() throws Exception {
        client.uploadCsv(entity, "AR", HEAD + "TAX_INVOICE,A1,2027-01-05,100.00,5.00,S5,DXB-1,100000000000901,B2B,,1,,\n");

        JsonNode r = client.uploadCsv(entity, "AR", HEAD
                + "TAX_INVOICE,A1,2027-01-05,100.00,5.00,S5,DXB-1,100000000000901,B2B,,1,,\n"
                + "TAX_INVOICE,A2,2027-01-06,50.00,2.50,S5,DXB-1,100000000000901,B2B,,1,,\n");

        assertThat(r.get("batch").get("rowsUnchanged").asInt()).isEqualTo(1);
        assertThat(r.get("batch").get("rowsLoaded").asInt()).isEqualTo(1);
        assertThat(allLineRevisions()).isEqualTo(2);
    }

    @Test
    void aCorrectedRowAddsARevisionAndKeepsTheOldOne() throws Exception {
        client.uploadCsv(entity, "AR", HEAD + "TAX_INVOICE,A1,2027-01-05,100.00,5.00,S5,DXB-1,100000000000901,B2B,,1,,\n");

        client.uploadCsv(entity, "AR", HEAD + "TAX_INVOICE,A1,2027-01-05,120.00,6.00,S5,DXB-1,100000000000901,B2B,,1,,\n");

        assertThat(currentLines()).isEqualTo(1);
        assertThat(allLineRevisions()).isEqualTo(2);
        var current = world.queryInTenant(tenant, () -> jdbc.sql(
                "SELECT revision, net_aed FROM tax_line WHERE filer_entity_id = ? AND superseded_at IS NULL")
                .param(entity).query().singleRow());
        assertThat(current.get("revision")).isEqualTo(2);
        assertThat((java.math.BigDecimal) current.get("net_aed")).isEqualByComparingTo("120.00");
    }

    @Test
    void aPaymentUpdateChangesTheDocumentButAddsNoLineRevision() throws Exception {
        client.uploadCsv(entity, "AP", HEAD + "TAX_INVOICE,B1,2027-01-05,100.00,5.00,S5,DXB-1,100000000000801,,,1,,\n");

        JsonNode r = client.uploadCsv(entity, "AP", HEAD + "TAX_INVOICE,B1,2027-01-05,100.00,5.00,S5,DXB-1,100000000000801,,,1,2027-02-01,\n");

        assertThat(r.get("batch").get("rowsUnchanged").asInt()).isEqualTo(1);
        assertThat(allLineRevisions()).isEqualTo(1);
        var paid = world.queryInTenant(tenant, () -> jdbc.sql("SELECT paid_date FROM source_document WHERE doc_number = 'B1'").query().singleRow());
        assertThat(paid.get("paid_date").toString()).isEqualTo("2027-02-01");
    }

    @Test
    void twoDifferentRowsWithTheSameKeyInOneFileAreNotSilentlyMerged() throws Exception {
        JsonNode r = client.uploadCsv(entity, "AR", HEAD
                + "TAX_INVOICE,A1,2027-01-05,100.00,5.00,S5,DXB-1,100000000000901,B2B,,1,,\n"
                + "TAX_INVOICE,A1,2027-01-05,999.00,49.95,S5,DXB-1,100000000000901,B2B,,1,,\n");

        assertThat(r.get("batch").get("rowsLoaded").asInt()).isEqualTo(1);
        assertThat(r.get("batch").get("rowsRejected").asInt()).isEqualTo(1);
        var rows = client.get(preparer, "/api/entities/" + entity + "/ingest/batches/" + r.get("batch").get("id").asString() + "/rows?status=REJECTED");
        assertThat(rows.toString()).contains("DUPLICATE_KEY_CONFLICT");
    }

    @Test
    void anExactDuplicateRowInOneFileIsHarmless() throws Exception {
        JsonNode r = client.uploadCsv(entity, "AR", HEAD
                + "TAX_INVOICE,A1,2027-01-05,100.00,5.00,S5,DXB-1,100000000000901,B2B,,1,,\n"
                + "TAX_INVOICE,A1,2027-01-05,100.00,5.00,S5,DXB-1,100000000000901,B2B,,1,,\n");

        assertThat(r.get("batch").get("rowsLoaded").asInt()).isEqualTo(1);
        assertThat(r.get("batch").get("rowsUnchanged").asInt()).isEqualTo(1);
        assertThat(currentLines()).isEqualTo(1);
    }

    @Test
    void foreignCurrencyWithNoRateIsHeldThenLoadsWhenTheRateArrives() throws Exception {
        JsonNode r = client.uploadCsv(entity, "AR", HEAD + "TAX_INVOICE,U1,2027-01-05,100.00,5.00,S5,DXB-1,100000000000901,B2B,USD,1,,\n");
        assertThat(r.get("batch").get("rowsHeld").asInt()).isEqualTo(1);
        assertThat(currentLines()).isZero();

        client.uploadCsv(entity, "FX", "currency,rate_date,aed_per_unit\nUSD,2027-01-05,3.6725\n");

        assertThat(currentLines()).isEqualTo(1);
        var line = world.queryInTenant(tenant, () -> jdbc.sql(
                "SELECT net_aed, vat_aed, fx_rate, currency FROM tax_line WHERE filer_entity_id = ?").param(entity).query().singleRow());
        assertThat((java.math.BigDecimal) line.get("net_aed")).isEqualByComparingTo("367.25");
        assertThat((java.math.BigDecimal) line.get("vat_aed")).isEqualByComparingTo("18.36");
        assertThat((java.math.BigDecimal) line.get("fx_rate")).isEqualByComparingTo("3.6725");
    }

    @Test
    void anFxRateAlreadyLoadedIsNotOverwritten() throws Exception {
        client.uploadCsv(entity, "FX", "currency,rate_date,aed_per_unit\nEUR,2027-01-05,4.00\n");

        JsonNode r = client.uploadCsv(entity, "FX", "currency,rate_date,aed_per_unit\nEUR,2027-01-05,9.99\n");

        assertThat(r.get("batch").get("rowsUnchanged").asInt()).isEqualTo(1);
        var rate = world.queryInTenant(tenant, () -> jdbc.sql("SELECT aed_per_unit FROM fx_rate WHERE currency = 'EUR'").query(java.math.BigDecimal.class).single());
        assertThat(rate).isEqualByComparingTo("4.00");
    }

    @Test
    void oneTenantsFxRatesDoNotAffectAnother() throws Exception {
        client.uploadCsv(entity, "FX", "currency,rate_date,aed_per_unit\nGBP,2027-01-05,5.00\n");
        UUID other = world.newTenant("Other " + UUID.randomUUID());

        long seen = world.queryInTenant(other, () -> jdbc.sql("SELECT COUNT(*) FROM fx_rate").query(Long.class).single());

        assertThat(seen).isZero();
    }

    @Test
    void creditNotesReduceTaxAndArriveAsPositiveAmounts() throws Exception {
        client.uploadCsv(entity, "AR", HEAD + "CREDIT_NOTE,C1,2027-01-05,100.00,5.00,S5,DXB-1,100000000000901,B2B,,1,,\n");

        var line = world.queryInTenant(tenant, () -> jdbc.sql("SELECT net_aed, vat_aed FROM tax_line WHERE filer_entity_id = ?").param(entity).query().singleRow());
        assertThat((java.math.BigDecimal) line.get("net_aed")).isEqualByComparingTo("-100.00");
        assertThat((java.math.BigDecimal) line.get("vat_aed")).isEqualByComparingTo("-5.00");
    }

    @Test
    void aNegativeCreditNoteIsRejectedSoTheSignCannotBeAppliedTwice() throws Exception {
        JsonNode r = client.uploadCsv(entity, "AR", HEAD + "CREDIT_NOTE,C1,2027-01-05,-100.00,-5.00,S5,DXB-1,100000000000901,B2B,,1,,\n");

        assertThat(r.get("batch").get("rowsRejected").asInt()).isEqualTo(1);
    }

    @Test
    void aRowLandsInThePeriodOfItsTaxPoint() throws Exception {
        client.uploadCsv(entity, "AR", "doc_type,doc_number,doc_date,net_amount,vat_amount,tax_code,establishment_code,counterparty_trn,customer_type,payment_received_date\n"
                + "TAX_INVOICE,P1,2027-02-10,100.00,5.00,S5,DXB-1,100000000000901,B2B,2027-01-20\n");

        UUID january = client.periodId(entity, "2027-01-01");
        long inJanuary = world.queryInTenant(tenant, () -> jdbc.sql("SELECT COUNT(*) FROM tax_line WHERE period_id = ?").param(january).query(Long.class).single());

        // Payment came first, so it decides the tax point.
        assertThat(inJanuary).isEqualTo(1);
    }

    @Test
    void aPurchaseFollowsItsInvoiceDateBecauseInputTaxNeedsTheInvoiceHeld() throws Exception {
        client.uploadCsv(entity, "AP", "doc_type,doc_number,doc_date,net_amount,vat_amount,tax_code,establishment_code,counterparty_trn,supply_date\n"
                + "TAX_INVOICE,B1,2027-02-10,100.00,5.00,S5,DXB-1,100000000000801,2027-01-20\n");

        UUID february = client.periodId(entity, "2027-02-01");
        long inFebruary = world.queryInTenant(tenant, () -> jdbc.sql("SELECT COUNT(*) FROM tax_line WHERE period_id = ?").param(february).query(Long.class).single());

        assertThat(inFebruary).isEqualTo(1);
    }

    @Test
    void anExplicitTaxPointOverridesTheDefaultForAPurchase() throws Exception {
        client.uploadCsv(entity, "AP", "doc_type,doc_number,doc_date,net_amount,vat_amount,tax_code,establishment_code,counterparty_trn,tax_point_date\n"
                + "TAX_INVOICE,B1,2027-02-10,100.00,5.00,S5,DXB-1,100000000000801,2027-01-20\n");

        UUID january = client.periodId(entity, "2027-01-01");
        long inJanuary = world.queryInTenant(tenant, () -> jdbc.sql("SELECT COUNT(*) FROM tax_line WHERE period_id = ?").param(january).query(Long.class).single());

        assertThat(inJanuary).isEqualTo(1);
    }

    @Test
    void anXlsxUploadWorksIncludingRealDateCells() throws Exception {
        byte[] xlsx;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("AR");
            String[] head = {"doc_type", "doc_number", "doc_date", "net_amount", "vat_amount", "tax_code", "establishment_code", "counterparty_trn", "customer_type"};
            Row h = s.createRow(0);
            for (int i = 0; i < head.length; i++) {
                h.createCell(i).setCellValue(head[i]);
            }
            Row r = s.createRow(1);
            r.createCell(0).setCellValue("TAX_INVOICE");
            r.createCell(1).setCellValue("X1");
            var date = r.createCell(2);
            date.setCellValue(java.time.LocalDate.parse("2027-01-05"));
            var style = wb.createCellStyle();
            style.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("dd/mm/yyyy"));
            date.setCellStyle(style);
            r.createCell(3).setCellValue(100.5);
            r.createCell(4).setCellValue(5.03);
            r.createCell(5).setCellValue("S5");
            r.createCell(6).setCellValue("DXB-1");
            r.createCell(7).setCellValue("100000000000901");
            r.createCell(8).setCellValue("B2B");
            wb.write(out);
            xlsx = out.toByteArray();
        }

        JsonNode load = client.upload(entity, "AR", "ar.xlsx", xlsx);

        assertThat(load.get("batch").get("rowsLoaded").asInt()).as(load.toString()).isEqualTo(1);
        var doc = world.queryInTenant(tenant, () -> jdbc.sql("SELECT doc_date FROM source_document WHERE doc_number = 'X1'").query().singleRow());
        assertThat(doc.get("doc_date").toString()).isEqualTo("2027-01-05");
    }

    @Test
    void theRestApiLoadsRowsTheSameWayAndIsIdempotent() throws Exception {
        List<Map<String, String>> rows = List.of(Map.of(
                "doc_type", "TAX_INVOICE", "doc_number", "API1", "doc_date", "2027-01-05", "net_amount", "100.00", "vat_amount", "5.00",
                "tax_code", "S5", "establishment_code", "DXB-1", "counterparty_trn", "100000000000901", "customer_type", "B2B"));

        JsonNode first = client.post(preparer, "/api/entities/" + entity + "/ingest/AR/rows", rows);
        JsonNode second = client.post(preparer, "/api/entities/" + entity + "/ingest/AR/rows", rows);

        assertThat(first.get("batch").get("channel").asString()).isEqualTo("API");
        assertThat(first.get("batch").get("rowsLoaded").asInt()).isEqualTo(1);
        assertThat(second.get("alreadyProcessed").asBoolean()).isTrue();
    }

    @Test
    void customsDeclarationsLoadAsImportLines() throws Exception {
        client.uploadCsv(entity, "CUSTOMS", "doc_type,declaration_number,declaration_date,customs_value_aed,vat_aed\nIMPORT_DECLARATION,D1,2027-01-05,1000.00,50.00\n");

        var line = world.queryInTenant(tenant, () -> jdbc.sql("SELECT treatment, source_system, recoverable_vat_aed FROM tax_line WHERE filer_entity_id = ?").param(entity).query().singleRow());
        assertThat(line.get("treatment")).isEqualTo("IMPORT");
        assertThat(line.get("source_system")).isEqualTo("CUSTOMS");
        assertThat((java.math.BigDecimal) line.get("recoverable_vat_aed")).isEqualByComparingTo("50.00");
    }

    @Test
    void theRawFileIsKeptInLockedStorageAndMatchesWhatWasUploaded() throws Exception {
        byte[] file = (HEAD + "TAX_INVOICE,A1,2027-01-05,100.00,5.00,S5,DXB-1,100000000000901,B2B,,1,,\n").getBytes();

        JsonNode r = client.upload(entity, "AR", "a.csv", file);

        String key = r.get("batch").get("storageKey").asString();
        assertThat(world.queryInTenant(tenant, () -> vault.get(key))).isEqualTo(file);
        assertThat(vault.retention(key).mode()).isEqualTo("GOVERNANCE");
        assertThat(key).startsWith("t/" + tenant + "/");
    }

    @Test
    void lineageRunsFromABoxToTheExactSourceFileRow() throws Exception {
        client.uploadCsv(entity, "AR", HEAD
                + "TAX_INVOICE,A1,2027-01-05,100.00,5.00,S5,DXB-1,100000000000901,B2B,,1,,\n"
                + "TAX_INVOICE,A2,2027-01-06,200.00,10.00,S5,DXB-1,100000000000901,B2B,,1,,Second row\n");
        UUID period = client.periodId(entity, "2027-01-01");
        client.post(preparer, "/api/periods/" + period + "/recompute", null);

        JsonNode page = client.get(preparer, "/api/periods/" + period + "/boxes/1b/lines?size=10");
        assertThat(page.get("total").asInt()).isEqualTo(2);
        JsonNode second = page.get("rows").get(1);
        assertThat(second.get("docNumber").asString()).isEqualTo("A2");
        assertThat(second.get("sourceRowNo").asInt()).isEqualTo(2);

        JsonNode lineage = client.get(preparer, "/api/lines/" + second.get("line").get("id").asString() + "/lineage");

        assertThat(lineage.get("sourceRow").get("row_no").asInt()).isEqualTo(2);
        assertThat(lineage.get("sourceRow").get("raw").asString()).contains("Second row");
        assertThat(lineage.get("batch").get("file_name").asString()).startsWith("AR-");
        assertThat(lineage.get("batch").get("content_hash").asString()).hasSize(64);
    }

    @Test
    void drillDownOnACalculatedBoxPointsAtItsParts() throws Exception {
        UUID period = client.periodId(entity, "2027-01-01");

        client.getRaw(preparer, "/api/periods/" + period + "/boxes/14/lines")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("BOX_IS_COMPUTED"));
    }

    @Test
    void aVoidRowRemovesTheLineButKeepsItsHistory() throws Exception {
        client.uploadCsv(entity, "AR", HEAD + "TAX_INVOICE,V1,2027-01-05,100.00,5.00,S5,DXB-1,100000000000901,B2B,,1,,\n");

        client.uploadCsv(entity, "AR", "doc_type,doc_number,doc_date,net_amount,vat_amount,counterparty_trn,void\nTAX_INVOICE,V1,2027-01-05,100.00,5.00,100000000000901,true\n");

        assertThat(currentLines()).isZero();
        assertThat(allLineRevisions()).isEqualTo(1);
        var doc = world.queryInTenant(tenant, () -> jdbc.sql("SELECT voided_at FROM source_document WHERE doc_number = 'V1'").query().singleRow());
        assertThat(doc.get("voided_at")).isNotNull();
    }

    @Test
    void theLedgerRefusesEveryChangeExceptSupersedingACurrentLine() throws Exception {
        client.uploadCsv(entity, "AR", HEAD + "TAX_INVOICE,I1,2027-01-05,100.00,5.00,S5,DXB-1,100000000000901,B2B,,1,,\n");

        assertThatThrownBy(() -> world.inTenant(tenant, () -> jdbc.sql("UPDATE tax_line SET vat_aed = 1 WHERE filer_entity_id = ?").param(entity).update()))
                .rootCause().hasMessageContaining("immutable");
        assertThatThrownBy(() -> world.inTenant(tenant, () -> jdbc.sql("UPDATE tax_line SET vat_aed = 1, superseded_at = now() WHERE filer_entity_id = ?").param(entity).update()))
                .rootCause().hasMessageContaining("immutable");
        assertThatThrownBy(() -> world.inTenant(tenant, () -> jdbc.sql("DELETE FROM tax_line WHERE filer_entity_id = ?").param(entity).update()))
                .rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void loadingToAGroupMemberIsRefused() throws Exception {
        java.util.Map<String, Object> rep = new java.util.HashMap<>();
        rep.put("legalName", "Rep");
        rep.put("trn", TestClient.nextTrn());
        rep.put("entityKind", "GROUP_REPRESENTATIVE");
        rep.put("filingFrequency", "MONTHLY");
        rep.put("firstPeriodStart", "2027-01-01");
        String repId = client.post(admin, "/api/entities", rep).get("id").asString();
        java.util.Map<String, Object> member = new java.util.HashMap<>();
        member.put("legalName", "Member");
        member.put("entityKind", "GROUP_MEMBER");
        member.put("groupRepId", repId);
        String memberId = client.post(admin, "/api/entities", member).get("id").asString();
        TestUser memberPreparer = world.newUser(tenant, "CLIENT", "PREPARER");

        new TestClient(mvc, admin, memberPreparer).uploadRaw(UUID.fromString(memberId), "AR", "a.csv", (HEAD + "TAX_INVOICE,A1,2027-01-05,100.00,5.00,S5,,100000000000901,B2B,,1,,\n").getBytes())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("LOAD_TO_FILER"));
    }

    @Test
    void aUserWithoutEditRightsCannotLoad() throws Exception {
        TestUser reader = world.newUser(tenant, "EXTERNAL", "AUDITOR");

        new TestClient(mvc, admin, reader).uploadRaw(entity, "AR", "a.csv", HEAD.getBytes()).andExpect(status().isForbidden());
    }

    @Test
    void thePollerLoadsSftpDropsAndSetsAsideBadFiles(@TempDir Path inbox) throws Exception {
        Path dir = inbox.resolve(entity.toString()).resolve("AR");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("good.csv"), HEAD + "TAX_INVOICE,S1,2027-01-05,100.00,5.00,S5,DXB-1,100000000000901,B2B,,1,,\n");
        Files.writeString(dir.resolve("bad.csv"), "not,a,template\n1,2,3\n");
        Files.writeString(dir.resolve("partial.csv.part"), "still uploading");

        new SftpInboxPoller(sftpIngestion(), inbox.toString()).pollTenant(tenant, inbox);

        assertThat(currentLines()).isEqualTo(1);
        assertThat(dir.resolve("processed/good.csv")).exists();
        assertThat(dir.resolve("failed/bad.csv")).exists();
        assertThat(Files.readString(dir.resolve("failed/bad.csv.error.txt"))).contains("Missing required columns");
        assertThat(dir.resolve("partial.csv.part")).exists();
        var batch = world.queryInTenant(tenant, () -> jdbc.sql("SELECT channel, created_by FROM ingest_batch WHERE entity_id = ?").param(entity).query().singleRow());
        assertThat(batch.get("channel")).isEqualTo("SFTP");
        assertThat(batch.get("created_by")).isEqualTo("sftp-inbox");
    }

    @Autowired IngestionService ingestionService;

    private IngestionService sftpIngestion() {
        return ingestionService;
    }

    @Test
    void anAuditEventRecordsEachFileWithItsFingerprint() throws Exception {
        JsonNode r = client.uploadCsv(entity, "AR", HEAD + "TAX_INVOICE,A1,2027-01-05,100.00,5.00,S5,DXB-1,100000000000901,B2B,,1,,\n");

        String after = world.queryInTenant(tenant, () -> jdbc.sql("SELECT after_json FROM audit_event WHERE action = 'DATA_LOADED' AND object_id = ?")
                .param(r.get("batch").get("id").asString()).query(String.class).single());

        assertThat(after).contains(r.get("batch").get("contentHash").asString());
    }
}
