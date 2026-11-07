package ae.vattool.ingestion.connectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ae.vattool.ingestion.FxFeedService;
import ae.vattool.support.IntegrationTest;
import ae.vattool.support.Scenario;
import ae.vattool.support.TestClient;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;

/** ING-04 and ING-08, against a stub server, since no ERP or Central Bank source is reachable from a test. */
class ConnectorTest extends IntegrationTest {

    @Autowired ConnectorSyncJob job;
    @Autowired FxFeedService fx;
    @Autowired JdbcClient jdbc;

    Scenario s;
    UUID entity;
    HttpServer server;
    final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    final List<String> auth = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() throws Exception {
        s = new Scenario(mvc, world);
        entity = s.cleanEntity();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void serve(String path, int code, String... pages) {
        int[] page = {0};
        server.createContext(path, ex -> {
            requests.add(ex.getRequestURI().toString());
            auth.add(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] body = pages[Math.min(page[0]++, pages.length - 1)].getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(code, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
    }

    private JsonNode connect(String system) throws Exception {
        return s.client.post(s.admin, "/api/entities/" + entity + "/connectors", Map.of("system", system, "baseUrl", base(), "authKind", "BEARER", "secret", "token-1", "kinds", List.of("AR"), "enabled", true));
    }

    @Test
    void everyProfileSaysItHasNotBeenRunAgainstALiveSystem() throws Exception {
        JsonNode profiles = s.client.get(s.preparer, "/api/connector-profiles");

        assertThat(profiles).hasSize(7);
        assertThat(profiles.toString()).contains("SAP", "ORACLE_FUSION", "DYNAMICS_365", "NETSUITE", "ODOO", "ZOHO_BOOKS", "TALLY");
        for (JsonNode p : profiles) {
            assertThat(p.get("status").asString()).isEqualTo("UNVERIFIED");
        }
    }

    @Test
    void aPullFollowsPagesMapsFieldsAndLoadsThroughNormalIngestion() throws Exception {
        String one = "{\"invoices\":[" + String.join(",", java.util.Collections.nCopies(1, "{}")) + "]}";
        assertThat(one).isNotEmpty();
        StringBuilder first = new StringBuilder("{\"invoices\":[");
        for (int i = 0; i < 200; i++) {
            first.append(i == 0 ? "" : ",").append("{\"invoice_number\":\"Z-").append(i).append("\",\"date\":\"2027-01-10\",\"sub_total\":\"100.0000\",\"tax_total\":5,\"currency_code\":\"AED\",\"customer_name\":\"Acme\"}");
        }
        first.append("]}");
        serve("/books/v3/invoices", 200, first.toString(), "{\"invoices\":[{\"invoice_number\":\"Z-LAST\",\"date\":\"2027-01-11T00:00:00+0400\",\"sub_total\":\"100.00\",\"tax_total\":\"5.00\",\"currency_code\":\"AED\",\"customer_name\":\"Acme\"}]}");
        String id = connect("ZOHO_BOOKS").get("id").asString();

        JsonNode result = s.client.post(s.preparer, "/api/connectors/" + id + "/sync", Map.of("kind", "AR", "from", "2027-01-01", "to", "2027-01-31"));

        assertThat(result.get("run").get("status").asString()).isEqualTo("OK");
        assertThat(result.get("run").get("rowsFetched").asInt()).isEqualTo(201);
        // No tax code in the export, so the rows load as lines a person must classify.
        assertThat(result.get("load").get("batch").get("rowsRejected").asInt()).isZero();
        assertThat(result.get("load").get("batch").get("rowsLoaded").asInt()).isEqualTo(201);
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0)).contains("date_start=2027-01-01", "page=1", "per_page=200");
        assertThat(requests.get(1)).contains("page=2");
        assertThat(auth.getFirst()).isEqualTo("Bearer token-1");
    }

    @Test
    void reloadingTheSamePullChangesNothing() throws Exception {
        serve("/books/v3/invoices", 200, "{\"invoices\":[{\"invoice_number\":\"Z-1\",\"date\":\"2027-01-10\",\"sub_total\":\"100\",\"tax_total\":\"5\"}]}");
        String id = connect("ZOHO_BOOKS").get("id").asString();

        s.client.post(s.preparer, "/api/connectors/" + id + "/sync", Map.of("kind", "AR", "from", "2027-01-01", "to", "2027-01-31"));
        JsonNode again = s.client.post(s.preparer, "/api/connectors/" + id + "/sync", Map.of("kind", "AR", "from", "2027-01-01", "to", "2027-01-31"));

        assertThat(again.get("load").get("alreadyProcessed").asBoolean()).isTrue();
    }

    @Test
    void anODataDateAndFourDecimalsBecomeTheTemplatesForm() {
        assertThat(RowMapper.normalise("doc_date", "/Date(1800000000000)/")).isEqualTo("2027-01-15");
        assertThat(RowMapper.normalise("net_amount", "100.5000")).isEqualTo("100.50");
        // Real extra precision stays, so normal validation rejects it rather than rounding silently.
        assertThat(RowMapper.normalise("net_amount", "100.5055")).isEqualTo("100.5055");
    }

    @Test
    void aFailedCallIsRecordedAndReported() throws Exception {
        serve("/books/v3/invoices", 503, "{}");
        String id = connect("ZOHO_BOOKS").get("id").asString();

        s.client.postRaw(s.preparer, "/api/connectors/" + id + "/sync", Map.of("kind", "AR", "from", "2027-01-01", "to", "2027-01-31"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONNECTOR_REFUSED"));

        JsonNode run = s.client.get(s.preparer, "/api/connectors/" + id + "/runs").get(0);
        assertThat(run.get("status").asString()).isEqualTo("FAILED");
        assertThat(run.get("error").asString()).contains("503");
    }

    @Test
    void aResponseOfTheWrongShapeSaysTheMappingNeedsWork() throws Exception {
        serve("/books/v3/invoices", 200, "{\"somethingElse\":[]}");
        String id = connect("ZOHO_BOOKS").get("id").asString();

        s.client.postRaw(s.preparer, "/api/connectors/" + id + "/sync", Map.of("kind", "AR", "from", "2027-01-01", "to", "2027-01-31"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONNECTOR_BAD_RESPONSE"));
    }

    @Test
    void theDailyJobPullsFromWhereTheLastRunEnded() throws Exception {
        serve("/books/v3/invoices", 200, "{\"invoices\":[]}");
        String id = connect("ZOHO_BOOKS").get("id").asString();
        s.client.post(s.preparer, "/api/connectors/" + id + "/sync", Map.of("kind", "AR", "from", "2027-01-01", "to", "2027-01-31"));

        int ok = world.queryInTenant(s.tenant, () -> job.run(LocalDate.parse("2027-02-05")));

        assertThat(ok).isEqualTo(1);
        assertThat(requests.getLast()).contains("date_start=2027-02-01", "date_end=2027-02-05");
    }

    @Test
    void aFileOnlySystemTakesNoConnectionButLoadsItsExport() throws Exception {
        s.client.postRaw(s.admin, "/api/entities/" + entity + "/connectors", Map.of("system", "TALLY", "baseUrl", base(), "authKind", "NONE", "enabled", false))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("CONNECTOR_FILE_ONLY"));

        String csv = "Voucher No.,Date,Taxable Value,VAT Amount,TRN,Particulars\nV-1,2027-01-10,1000.00,50.00," + Scenario.CUSTOMER + ",Acme\n";
        JsonNode load = TestClient.read(mvc.perform(multipart("/api/entities/" + entity + "/ingest-export/TALLY/AR")
                .file(new MockMultipartFile("file", "register.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8))).with(s.preparer.auth())).andExpect(status().isOk()));

        assertThat(load.get("batch").get("rowsLoaded").asInt()).isEqualTo(1);
    }

    @Test
    void anExportMissingAColumnSaysWhich() throws Exception {
        mvc.perform(multipart("/api/entities/" + entity + "/ingest-export/TALLY/AR")
                        .file(new MockMultipartFile("file", "register.csv", "text/csv", "Voucher No.,Date\nV-1,2027-01-10\n".getBytes(StandardCharsets.UTF_8))).with(s.preparer.auth()))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("CONNECTOR_COLUMNS_MISSING"));
    }

    @Test
    void theSecretIsEncryptedAndNeverReturned() throws Exception {
        JsonNode c = connect("ZOHO_BOOKS");

        assertThat(c.toString()).doesNotContain("token-1");
        assertThat(c.get("hasSecret").asBoolean()).isTrue();
        String stored = world.queryInTenant(s.tenant, () -> jdbc.sql("SELECT secret_encrypted FROM connector").query(String.class).single());
        assertThat(stored).doesNotContain("token-1");
    }

    @Test
    void onlyAnAdminCanConnectAndAPreparerCanPull() throws Exception {
        s.client.postRaw(s.preparer, "/api/entities/" + entity + "/connectors", Map.of("system", "ZOHO_BOOKS", "baseUrl", base(), "authKind", "NONE", "enabled", false)).andExpect(status().isForbidden());
    }

    // ---- FX feed ----

    @Test
    void aFeedAddsRatesReleasesHeldRowsAndNeverChangesAnExistingRate() throws Exception {
        s.client.uploadCsv(entity, "AR", "doc_type,doc_number,doc_date,net_amount,vat_amount,tax_code,establishment_code,counterparty_trn,currency,customer_type\n"
                + "TAX_INVOICE,USD-1,2027-01-10,100.00,5.00,S5,DXB-1," + Scenario.CUSTOMER + ",USD,B2B\n");
        UUID period = s.client.periodId(entity, "2027-01-01");
        s.client.post(s.preparer, "/api/periods/" + period + "/reconcile", null);
        assertThat(s.client.get(s.preparer, "/api/periods/" + period + "/exceptions?status=OPEN").toString()).contains("\"ruleId\":\"D01\"");
        serve("/rates.csv", 200, "currency,rate_date,aed_per_unit,source\nUSD,2027-01-10,3.6725,CBUAE\n");

        FxFeedService.Pulled pulled = world.queryInTenant(s.tenant, () -> fx.pull(base() + "/rates.csv"));

        assertThat(pulled.added()).isEqualTo(1);
        s.client.post(s.preparer, "/api/periods/" + period + "/reconcile", null);
        assertThat(s.client.get(s.preparer, "/api/periods/" + period + "/exceptions?status=OPEN").toString()).doesNotContain("\"ruleId\":\"D01\"");

        server.removeContext("/rates.csv");
        serve("/rates.csv", 200, "currency,rate_date,aed_per_unit\nUSD,2027-01-10,9.9999\n");
        assertThat(world.queryInTenant(s.tenant, () -> fx.pull(base() + "/rates.csv")).added()).isZero();
    }

    @Test
    void withNoFeedConfiguredItSaysSo() throws Exception {
        s.client.postRaw(s.admin, "/api/fx/feed/pull", null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FX_FEED_NOT_CONFIGURED"));
    }
}
