package ae.vattool.filing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ae.vattool.support.IntegrationTest;
import ae.vattool.support.Scenario;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** FIL-04: the slot exists, and nothing can submit through it until the FTA publishes an API. */
class DirectSubmissionTest extends IntegrationTest {

    @Test
    void directSubmissionIsUnavailableAndSaysWhy() throws Exception {
        Scenario s = new Scenario(mvc, world);
        UUID entity = s.cleanEntity();
        UUID period = s.cleanJanuary(entity, "1000.00", "50.00");
        s.toApproved(period);

        JsonNode state = s.client.get(s.reviewer, "/api/periods/" + period + "/direct-submission");

        assertThat(state.get("available").asBoolean()).isFalse();
        assertThat(state.get("reason").asString()).contains("EmaraTax");
        s.client.postRaw(s.reviewer, "/api/periods/" + period + "/direct-submission", null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DIRECT_SUBMISSION_UNAVAILABLE"));
    }
}
