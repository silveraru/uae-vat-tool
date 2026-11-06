package ae.vattool.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ae.vattool.support.IntegrationTest;
import ae.vattool.support.Scenario;
import ae.vattool.support.TestWorld.TestUser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * Row-level security separates tenants, not entities inside one. A user granted one client must get
 * nothing from another client of the same firm, on any route, even with a valid id for that client.
 */
class CrossEntityAccessTest extends IntegrationTest {

    Scenario s;
    UUID mine;
    UUID theirs;
    UUID myPeriod;
    UUID theirPeriod;
    TestUser scoped;

    @BeforeEach
    void setUp() throws Exception {
        s = new Scenario(mvc, world);
        mine = s.cleanEntity();
        theirs = s.cleanEntity();
        myPeriod = s.cleanJanuary(mine, "1000.00", "50.00");
        theirPeriod = s.cleanJanuary(theirs, "2000.00", "100.00");
        scoped = world.newUser(s.tenant, "CLIENT");
        world.grantOnEntity(scoped, mine, "PREPARER");
    }

    private void refused(ResultActions result) throws Exception {
        int code = result.andReturn().getResponse().getStatus();
        assertThat(code).as("response status").isIn(403, 404);
    }

    @Test
    void everyReadRouteForAnotherEntityIsRefused() throws Exception {
        s.client.post(s.reviewer, "/api/periods/" + theirPeriod + "/recompute", null);
        for (String path : List.of(
                "/api/entities/" + theirs,
                "/api/entities/" + theirs + "/periods",
                "/api/entities/" + theirs + "/establishments",
                "/api/entities/" + theirs + "/mappings",
                "/api/entities/" + theirs + "/ingest/batches",
                "/api/periods/" + theirPeriod,
                "/api/periods/" + theirPeriod + "/return",
                "/api/periods/" + theirPeriod + "/versions",
                "/api/periods/" + theirPeriod + "/lines",
                "/api/periods/" + theirPeriod + "/boxes/1b/lines",
                "/api/periods/" + theirPeriod + "/exceptions",
                "/api/periods/" + theirPeriod + "/certificate",
                "/api/periods/" + theirPeriod + "/workflow",
                "/api/periods/" + theirPeriod + "/comments",
                "/api/periods/" + theirPeriod + "/adjustments",
                "/api/periods/" + theirPeriod + "/corrections",
                "/api/periods/" + theirPeriod + "/filing",
                "/api/entities/" + theirs + "/evidence?periodId=" + theirPeriod)) {
            refused(s.client.getRaw(scoped, path));
        }
    }

    @Test
    void everyWriteRouteForAnotherEntityIsRefused() throws Exception {
        for (String path : List.of(
                "/api/periods/" + theirPeriod + "/recompute",
                "/api/periods/" + theirPeriod + "/submit",
                "/api/periods/" + theirPeriod + "/review",
                "/api/periods/" + theirPeriod + "/approve",
                "/api/periods/" + theirPeriod + "/pack",
                "/api/periods/" + theirPeriod + "/adjustments/supplier-reversal")) {
            refused(s.client.postRaw(scoped, path, null));
        }
        refused(s.client.postRaw(scoped, "/api/periods/" + theirPeriod + "/reclassify", Map.of("lineIds", List.of(UUID.randomUUID()), "treatment", "EXEMPT", "reasonCode", "OTHER", "reason", "x")));
        refused(s.client.postRaw(scoped, "/api/periods/" + theirPeriod + "/comments", Map.of("objectType", "BOX", "objectId", "1b", "body", "x")));
        refused(mvc.perform(multipart("/api/entities/" + theirs + "/ingest/AR").file(new MockMultipartFile("file", "a.csv", "text/csv", "x".getBytes())).with(scoped.auth())));
        refused(mvc.perform(multipart("/api/entities/" + theirs + "/evidence").file(new MockMultipartFile("file", "a.pdf", "application/pdf", "x".getBytes())).with(scoped.auth())));
    }

    @Test
    void aMappingVersionOfAnotherEntityCannotBeReadThroughMyEntityUrl() throws Exception {
        String theirVersion = s.client.get(s.admin, "/api/entities/" + theirs + "/mappings").get(0).get("id").asString();

        s.client.getRaw(scoped, "/api/entities/" + mine + "/mappings/" + theirVersion).andExpect(status().isNotFound());
    }

    @Test
    void aMatchProposalOfAnotherEntityCannotBeDecidedThroughMyPeriod() throws Exception {
        // Set up a fuzzy proposal on the other entity.
        s.client.uploadCsv(theirs, "AR", Scenario.AR_HEAD + "TAX_INVOICE,INV-1001,2027-01-05,100.00,5.00,S5,DXB-1," + Scenario.CUSTOMER + ",B2B\n");
        String trn = s.client.get(s.admin, "/api/entities/" + theirs).get("trn").asString();
        var e = new ae.vattool.ingestion.einvoice.EinvoiceEvent("1.0", UUID.randomUUID(), "einvoice.issued", java.time.OffsetDateTime.now(), s.tenant, trn, UUID.randomUUID(),
                new ae.vattool.ingestion.einvoice.EinvoiceEvent.Invoice("INVOICE", "INV1001", java.time.LocalDate.parse("2027-01-06"), trn, Scenario.CUSTOMER, "AED", new java.math.BigDecimal("100.00"), new java.math.BigDecimal("5.00"), null), null, null);
        ae.vattool.platform.tenancy.RequestContext.asSystem(s.tenant, "test", () -> einvoices.apply(e));
        s.client.post(s.reviewer, "/api/periods/" + theirPeriod + "/reconcile", null);
        JsonNode proposals = s.client.get(s.reviewer, "/api/periods/" + theirPeriod + "/match-proposals");
        assertThat(proposals).isNotEmpty();

        s.client.postRaw(scoped, "/api/periods/" + myPeriod + "/match-proposals/" + proposals.get(0).get("id").asString(), Map.of("accept", true))
                .andExpect(status().isNotFound());

        assertThat(s.client.get(s.reviewer, "/api/periods/" + theirPeriod + "/match-proposals")).isNotEmpty();
    }

    @org.springframework.beans.factory.annotation.Autowired ae.vattool.ingestion.einvoice.EinvoiceStore einvoices;

    @Test
    void evidenceOfAnotherEntityCannotBeRead() throws Exception {
        String id = s.evidence(theirs, theirPeriod, "secret.pdf");

        refused(s.client.getRaw(scoped, "/api/evidence/" + id));
        refused(s.client.getRaw(scoped, "/api/evidence/" + id + "/content"));
    }

    @Test
    void anExceptionOfAnotherEntityCannotBeWaivedOrResolved() throws Exception {
        s.client.uploadCsv(theirs, "AR", Scenario.AR_HEAD + "TAX_INVOICE,BAD,2027-01-11,100.00,5.00,NOPE,DXB-1," + Scenario.CUSTOMER + ",B2B\n");
        s.client.post(s.reviewer, "/api/periods/" + theirPeriod + "/reconcile", null);
        String id = s.client.get(s.reviewer, "/api/periods/" + theirPeriod + "/exceptions?severity=BLOCKING").get(0).get("id").asString();

        refused(s.client.postRaw(scoped, "/api/exceptions/" + id + "/resolve", Map.of("resolutionCode", "FIXED_AT_SOURCE")));
        refused(s.client.postRaw(scoped, "/api/exceptions/" + id + "/waive", Map.of("reason", "x")));
    }

    @Test
    void mySameFirmColleagueHasAccessToMyEntitySoTheRefusalsAboveAreAboutEntitiesNotBrokenAuth() throws Exception {
        s.client.getRaw(scoped, "/api/periods/" + myPeriod + "/return").andExpect(status().isOk());
    }
}
