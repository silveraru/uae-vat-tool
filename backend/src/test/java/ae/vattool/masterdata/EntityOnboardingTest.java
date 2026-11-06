package ae.vattool.masterdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ae.vattool.support.IntegrationTest;
import ae.vattool.support.TestWorld.TestUser;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

class EntityOnboardingTest extends IntegrationTest {

    private static final AtomicLong TRN = new AtomicLong(100_000_000_100_000L);

    @Autowired JdbcClient jdbc;

    UUID tenant;
    TestUser admin;

    @BeforeEach
    void setUp() {
        tenant = world.newTenant("Onboarding firm " + UUID.randomUUID());
        admin = world.newUser(tenant, "FIRM", "FIRM_ADMIN");
    }

    private static String nextTrn() {
        return String.valueOf(TRN.incrementAndGet());
    }

    private static Map<String, Object> standalone(String trn) {
        Map<String, Object> m = new HashMap<>();
        m.put("legalName", "Client LLC");
        m.put("trn", trn);
        m.put("entityKind", "STANDALONE");
        m.put("filingFrequency", "MONTHLY");
        m.put("firstPeriodStart", "2027-01-01");
        return m;
    }

    private String createEntity() throws Exception {
        return body(postJson(admin, "/api/entities", standalone(nextTrn())).andExpect(status().isCreated())).get("id").asString();
    }

    @Test
    void onboardingCreatesTheEntityAndItsFirstPeriodWithTheCorrectDueDate() throws Exception {
        String id = createEntity();

        JsonNode periods = body(getAs(admin, "/api/entities/" + id + "/periods").andExpect(status().isOk()));

        assertThat(periods).hasSize(1);
        assertThat(periods.get(0).get("periodStart").asString()).isEqualTo("2027-01-01");
        assertThat(periods.get(0).get("periodEnd").asString()).isEqualTo("2027-01-31");
        assertThat(periods.get(0).get("dueDate").asString()).isEqualTo("2027-02-28");
        assertThat(periods.get(0).get("state").asString()).isEqualTo("OPEN");
    }

    @Test
    void aTrnThatIsNotFifteenDigitsIsRefused() throws Exception {
        postJson(admin, "/api/entities", standalone("12345"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("TRN_INVALID"));
    }

    @Test
    void theSameTrnCannotBeOnboardedTwice() throws Exception {
        String trn = nextTrn();
        postJson(admin, "/api/entities", standalone(trn)).andExpect(status().isCreated());

        postJson(admin, "/api/entities", standalone(trn))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRN_ALREADY_REGISTERED"));
    }

    @Test
    void aFirstPeriodNotStartingOnTheFirstOfTheMonthIsRefused() throws Exception {
        Map<String, Object> in = standalone(nextTrn());
        in.put("firstPeriodStart", "2027-01-15");

        postJson(admin, "/api/entities", in)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("FIRST_PERIOD_INVALID"));
    }

    @Test
    void aTaxGroupHasARepresentativeAndMembersThatFileThroughIt() throws Exception {
        Map<String, Object> rep = standalone(nextTrn());
        rep.put("entityKind", "GROUP_REPRESENTATIVE");
        String repId = body(postJson(admin, "/api/entities", rep).andExpect(status().isCreated())).get("id").asString();

        Map<String, Object> member = new HashMap<>();
        member.put("legalName", "Member Co");
        member.put("entityKind", "GROUP_MEMBER");
        member.put("groupRepId", repId);
        String memberId = body(postJson(admin, "/api/entities", member).andExpect(status().isCreated())).get("id").asString();

        JsonNode members = body(getAs(admin, "/api/entities/" + repId + "/members"));
        assertThat(members).hasSize(2);
        // A member has no calendar of its own.
        assertThat(body(getAs(admin, "/api/entities/" + memberId + "/periods"))).isEmpty();
    }

    @Test
    void aMemberMustPointAtARepresentative() throws Exception {
        String notARep = createEntity();
        Map<String, Object> member = new HashMap<>();
        member.put("legalName", "Member Co");
        member.put("entityKind", "GROUP_MEMBER");
        member.put("groupRepId", notARep);

        postJson(admin, "/api/entities", member)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("GROUP_REP_INVALID"));
    }

    @Test
    void establishmentsCarryAnEmirateAndDesignatedZoneFlag() throws Exception {
        String id = createEntity();
        postJson(admin, "/api/entities/" + id + "/establishments", Map.of(
                "code", "DXB-1", "name", "Dubai office", "emirate", "DXB", "designatedZone", true, "zoneName", "JAFZA"))
                .andExpect(status().isCreated());

        postJson(admin, "/api/entities/" + id + "/establishments", Map.of(
                "code", "X", "name", "Nowhere", "emirate", "XXX", "designatedZone", false))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("EMIRATE_INVALID"));

        JsonNode list = body(getAs(admin, "/api/entities/" + id + "/establishments"));
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("designatedZone").asBoolean()).isTrue();
    }

    @Test
    void mappingVersionsAreKeptAndSelectedByDate() throws Exception {
        String id = createEntity();
        postJson(admin, "/api/entities/" + id + "/mappings", Map.of(
                "effectiveFrom", "2027-01-01",
                "entries", List.of(Map.of("matchType", "TAX_CODE", "sourceCode", "S5", "treatment", "STANDARD"))))
                .andExpect(status().isCreated());
        postJson(admin, "/api/entities/" + id + "/mappings", Map.of(
                "effectiveFrom", "2027-03-01", "note", "S5 corrected",
                "entries", List.of(Map.of("matchType", "TAX_CODE", "sourceCode", "S5", "treatment", "EXEMPT"))))
                .andExpect(status().isCreated());

        JsonNode history = body(getAs(admin, "/api/entities/" + id + "/mappings"));
        assertThat(history).hasSize(2);
        assertThat(history.get(0).get("versionNo").asInt()).isEqualTo(2);
        assertThat(history.get(1).get("versionNo").asInt()).isEqualTo(1);
    }

    @Test
    void aCodeMappedTwiceInOneVersionIsRefused() throws Exception {
        String id = createEntity();

        postJson(admin, "/api/entities/" + id + "/mappings", Map.of(
                "effectiveFrom", "2027-01-01",
                "entries", List.of(
                        Map.of("matchType", "TAX_CODE", "sourceCode", "S5", "treatment", "STANDARD"),
                        Map.of("matchType", "TAX_CODE", "sourceCode", "s5 ", "treatment", "EXEMPT"))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("MAPPING_DUPLICATE"));
    }

    @Test
    void publishedMappingsCannotBeChangedEvenByTheApplicationRole() throws Exception {
        String id = createEntity();
        postJson(admin, "/api/entities/" + id + "/mappings", Map.of(
                "effectiveFrom", "2027-01-01",
                "entries", List.of(Map.of("matchType", "TAX_CODE", "sourceCode", "S5", "treatment", "STANDARD"))))
                .andExpect(status().isCreated());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> world.inTenant(tenant,
                () -> jdbc.sql("UPDATE mapping_entry SET treatment = 'EXEMPT'").update()))
                .rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void aTaxAgentLinkAndSignatoriesAreRecorded() throws Exception {
        String id = createEntity();
        postJson(admin, "/api/entities/" + id + "/agents", Map.of(
                "agencyName", "Agency One", "tan", "TAN1", "agentName", "Ali", "taan", "TAAN1", "validFrom", "2027-01-01"))
                .andExpect(status().isCreated());
        // Appointing a new agent ends the old appointment.
        postJson(admin, "/api/entities/" + id + "/agents", Map.of(
                "agencyName", "Agency One", "tan", "TAN1", "agentName", "Sara", "taan", "TAAN2", "validFrom", "2027-02-01"))
                .andExpect(status().isCreated());
        postJson(admin, "/api/entities/" + id + "/signatories", Map.of("name", "CFO", "email", "cfo@test.example"))
                .andExpect(status().isCreated());

        JsonNode agents = body(getAs(admin, "/api/entities/" + id + "/agents"));
        assertThat(agents).hasSize(1);
        assertThat(agents.get(0).get("taan").asString()).isEqualTo("TAAN2");
        assertThat(body(getAs(admin, "/api/entities/" + id + "/signatories"))).hasSize(1);
    }

    @Test
    void onlyAFirmAdminCanOnboard() throws Exception {
        TestUser preparer = world.newUser(tenant, "CLIENT", "PREPARER");

        postJson(preparer, "/api/entities", standalone(nextTrn())).andExpect(status().isForbidden());
    }

    @Test
    void usersSeeOnlyEntitiesTheyAreGrantedAndNothingElse() throws Exception {
        String granted = createEntity();
        String other = createEntity();
        TestUser preparer = world.newUser(tenant, "CLIENT");
        world.grantOnEntity(preparer, UUID.fromString(granted), "PREPARER");

        JsonNode visible = body(getAs(preparer, "/api/entities"));

        assertThat(visible).hasSize(1);
        assertThat(visible.get(0).get("id").asString()).isEqualTo(granted);
        getAs(preparer, "/api/entities/" + other).andExpect(status().isForbidden());
    }

    @Test
    void anEntityInAnotherTenantIsInvisibleEvenToItsOwnAdminRoleElsewhere() throws Exception {
        String mine = createEntity();
        UUID otherTenant = world.newTenant("Other firm " + UUID.randomUUID());
        TestUser otherAdmin = world.newUser(otherTenant, "FIRM", "FIRM_ADMIN");

        getAs(otherAdmin, "/api/entities/" + mine).andExpect(status().isNotFound());
        assertThat(body(getAs(otherAdmin, "/api/entities"))).isEmpty();
    }

    @Test
    void externalAuditorAccessMustHaveAnEndDate() throws Exception {
        String id = createEntity();
        TestUser auditor = world.newUser(tenant, "EXTERNAL");

        postJson(admin, "/api/entities/" + id + "/grants", Map.of("userId", auditor.id(), "role", "AUDITOR"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("AUDITOR_ACCESS_MUST_EXPIRE"));

        postJson(admin, "/api/entities/" + id + "/grants", Map.of(
                "userId", auditor.id(), "role", "AUDITOR", "validTo", OffsetDateTime.now().plusDays(30).toString()))
                .andExpect(status().isCreated());
    }

    @Test
    void expiredAndRevokedAccessStopsWorkingImmediately() throws Exception {
        String id = createEntity();
        TestUser auditor = world.newUser(tenant, "EXTERNAL");
        String expired = OffsetDateTime.now().minusMinutes(1).toString();
        postJson(admin, "/api/entities/" + id + "/grants", Map.of("userId", auditor.id(), "role", "AUDITOR", "validTo", expired))
                .andExpect(status().isCreated());
        getAs(auditor, "/api/entities/" + id).andExpect(status().isForbidden());

        TestUser preparer = world.newUser(tenant, "CLIENT");
        String grantId = body(postJson(admin, "/api/entities/" + id + "/grants", Map.of("userId", preparer.id(), "role", "PREPARER"))
                .andExpect(status().isCreated())).get("id").asString();
        getAs(preparer, "/api/entities/" + id).andExpect(status().isOk());

        mvc.perform(delete("/api/entities/" + id + "/grants/" + grantId).with(admin.auth())).andExpect(status().isNoContent());
        getAs(preparer, "/api/entities/" + id).andExpect(status().isForbidden());
    }

    @Test
    void onboardingIsWrittenToTheAuditLog() throws Exception {
        String id = createEntity();

        int events = world.queryInTenant(tenant, () -> jdbc.sql(
                "SELECT count(*) FROM audit_event WHERE action = 'ENTITY_CREATED' AND object_id = ?")
                .param(id).query(Integer.class).single());

        assertThat(events).isEqualTo(1);
    }
}
