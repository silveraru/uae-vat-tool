package ae.vattool.returns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import ae.vattool.support.IntegrationTest;
import ae.vattool.support.TestClient;
import ae.vattool.support.TestWorld.TestUser;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * Five synthetic entities through the real upload path, compared box by box with figures worked out
 * by hand before the engine existed. Boxes not listed as expected must be exactly zero.
 */
class GoldenReturnsTest extends IntegrationTest {

    TestUser admin;
    TestUser preparer;
    TestClient client;

    @BeforeEach
    void setUp() {
        UUID tenant = world.newTenant("Golden firm " + UUID.randomUUID());
        admin = world.newUser(tenant, "FIRM", "FIRM_ADMIN");
        preparer = world.newUser(tenant, "CLIENT", "PREPARER");
        client = new TestClient(mvc, admin, preparer);
    }

    private JsonNode computeReturn(TestClient.Golden g) throws Exception {
        UUID period = client.periodId(g.entityId(), g.expected().get("period").asString());
        return client.post(preparer, "/api/periods/" + period + "/recompute", null);
    }

    private static void assertBoxes(String name, JsonNode expected, JsonNode actual) {
        assertThat(actual).as(name + " produced no return").isNotNull();
        for (var entry : actual.properties()) {
            String code = entry.getKey();
            JsonNode want = expected.get(code);
            for (String column : new String[] {"amount", "vat", "adjustment"}) {
                BigDecimal got = TestClient.money(entry.getValue(), column);
                BigDecimal wanted = want == null || !want.has(column) ? BigDecimal.ZERO : new BigDecimal(want.get(column).asString());
                if (got.compareTo(wanted) != 0) {
                    fail("%s box %s %s: expected %s but the engine produced %s".formatted(name, code, column, wanted, got));
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"g1-standalone-monthly", "g2-standalone-quarterly", "g3-tax-group", "g4-importer-drc", "g5-designated-zone"})
    void everyBoxMatchesTheHandWorkedFigures(String name) throws Exception {
        TestClient.Golden g = client.loadGolden(name);

        JsonNode view = computeReturn(g);

        assertBoxes(name, g.expected().get("boxes"), TestClient.JSON.readTree(view.get("version").get("boxValues").asString()));
    }

    @Test
    void aTaxGroupShowsWhatEachMemberContributed() throws Exception {
        TestClient.Golden g = client.loadGolden("g3-tax-group");

        JsonNode view = computeReturn(g);

        UUID period = client.periodId(g.entityId(), g.expected().get("period").asString());
        assertThat(client.get(preparer, "/api/periods/" + period + "/group-members")).hasSize(3);
        JsonNode members = TestClient.JSON.readTree(view.get("version").get("memberValues").asString());
        for (var want : g.expected().get("members").properties()) {
            UUID id = g.memberIds().get(want.getKey());
            JsonNode got = members.get(id.toString());
            assertThat(got).as("member " + want.getKey()).isNotNull();
            for (var box : want.getValue().properties()) {
                assertThat(TestClient.money(got.get(box.getKey()), "amount")).as(want.getKey() + " box " + box.getKey() + " amount")
                        .isEqualByComparingTo(box.getValue().get("amount").asString());
                assertThat(TestClient.money(got.get(box.getKey()), "vat")).as(want.getKey() + " box " + box.getKey() + " vat")
                        .isEqualByComparingTo(box.getValue().get("vat").asString());
            }
        }
    }

    @Test
    void anImportAlsoRecordedInApIsCountedOnce() throws Exception {
        TestClient.Golden g = client.loadGolden("g4-importer-drc");

        JsonNode boxes = TestClient.JSON.readTree(computeReturn(g).get("version").get("boxValues").asString());

        // DEC-1 is in customs and in AP. Only the customs declaration reaches boxes 6 and 10.
        assertThat(TestClient.money(boxes.get("6"), "vat")).isEqualByComparingTo("12500.00");
        assertThat(TestClient.money(boxes.get("9"), "vat")).isEqualByComparingTo("0.00");
    }

    @Test
    void reloadingTheSameFilesChangesNothingAndCreatesNoNewVersion() throws Exception {
        TestClient.Golden g = client.loadGolden("g1-standalone-monthly");
        JsonNode first = computeReturn(g);

        JsonNode reload = client.upload(g.entityId(), "AR", "ar.csv",
                Files.readAllBytes(TestClient.GOLDEN.resolve("g1-standalone-monthly/ar.csv")));
        JsonNode second = computeReturn(g);

        assertThat(reload.get("alreadyProcessed").asBoolean()).isTrue();
        assertThat(second.get("version").get("versionNo").asInt()).isEqualTo(first.get("version").get("versionNo").asInt());
        assertThat(second.get("version").get("valuesHash").asString()).isEqualTo(first.get("version").get("valuesHash").asString());
    }
}
