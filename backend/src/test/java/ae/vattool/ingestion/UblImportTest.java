package ae.vattool.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ae.vattool.ingestion.ubl.UblParser;
import ae.vattool.platform.web.DomainException;
import ae.vattool.support.IntegrationTest;
import ae.vattool.support.Scenario;
import ae.vattool.support.TestClient;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;

class UblImportTest extends IntegrationTest {

    private static String invoice(String root, String typeTag, String type, String uuid, String number, String supplier, String buyer, String net, String vat, String extra) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <%s xmlns="urn:oasis:names:specification:ubl:schema:xsd:%s-2"
                     xmlns:cac="urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2"
                     xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2">
                  <cbc:ID>%s</cbc:ID>
                  <cbc:UUID>%s</cbc:UUID>
                  <cbc:IssueDate>2027-01-10</cbc:IssueDate>
                  <cbc:%s>%s</cbc:%s>
                  <cbc:DocumentCurrencyCode>AED</cbc:DocumentCurrencyCode>
                  %s
                  <cac:AccountingSupplierParty><cac:Party>
                    <cbc:EndpointID schemeID="0235">%s</cbc:EndpointID>
                    <cac:PartyTaxScheme><cbc:CompanyID>%s</cbc:CompanyID><cac:TaxScheme><cbc:ID>VAT</cbc:ID></cac:TaxScheme></cac:PartyTaxScheme>
                  </cac:Party></cac:AccountingSupplierParty>
                  <cac:AccountingCustomerParty><cac:Party>
                    <cac:PartyTaxScheme><cbc:CompanyID>%s</cbc:CompanyID><cac:TaxScheme><cbc:ID>VAT</cbc:ID></cac:TaxScheme></cac:PartyTaxScheme>
                  </cac:Party></cac:AccountingCustomerParty>
                  <cac:TaxTotal><cbc:TaxAmount currencyID="AED">%s</cbc:TaxAmount></cac:TaxTotal>
                  <cac:LegalMonetaryTotal><cbc:TaxExclusiveAmount currencyID="AED">%s</cbc:TaxExclusiveAmount></cac:LegalMonetaryTotal>
                </%s>
                """.formatted(root, root, number, uuid, typeTag, type, typeTag, extra, supplier.substring(0, 10), supplier, buyer, vat, net, root);
    }

    Scenario s;
    UUID entity;
    String trn;

    @BeforeEach
    void setUp() throws Exception {
        s = new Scenario(mvc, world);
        entity = s.client.simpleEntity("2027-01-01");
        trn = s.client.get(s.admin, "/api/entities/" + entity).get("trn").asString();
    }

    private JsonNode upload(String name, byte[] content) throws Exception {
        return TestClient.read(mvc.perform(multipart("/api/entities/" + entity + "/einvoices/ubl")
                .file(new MockMultipartFile("file", name, "application/xml", content)).with(s.preparer.auth())).andExpect(status().isOk()));
    }

    private JsonNode einvoices() throws Exception {
        return s.client.get(s.preparer, "/api/entities/" + entity + "/einvoices?from=2027-01-01&to=2027-01-31");
    }

    private String sale(String uuid, String number, String net, String vat) {
        return invoice("Invoice", "InvoiceTypeCode", "380", uuid, number, trn, Scenario.CUSTOMER, net, vat, "");
    }

    @Test
    void anIssuedInvoiceJoinsTheReconciliationSet() throws Exception {
        String uuid = UUID.randomUUID().toString();

        JsonNode out = upload("inv.xml", sale(uuid, "INV-1", "1000.00", "50.00").getBytes(StandardCharsets.UTF_8));

        assertThat(out.get("batch").get("rowsLoaded").asInt()).isEqualTo(1);
        JsonNode e = einvoices().get(0);
        assertThat(e.get("invoiceUuid").asString()).isEqualTo(uuid);
        assertThat(e.get("direction").asString()).isEqualTo("ISSUED");
        assertThat(e.get("netAed").asDouble()).isEqualTo(1000.0);
        assertThat(e.get("corner5Status").asString()).isEqualTo("PENDING");
    }

    @Test
    void anInvoiceWhereThisEntityIsTheBuyerIsReceived() throws Exception {
        upload("in.xml", invoice("Invoice", "InvoiceTypeCode", "380", UUID.randomUUID().toString(), "S-9", Scenario.SUPPLIER, trn, "200.00", "10.00", "").getBytes(StandardCharsets.UTF_8));

        assertThat(einvoices().get(0).get("direction").asString()).isEqualTo("RECEIVED");
    }

    @Test
    void aCreditNoteIsHeldNegativeSoItMatchesTheErp() throws Exception {
        String xml = invoice("CreditNote", "CreditNoteTypeCode", "381", UUID.randomUUID().toString(), "CN-1", trn, Scenario.CUSTOMER, "100.00", "5.00",
                "<cac:BillingReference><cac:InvoiceDocumentReference><cbc:ID>INV-1</cbc:ID></cac:InvoiceDocumentReference></cac:BillingReference>");

        upload("cn.xml", xml.getBytes(StandardCharsets.UTF_8));

        JsonNode e = einvoices().get(0);
        assertThat(e.get("docType").asString()).isEqualTo("CREDIT_NOTE");
        assertThat(e.get("netAed").asDouble()).isEqualTo(-100.0);
        assertThat(e.get("billingReference").asString()).isEqualTo("INV-1");
    }

    @Test
    void aZipIsLoadedDocumentByDocumentAndABadOneOnlyRejectsItself() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bytes)) {
            z.putNextEntry(new ZipEntry("a.xml"));
            z.write(sale(UUID.randomUUID().toString(), "A", "100.00", "5.00").getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("broken.xml"));
            z.write("<not-closed".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("readme.txt"));
            z.write("ignored".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }

        JsonNode batch = upload("all.zip", bytes.toByteArray()).get("batch");

        assertThat(batch.get("rowsTotal").asInt()).isEqualTo(2);
        assertThat(batch.get("rowsLoaded").asInt()).isEqualTo(1);
        assertThat(batch.get("rowsRejected").asInt()).isEqualTo(1);
    }

    @Test
    void reloadingTheSameFileChangesNothing() throws Exception {
        byte[] xml = sale(UUID.randomUUID().toString(), "INV-1", "1000.00", "50.00").getBytes(StandardCharsets.UTF_8);

        upload("inv.xml", xml);

        assertThat(upload("inv.xml", xml).get("alreadyProcessed").asBoolean()).isTrue();
        assertThat(einvoices()).hasSize(1);
    }

    @Test
    void theSameUuidWithDifferentFiguresIsRefusedAndNotOverwritten() throws Exception {
        String uuid = UUID.randomUUID().toString();
        upload("inv.xml", sale(uuid, "INV-1", "1000.00", "50.00").getBytes(StandardCharsets.UTF_8));

        JsonNode batch = upload("changed.xml", sale(uuid, "INV-1", "9000.00", "450.00").getBytes(StandardCharsets.UTF_8)).get("batch");

        assertThat(batch.get("rowsRejected").asInt()).isEqualTo(1);
        assertThat(einvoices().get(0).get("netAed").asDouble()).isEqualTo(1000.0);
    }

    @Test
    void anInvoiceForSomeoneElseIsRejected() throws Exception {
        JsonNode batch = upload("x.xml", invoice("Invoice", "InvoiceTypeCode", "380", UUID.randomUUID().toString(), "X", Scenario.SUPPLIER, Scenario.CUSTOMER, "1.00", "0.05", "").getBytes(StandardCharsets.UTF_8)).get("batch");

        assertThat(batch.get("rowsRejected").asInt()).isEqualTo(1);
        JsonNode rows = s.client.get(s.preparer, "/api/entities/" + entity + "/ingest/batches/" + batch.get("id").asString() + "/rows?status=REJECTED");
        assertThat(rows.get(0).get("errors").toString()).contains("UBL_TRN_NOT_THIS_ENTITY");
    }

    @Test
    void aBuyerGivenAsTheTenDigitTinIsRecognised() throws Exception {
        upload("tin.xml", invoice("Invoice", "InvoiceTypeCode", "380", UUID.randomUUID().toString(), "T-1", Scenario.SUPPLIER, trn.substring(0, 10), "10.00", "0.50", "").getBytes(StandardCharsets.UTF_8));

        assertThat(einvoices().get(0).get("direction").asString()).isEqualTo("RECEIVED");
    }

    @Test
    void itCanArriveByApiAsXml() throws Exception {
        mvc.perform(post("/api/entities/" + entity + "/einvoices/ubl").with(s.preparer.auth()).contentType("application/xml")
                .content(sale(UUID.randomUUID().toString(), "API-1", "1.00", "0.05"))).andExpect(status().isOk());

        assertThat(einvoices().get(0).get("number").asString()).isEqualTo("API-1");
    }

    @Test
    void corner5StatusFromTheOtherAspClearsCheckR01() throws Exception {
        String uuid = UUID.randomUUID().toString();
        s.client.uploadCsv(entity, "AR", "doc_type,doc_number,doc_date,net_amount,vat_amount,tax_code,establishment_code,counterparty_trn,customer_type,einvoice_uuid\n"
                + "TAX_INVOICE,INV-1,2027-01-10,1000.00,50.00,S5,DXB-1," + Scenario.CUSTOMER + ",B2B," + uuid + "\n");
        upload("inv.xml", sale(uuid, "INV-1", "1000.00", "50.00").getBytes(StandardCharsets.UTF_8));
        UUID period = s.client.periodId(entity, "2027-01-01");
        s.client.post(s.preparer, "/api/periods/" + period + "/reconcile", null);
        assertThat(s.client.get(s.preparer, "/api/periods/" + period + "/exceptions?status=OPEN").toString()).contains("R01");

        JsonNode applied = s.client.post(s.preparer, "/api/entities/" + entity + "/einvoices/corner5-status", List.of(Map.of("invoiceUuid", uuid, "status", "REPORTED")));

        assertThat(applied.get("applied").asInt()).isEqualTo(1);
        s.client.post(s.preparer, "/api/periods/" + period + "/reconcile", null);
        assertThat(s.client.get(s.preparer, "/api/periods/" + period + "/exceptions?status=OPEN").toString()).doesNotContain("\"ruleId\":\"R01\"");
    }

    @Test
    void aCorner5StatusEnteredByHandCanBeCorrectedAndCorrectedBack() throws Exception {
        String uuid = UUID.randomUUID().toString();
        upload("one.xml", sale(uuid, "INV-BACK", "1000.00", "50.00").getBytes(StandardCharsets.UTF_8));

        for (String status : List.of("REPORTED", "FAILED", "REPORTED")) {
            s.client.post(s.preparer, "/api/entities/" + entity + "/einvoices/corner5-status", List.of(Map.of("invoiceUuid", uuid, "status", status)));
            assertThat(einvoices().get(0).get("corner5Status").asString()).isEqualTo(status);
        }
    }

    @Test
    void anUnknownCorner5StatusIsRefused() throws Exception {
        s.client.postRaw(s.preparer, "/api/entities/" + entity + "/einvoices/corner5-status", List.of(Map.of("invoiceUuid", UUID.randomUUID().toString(), "status", "MAYBE")))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void aDocumentInAnotherCurrencyNeedsItsAedFigures() {
        String usd = invoice("Invoice", "InvoiceTypeCode", "380", UUID.randomUUID().toString(), "U-1", Scenario.SUPPLIER, Scenario.CUSTOMER, "100.00", "5.00", "")
                .replace("<cbc:DocumentCurrencyCode>AED", "<cbc:DocumentCurrencyCode>USD").replace("currencyID=\"AED\"", "currencyID=\"USD\"");

        assertThatThrownBy(() -> UblParser.parse(usd.getBytes(StandardCharsets.UTF_8))).isInstanceOf(DomainException.class).hasMessageContaining("exchange rate");

        String withRate = usd.replace("<cac:LegalMonetaryTotal>", """
                <cac:TaxTotal><cbc:TaxAmount currencyID="AED">18.36</cbc:TaxAmount></cac:TaxTotal>
                <cac:TaxExchangeRate><cbc:CalculationRate>3.6725</cbc:CalculationRate></cac:TaxExchangeRate>
                <cac:LegalMonetaryTotal>""");
        UblParser.Invoice parsed = UblParser.parse(withRate.getBytes(StandardCharsets.UTF_8));
        assertThat(parsed.netAed()).isEqualByComparingTo("367.25");
        assertThat(parsed.vatAed()).isEqualByComparingTo("18.36");
    }

    @Test
    void anExternalEntityInTheXmlIsRefusedNotFetched() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE Invoice [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><Invoice><ID>&x;</ID></Invoice>";

        assertThatThrownBy(() -> UblParser.parse(xxe.getBytes(StandardCharsets.UTF_8))).isInstanceOf(DomainException.class);
    }
}
