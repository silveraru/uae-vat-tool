package ae.vattool.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ae.vattool.support.IntegrationTest;
import ae.vattool.support.Scenario;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** WF-04: client approval with an e-signature and a captured identity. */
class ESignatureTest extends IntegrationTest {

    @Autowired JdbcClient jdbc;

    Scenario s;
    UUID entity;
    UUID period;
    String approverName;

    @BeforeEach
    void setUp() throws Exception {
        s = new Scenario(mvc, world);
        entity = s.cleanEntity();
        period = s.cleanJanuary(entity, "1000.00", "50.00");
        putJson(s.admin, "/api/entities/" + entity + "/settings", Map.of("requireEsignature", true)).andExpect(status().is2xxSuccessful());
        approverName = "User " + s.approver.id();
        s.submit(period);
        s.review(period);
    }

    private org.springframework.test.web.servlet.ResultActions approve(Object body) throws Exception {
        return s.client.postRaw(s.approver, "/api/periods/" + period + "/approve", body);
    }

    @Test
    void anEntityThatRequiresASignatureRefusesAPlainApproval() throws Exception {
        approve(Map.of("note", "OK")).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("ESIGNATURE_REQUIRED"));

        assertThat(s.client.get(s.preparer, "/api/periods/" + period).get("state").asString()).isEqualTo("IN_REVIEW");
    }

    @Test
    void consentMustBeGivenAndTheNameMustMatch() throws Exception {
        approve(Map.of("signature", Map.of("typedName", approverName, "consent", false))).andExpect(status().isUnprocessableContent());
        approve(Map.of("signature", Map.of("typedName", "Someone Else", "consent", true)))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("ESIGNATURE_NAME_MISMATCH"));
    }

    @Test
    void aValidSignatureLocksTheReturnAndRecordsWhoSignedWhat() throws Exception {
        JsonNode status = TestClientRead.read(approve(Map.of("signature", Map.of("typedName", approverName.toUpperCase(), "consent", true))).andExpect(status().isOk()));

        assertThat(status.get("period").get("state").asString()).isEqualTo("APPROVED");
        JsonNode signature = status.get("signatures").get(0);
        assertThat(signature.get("signerEmail").asString()).isEqualTo(s.approver.id() + "@test.example");
        assertThat(signature.get("signatureHash").asString()).hasSize(64);
        String stored = world.queryInTenant(s.tenant, () -> jdbc.sql("SELECT values_hash FROM esignature WHERE period_id = ?").param(period).query(String.class).single());
        assertThat(stored).isEqualTo(s.client.get(s.preparer, "/api/periods/" + period + "/return").get("version").get("valuesHash").asString());
    }

    @Test
    void onlyAnAuthorisedSignatoryOnFileCanSign() throws Exception {
        s.client.post(s.admin, "/api/entities/" + entity + "/signatories", Map.of("name", "The CFO", "email", "cfo@client.example", "validFrom", "2020-01-01"));

        approve(Map.of("signature", Map.of("typedName", approverName, "consent", true)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOT_AUTHORISED_SIGNATORY"));
    }

    @Test
    void theSignatureAppearsInTheReturnPack() throws Exception {
        approve(Map.of("signature", Map.of("typedName", approverName, "consent", true))).andExpect(status().isOk());

        s.client.post(s.reviewer, "/api/periods/" + period + "/pack", null);

        byte[] pdf = mvc.perform(get("/api/periods/" + period + "/pack/pdf").with(s.reviewer.auth())).andReturn().getResponse().getContentAsByteArray();
        try (PDDocument d = Loader.loadPDF(pdf)) {
            // A long name wraps across lines in the table cell.
            assertThat(new PDFTextStripper().getText(d).replaceAll("\\s+", "")).contains("Cliente-signature", approverName.replace(" ", ""));
        }
    }

    @Test
    void anEntityWithoutTheSettingStillApprovesAsBefore() throws Exception {
        UUID other = s.cleanEntity();
        UUID p = s.cleanJanuary(other, "1000.00", "50.00");

        s.toApproved(p);

        assertThat(s.client.get(s.preparer, "/api/periods/" + p).get("state").asString()).isEqualTo("APPROVED");
    }

    /** Keeps the test's imports in one place. */
    private static final class TestClientRead {
        static JsonNode read(org.springframework.test.web.servlet.ResultActions r) throws Exception {
            return ae.vattool.support.TestClient.read(r);
        }
    }
}
