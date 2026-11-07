package ae.vattool.returns;

import static org.assertj.core.api.Assertions.assertThat;

import ae.vattool.support.IntegrationTest;
import ae.vattool.support.Scenario;
import ae.vattool.support.TestClient;
import ae.vattool.support.TestWorld.TestUser;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/** RET-02. A box figure and its detail come from different queries, so these check they never disagree. */
class DrillDownTest extends IntegrationTest {

    TestUser preparer;
    TestClient client;

    @BeforeEach
    void setUp() {
        UUID tenant = world.newTenant("Drill-down firm " + UUID.randomUUID());
        TestUser admin = world.newUser(tenant, "FIRM", "FIRM_ADMIN");
        preparer = world.newUser(tenant, "CLIENT", "PREPARER");
        client = new TestClient(mvc, admin, preparer);
    }

    /** Two lines to a page, so every box crosses pages, and lines of different treatments have to interleave. */
    @ParameterizedTest
    @ValueSource(strings = {"g1-standalone-monthly", "g2-standalone-quarterly", "g3-tax-group", "g4-importer-drc", "g5-designated-zone"})
    void everyBoxAddsUpToItsOwnLinesInOrderAndWithoutRepeats(String name) throws Exception {
        TestClient.Golden g = client.loadGolden(name);
        UUID period = client.periodId(g.entityId(), g.expected().get("period").asString());
        JsonNode view = client.post(preparer, "/api/periods/" + period + "/recompute", null);
        JsonNode values = TestClient.JSON.readTree(view.get("version").get("boxValues").asString());

        for (JsonNode box : view.get("form")) {
            if (box.get("computed").asBoolean()) {
                continue;
            }
            String code = box.get("code").asString();
            BigDecimal net = BigDecimal.ZERO;
            List<String> taxPoints = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            long total;
            int page = 0;
            do {
                JsonNode result = client.get(preparer, "/api/periods/" + period + "/boxes/" + code + "/lines?size=2&page=" + page++);
                total = result.get("total").asLong();
                for (JsonNode row : result.get("rows")) {
                    net = net.add(new BigDecimal(row.get("line").get("netAed").asString()));
                    taxPoints.add(row.get("line").get("taxPoint").asString());
                    ids.add(row.get("line").get("id").asString());
                }
            } while (taxPoints.size() < total && page < 1000);

            assertThat(taxPoints).as("%s box %s", name, code).hasSize((int) total).isSorted();
            assertThat(ids).as("%s box %s", name, code).hasSize((int) total);
            assertThat(net.abs()).as("%s box %s", name, code).isEqualByComparingTo(TestClient.money(values.get(code), "amount").abs());
        }
    }

    @Test
    void anApprovedAdjustmentIsCountedInItsBoxAndListedWithItsLines() throws Exception {
        Scenario s = new Scenario(mvc, world);
        UUID entity = s.cleanEntity();
        UUID period = s.cleanJanuary(entity, "1000.00", "50.00");
        String id = s.client.post(s.reviewer, "/api/periods/" + period + "/adjustments", Map.of("type", "MANUAL_JOURNAL", "boxCode", "1b", "vatAed", "-10.00",
                "description", "Journal", "reason", "Correction", "evidenceId", s.evidence(entity, period, "journal.pdf"))).get("id").asString();
        s.client.post(s.approver, "/api/adjustments/" + id + "/decision", Map.of("approve", true));

        JsonNode page = s.client.get(s.preparer, "/api/periods/" + period + "/boxes/1b/lines?size=10");

        assertThat(page.get("total").asInt()).isEqualTo(2);
        List<String> kinds = new ArrayList<>();
        page.get("rows").forEach(r -> kinds.add(r.get("line").get("lineKind").asString()));
        assertThat(kinds).containsExactlyInAnyOrder("NORMAL", "ADJUSTMENT");
    }

    @Test
    void aPagePastTheEndIsEmptyAndStillGivesTheTotal() throws Exception {
        Scenario s = new Scenario(mvc, world);
        UUID entity = s.cleanEntity();
        UUID period = s.cleanJanuary(entity, "1000.00", "50.00");

        JsonNode page = s.client.get(s.preparer, "/api/periods/" + period + "/boxes/1b/lines?size=10&page=99");

        assertThat(page.get("rows")).isEmpty();
        assertThat(page.get("total").asInt()).isEqualTo(1);
    }
}
