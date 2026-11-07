package ae.vattool.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ae.vattool.support.IntegrationTest;
import ae.vattool.support.Scenario;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** NFR-06: tax records cannot go early, and the report shows what has aged out. */
class RetentionTest extends IntegrationTest {

    private static final List<String> WORKING_TABLES_THAT_MAY_DELETE = List.of(
            "period_recompute_request", "staff_assignment", "margin_sale", "registration_watch", "registration_watch_month", "connector", "app_user", "entity_grant");

    @Test
    void theApplicationCannotDeleteTaxRecords() throws Exception {
        List<String> deletable = new ArrayList<>();
        try (Connection c = superuserConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery("""
                SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p') AND NOT c.relispartition
                  AND has_table_privilege('vat_app', c.oid, 'DELETE')
                """)) {
            while (rs.next()) {
                deletable.add(rs.getString(1));
            }
        }

        assertThat(deletable).allMatch(WORKING_TABLES_THAT_MAY_DELETE::contains);
    }

    @Test
    void aPeriodIsKeptFiveYearsFromItsEndAndFifteenWithRealEstateRecords() throws Exception {
        Scenario s = new Scenario(mvc, world);
        UUID entity = s.cleanEntity();
        UUID p = s.client.periodId(entity, "2027-01-01");

        JsonNode standard = row(s, p, "2030-01-01");
        assertThat(standard.get("retainUntil").asString()).isEqualTo("2032-01-31");
        assertThat(standard.get("expired").asBoolean()).isFalse();
        assertThat(row(s, p, "2032-02-01").get("expired").asBoolean()).isTrue();

        s.client.post(s.admin, "/api/periods/" + p + "/real-estate-records", Map.of("realEstate", true, "reason", "Holds a property sale"));

        JsonNode longer = row(s, p, "2032-02-01");
        assertThat(longer.get("retainUntil").asString()).isEqualTo("2042-01-31");
        assertThat(longer.get("expired").asBoolean()).isFalse();
    }

    @Test
    void aLegalHoldStopsAPeriodExpiring() throws Exception {
        Scenario s = new Scenario(mvc, world);
        UUID entity = s.cleanEntity();
        UUID p = s.client.periodId(entity, "2027-01-01");

        s.client.post(s.reviewer, "/api/entities/" + entity + "/audit-cases", Map.of("ftaReference", "F-1", "notifiedOn", "2027-06-01", "periodFrom", "2027-01-01", "periodTo", "2027-01-31"));

        JsonNode held = row(s, p, "2040-01-01");
        assertThat(held.get("activeHolds").asInt()).isEqualTo(1);
        assertThat(held.get("expired").asBoolean()).isFalse();
    }

    @Test
    void onlyTheFirmAdminSeesTheReport() throws Exception {
        Scenario s = new Scenario(mvc, world);

        s.client.getRaw(s.reviewer, "/api/retention").andExpect(status().isForbidden());
    }

    @Test
    void theReportFindsOneClientByPartOfItsName() throws Exception {
        Scenario s = new Scenario(mvc, world);
        UUID entity = s.client.simpleEntity("2027-01-01");
        String name = s.client.get(s.admin, "/api/entities/" + entity).get("legalName").asString();
        s.client.periodId(entity, "2027-01-01");

        JsonNode found = s.client.get(s.admin, "/api/retention?client=" + name.substring(name.length() - 12).toUpperCase());
        assertThat(found).isNotEmpty().allSatisfy(r -> assertThat(r.get("entityId").asString()).isEqualTo(entity.toString()));
        // A percent sign is a character to find, not a wildcard that returns every client.
        assertThat(s.client.get(s.admin, "/api/retention?client=%25")).isEmpty();
    }

    private JsonNode row(Scenario s, UUID period, String asOf) throws Exception {
        for (JsonNode r : s.client.get(s.admin, "/api/retention?asOf=" + asOf)) {
            if (r.get("periodId").asString().equals(period.toString())) {
                return r;
            }
        }
        throw new AssertionError("No retention row for " + period);
    }
}
