package ae.vattool.classification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ae.vattool.support.IntegrationTest;
import ae.vattool.support.Scenario;
import ae.vattool.support.TestClient;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** TXC-05 and TXC-06: export evidence, and advisory suggestions a person must confirm. */
class ClassificationHelpTest extends IntegrationTest {

    private static final String AR = "doc_type,doc_number,doc_date,net_amount,vat_amount,tax_code,establishment_code,counterparty_country,goods_or_services,description,counterparty_trn,customer_type\n";

    Scenario s;
    UUID entity;

    @BeforeEach
    void setUp() throws Exception {
        s = new Scenario(mvc, world);
        entity = s.cleanEntity();
    }

    private String exceptions(UUID p) throws Exception {
        s.client.post(s.preparer, "/api/periods/" + p + "/reconcile", null);
        return s.client.get(s.preparer, "/api/periods/" + p + "/exceptions?status=OPEN").toString();
    }

    private String box(UUID p, String code, String column) throws Exception {
        s.client.post(s.preparer, "/api/periods/" + p + "/recompute", null);
        return TestClient.JSON.readTree(s.client.get(s.preparer, "/api/periods/" + p + "/return").get("version").get("boxValues").asString()).get(code).get(column).asString();
    }

    // ---- Export evidence ----

    private UUID export() throws Exception {
        s.client.uploadCsv(entity, "AR", AR + "TAX_INVOICE,X-1,2027-01-10,5000.00,0.00,Z0,DXB-1,SA,GOODS,Goods to Riyadh,,B2C\n");
        return s.client.periodId(entity, "2027-01-01");
    }

    @Test
    void anExportWithoutEvidenceIsListedAndWarned() throws Exception {
        UUID p = export();

        JsonNode rows = s.client.get(s.preparer, "/api/periods/" + p + "/exports");

        assertThat(rows.get(0).get("status").asString()).isEqualTo("INCOMPLETE");
        assertThat(rows.get(0).get("limitOn").asString()).isEqualTo("2027-04-10");
        assertThat(exceptions(p)).contains("\"ruleId\":\"T07\"");
    }

    @Test
    void bothKindsOfEvidenceAndADateClearTheWarning() throws Exception {
        UUID p = export();
        String doc = s.client.get(s.preparer, "/api/periods/" + p + "/exports").get(0).get("documentId").asString();

        s.client.post(s.preparer, "/api/documents/" + doc + "/export-evidence", Map.of("exportedOn", "2027-01-20", "exitReference", "EX-1",
                "officialEvidenceId", s.evidence(entity, p, "customs.pdf"), "commercialEvidenceId", s.evidence(entity, p, "bl.pdf")));

        assertThat(s.client.get(s.preparer, "/api/periods/" + p + "/exports").get(0).get("status").asString()).isEqualTo("COMPLETE");
        assertThat(exceptions(p)).doesNotContain("\"ruleId\":\"T07\"");
    }

    @Test
    void noExportAfterNinetyDaysBlocksTheEarliestOpenPeriod() throws Exception {
        UUID jan = export();
        s.client.uploadCsv(entity, "GL", "account_code,account_type,period_end,balance_aed\n2201,OUTPUT,2027-01-31,0.00\n");
        s.toFiled(entity, jan);
        UUID feb = s.client.periodId(entity, "2027-02-01");
        assertThat(exceptions(feb)).doesNotContain("\"ruleId\":\"T08\"");

        // Feb and Mar end before the 90 days run out on 10 April, so file them to bring April forward.
        s.client.uploadCsv(entity, "GL", "account_code,account_type,period_end,balance_aed\n2201,OUTPUT,2027-02-28,0.00\n2202,OUTPUT,2027-03-31,0.00\n");
        for (String start : List.of("2027-02-01", "2027-03-01")) {
            UUID p = s.client.periodId(entity, start);
            s.submit(p);
            s.review(p);
            var r = s.client.postRaw(s.approver, "/api/periods/" + p + "/approve", null).andReturn().getResponse();
            assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
            s.client.post(s.reviewer, "/api/periods/" + p + "/filing", Map.of("submissionRef", "EMT-" + start, "submittedOn", "2027-03-20", "ackEvidenceId", s.evidence(entity, p, "ack.pdf")));
        }
        UUID april = s.client.periodId(entity, "2027-04-01");

        assertThat(exceptions(april)).contains("\"ruleId\":\"T08\"");
    }

    @Test
    void anExtensionNeedsTheFtaLetterAndMovesTheLimit() throws Exception {
        UUID p = export();
        String doc = s.client.get(s.preparer, "/api/periods/" + p + "/exports").get(0).get("documentId").asString();

        s.client.postRaw(s.preparer, "/api/documents/" + doc + "/export-evidence", Map.of("extendedUntil", "2027-07-01"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("EVIDENCE_REQUIRED"));
        s.client.post(s.preparer, "/api/documents/" + doc + "/export-evidence", Map.of("extendedUntil", "2027-07-01", "extensionEvidenceId", s.evidence(entity, p, "fta.pdf")));

        assertThat(s.client.get(s.preparer, "/api/periods/" + p + "/exports").get(0).get("limitOn").asString()).isEqualTo("2027-07-01");
    }

    @Test
    void anExportAfterTheLimitIsShownAsLate() throws Exception {
        UUID p = export();
        String doc = s.client.get(s.preparer, "/api/periods/" + p + "/exports").get(0).get("documentId").asString();

        s.client.post(s.preparer, "/api/documents/" + doc + "/export-evidence", Map.of("exportedOn", "2027-05-01"));

        assertThat(s.client.get(s.preparer, "/api/periods/" + p + "/exports").get(0).get("status").asString()).isEqualTo("LATE");
    }

    // ---- Suggestions ----

    private void history() throws Exception {
        s.client.uploadCsv(entity, "AP", "doc_type,doc_number,doc_date,net_amount,vat_amount,tax_code,establishment_code,counterparty_trn,has_tax_invoice,gl_account\n"
                + "TAX_INVOICE,H-1,2027-01-01,100.00,5.00,S5,DXB-1," + Scenario.SUPPLIER + ",true,7000\n"
                + "TAX_INVOICE,H-2,2027-01-02,100.00,5.00,S5,DXB-1," + Scenario.SUPPLIER + ",true,7000\n"
                + "TAX_INVOICE,H-3,2027-01-03,100.00,5.00,S5,DXB-1," + Scenario.SUPPLIER + ",true,7000\n");
    }

    private UUID unmapped() throws Exception {
        s.client.uploadCsv(entity, "AP", "doc_type,doc_number,doc_date,net_amount,vat_amount,tax_code,establishment_code,counterparty_trn,has_tax_invoice,gl_account\n"
                + "TAX_INVOICE,U-1,2027-02-01,200.00,10.00,NEW,DXB-1," + Scenario.SUPPLIER + ",true,7000\n");
        return s.client.periodId(entity, "2027-02-01");
    }

    @Test
    void anUnclassifiedLineGetsASuggestionFromHistoryThatChangesNothingUntilAccepted() throws Exception {
        history();
        UUID p = unmapped();

        JsonNode generated = s.client.post(s.preparer, "/api/periods/" + p + "/suggestions", null);

        assertThat(generated.get("suggestionsAdded").asInt()).isEqualTo(1);
        JsonNode suggestion = s.client.get(s.preparer, "/api/periods/" + p + "/suggestions").get(0);
        assertThat(suggestion.get("treatment").asString()).isEqualTo("STANDARD");
        assertThat(suggestion.get("method").asString()).isEqualTo("HISTORY_COUNTERPARTY");
        assertThat(new BigDecimal(suggestion.get("confidence").asString())).isEqualByComparingTo("0.95");
        assertThat(suggestion.get("engineVersion").asString()).isEqualTo("rule-suggest-1.0");
        assertThat(suggestion.get("inputs").asString()).contains(Scenario.SUPPLIER, "matchingLines");
        assertThat(new BigDecimal(box(p, "9", "vat"))).isEqualByComparingTo("0");
        assertThat(exceptions(p)).contains("\"ruleId\":\"D03\"");

        JsonNode accepted = s.client.post(s.preparer, "/api/suggestions/accept", Map.of("suggestionIds", List.of(suggestion.get("id").asString())));

        assertThat(accepted.get("changed").asInt()).isEqualTo(1);
        assertThat(new BigDecimal(box(p, "9", "vat"))).isEqualByComparingTo("10.00");
        assertThat(exceptions(p)).doesNotContain("\"ruleId\":\"D03\"");
    }

    @Test
    void generatingAgainAddsNothingAndARejectionSticks() throws Exception {
        history();
        UUID p = unmapped();
        s.client.post(s.preparer, "/api/periods/" + p + "/suggestions", null);
        String id = s.client.get(s.preparer, "/api/periods/" + p + "/suggestions").get(0).get("id").asString();

        assertThat(s.client.post(s.preparer, "/api/periods/" + p + "/suggestions", null).get("suggestionsAdded").asInt()).isZero();
        s.client.post(s.preparer, "/api/suggestions/" + id + "/reject", null);

        assertThat(s.client.get(s.preparer, "/api/periods/" + p + "/suggestions?status=PROPOSED")).isEmpty();
        assertThat(s.client.post(s.preparer, "/api/periods/" + p + "/suggestions", null).get("suggestionsAdded").asInt()).isZero();
        s.client.postRaw(s.preparer, "/api/suggestions/accept", Map.of("suggestionIds", List.of(id))).andExpect(status().isConflict());
    }

    @Test
    void aKeywordCanSuggestWhenThereIsNoHistory() throws Exception {
        s.client.uploadCsv(entity, "AR", AR + "TAX_INVOICE,K-1,2027-01-10,5000.00,0.00,UNMAPPED,DXB-1,,,Sale of a residential flat,,B2C\n");
        UUID p = s.client.periodId(entity, "2027-01-01");

        s.client.post(s.preparer, "/api/periods/" + p + "/suggestions", null);

        JsonNode suggestion = s.client.get(s.preparer, "/api/periods/" + p + "/suggestions").get(0);
        assertThat(suggestion.get("treatment").asString()).isEqualTo("EXEMPT");
        assertThat(suggestion.get("method").asString()).isEqualTo("KEYWORD");
        assertThat(new BigDecimal(suggestion.get("confidence").asString())).isEqualByComparingTo("0.6");
    }

    @Test
    void aSuggestionForALineSinceRevisedGoesStale() throws Exception {
        history();
        UUID p = unmapped();
        s.client.post(s.preparer, "/api/periods/" + p + "/suggestions", null);
        JsonNode suggestion = s.client.get(s.preparer, "/api/periods/" + p + "/suggestions").get(0);
        s.client.post(s.preparer, "/api/periods/" + p + "/reclassify", Map.of("lineIds", List.of(suggestion.get("lineId").asString()), "treatment", "EXEMPT", "reasonCode", "WRONG_TAX_CODE", "reason", "Checked"));

        s.client.post(s.preparer, "/api/periods/" + p + "/suggestions", null);

        assertThat(s.client.get(s.preparer, "/api/periods/" + p + "/suggestions?status=STALE")).hasSize(1);
    }

    @Test
    void suggestionsCannotBeAcceptedOnALockedPeriodOrByAnotherEntitysUser() throws Exception {
        history();
        UUID p = unmapped();
        s.client.post(s.preparer, "/api/periods/" + p + "/suggestions", null);
        String id = s.client.get(s.preparer, "/api/periods/" + p + "/suggestions").get(0).get("id").asString();
        var scoped = world.newUser(s.tenant, "CLIENT");
        world.grantOnEntity(scoped, s.cleanEntity(), "PREPARER");

        s.client.postRaw(scoped, "/api/suggestions/accept", Map.of("suggestionIds", List.of(id))).andExpect(status().isForbidden());
        s.client.postRaw(s.approver, "/api/suggestions/accept", Map.of("suggestionIds", List.of(id))).andExpect(status().isForbidden());
    }
}
