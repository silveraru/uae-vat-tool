package ae.vattool.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ae.vattool.support.TestWorld.TestUser;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Drives the API the way a client would, so tests cover the real path from file to return. */
public class TestClient {

    private static final AtomicLong TRN = new AtomicLong(100_000_000_500_000L);
    public static final JsonMapper JSON = JsonMapper.builder().build();
    public static final Path GOLDEN = Path.of("../fixtures/golden");

    private final MockMvc mvc;
    private final TestUser admin;
    private final TestUser preparer;

    public TestClient(MockMvc mvc, TestUser admin, TestUser preparer) {
        this.mvc = mvc;
        this.admin = admin;
        this.preparer = preparer;
    }

    public static String nextTrn() {
        return String.valueOf(TRN.incrementAndGet());
    }

    public JsonNode post(TestUser user, String path, Object body) throws Exception {
        ResultActions r = postRaw(user, path, body).andExpect(status().is2xxSuccessful());
        return read(r);
    }

    public ResultActions postRaw(TestUser user, String path, Object body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(path).with(user.auth()).contentType("application/json")
                .content(body == null ? "{}" : JSON.writeValueAsString(body)));
    }

    public JsonNode get(TestUser user, String path) throws Exception {
        return read(mvc.perform(MockMvcRequestBuilders.get(path).with(user.auth())).andExpect(status().isOk()));
    }

    public ResultActions getRaw(TestUser user, String path) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(path).with(user.auth()));
    }

    public static JsonNode read(ResultActions r) throws Exception {
        return JSON.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** A standalone monthly entity in Dubai with the usual tax codes, starting January 2027. */
    public UUID simpleEntity() throws Exception {
        return simpleEntity("2027-01-01");
    }

    public UUID simpleEntity(String goLive) throws Exception {
        Map<String, Object> in = new HashMap<>();
        in.put("legalName", "Client " + UUID.randomUUID());
        in.put("trn", nextTrn());
        in.put("entityKind", "STANDALONE");
        in.put("filingFrequency", "MONTHLY");
        in.put("firstPeriodStart", "2027-01-01");
        in.put("einvoiceGoLive", goLive);
        UUID id = UUID.fromString(post(admin, "/api/entities", in).get("id").asString());
        post(admin, "/api/entities/" + id + "/establishments", Map.of("code", "DXB-1", "name", "Dubai", "emirate", "DXB", "designatedZone", false));
        post(admin, "/api/entities/" + id + "/mappings", Map.of("effectiveFrom", "2018-01-01", "entries", List.of(
                Map.of("matchType", "TAX_CODE", "sourceCode", "S5", "treatment", "STANDARD"),
                Map.of("matchType", "TAX_CODE", "sourceCode", "Z0", "treatment", "ZERO_RATED"),
                Map.of("matchType", "TAX_CODE", "sourceCode", "EX", "treatment", "EXEMPT"),
                Map.of("matchType", "TAX_CODE", "sourceCode", "IM", "treatment", "IMPORT"),
                Map.of("matchType", "GL_ACCOUNT", "sourceCode", "6100", "blockedCategory", "ENTERTAINMENT"))));
        return id;
    }

    public UUID periodId(UUID entityId, String startsOn) throws Exception {
        post(admin, "/api/entities/" + entityId + "/periods/ensure", startsOn);
        JsonNode periods = get(admin, "/api/entities/" + entityId + "/periods");
        for (JsonNode p : periods) {
            if (p.get("periodStart").asString().equals(startsOn)) {
                return UUID.fromString(p.get("id").asString());
            }
        }
        throw new IllegalStateException("No period starting " + startsOn);
    }

    public JsonNode upload(UUID entityId, String kind, String fileName, byte[] content) throws Exception {
        return read(uploadRaw(entityId, kind, fileName, content).andExpect(status().isOk()));
    }

    public ResultActions uploadRaw(UUID entityId, String kind, String fileName, byte[] content) throws Exception {
        return mvc.perform(multipart("/api/entities/" + entityId + "/ingest/" + kind)
                .file(new MockMultipartFile("file", fileName, "text/csv", content)).with(preparer.auth()));
    }

    public JsonNode uploadCsv(UUID entityId, String kind, String csv) throws Exception {
        return upload(entityId, kind, kind + "-" + UUID.randomUUID() + ".csv", csv.getBytes(StandardCharsets.UTF_8));
    }

    public record Golden(UUID entityId, Map<String, UUID> memberIds, JsonNode expected) {}

    /** Sets up a golden fixture through the API and loads its files. */
    public Golden loadGolden(String name) throws Exception {
        Path dir = GOLDEN.resolve(name);
        JsonNode setup = JSON.readTree(Files.readString(dir.resolve("setup.json")));
        JsonNode created = post(admin, "/api/entities", setup.get("entity"));
        UUID entity = UUID.fromString(created.get("id").asString());
        Map<String, UUID> members = new HashMap<>();
        members.put(created.get("legalName").asString(), entity);
        for (JsonNode est : setup.get("establishments")) {
            post(admin, "/api/entities/" + entity + "/establishments", est);
        }
        if (setup.has("members")) {
            for (JsonNode m : setup.get("members")) {
                Map<String, Object> in = new HashMap<>();
                in.put("legalName", m.get("legalName").asString());
                in.put("entityKind", "GROUP_MEMBER");
                in.put("groupRepId", entity);
                UUID member = UUID.fromString(post(admin, "/api/entities", in).get("id").asString());
                members.put(m.get("legalName").asString(), member);
                for (JsonNode est : m.get("establishments")) {
                    post(admin, "/api/entities/" + member + "/establishments", est);
                }
            }
        }
        List<JsonNode> entries = new ArrayList<>();
        setup.get("mappings").forEach(entries::add);
        post(admin, "/api/entities/" + entity + "/mappings", Map.of("effectiveFrom", "2018-01-01", "entries", entries));
        for (JsonNode cp : setup.get("counterparties")) {
            post(admin, "/api/counterparties", cp);
        }
        for (String[] f : new String[][] {{"fx.csv", "FX"}, {"ar.csv", "AR"}, {"ap.csv", "AP"}, {"customs.csv", "CUSTOMS"}, {"gl.csv", "GL"}}) {
            Path file = dir.resolve(f[0]);
            if (Files.exists(file)) {
                JsonNode load = upload(entity, f[1], f[0], Files.readAllBytes(file));
                int rejected = load.get("batch").get("rowsRejected").asInt();
                int held = load.get("batch").get("rowsHeld").asInt();
                if (rejected != 0 || held != 0) {
                    throw new IllegalStateException(name + "/" + f[0] + " rejected " + rejected + " and held " + held);
                }
            }
        }
        return new Golden(entity, members, JSON.readTree(Files.readString(dir.resolve("expected.json"))));
    }

    public static BigDecimal money(JsonNode box, String column) {
        return new BigDecimal(box.get(column).asString());
    }
}
