package ae.vattool.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ae.vattool.ingestion.einvoice.EinvoiceEvent;
import ae.vattool.ingestion.einvoice.EinvoiceStore;
import ae.vattool.platform.tenancy.RequestContext;
import ae.vattool.support.IntegrationTest;
import ae.vattool.support.TestClient;
import ae.vattool.support.TestWorld.TestUser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** Each check is shown failing on a real problem and passing once it is fixed. */
class ReconciliationTest extends IntegrationTest {

    private static final String AR_HEAD = "doc_type,doc_number,doc_date,net_amount,vat_amount,tax_code,establishment_code,counterparty_trn,customer_type,original_doc_number,posting_date,einvoice_uuid\n";
    private static final String AP_HEAD = "doc_type,doc_number,doc_date,net_amount,vat_amount,tax_code,establishment_code,counterparty_trn,has_tax_invoice,agreed_payment_date,paid_date,paid_amount,goods_or_services,description\n";
    private static final String CUSTOMER = "100000000000901";
    private static final String SUPPLIER = "100000000000801";

    @Autowired JdbcClient jdbc;
    @Autowired EinvoiceStore einvoices;

    UUID tenant;
    TestUser admin;
    TestUser preparer;
    TestUser reviewer;
    TestClient client;
    UUID entity;
    String trn;
    UUID january;

    @BeforeEach
    void setUp() throws Exception {
        tenant = world.newTenant("Recon firm " + UUID.randomUUID());
        admin = world.newUser(tenant, "FIRM", "FIRM_ADMIN");
        preparer = world.newUser(tenant, "CLIENT", "PREPARER");
        reviewer = world.newUser(tenant, "FIRM", "TAX_REVIEWER");
        client = new TestClient(mvc, admin, preparer);
        entity = client.simpleEntity();
        trn = client.get(admin, "/api/entities/" + entity).get("trn").asString();
        january = client.periodId(entity, "2027-01-01");
        // Control balances and a verified supplier are in place unless a test is about them.
        client.post(admin, "/api/counterparties", Map.of("trn", SUPPLIER, "name", "Good Supplier", "trnStatus", "ACTIVE"));
    }

    private static int eventSeq = 0;

    private void issued(UUID uuid, String number, String date, String net, String vat, String docType, String billingRef, String corner5) {
        event(EinvoiceEvent.ISSUED, uuid, new EinvoiceEvent.Invoice(docType, number, LocalDate.parse(date), trn, CUSTOMER, "AED", new BigDecimal(net), new BigDecimal(vat), billingRef), null, null);
        if (corner5 != null) {
            event(EinvoiceEvent.CORNER5, uuid, null, null, new EinvoiceEvent.Corner5(corner5));
        }
    }

    private void received(UUID uuid, String number, String date, String net, String vat) {
        event(EinvoiceEvent.RECEIVED, uuid, new EinvoiceEvent.Invoice("INVOICE", number, LocalDate.parse(date), SUPPLIER, trn, "AED", new BigDecimal(net), new BigDecimal(vat), null), null, null);
    }

    private synchronized void event(String type, UUID uuid, EinvoiceEvent.Invoice inv, EinvoiceEvent.Rejection rej, EinvoiceEvent.Corner5 c5) {
        EinvoiceEvent e = new EinvoiceEvent("1.0", UUID.randomUUID(), type, OffsetDateTime.parse("2027-01-31T08:00:00Z").plusSeconds(eventSeq++),
                tenant, trn, uuid, inv, rej, c5);
        RequestContext.asSystem(tenant, "test", () -> einvoices.apply(e));
    }

    private JsonNode run() throws Exception {
        return run(january);
    }

    private JsonNode run(UUID period) throws Exception {
        return client.post(preparer, "/api/periods/" + period + "/reconcile", null);
    }

    private List<JsonNode> open(String rule) throws Exception {
        return open(rule, january);
    }

    private List<JsonNode> open(String rule, UUID period) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode e : client.get(preparer, "/api/periods/" + period + "/exceptions")) {
            if (e.get("ruleId").asString().equals(rule) && List.of("OPEN", "ASSIGNED").contains(e.get("status").asString())) {
                out.add(e);
            }
        }
        return out;
    }

    private void sale(String number, String date, String net, String vat) throws Exception {
        client.uploadCsv(entity, "AR", AR_HEAD + "TAX_INVOICE," + number + "," + date + "," + net + "," + vat + ",S5,DXB-1," + CUSTOMER + ",B2B,,,\n");
    }

    @Test
    void r01FlagsASaleWithNoEinvoiceAndClearsWhenItIsReported() throws Exception {
        sale("A1", "2027-01-05", "100.00", "5.00");
        run();
        assertThat(open("R01")).hasSize(1);
        assertThat(open("R01").getFirst().get("message").asString()).contains("no e-invoice found");

        issued(UUID.randomUUID(), "A1", "2027-01-05", "100.00", "5.00", "INVOICE", null, "REPORTED");
        run();

        assertThat(open("R01")).isEmpty();
    }

    @Test
    void r01FlagsAnEinvoiceNotYetReportedToTheFta() throws Exception {
        sale("A1", "2027-01-05", "100.00", "5.00");
        issued(UUID.randomUUID(), "A1", "2027-01-05", "100.00", "5.00", "INVOICE", null, "FAILED");

        run();

        assertThat(open("R01").getFirst().get("message").asString()).contains("not reported to the FTA");
    }

    @Test
    void r01FlagsARejectedEinvoice() throws Exception {
        UUID uuid = UUID.randomUUID();
        sale("A1", "2027-01-05", "100.00", "5.00");
        issued(uuid, "A1", "2027-01-05", "100.00", "5.00", "INVOICE", null, "REPORTED");
        event(EinvoiceEvent.REJECTED, uuid, null, new EinvoiceEvent.Rejection("Bad TRN"), null);

        run();

        assertThat(open("R01").getFirst().get("message").asString()).contains("rejected");
    }

    @Test
    void r01IgnoresB2cSalesAndSalesBeforeGoLive() throws Exception {
        client.uploadCsv(entity, "AR", AR_HEAD + "B2C_SALE,R1,2027-01-05,100.00,5.00,S5,DXB-1,,B2C,,,\n");
        UUID later = client.simpleEntity("2027-07-01");
        client.uploadCsv(later, "AR", AR_HEAD + "TAX_INVOICE,A1,2027-01-05,100.00,5.00,S5,DXB-1," + CUSTOMER + ",B2B,,,\n");
        UUID laterPeriod = client.periodId(later, "2027-01-01");

        run();
        run(laterPeriod);

        assertThat(open("R01")).isEmpty();
        assertThat(open("R01", laterPeriod)).isEmpty();
    }

    @Test
    void r02FlagsAnEinvoiceThatIsNotInTheErp() throws Exception {
        issued(UUID.randomUUID(), "GHOST", "2027-01-05", "100.00", "5.00", "INVOICE", null, "REPORTED");

        run();

        assertThat(open("R02").getFirst().get("message").asString()).contains("GHOST", "not in the ERP");
    }

    @Test
    void r02FlagsAnEinvoicePostedInADifferentPeriod() throws Exception {
        UUID uuid = UUID.randomUUID();
        issued(uuid, "A1", "2027-01-05", "100.00", "5.00", "INVOICE", null, "REPORTED");
        // The ERP gives a February tax point to the invoice dated in January.
        client.uploadCsv(entity, "AR", "doc_type,doc_number,doc_date,net_amount,vat_amount,tax_code,establishment_code,counterparty_trn,customer_type,tax_point_date,einvoice_uuid\n"
                + "TAX_INVOICE,A1,2027-01-05,100.00,5.00,S5,DXB-1," + CUSTOMER + ",B2B,2027-02-10," + uuid + "\n");
        client.periodId(entity, "2027-02-01");

        run();

        assertThat(open("R02").getFirst().get("message").asString()).contains("different period");
    }

    @Test
    void r03AllowsOneDirhamOfRoundingAndFlagsMore() throws Exception {
        sale("OK", "2027-01-05", "100.00", "5.00");
        issued(UUID.randomUUID(), "OK", "2027-01-05", "100.50", "5.50", "INVOICE", null, "REPORTED");
        sale("BAD", "2027-01-06", "100.00", "5.00");
        issued(UUID.randomUUID(), "BAD", "2027-01-06", "200.00", "10.00", "INVOICE", null, "REPORTED");

        run();

        assertThat(open("R03")).hasSize(1);
        assertThat(open("R03").getFirst().get("message").asString()).contains("BAD");
    }

    @Test
    void r04WarnsAboutAReceivedEinvoiceNotInAp() throws Exception {
        received(UUID.randomUUID(), "SUP-1", "2027-01-05", "100.00", "5.00");

        run();

        assertThat(open("R04").getFirst().get("severity").asString()).isEqualTo("WARNING");
    }

    @Test
    void r05FlagsInputVatWithNoInvoiceAndClearsWithAReceivedEinvoice() throws Exception {
        client.uploadCsv(entity, "AP", AP_HEAD + "TAX_INVOICE,B1,2027-01-05,100.00,5.00,S5,DXB-1," + SUPPLIER + ",false,2027-02-05,,,SERVICES,\n");
        run();
        assertThat(open("R05")).hasSize(1);

        received(UUID.randomUUID(), "B1", "2027-01-05", "100.00", "5.00");
        run();

        assertThat(open("R05")).isEmpty();
    }

    @Test
    void r05AcceptsAPaperInvoiceThatIsHeld() throws Exception {
        client.uploadCsv(entity, "AP", AP_HEAD + "TAX_INVOICE,B1,2027-01-05,100.00,5.00,S5,DXB-1," + SUPPLIER + ",true,2027-02-05,,,SERVICES,\n");

        run();

        assertThat(open("R05")).isEmpty();
    }

    @Test
    void r06FlagsAMissingGlThenAnImbalanceThenAcceptsListedTimingItems() throws Exception {
        sale("A1", "2027-01-05", "10000.00", "500.00");
        issued(UUID.randomUUID(), "A1", "2027-01-05", "10000.00", "500.00", "INVOICE", null, "REPORTED");
        run();
        assertThat(open("R06").getFirst().get("message").asString()).contains("have not been loaded");

        client.uploadCsv(entity, "GL", "account_code,account_type,period_end,balance_aed\n2201,OUTPUT,2027-01-31,1500.00\n");
        run();
        assertThat(open("R06")).hasSize(1);
        assertThat(open("R06").getFirst().get("fingerprint").asString()).isEqualTo("OUTPUT");

        client.post(preparer, "/api/periods/" + january + "/timing-items", Map.of("accountType", "OUTPUT", "amountAed", "1000.00", "description", "December invoice posted in January"));
        run();
        assertThat(open("R06")).isEmpty();
    }

    @Test
    void r06HonoursTheTolerance() throws Exception {
        sale("A1", "2027-01-05", "10000.00", "500.00");
        client.uploadCsv(entity, "GL", "account_code,account_type,period_end,balance_aed\n2201,OUTPUT,2027-01-31,599.00\n");

        run();

        assertThat(open("R06")).isEmpty();
    }

    @Test
    void r07FlagsBoxSixWhenAReclassificationPullsItAwayFromCustoms() throws Exception {
        client.uploadCsv(entity, "CUSTOMS", "doc_type,declaration_number,declaration_date,customs_value_aed,vat_aed\nIMPORT_DECLARATION,D1,2027-01-05,1000.00,50.00\n");
        run();
        assertThat(open("R07")).isEmpty();

        JsonNode lines = client.get(preparer, "/api/periods/" + january + "/lines?treatment=IMPORT");
        client.post(preparer, "/api/periods/" + january + "/reclassify", Map.of("lineIds", List.of(lines.get("rows").get(0).get("id").asString()),
                "treatment", "OUT_OF_SCOPE", "reasonCode", "OTHER", "reason", "test"));
        run();

        assertThat(open("R07").getFirst().get("message").asString()).contains("customs declarations show 50.00");
    }

    @Test
    void r08WarnsAboutAnUnverifiedOrInactiveSupplier() throws Exception {
        String inactive = "100000000000888";
        String unknown = "100000000000889";
        client.post(admin, "/api/counterparties", Map.of("trn", inactive, "name", "Closed Co", "trnStatus", "INACTIVE"));
        client.uploadCsv(entity, "AP", AP_HEAD
                + "TAX_INVOICE,B1,2027-01-05,100.00,5.00,S5,DXB-1," + SUPPLIER + ",true,2027-02-05,,,SERVICES,\n"
                + "TAX_INVOICE,B2,2027-01-05,100.00,5.00,S5,DXB-1," + inactive + ",true,2027-02-05,,,SERVICES,\n"
                + "TAX_INVOICE,B3,2027-01-05,100.00,5.00,S5,DXB-1," + unknown + ",true,2027-02-05,,,SERVICES,\n");

        run();

        List<JsonNode> found = open("R08");
        assertThat(found).hasSize(2);
        assertThat(found.toString()).contains("marked inactive", "not verified").doesNotContain(SUPPLIER);
        assertThat(found.getFirst().get("details").asString()).contains("NOT_CHECKED");
    }

    @Test
    void r09WarnsAboutACreditNoteWithNoOriginal() throws Exception {
        sale("A1", "2027-01-05", "100.00", "5.00");
        client.uploadCsv(entity, "AR", AR_HEAD
                + "CREDIT_NOTE,C1,2027-01-06,10.00,0.50,S5,DXB-1," + CUSTOMER + ",B2B,A1,,\n"
                + "CREDIT_NOTE,C2,2027-01-06,10.00,0.50,S5,DXB-1," + CUSTOMER + ",B2B,NOPE,,\n"
                + "CREDIT_NOTE,C3,2027-01-06,10.00,0.50,S5,DXB-1," + CUSTOMER + ",B2B,,,\n");

        run();

        List<JsonNode> found = open("R09");
        assertThat(found).hasSize(2);
        assertThat(found.toString()).contains("C2", "C3").doesNotContain("\"C1");
    }

    @Test
    void r10FlagsTheSameInvoiceNumberTwiceFromOneSupplier() throws Exception {
        client.uploadCsv(entity, "AP", AP_HEAD
                + "TAX_INVOICE,DUP,2027-01-05,100.00,5.00,S5,DXB-1," + SUPPLIER + ",true,2027-02-05,,,SERVICES,\n"
                + "TAX_INVOICE,DUP,2027-01-09,100.00,5.00,S5,DXB-1," + SUPPLIER + ",true,2027-02-05,,,SERVICES,\n");

        run();

        assertThat(open("R10").getFirst().get("message").asString()).contains("DUP", "2 times");
    }

    @Test
    void r10FlagsDuplicateEinvoiceNumbers() throws Exception {
        issued(UUID.randomUUID(), "SAME", "2027-01-05", "100.00", "5.00", "INVOICE", null, "REPORTED");
        issued(UUID.randomUUID(), "SAME", "2027-01-06", "100.00", "5.00", "INVOICE", null, "REPORTED");

        run();

        assertThat(open("R10").toString()).contains("E-invoice number SAME");
    }

    @Test
    void r11FlagsAnInvoiceUnpaidSixMonthsPastItsAgreedDate() throws Exception {
        client.uploadCsv(entity, "AP", AP_HEAD + "TAX_INVOICE,OLD,2027-01-05,1000.00,50.00,S5,DXB-1," + SUPPLIER + ",true,2027-01-20,,,SERVICES,\n");
        UUID july = client.periodId(entity, "2027-07-01");
        UUID june = client.periodId(entity, "2027-06-01");

        run(june);
        run(july);

        // 20 Jan + 6 months is 20 Jul, which is inside July's period but not June's.
        assertThat(open("R11", june)).isEmpty();
        assertThat(open("R11", july).getFirst().get("message").asString()).contains("OLD", "50.00");
        assertThat(open("R11", july).getFirst().get("severity").asString()).isEqualTo("BLOCKING");
    }

    @Test
    void r11ClearsForAnInvoicePaidInTime() throws Exception {
        client.uploadCsv(entity, "AP", AP_HEAD + "TAX_INVOICE,PAID,2027-01-05,1000.00,50.00,S5,DXB-1," + SUPPLIER + ",true,2027-01-20,2027-03-01,,SERVICES,\n");
        UUID july = client.periodId(entity, "2027-07-01");

        run(july);

        assertThat(open("R11", july)).isEmpty();
    }

    @Test
    void r11ReversesOnlyTheUnpaidShareOfAPartPayment() throws Exception {
        client.uploadCsv(entity, "AP", AP_HEAD + "TAX_INVOICE,PART,2027-01-05,1000.00,50.00,S5,DXB-1," + SUPPLIER + ",true,2027-01-20,2027-03-01,525.00,SERVICES,\n");
        UUID july = client.periodId(entity, "2027-07-01");

        run(july);

        // Half of the 1,050 gross is paid, so half of the 50 input VAT is reversed.
        assertThat(open("R11", july).getFirst().get("message").asString()).contains("25.00");
    }

    @Test
    void r11ConsidersOpeningUnpaidInvoicesFromBeforeGoLive() throws Exception {
        client.uploadCsv(entity, "OPENING_AP", "doc_number,doc_date,agreed_payment_date,gross_aed,recoverable_vat_aed\nOPEN-1,2026-11-01,2026-12-01,10500.00,500.00\n");
        UUID june = client.periodId(entity, "2027-06-01");

        run(june);

        assertThat(open("R11", june).getFirst().get("message").asString()).contains("OPEN-1", "500.00");
    }

    @Test
    void r12WarnsAboutACutOffProblem() throws Exception {
        client.uploadCsv(entity, "AR", AR_HEAD + "TAX_INVOICE,LATE,2027-01-05,100.00,5.00,S5,DXB-1," + CUSTOMER + ",B2B,,2027-02-03,\n");

        run();

        assertThat(open("R12").getFirst().get("message").asString()).contains("LATE", "2027-02-03");
    }

    @Test
    void r13CatchesStandardRatedSalesWithNoEmirate() throws Exception {
        client.uploadCsv(entity, "AR", "doc_type,doc_number,doc_date,net_amount,vat_amount,tax_code,counterparty_trn,customer_type\nTAX_INVOICE,NOEST,2027-01-05,100.00,5.00,S5," + CUSTOMER + ",B2B\n");

        run();

        assertThat(open("R13").getFirst().get("message").asString()).contains("1 line with no emirate");
    }

    @Test
    void r14WarnsAboutALargeSwingUntilAPersonCommentsOnIt() throws Exception {
        sale("JAN", "2027-01-05", "1000.00", "50.00");
        sale("FEB", "2027-02-05", "2000.00", "100.00");
        UUID february = client.periodId(entity, "2027-02-01");
        run();
        run(february);
        List<JsonNode> found = open("R14", february);
        assertThat(found).isNotEmpty();
        assertThat(found.toString()).contains("100.00%");

        world.inTenant(tenant, () -> jdbc.sql("""
                INSERT INTO comment (id, tenant_id, entity_id, period_id, object_type, object_id, author_id, body)
                VALUES (?, ?, ?, ?, 'BOX', '1b', ?, 'One large order in February')
                """).params(UUID.randomUUID(), tenant, entity, february, preparer.id()).update());
        run(february);

        assertThat(open("R14", february)).isEmpty();
    }

    @Test
    void d01BlocksWhileRowsWaitForAnFxRate() throws Exception {
        client.uploadCsv(entity, "AR", "doc_type,doc_number,doc_date,net_amount,vat_amount,tax_code,establishment_code,counterparty_trn,customer_type,currency\nTAX_INVOICE,U1,2027-01-05,100.00,5.00,S5,DXB-1," + CUSTOMER + ",B2B,USD\n");
        run();
        assertThat(open("D01").getFirst().get("severity").asString()).isEqualTo("BLOCKING");

        client.uploadCsv(entity, "FX", "currency,rate_date,aed_per_unit\nUSD,2027-01-05,3.6725\n");
        run();

        assertThat(open("D01")).isEmpty();
    }

    @Test
    void d02BlocksOnRejectedRowsAndClearsWhenTheRowLoads() throws Exception {
        client.uploadCsv(entity, "AR", AR_HEAD + "TAX_INVOICE,R1,2027-01-05,100.00,5.00,S5,NOPE," + CUSTOMER + ",B2B,,,\n");
        run();
        assertThat(open("D02").getFirst().get("message").asString()).contains("1 row rejected", "ESTABLISHMENT_UNKNOWN");

        sale("R1", "2027-01-05", "100.00", "5.00");
        run();

        assertThat(open("D02")).isEmpty();
    }

    @Test
    void d03BlocksOnLinesWithNoTreatmentAndClearsWhenAPersonClassifiesThem() throws Exception {
        client.uploadCsv(entity, "AR", AR_HEAD + "TAX_INVOICE,U1,2027-01-05,100.00,5.00,UNMAPPED,DXB-1," + CUSTOMER + ",B2B,,,\n");
        run();
        assertThat(open("D03").getFirst().get("message").asString()).contains("no VAT treatment");

        JsonNode lines = client.get(preparer, "/api/periods/" + january + "/lines?treatment=UNCLASSIFIED");
        client.post(preparer, "/api/periods/" + january + "/reclassify", Map.of("lineIds", List.of(lines.get("rows").get(0).get("id").asString()),
                "treatment", "STANDARD", "reasonCode", "WRONG_TAX_CODE", "reason", "Local sale"));
        run();

        assertThat(open("D03")).isEmpty();
    }

    @Test
    void t01BlocksOnAConflictBetweenTaxCodeAndPlaceOfSupply() throws Exception {
        client.uploadCsv(entity, "AR", "doc_type,doc_number,doc_date,net_amount,vat_amount,tax_code,establishment_code,counterparty_country,customer_type,goods_or_services\nTAX_INVOICE,X1,2027-01-05,100.00,5.00,S5,DXB-1,US,B2B,GOODS\n");

        run();

        assertThat(open("T01").getFirst().get("severity").asString()).isEqualTo("BLOCKING");
    }

    @Test
    void t02WarnsAboutAPossibleDrcSupply() throws Exception {
        client.uploadCsv(entity, "AP", AP_HEAD + "TAX_INVOICE,G1,2027-01-05,100.00,5.00,S5,DXB-1," + SUPPLIER + ",true,2027-02-05,,,GOODS,Gold bars\n");

        run();

        assertThat(open("T02")).hasSize(1);
    }

    @Test
    void aFuzzyMatchIsOnlyAProposalUntilAPersonAcceptsIt() throws Exception {
        sale("INV-1001", "2027-01-05", "100.00", "5.00");
        UUID uuid = UUID.randomUUID();
        // Same customer and amount, one day apart, slightly different reference.
        issued(uuid, "INV1001", "2027-01-06", "100.00", "5.00", "INVOICE", null, "REPORTED");
        run();
        assertThat(open("R01")).hasSize(1);
        assertThat(open("R02")).hasSize(1);
        JsonNode proposals = client.get(preparer, "/api/periods/" + january + "/match-proposals");
        assertThat(proposals).hasSize(1);
        assertThat(new BigDecimal(proposals.get(0).get("score").asString())).isGreaterThan(new BigDecimal("0.7"));

        client.post(preparer, "/api/periods/" + january + "/match-proposals/" + proposals.get(0).get("id").asString(), Map.of("accept", true));

        assertThat(open("R01")).isEmpty();
        assertThat(open("R02")).isEmpty();
        var match = world.queryInTenant(tenant, () -> jdbc.sql("SELECT method, status, decided_by FROM doc_match WHERE invoice_uuid = ?").param(uuid).query().singleRow());
        assertThat(match.get("method")).isEqualTo("FUZZY");
        assertThat(match.get("status")).isEqualTo("CONFIRMED");
        assertThat(match.get("decided_by")).isEqualTo(preparer.id().toString());
    }

    @Test
    void aRejectedFuzzyMatchStaysRejectedOnTheNextRun() throws Exception {
        sale("INV-1001", "2027-01-05", "100.00", "5.00");
        issued(UUID.randomUUID(), "INV1001", "2027-01-06", "100.00", "5.00", "INVOICE", null, "REPORTED");
        run();
        JsonNode proposals = client.get(preparer, "/api/periods/" + january + "/match-proposals");

        client.post(preparer, "/api/periods/" + january + "/match-proposals/" + proposals.get(0).get("id").asString(), Map.of("accept", false));
        run();

        assertThat(client.get(preparer, "/api/periods/" + january + "/match-proposals")).isEmpty();
        assertThat(open("R01")).hasSize(1);
    }

    @Test
    void matchesStoreTheirMethodAndScore() throws Exception {
        UUID byUuid = UUID.randomUUID();
        client.uploadCsv(entity, "AR", AR_HEAD + "TAX_INVOICE,U1,2027-01-05,100.00,5.00,S5,DXB-1," + CUSTOMER + ",B2B,,," + byUuid + "\n");
        issued(byUuid, "DIFFERENT-NUMBER", "2027-01-05", "100.00", "5.00", "INVOICE", null, "REPORTED");
        sale("K1", "2027-01-06", "100.00", "5.00");
        UUID byKey = UUID.randomUUID();
        issued(byKey, "K1", "2027-01-06", "100.00", "5.00", "INVOICE", null, "REPORTED");

        run();

        var uuidMatch = world.queryInTenant(tenant, () -> jdbc.sql("SELECT method, score FROM doc_match WHERE invoice_uuid = ?").param(byUuid).query().singleRow());
        var keyMatch = world.queryInTenant(tenant, () -> jdbc.sql("SELECT method, score FROM doc_match WHERE invoice_uuid = ?").param(byKey).query().singleRow());
        assertThat(uuidMatch.get("method")).isEqualTo("UUID");
        assertThat((BigDecimal) uuidMatch.get("score")).isEqualByComparingTo("1");
        assertThat(keyMatch.get("method")).isEqualTo("KEY");
        assertThat((BigDecimal) keyMatch.get("score")).isEqualByComparingTo("0.99");
    }

    @Test
    void rerunningDoesNotDuplicateExceptions() throws Exception {
        sale("A1", "2027-01-05", "100.00", "5.00");

        run();
        run();
        run();

        assertThat(open("R01")).hasSize(1);
    }

    @Test
    void aWaivedExceptionStaysWaivedWhileItsFiguresAreUnchanged() throws Exception {
        sale("A1", "2027-01-05", "100.00", "5.00");
        run();
        String id = open("R01").getFirst().get("id").asString();

        client.post(reviewer, "/api/exceptions/" + id + "/waive", Map.of("reason", "Issued through a different channel"));
        run();

        assertThat(open("R01")).isEmpty();
        var row = world.queryInTenant(tenant, () -> jdbc.sql("SELECT status, waiver_reason, waiver_approver FROM recon_exception WHERE id = ?").param(UUID.fromString(id)).query().singleRow());
        assertThat(row.get("status")).isEqualTo("WAIVED");
        assertThat(row.get("waiver_approver")).isEqualTo(reviewer.id());
    }

    @Test
    void aWaiverLapsesWhenTheFiguresBehindItChange() throws Exception {
        sale("A1", "2027-01-05", "100.00", "5.00");
        issued(UUID.randomUUID(), "A1", "2027-01-05", "200.00", "10.00", "INVOICE", null, "REPORTED");
        run();
        String id = open("R03").getFirst().get("id").asString();
        client.post(reviewer, "/api/exceptions/" + id + "/waive", Map.of("reason", "Agreed with the client"));
        run();
        assertThat(open("R03")).isEmpty();

        // The ERP amount changes, so the waiver no longer describes what is wrong.
        sale("A1", "2027-01-05", "150.00", "7.50");
        run();

        assertThat(open("R03")).hasSize(1);
        assertThat(open("R03").getFirst().get("waiverReason").isNull()).isTrue();
    }

    @Test
    void aWaiverNeedsAReason() throws Exception {
        sale("A1", "2027-01-05", "100.00", "5.00");
        run();
        String id = open("R01").getFirst().get("id").asString();

        client.postRaw(reviewer, "/api/exceptions/" + id + "/waive", Map.of("reason", "  "))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("WAIVER_REASON_REQUIRED"));
    }

    @Test
    void aPreparerCannotWaive() throws Exception {
        sale("A1", "2027-01-05", "100.00", "5.00");
        run();
        String id = open("R01").getFirst().get("id").asString();

        client.postRaw(preparer, "/api/exceptions/" + id + "/waive", Map.of("reason", "Trust me")).andExpect(status().isForbidden());
    }

    @Test
    void theOwnerOfAnExceptionCannotWaiveIt() throws Exception {
        sale("A1", "2027-01-05", "100.00", "5.00");
        run();
        String id = open("R01").getFirst().get("id").asString();
        client.post(reviewer, "/api/exceptions/" + id + "/assign", Map.of("ownerId", reviewer.id()));

        client.postRaw(reviewer, "/api/exceptions/" + id + "/waive", Map.of("reason", "Mine to waive"))
                .andExpect(status().isForbidden());
    }

    @Test
    void assigningSetsTheOwnerAndStatus() throws Exception {
        sale("A1", "2027-01-05", "100.00", "5.00");
        run();
        String id = open("R01").getFirst().get("id").asString();

        JsonNode assigned = client.post(reviewer, "/api/exceptions/" + id + "/assign", Map.of("ownerId", preparer.id(), "dueDate", "2027-02-20"));

        assertThat(assigned.get("status").asString()).isEqualTo("ASSIGNED");
        assertThat(assigned.get("ownerId").asString()).isEqualTo(preparer.id().toString());
        assertThat(assigned.get("dueDate").asString()).isEqualTo("2027-02-20");
    }

    @Test
    void markingResolvedWithoutFixingTheDataReopensOnTheNextRun() throws Exception {
        sale("A1", "2027-01-05", "100.00", "5.00");
        run();
        String id = open("R01").getFirst().get("id").asString();

        client.post(preparer, "/api/exceptions/" + id + "/resolve", Map.of("resolutionCode", "FIXED_AT_SOURCE", "note", "Done"));
        assertThat(open("R01")).isEmpty();
        run();

        assertThat(open("R01")).hasSize(1);
    }

    @Test
    void theCertificateSaysPlainlyWhetherThePeriodReconciled() throws Exception {
        sale("A1", "2027-01-05", "100.00", "5.00");
        run();
        JsonNode dirty = client.get(preparer, "/api/periods/" + january + "/certificate");
        assertThat(dirty.get("conclusion").asString()).isEqualTo("NOT_RECONCILED");

        for (JsonNode e : client.get(preparer, "/api/periods/" + january + "/exceptions?severity=BLOCKING")) {
            client.post(reviewer, "/api/exceptions/" + e.get("id").asString() + "/waive", Map.of("reason", "For this test"));
        }
        JsonNode waived = client.get(preparer, "/api/periods/" + january + "/certificate");

        assertThat(waived.get("conclusion").asString()).isEqualTo("RECONCILED_WITH_WAIVERS");
        assertThat(waived.get("waivers").size()).isPositive();
    }

    @Test
    void theCertificateReportsTheVatVarianceAgainstCornerFive() throws Exception {
        sale("A1", "2027-01-05", "1000.00", "50.00");
        issued(UUID.randomUUID(), "A1", "2027-01-05", "1000.00", "50.40", "INVOICE", null, "REPORTED");
        sale("A2", "2027-01-06", "100.00", "5.00");
        issued(UUID.randomUUID(), "A2", "2027-01-06", "100.00", "5.00", "INVOICE", null, "FAILED");
        run();

        JsonNode c5 = client.get(preparer, "/api/periods/" + january + "/certificate").get("corner5");

        assertThat(c5.get("issuedInvoices").asInt()).isEqualTo(2);
        assertThat(c5.get("reportedToFta").asInt()).isEqualTo(1);
        assertThat(c5.get("notReported").asInt()).isEqualTo(1);
        assertThat(new BigDecimal(c5.get("vatVariance").asString())).isEqualByComparingTo("-0.40");
    }

    @Test
    void outOfOrderEinvoiceEventsReachTheSameResult() throws Exception {
        UUID uuid = UUID.randomUUID();
        OffsetDateTime t = OffsetDateTime.parse("2027-01-31T08:00:00Z");
        EinvoiceEvent status = new EinvoiceEvent("1.0", UUID.randomUUID(), EinvoiceEvent.CORNER5, t.plusSeconds(5), tenant, trn, uuid, null, null, new EinvoiceEvent.Corner5("REPORTED"));
        EinvoiceEvent invoice = new EinvoiceEvent("1.0", UUID.randomUUID(), EinvoiceEvent.ISSUED, t, tenant, trn, uuid,
                new EinvoiceEvent.Invoice("INVOICE", "OOO", LocalDate.parse("2027-01-05"), trn, CUSTOMER, "AED", new BigDecimal("100.00"), new BigDecimal("5.00"), null), null, null);
        EinvoiceEvent older = new EinvoiceEvent("1.0", UUID.randomUUID(), EinvoiceEvent.CORNER5, t.plusSeconds(1), tenant, trn, uuid, null, null, new EinvoiceEvent.Corner5("PENDING"));

        RequestContext.asSystem(tenant, "test", () -> {
            einvoices.apply(status);
            einvoices.apply(invoice);
            einvoices.apply(older);
        });

        var row = world.queryInTenant(tenant, () -> jdbc.sql("SELECT corner5_status, number, has_invoice_data FROM einvoice WHERE invoice_uuid = ?").param(uuid).query().singleRow());
        assertThat(row.get("corner5_status")).isEqualTo("REPORTED");
        assertThat(row.get("number")).isEqualTo("OOO");
        assertThat(row.get("has_invoice_data")).isEqualTo(true);
    }

    @Test
    void aRepeatedEinvoiceEventIsIgnored() throws Exception {
        UUID uuid = UUID.randomUUID();
        EinvoiceEvent e = new EinvoiceEvent("1.0", UUID.randomUUID(), EinvoiceEvent.ISSUED, OffsetDateTime.now(), tenant, trn, uuid,
                new EinvoiceEvent.Invoice("INVOICE", "R1", LocalDate.parse("2027-01-05"), trn, CUSTOMER, "AED", new BigDecimal("100.00"), new BigDecimal("5.00"), null), null, null);

        EinvoiceStore.Applied first = RequestContext.asSystem(tenant, "test", () -> einvoices.apply(e));
        EinvoiceStore.Applied second = RequestContext.asSystem(tenant, "test", () -> einvoices.apply(e));

        assertThat(first).isEqualTo(EinvoiceStore.Applied.APPLIED);
        assertThat(second).isEqualTo(EinvoiceStore.Applied.DUPLICATE);
    }

    @Test
    void reclassifyingNeedsAReasonAndIsAudited() throws Exception {
        sale("A1", "2027-01-05", "100.00", "5.00");
        String lineId = client.get(preparer, "/api/periods/" + january + "/lines").get("rows").get(0).get("id").asString();

        client.postRaw(preparer, "/api/periods/" + january + "/reclassify", Map.of("lineIds", List.of(lineId), "treatment", "EXEMPT", "reasonCode", "WRONG_TAX_CODE", "reason", " "))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
        client.postRaw(preparer, "/api/periods/" + january + "/reclassify", Map.of("lineIds", List.of(lineId), "treatment", "EXEMPT", "reasonCode", "BECAUSE", "reason", "x"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("REASON_CODE_INVALID"));
        client.post(preparer, "/api/periods/" + january + "/reclassify", Map.of("lineIds", List.of(lineId), "treatment", "EXEMPT", "reasonCode", "WRONG_TAX_CODE", "reason", "Residential"));

        var audit = world.queryInTenant(tenant, () -> jdbc.sql("SELECT actor_id, reason, after_json FROM audit_event WHERE action = 'LINES_RECLASSIFIED'").query().singleRow());
        assertThat(audit.get("actor_id")).isEqualTo(preparer.id().toString());
        assertThat(audit.get("reason")).isEqualTo("Residential");
        assertThat((String) audit.get("after_json")).contains("STANDARD", lineId);
        var line = client.get(preparer, "/api/periods/" + january + "/lines").get("rows").get(0);
        assertThat(line.get("treatment").asString()).isEqualTo("EXEMPT");
        assertThat(line.get("classifiedBy").asString()).isEqualTo("USER");
        assertThat(line.get("revision").asInt()).isEqualTo(2);
    }

    @Test
    void aPersonsReclassificationSurvivesReloadingTheSameData() throws Exception {
        sale("A1", "2027-01-05", "100.00", "5.00");
        String lineId = client.get(preparer, "/api/periods/" + january + "/lines").get("rows").get(0).get("id").asString();
        client.post(preparer, "/api/periods/" + january + "/reclassify", Map.of("lineIds", List.of(lineId), "treatment", "EXEMPT", "reasonCode", "WRONG_TAX_CODE", "reason", "Residential"));

        // A later ERP extract repeats the same row inside a different file.
        client.uploadCsv(entity, "AR", AR_HEAD + "TAX_INVOICE,A1,2027-01-05,100.00,5.00,S5,DXB-1," + CUSTOMER + ",B2B,,,\nTAX_INVOICE,A2,2027-01-06,1.00,0.05,S5,DXB-1," + CUSTOMER + ",B2B,,,\n");

        var line = client.get(preparer, "/api/periods/" + january + "/lines?treatment=EXEMPT").get("rows").get(0);
        assertThat(line.get("classifiedBy").asString()).isEqualTo("USER");
    }

    @Test
    void newBoxValuesCreateANumberedVersionWithADiff() throws Exception {
        sale("A1", "2027-01-05", "100.00", "5.00");
        client.post(preparer, "/api/periods/" + january + "/recompute", null);
        sale("A2", "2027-01-06", "200.00", "10.00");
        client.post(preparer, "/api/periods/" + january + "/recompute", null);

        JsonNode versions = client.get(preparer, "/api/periods/" + january + "/versions");

        assertThat(versions.get(0).get("versionNo").asInt()).isEqualTo(2);
        assertThat(versions.get(0).get("diff").asString().replace(" ", "")).contains("\"box\":\"1b\"", "\"from\":\"100.00\"", "\"to\":\"300.00\"");
        assertThat(versions.get(1).get("versionNo").asInt()).isEqualTo(1);
    }
}
