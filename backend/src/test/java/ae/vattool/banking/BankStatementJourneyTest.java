package ae.vattool.banking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ae.vattool.support.IntegrationTest;
import ae.vattool.support.Scenario;
import ae.vattool.support.TestClient;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;

class BankStatementJourneyTest extends IntegrationTest {

    private static final String GIBAN = "AE99FTA0000000001";

    Scenario s;
    UUID entity;
    UUID period;

    @BeforeEach
    void setUp() throws Exception {
        s = new Scenario(mvc, world);
        entity = s.cleanEntity();
        period = s.cleanJanuary(entity, "10000.00", "500.00");
        putJson(s.admin, "/api/entities/" + entity + "/settings", Map.of("ftaGiban", GIBAN)).andExpect(status().is2xxSuccessful());
    }

    private JsonNode upload(String content) throws Exception {
        return TestClient.read(mvc.perform(multipart("/api/entities/" + entity + "/bank-statements")
                .file(new MockMultipartFile("file", "stmt.sta", "text/plain", content.getBytes(StandardCharsets.UTF_8))).with(s.reviewer.auth())).andExpect(status().isOk()));
    }

    private static String statement(String lines) {
        return ":20:S\n:25:AE07\n:60F:C270101AED100000,00\n" + lines + ":62F:C270331AED100000,00\n-\n";
    }

    @Test
    void aFiledReturnIsSettledByAStatementWithTheGibanAndTheAmount() throws Exception {
        s.toFiled(entity, period);

        JsonNode out = upload(statement(":61:2702250225D500,00NTRFVAT\n:86:FTA " + GIBAN + " VAT JAN\n"));

        assertThat(out.get("returnsSettled").asInt()).isEqualTo(1);
        assertThat(s.client.get(s.preparer, "/api/periods/" + period).get("state").asString()).isEqualTo("PAID");
        JsonNode position = s.client.get(s.reviewer, "/api/periods/" + period + "/filing");
        assertThat(position.get("payments").get(0).get("matchStatus").asString()).isEqualTo("MATCHED");
        assertThat(position.get("payments").get(0).get("paidOn").asString()).isEqualTo("2027-02-25");
        assertThat(new BigDecimal(position.get("outstanding").asString())).isEqualByComparingTo("0");
    }

    @Test
    void aMatchingAmountWithoutTheGibanWaitsForAPerson() throws Exception {
        s.toFiled(entity, period);

        JsonNode out = upload(statement(":61:2702250225D500,00NTRFVAT\n:86:SOME PAYMENT\n"));

        assertThat(out.get("returnsSettled").asInt()).isZero();
        assertThat(s.client.get(s.preparer, "/api/periods/" + period).get("state").asString()).isEqualTo("FILED");
        JsonNode candidates = s.client.get(s.reviewer, "/api/periods/" + period + "/bank-candidates");
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).get("amountIsDue").asBoolean()).isTrue();
        assertThat(candidates.get(0).get("ibanInText").asBoolean()).isFalse();

        s.client.post(s.reviewer, "/api/periods/" + period + "/payment/from-bank", Map.of("bankTxnId", candidates.get(0).get("txn").get("id").asString()));

        assertThat(s.client.get(s.preparer, "/api/periods/" + period).get("state").asString()).isEqualTo("PAID");
    }

    @Test
    void oneBankLineCannotSettleTwoReturns() throws Exception {
        s.toFiled(entity, period);
        upload(statement(":61:2702250225D500,00NTRFVAT\n:86:SOME PAYMENT\n"));
        String txn = s.client.get(s.reviewer, "/api/periods/" + period + "/bank-candidates").get(0).get("txn").get("id").asString();
        s.client.post(s.reviewer, "/api/periods/" + period + "/payment/from-bank", Map.of("bankTxnId", txn));

        s.client.uploadCsv(entity, "AR", Scenario.AR_HEAD + "TAX_INVOICE,FEB,2027-02-10,10000.00,500.00,S5,DXB-1," + Scenario.CUSTOMER + ",B2B\n");
        s.client.uploadCsv(entity, "GL", "account_code,account_type,period_end,balance_aed\n2201,OUTPUT,2027-02-28,500.00\n");
        UUID feb = s.client.periodId(entity, "2027-02-01");
        s.toFiled(entity, feb);

        s.client.postRaw(s.reviewer, "/api/periods/" + feb + "/payment/from-bank", Map.of("bankTxnId", txn))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BANK_TXN_USED"));
    }

    @Test
    void anOverpaymentAndACreditAreRefused() throws Exception {
        s.toFiled(entity, period);
        upload(statement(":61:2702250225D900,00NTRFA\n:86:TOO MUCH\n:61:2702260226C500,00NTRFB\n:86:MONEY IN\n"));
        JsonNode statements = s.client.get(s.reviewer, "/api/entities/" + entity + "/bank-statements");
        JsonNode txns = s.client.get(s.reviewer, "/api/entities/" + entity + "/bank-statements/" + statements.get(0).get("id").asString() + "/transactions");

        for (JsonNode t : txns) {
            s.client.postRaw(s.reviewer, "/api/periods/" + period + "/payment/from-bank", Map.of("bankTxnId", t.get("id").asString()))
                    .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("BANK_TXN_MISMATCH"));
        }
    }

    @Test
    void reloadingAStatementChangesNothingAndAnOverlapCountsOnce() throws Exception {
        String one = statement(":61:2702250225D10,00NTRFA\n:86:ONE\n");
        String overlap = ":20:S2\n:25:AE07\n:60F:C270101AED100000,00\n:61:2702250225D10,00NTRFA\n:86:ONE\n:61:2702260226D20,00NTRFB\n:86:TWO\n:62F:C270331AED100000,00\n-\n";

        JsonNode first = upload(one);
        JsonNode again = upload(one);
        JsonNode second = upload(overlap);

        assertThat(first.get("result").get("statements").get(0).get("txnNew").asInt()).isEqualTo(1);
        assertThat(again.get("result").get("alreadyProcessed").asBoolean()).isTrue();
        assertThat(second.get("result").get("statements").get(0).get("txnCount").asInt()).isEqualTo(2);
        assertThat(second.get("result").get("statements").get(0).get("txnNew").asInt()).isEqualTo(1);
    }

    @Test
    void aStatementThatDoesNotAddUpIsFlagged() throws Exception {
        JsonNode out = upload(":20:S\n:25:AE07\n:60F:C270101AED1000,00\n:61:2702250225D10,00NTRFA\n:62F:C270331AED1000,00\n-\n");

        JsonNode st = out.get("result").get("statements").get(0);
        assertThat(st.get("balanceChecked").asBoolean()).isTrue();
        assertThat(st.get("balanceProblem").asString()).contains("does not equal closing");
    }

    @Test
    void aStatementIsKeptAsEvidence() throws Exception {
        JsonNode out = upload(statement(":61:2702250225D10,00NTRFA\n"));

        String evidence = out.get("result").get("statements").get(0).get("evidenceId").asString();
        assertThat(s.client.get(s.reviewer, "/api/evidence/" + evidence).get("fileName").asString()).isEqualTo("stmt.sta");
    }

    // ---- Supplier invoices and the six-month rule (ADJ-01) ----

    private UUID withUnpaid() throws Exception {
        UUID e = s.cleanEntity();
        entity = e;
        s.client.uploadCsv(e, "AP", Scenario.AP_HEAD + "TAX_INVOICE,BILL-77,2027-01-05,1000.00,50.00,S5,DXB-1," + Scenario.SUPPLIER + ",true,2027-01-20,,,SERVICES\n");
        return e;
    }

    @Test
    void aBankPaymentNamingTheInvoiceStopsTheReversal() throws Exception {
        UUID e = withUnpaid();
        UUID july = s.client.periodId(e, "2027-07-01");

        JsonNode out = upload(statement(":61:2702100210D1050,00NTRFA\n:86:PAYMENT BILL-77 SUPPLIER\n"));

        assertThat(out.get("result").get("supplierInvoicesMatched").asInt()).isEqualTo(1);
        JsonNode run = s.client.post(s.reviewer, "/api/periods/" + july + "/adjustments/supplier-reversal", null);
        assertThat(run.get("reversed").asInt()).isZero();
    }

    @Test
    void anAmountThatDiffersOrANumberThatOnlyLooksSimilarIsNotAMatch() throws Exception {
        UUID e = withUnpaid();
        UUID july = s.client.periodId(e, "2027-07-01");

        JsonNode out = upload(statement(":61:2702100210D1000,00NTRFA\n:86:PAYMENT BILL-77\n:61:2702110211D1050,00NTRFB\n:86:PAYMENT BILL-778\n"));

        assertThat(out.get("result").get("supplierInvoicesMatched").asInt()).isZero();
        assertThat(s.client.post(s.reviewer, "/api/periods/" + july + "/adjustments/supplier-reversal", null).get("reversed").asInt()).isEqualTo(1);
    }

    @Test
    void reloadingApDoesNotEraseWhatTheBankShowed() throws Exception {
        UUID e = withUnpaid();
        upload(statement(":61:2702100210D1050,00NTRFA\n:86:PAYMENT BILL-77\n"));

        s.client.uploadCsv(e, "AP", Scenario.AP_HEAD + "TAX_INVOICE,BILL-77,2027-01-05,1000.00,50.00,S5,DXB-1," + Scenario.SUPPLIER + ",true,2027-01-20,,,SERVICES\n");

        UUID july = s.client.periodId(e, "2027-07-01");
        assertThat(s.client.post(s.reviewer, "/api/periods/" + july + "/adjustments/supplier-reversal", null).get("reversed").asInt()).isZero();
    }

    @Test
    void anotherTenantCannotReadStatementsOrUseBankLines() throws Exception {
        upload(statement(":61:2702250225D10,00NTRFA\n"));
        Scenario other = new Scenario(mvc, world);

        assertThat(other.client.get(other.reviewer, "/api/entities/" + entity + "/bank-statements")).isEmpty();
        var scoped = world.newUser(s.tenant, "CLIENT");
        world.grantOnEntity(scoped, s.cleanEntity(), "PREPARER");
        s.client.getRaw(scoped, "/api/entities/" + entity + "/bank-statements").andExpect(status().isForbidden());
        mvc.perform(multipart("/api/entities/" + entity + "/bank-statements")
                .file(new MockMultipartFile("file", "x.sta", "text/plain", "x".getBytes(StandardCharsets.UTF_8))).with(other.reviewer.auth())).andExpect(status().isNotFound());
    }
}
