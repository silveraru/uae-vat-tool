package ae.vattool.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ae.vattool.support.IntegrationTest;
import ae.vattool.support.Scenario;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** NFR-10: a firm reads its rule pack and changes it by saving an edited copy, never by changing a version in place. */
class RulePackEditingTest extends IntegrationTest {

    Scenario s;
    UUID entity;
    String base;

    @BeforeEach
    void setUp() throws Exception {
        s = new Scenario(mvc, world);
        entity = s.cleanEntity();
        base = s.client.get(s.admin, "/api/rule-packs").get(0).get("id").asString();
    }

    private JsonNode pack(String id) throws Exception {
        for (JsonNode p : s.client.get(s.admin, "/api/rule-packs")) {
            if (p.get("id").asString().equals(id)) {
                return p;
            }
        }
        throw new AssertionError("No pack " + id);
    }

    private static JsonNode change(JsonNode check, String path) {
        for (JsonNode c : check.get("changes")) {
            if (c.get("path").asString().equals(path)) {
                return c;
            }
        }
        throw new AssertionError("No change at " + path);
    }

    private static List<String> paths(JsonNode check) {
        List<String> out = new ArrayList<>();
        check.get("changes").forEach(c -> out.add(c.get("path").asString()));
        return out;
    }

    @Test
    void aPackCanBeReadRuleByRuleAndAsItsSourceWithComments() throws Exception {
        JsonNode view = s.client.get(s.admin, "/api/rule-packs/" + base);

        assertThat(view.get("pack").get("version").asString()).isEqualTo("AE-VAT-1.1");
        assertThat(view.get("pack").get("inForceToday").asBoolean()).isTrue();
        assertThat(view.get("definition").get("classification").get("rules").size()).isGreaterThan(5);
        assertThat(view.get("definition").get("reconciliation").get("glToleranceAed").decimalValue()).isEqualByComparingTo("100");
        assertThat(view.get("yaml").asString()).contains("# Rule pack AE-VAT v1", "CONFIRM");
    }

    @Test
    void anEditedCopyShowsWhatItChangesAndSavesAsANewDraftThatTakesOver() throws Exception {
        JsonNode copy = s.client.get(s.admin, "/api/rule-packs/" + base + "/copy");
        assertThat(copy.get("suggestedVersion").asString()).isEqualTo("AE-VAT-1.1-firm-1");
        String edited = copy.get("yaml").asString()
                .replace("glToleranceAed: 100", "glToleranceAed: 50")
                .replace("    - { id: DRC-SCRAP, category: SCRAP_METAL, patterns: [\"\\\\bscrap\\\\b\"] }",
                        "    - { id: DRC-SCRAP, category: SCRAP_METAL, patterns: [\"\\\\bscrap\\\\b\", \"\\\\bscrap copper\\\\b\"] }");

        JsonNode check = s.client.post(s.admin, "/api/rule-packs/check", Map.of("yaml", edited, "basedOn", base));

        assertThat(check.get("valid").asBoolean()).isTrue();
        assertThat(paths(check)).contains("version", "reconciliation.glToleranceAed", "classification.drcPatterns[DRC-SCRAP].patterns");
        JsonNode tolerance = change(check, "reconciliation.glToleranceAed");
        assertThat(tolerance.get("before").asString()).isEqualTo("100");
        assertThat(tolerance.get("after").asString()).isEqualTo("50");

        JsonNode saved = s.client.post(s.admin, "/api/rule-packs/edits", Map.of("yaml", edited, "basedOn", base, "note", "Tighter GL tolerance agreed by the tax team"));

        assertThat(saved.get("version").asString()).isEqualTo("AE-VAT-1.1-firm-1");
        assertThat(saved.get("status").asString()).isEqualTo("DRAFT");
        assertThat(saved.get("basedOnVersion").asString()).isEqualTo("AE-VAT-1.1");
        assertThat(saved.get("editNote").asString()).contains("Tighter GL tolerance");
        assertThat(saved.get("inForceToday").asBoolean()).isTrue();
        assertThat(pack(base).get("inForceToday").asBoolean()).isFalse();
        // The original is untouched, and the copy keeps every comment.
        assertThat(s.client.get(s.admin, "/api/rule-packs/" + base).get("definition").get("reconciliation").get("glToleranceAed").decimalValue()).isEqualByComparingTo("100");
        assertThat(s.client.get(s.admin, "/api/rule-packs/" + saved.get("id").asString()).get("yaml").asString()).contains("CONFIRM");
        assertThat(s.client.get(s.admin, "/api/rule-packs/" + saved.get("id").asString() + "/copy").get("suggestedVersion").asString()).isEqualTo("AE-VAT-1.1-firm-2");
    }

    @Test
    void aBrokenUnchangedOrClashingCopyIsRefusedWithReasons() throws Exception {
        String copy = s.client.get(s.admin, "/api/rule-packs/" + base + "/copy").get("yaml").asString();
        String original = s.client.get(s.admin, "/api/rule-packs/" + base).get("yaml").asString();

        JsonNode broken = s.client.post(s.admin, "/api/rule-packs/check", Map.of("yaml", copy.replace("rates:\n  standardPct: 5", "rates:\n  standardPct: [5"), "basedOn", base));
        assertThat(broken.get("valid").asBoolean()).isFalse();
        assertThat(broken.get("problems").get(0).asString()).contains("line");

        JsonNode unchanged = s.client.post(s.admin, "/api/rule-packs/check", Map.of("yaml", copy, "basedOn", base));
        assertThat(unchanged.get("valid").asBoolean()).isFalse();
        assertThat(unchanged.get("problems").toString()).contains("does not change any rule");

        JsonNode clash = s.client.post(s.admin, "/api/rule-packs/check", Map.of("yaml", original.replace("glToleranceAed: 100", "glToleranceAed: 90"), "basedOn", base));
        assertThat(clash.get("problems").toString()).contains("already exists");

        String good = copy.replace("glToleranceAed: 100", "glToleranceAed: 90");
        s.client.postRaw(s.admin, "/api/rule-packs/edits", Map.of("yaml", good, "basedOn", base))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
        s.client.postRaw(s.admin, "/api/rule-packs/edits", Map.of("yaml", copy, "basedOn", base, "note", "No change"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("RULE_PACK_INVALID"));
    }

    @Test
    void aPackLoadedByMistakeCanBeRetiredButNeverTheLastOneInForce() throws Exception {
        s.client.postRaw(s.admin, "/api/rule-packs/" + base + "/retire", Map.of("note", "Testing"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RULE_PACK_LAST"));
        String copy = s.client.get(s.admin, "/api/rule-packs/" + base + "/copy").get("yaml").asString();
        String edit = s.client.post(s.admin, "/api/rule-packs/edits", Map.of("yaml", copy.replace("boxSwingPct: 25", "boxSwingPct: 2"), "basedOn", base,
                "note", "Typo: meant 20")).get("id").asString();

        JsonNode retired = s.client.post(s.admin, "/api/rule-packs/" + edit + "/retire", Map.of("note", "Typo, the swing should be 20%"));

        assertThat(retired.get("status").asString()).isEqualTo("RETIRED");
        assertThat(retired.get("retireNote").asString()).contains("Typo");
        assertThat(pack(base).get("inForceToday").asBoolean()).isTrue();
        s.client.postRaw(s.admin, "/api/rule-packs/" + edit + "/sign-off", Map.of("note", "x")).andExpect(status().isConflict());
    }

    @Test
    void onlyAnAdminEditsWhileAnyoneOnTheClientCanReadWhatApplies() throws Exception {
        s.client.getRaw(s.reviewer, "/api/rule-packs/" + base).andExpect(status().isForbidden());
        s.client.postRaw(s.reviewer, "/api/rule-packs/check", Map.of("yaml", "x", "basedOn", base)).andExpect(status().isForbidden());

        JsonNode applies = s.client.get(s.reviewer, "/api/entities/" + entity + "/rule-pack");

        assertThat(applies.get("pack").get("id").asString()).isEqualTo(base);
        assertThat(applies.get("definition").get("form").get("boxes").size()).isEqualTo(20);
    }
}
