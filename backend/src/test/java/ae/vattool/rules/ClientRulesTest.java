package ae.vattool.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ae.vattool.support.IntegrationTest;
import ae.vattool.support.Scenario;
import ae.vattool.support.TestClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** NFR-10: rules for one client sit on the firm's pack, apply to that client only, and are signed off like the pack. */
class ClientRulesTest extends IntegrationTest {

    private static final String AP_HEAD = "doc_type,doc_number,doc_date,net_amount,vat_amount,tax_code,establishment_code,counterparty_trn,has_tax_invoice,goods_or_services,description\n";
    private static final String AR_HEAD = "doc_type,doc_number,doc_date,net_amount,vat_amount,tax_code,establishment_code,counterparty_trn,customer_type\n";

    @Autowired JdbcClient jdbc;

    Scenario s;
    UUID withRules;
    UUID without;

    @BeforeEach
    void setUp() throws Exception {
        s = new Scenario(mvc, world);
        withRules = s.cleanEntity();
        without = s.cleanEntity();
    }

    private JsonNode publish(UUID entity, String from, Map<String, Object> content) throws Exception {
        return s.client.post(s.admin, "/api/entities/" + entity + "/client-rules", Map.of("effectiveFrom", from, "note", "Agreed with the client", "content", content));
    }

    private void purchase(UUID entity, String number, String taxCode, String net, String vat, String description) throws Exception {
        s.client.uploadCsv(entity, "AP", AP_HEAD + "TAX_INVOICE," + number + ",2027-01-05," + net + "," + vat + "," + taxCode + ",DXB-1," + Scenario.SUPPLIER
                + ",true,SERVICES," + description + "\n");
    }

    private Map<String, Object> line(UUID entity, String number) {
        return world.queryInTenant(s.tenant, () -> jdbc.sql("""
                SELECT l.treatment, l.classified_by, array_to_string(l.rule_ids, ',') AS rules, array_to_string(l.review_flags, ',') AS flags,
                       l.client_rule_version_id::text AS client_rules
                FROM tax_line l JOIN source_document d ON d.id = l.document_id
                WHERE l.filer_entity_id = ? AND d.doc_number = ? AND l.superseded_at IS NULL
                """).params(entity, number).query().singleRow());
    }

    private List<JsonNode> open(UUID period, String rule) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode e : s.client.get(s.preparer, "/api/periods/" + period + "/exceptions")) {
            if (e.get("ruleId").asString().equals(rule) && List.of("OPEN", "ASSIGNED").contains(e.get("status").asString())) {
                out.add(e);
            }
        }
        return out;
    }

    private static Map<String, Object> staffHousing() {
        return Map.of("classificationRules", List.of(Map.of(
                "id", "CL-STAFF-HOUSING", "description", "Rent for staff accommodation is a residential lease, so exempt",
                "when", Map.of("direction", "IN", "descriptionMatches", "\\bstaff (housing|accommodation)\\b"),
                "then", Map.of("treatment", "EXEMPT"))));
    }

    @Test
    void aClientRuleClassifiesThatClientsLinesOnlyAndNeverOverridesItsMapping() throws Exception {
        String version = publish(withRules, "2026-01-01", staffHousing()).get("id").asString();

        purchase(withRules, "B-1", "", "8000.00", "0.00", "Staff accommodation January");
        purchase(without, "B-1", "", "8000.00", "0.00", "Staff accommodation January");
        purchase(withRules, "B-2", "S5", "1000.00", "50.00", "Staff housing repairs");

        Map<String, Object> classified = line(withRules, "B-1");
        assertThat(classified.get("treatment")).isEqualTo("EXEMPT");
        assertThat(classified.get("classified_by")).isEqualTo("RULE");
        assertThat((String) classified.get("rules")).contains("CL-STAFF-HOUSING");
        assertThat(classified.get("client_rules")).isEqualTo(version);
        assertThat(line(without, "B-1").get("treatment")).isEqualTo("UNCLASSIFIED");
        assertThat(line(without, "B-1").get("client_rules")).isNull();
        // The client's tax code mapping stands. Where the rule disagrees, a person is asked.
        Map<String, Object> mapped = line(withRules, "B-2");
        assertThat(mapped.get("treatment")).isEqualTo("STANDARD");
        assertThat((String) mapped.get("flags")).contains("CLASSIFICATION_CONFLICT");
    }

    @Test
    void aFirmPatternSwitchedOffForAClientNoLongerFlagsItsLines() throws Exception {
        publish(withRules, "2026-01-01", Map.of("switchedOff", List.of(Map.of("id", "DRC-HYDROCARBONS",
                "reason", "Fuel bought at retail pumps for the client's own trucks, which is not domestic reverse charge"))));

        purchase(withRules, "F-1", "S5", "400.00", "20.00", "Diesel for delivery trucks");
        purchase(without, "F-1", "S5", "400.00", "20.00", "Diesel for delivery trucks");

        assertThat((String) line(withRules, "F-1").get("flags")).doesNotContain("DRC_POSSIBLE");
        assertThat((String) line(without, "F-1").get("flags")).contains("DRC_POSSIBLE");
    }

    @Test
    void aClientsOwnToleranceDecidesWhatReconciliationFlags() throws Exception {
        publish(withRules, "2026-01-01", Map.of("reconciliation", Map.of("glToleranceAed", 200)));
        for (UUID entity : List.of(withRules, without)) {
            s.client.uploadCsv(entity, "AR", AR_HEAD + "TAX_INVOICE,S-1,2027-01-10,10000.00,500.00,S5,DXB-1," + Scenario.CUSTOMER + ",B2B\n");
            s.client.uploadCsv(entity, "GL", "account_code,account_type,period_end,balance_aed\n2201,OUTPUT,2027-01-31,650.00\n");
        }
        UUID periodWith = s.client.periodId(withRules, "2027-01-01");
        UUID periodWithout = s.client.periodId(without, "2027-01-01");

        s.client.post(s.preparer, "/api/periods/" + periodWith + "/reconcile", null);
        s.client.post(s.preparer, "/api/periods/" + periodWithout + "/reconcile", null);

        assertThat(open(periodWith, "R06")).isEmpty();
        assertThat(open(periodWithout, "R06")).hasSize(1);
    }

    @Test
    void clientRulesAreVersionedAndADraftWatermarksTheReturnUntilSignedOff() throws Exception {
        String pack = s.client.get(s.admin, "/api/rule-packs").get(0).get("id").asString();
        s.client.post(s.admin, "/api/rule-packs/" + pack + "/sign-off", Map.of("note", "Reviewed by the tax team"));
        UUID period = s.cleanJanuary(withRules, "1000.00", "50.00");
        s.client.post(s.preparer, "/api/periods/" + period + "/reconcile", null);
        assertThat(s.client.get(s.preparer, "/api/periods/" + period + "/return").get("ruleDraft").asBoolean()).isFalse();

        JsonNode v1 = publish(withRules, "2026-01-01", staffHousing());
        assertThat(v1.get("status").asString()).isEqualTo("DRAFT");
        assertThat(v1.get("versionNo").asInt()).isEqualTo(1);
        s.client.post(s.preparer, "/api/periods/" + period + "/reconcile", null);

        JsonNode draft = s.client.get(s.preparer, "/api/periods/" + period + "/return");
        assertThat(draft.get("ruleDraft").asBoolean()).isTrue();
        assertThat(draft.get("version").get("clientRuleVersionId").asString()).isEqualTo(v1.get("id").asString());

        s.client.post(s.admin, "/api/entities/" + withRules + "/client-rules/" + v1.get("id").asString() + "/sign-off", Map.of("note", "Checked against the lease"));
        assertThat(s.client.get(s.preparer, "/api/periods/" + period + "/return").get("ruleDraft").asBoolean()).isFalse();

        JsonNode view = s.client.get(s.reviewer, "/api/entities/" + withRules + "/client-rules");
        assertThat(view.get("inForce").get("versionNo").asInt()).isEqualTo(1);
        assertThat(view.get("inForce").get("status").asString()).isEqualTo("SIGNED_OFF");
        assertThat(view.get("firmPack").get("id").asString()).isEqualTo(pack);

        publish(withRules, "2026-06-01", Map.of());
        JsonNode ended = s.client.get(s.reviewer, "/api/entities/" + withRules + "/client-rules");
        assertThat(ended.get("inForce").isNull()).isTrue();
        assertThat(ended.get("versions")).hasSize(2);
        JsonNode summaries = s.client.get(s.admin, "/api/client-rules");
        assertThat(summaries.toString()).contains(withRules.toString());
    }

    @Test
    void aReturnPackNamesTheClientRulesBehindItAndIsWatermarkedWhileTheyAreADraft() throws Exception {
        String pack = s.client.get(s.admin, "/api/rule-packs").get(0).get("id").asString();
        s.client.post(s.admin, "/api/rule-packs/" + pack + "/sign-off", Map.of("note", "Reviewed by the tax team"));
        UUID period = s.cleanJanuary(withRules, "1000.00", "50.00");
        publish(withRules, "2026-01-01", staffHousing());
        s.client.post(s.preparer, "/api/periods/" + period + "/reconcile", null);
        s.toApproved(period);

        JsonNode generated = s.client.post(s.reviewer, "/api/periods/" + period + "/pack", null);

        assertThat(generated.get("draftRules").asBoolean()).isTrue();
        JsonNode json = TestClient.JSON.readTree(mvc.perform(get("/api/periods/" + period + "/pack/json").with(s.reviewer.auth()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(json.get("return").get("clientRules").asInt()).isEqualTo(1);
        assertThat(json.has("watermark")).isTrue();
    }

    @Test
    void aCorrectedReturnNamesTheClientRulesInForceLikeAnyOther() throws Exception {
        UUID period = s.cleanJanuary(withRules, "10000.00", "500.00");
        String version = publish(withRules, "2026-01-01", staffHousing()).get("id").asString();
        s.toFiled(withRules, period);
        s.client.uploadCsv(withRules, "AR", Scenario.AR_HEAD + "TAX_INVOICE,LATE-1,2027-01-15,300000.00,15000.00,S5,DXB-1," + Scenario.CUSTOMER + ",B2B\n");

        s.client.post(s.reviewer, "/api/periods/" + period + "/corrections", Map.of("reason", "Missed a large invoice"));

        JsonNode corrected = null;
        for (JsonNode v : s.client.get(s.reviewer, "/api/periods/" + period + "/versions")) {
            if ("CORRECTED".equals(v.get("status").asString())) {
                corrected = v;
            }
        }
        assertThat(corrected).isNotNull();
        assertThat(corrected.get("clientRuleVersionId").asString()).isEqualTo(version);
    }

    @Test
    void clientRulesThatCouldMisleadAreRefusedAndOnlyAnAdminCanSetThem() throws Exception {
        String path = "/api/entities/" + withRules + "/client-rules";
        for (Map<String, Object> bad : List.of(
                Map.<String, Object>of("classificationRules", List.of(Map.of("id", "CL-1", "when", Map.of("descriptionMatches", "(unclosed"), "then", Map.of("treatment", "EXEMPT")))),
                Map.<String, Object>of("classificationRules", List.of(Map.of("id", "CL-2", "when", Map.of(), "then", Map.of("treatment", "EXEMPT")))),
                Map.<String, Object>of("classificationRules", List.of(Map.of("id", "POS-DOMESTIC", "when", Map.of("direction", "IN"), "then", Map.of("treatment", "EXEMPT")))),
                Map.<String, Object>of("classificationRules", List.of(Map.of("id", "CL-3", "when", Map.of("direction", "IN"), "then", Map.of("treatment", "CHEAP")))),
                Map.<String, Object>of("switchedOff", List.of(Map.of("id", "NO-SUCH-RULE", "reason", "x"))),
                Map.<String, Object>of("switchedOff", List.of(Map.of("id", "DRC-GOLD"))),
                Map.<String, Object>of("reconciliation", Map.of("glToleranceAed", -5)))) {
            s.client.postRaw(s.admin, path, Map.of("effectiveFrom", "2026-01-01", "note", "x", "content", bad))
                    .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("CLIENT_RULES_INVALID"));
        }
        s.client.postRaw(s.admin, path, Map.of("effectiveFrom", "2026-01-01", "content", staffHousing()))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
        s.client.postRaw(s.reviewer, path, Map.of("effectiveFrom", "2026-01-01", "note", "x", "content", staffHousing())).andExpect(status().isForbidden());
        s.client.postRaw(s.preparer, path, Map.of("effectiveFrom", "2026-01-01", "note", "x", "content", staffHousing())).andExpect(status().isForbidden());
        s.client.getRaw(s.reviewer, "/api/client-rules").andExpect(status().isForbidden());

        assertThat(s.client.get(s.reviewer, path).get("versions")).isEmpty();
    }
}
