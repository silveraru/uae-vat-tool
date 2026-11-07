package ae.vattool.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ae.vattool.support.IntegrationTest;
import ae.vattool.support.Scenario;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class BundledPackInstallTest extends IntegrationTest {

    @Test
    void aTenantThatAlreadyHasTheBundledPackIsToldSoAndCannotInstallItTwice() throws Exception {
        Scenario s = new Scenario(mvc, world);
        UUID entity = s.cleanEntity();
        s.client.periodId(entity, "2027-01-01");

        JsonNode bundled = s.client.get(s.admin, "/api/rule-packs/bundled");

        assertThat(bundled.get("version").asString()).isEqualTo("AE-VAT-1.1");
        assertThat(bundled.get("installed").asBoolean()).isTrue();
        s.client.postRaw(s.admin, "/api/rule-packs/install-bundled", null).andExpect(status().isConflict());
        s.client.getRaw(s.preparer, "/api/rule-packs/bundled").andExpect(status().isForbidden());
    }

    @Test
    void anOlderPackInstalledAheadOfTimeIsReplacedOnlyByAnExplicitInstallAndStaysDraft() throws Exception {
        Scenario s = new Scenario(mvc, world);
        String older = new String(getClass().getResourceAsStream("/rulepacks/ae-vat/v1/ae-vat-v1.yaml").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                .replace("version: \"AE-VAT-1.1\"", "version: \"AE-VAT-1.0\"");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/rule-packs").with(s.admin.auth()).contentType("text/plain").content(older)).andExpect(status().is2xxSuccessful());
        assertThat(s.client.get(s.admin, "/api/rule-packs/bundled").get("installed").asBoolean()).isFalse();

        JsonNode installed = s.client.post(s.admin, "/api/rule-packs/install-bundled", null);

        assertThat(installed.get("version").asString()).isEqualTo("AE-VAT-1.1");
        assertThat(installed.get("status").asString()).isEqualTo("DRAFT");
        assertThat(s.client.get(s.admin, "/api/rule-packs/bundled").get("installed").asBoolean()).isTrue();
    }
}
