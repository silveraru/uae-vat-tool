package ae.vattool.practice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ae.vattool.platform.crypto.TenantKeys;
import ae.vattool.support.IntegrationTest;
import ae.vattool.support.Scenario;
import ae.vattool.support.TestClient;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** PRC-02 and PRC-03: staff assignment and workload, and webhooks to a practice system. */
class PracticeToolsTest extends IntegrationTest {

    @Autowired WebhookDispatcher dispatcher;
    @Autowired JdbcClient jdbc;
    @Autowired TenantKeys keys;

    Scenario s;
    UUID entity;
    HttpServer server;
    final List<String> received = Collections.synchronizedList(new ArrayList<>());
    final List<String> signatures = Collections.synchronizedList(new ArrayList<>());
    final AtomicInteger respondWith = new AtomicInteger(200);

    @BeforeEach
    void setUp() throws Exception {
        s = new Scenario(mvc, world);
        entity = s.cleanEntity();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String startReceiver() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", ex -> {
            received.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            signatures.add(ex.getRequestHeaders().getFirst("X-VatTool-Signature"));
            ex.sendResponseHeaders(respondWith.get(), -1);
            ex.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    private int dispatch() {
        return world.queryInTenant(s.tenant, () -> dispatcher.run());
    }

    // ---- Assignment and workload ----

    @Test
    void assignmentsShowInTheWorkloadWithOverdueAndDueSoonCounts() throws Exception {
        UUID january = s.cleanJanuary(entity, "1000.00", "50.00");
        s.client.post(s.admin, "/api/entities/" + entity + "/assignments", Map.of("userId", s.reviewer.id().toString(), "duty", "REVIEWER"));
        assertThat(january).isNotNull();

        JsonNode overview = s.client.get(s.admin, "/api/practice/workload");

        JsonNode row = overview.get("people").get(0);
        assertThat(row.get("userId").asString()).isEqualTo(s.reviewer.id().toString());
        assertThat(row.get("entities").asInt()).isEqualTo(1);
        assertThat(row.get("openPeriods").asInt()).isEqualTo(1);
    }

    @Test
    void aClientWithNobodyAssignedIsListed() throws Exception {
        s.cleanJanuary(entity, "1000.00", "50.00");

        assertThat(s.client.get(s.admin, "/api/practice/workload").get("unassigned").toString()).contains(entity.toString());

        s.client.post(s.admin, "/api/entities/" + entity + "/assignments", Map.of("userId", s.reviewer.id().toString(), "duty", "REVIEWER"));

        assertThat(s.client.get(s.admin, "/api/practice/workload").get("unassigned").toString()).doesNotContain(entity.toString());
    }

    @Test
    void assigningSomeoneWithoutAccessIsShownNotHidden() throws Exception {
        var outsider = world.newUser(s.tenant, "FIRM");

        JsonNode rows = s.client.post(s.admin, "/api/entities/" + entity + "/assignments", Map.of("userId", outsider.id().toString(), "duty", "PREPARER"));

        assertThat(rows.get(0).get("hasAccess").asBoolean()).isFalse();
    }

    @Test
    void anAssignmentCanBeRemovedAndAddingTwiceIsHarmless() throws Exception {
        Map<String, String> in = Map.of("userId", s.reviewer.id().toString(), "duty", "REVIEWER");
        s.client.post(s.admin, "/api/entities/" + entity + "/assignments", in);
        JsonNode rows = s.client.post(s.admin, "/api/entities/" + entity + "/assignments", in);
        assertThat(rows).hasSize(1);

        mvc.perform(delete("/api/entities/" + entity + "/assignments/" + rows.get(0).get("id").asString()).with(s.admin.auth())).andExpect(status().isOk());

        assertThat(s.client.get(s.admin, "/api/entities/" + entity + "/assignments")).isEmpty();
    }

    @Test
    void onlyThoseWithThePracticePermissionCanAssign() throws Exception {
        s.client.postRaw(s.preparer, "/api/entities/" + entity + "/assignments", Map.of("userId", s.reviewer.id().toString(), "duty", "REVIEWER")).andExpect(status().isForbidden());
        s.client.getRaw(s.preparer, "/api/practice/workload").andExpect(status().isForbidden());
    }

    // ---- Webhooks ----

    private JsonNode subscribe(String url, String... events) throws Exception {
        return s.client.post(s.admin, "/api/practice/webhooks", Map.of("name", "Practice system", "url", url, "events", List.of(events)));
    }

    @Test
    void aSubscribedEventIsDeliveredOnceAndSigned() throws Exception {
        JsonNode created = subscribe(startReceiver(), "RETURN_APPROVED_AND_LOCKED");
        String secret = created.get("secret").asString();
        UUID period = s.cleanJanuary(entity, "1000.00", "50.00");
        s.toApproved(period);

        assertThat(dispatch()).isEqualTo(1);
        assertThat(dispatch()).isZero();

        assertThat(received).hasSize(1);
        JsonNode body = TestClient.JSON.readTree(received.getFirst());
        assertThat(body.get("type").asString()).isEqualTo("RETURN_APPROVED_AND_LOCKED");
        assertThat(body.get("entityId").asString()).isEqualTo(entity.toString());
        assertThat(body.get("eventId").asString()).startsWith(s.tenant.toString());
        String header = signatures.getFirst();
        String timestamp = header.substring(2, header.indexOf(','));
        assertThat(header.substring(header.indexOf("v1=") + 3)).isEqualTo(WebhookDispatcher.sign(secret, timestamp + "." + received.getFirst()));
    }

    @Test
    void anEventNotSubscribedToIsNotSent() throws Exception {
        subscribe(startReceiver(), "RETURN_FILED");
        UUID period = s.cleanJanuary(entity, "1000.00", "50.00");
        s.toApproved(period);

        assertThat(dispatch()).isZero();
        assertThat(received).isEmpty();
    }

    @Test
    void aNewEndpointDoesNotReceiveThePast() throws Exception {
        UUID period = s.cleanJanuary(entity, "1000.00", "50.00");
        s.toApproved(period);

        subscribe(startReceiver(), "RETURN_APPROVED_AND_LOCKED");

        assertThat(dispatch()).isZero();
    }

    @Test
    void aFailedDeliveryIsRetriedLaterAndRecorded() throws Exception {
        String id = subscribe(startReceiver(), "RETURN_APPROVED_AND_LOCKED").get("endpoint").get("id").asString();
        UUID period = s.cleanJanuary(entity, "1000.00", "50.00");
        s.toApproved(period);
        respondWith.set(500);

        assertThat(dispatch()).isZero();

        JsonNode delivery = s.client.get(s.admin, "/api/practice/webhooks/" + id + "/deliveries").get(0);
        assertThat(delivery.get("status").asString()).isEqualTo("PENDING");
        assertThat(delivery.get("attempts").asInt()).isEqualTo(1);
        assertThat(delivery.get("lastStatus").asInt()).isEqualTo(500);
        // Not due yet, so nothing is tried again until the delay passes.
        assertThat(dispatch()).isZero();
        assertThat(received).hasSize(1);
    }

    /** Two workers can run this at once, so a delivery is taken on a lease, and comes back if the worker holding it dies. */
    @Test
    void oneWorkerAtATimeTakesADeliveryAndItReturnsIfThatWorkerDies() throws Exception {
        String id = subscribe(startReceiver(), "RETURN_APPROVED_AND_LOCKED").get("endpoint").get("id").asString();
        s.toApproved(s.cleanJanuary(entity, "1000.00", "50.00"));
        respondWith.set(500);
        dispatch();
        Runnable leaseRunsOut = () -> world.queryInTenant(s.tenant, () ->
                jdbc.sql("UPDATE webhook_delivery SET next_attempt_at = now() - interval '1 minute' WHERE endpoint_id = ?").param(UUID.fromString(id)).update());

        leaseRunsOut.run();
        assertThat(world.queryInTenant(s.tenant, () -> dispatcher.claimDue())).hasSize(1);
        assertThat(world.queryInTenant(s.tenant, () -> dispatcher.claimDue())).isEmpty();

        leaseRunsOut.run();
        assertThat(world.queryInTenant(s.tenant, () -> dispatcher.claimDue())).hasSize(1);
    }

    @Test
    void aDisabledEndpointGetsNothing() throws Exception {
        String id = subscribe(startReceiver(), "RETURN_APPROVED_AND_LOCKED").get("endpoint").get("id").asString();
        s.client.post(s.admin, "/api/practice/webhooks/" + id + "/active", Map.of("active", false));
        UUID period = s.cleanJanuary(entity, "1000.00", "50.00");
        s.toApproved(period);

        assertThat(dispatch()).isZero();
        assertThat(received).isEmpty();
    }

    @Test
    void theSecretIsStoredEncrypted() throws Exception {
        JsonNode created = subscribe(startReceiver(), "RETURN_FILED");
        String secret = created.get("secret").asString();

        String stored = world.queryInTenant(s.tenant, () -> jdbc.sql("SELECT secret_encrypted FROM webhook_endpoint").query(String.class).single());

        assertThat(stored).doesNotContain(secret);
        assertThat(world.queryInTenant(s.tenant, () -> keys.decryptText(stored))).isEqualTo(secret);
        assertThat(s.client.get(s.admin, "/api/practice/webhooks").toString()).doesNotContain(secret).doesNotContain(stored);
    }

    @Test
    void anUnknownEventAndAnEmptyListAreRefused() throws Exception {
        s.client.postRaw(s.admin, "/api/practice/webhooks", Map.of("name", "x", "url", "http://127.0.0.1:1/x", "events", List.of("NOT_AN_EVENT")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("WEBHOOK_EVENTS_INVALID"));
    }

    @Test
    void onlyAFirmAdminCanManageWebhooks() throws Exception {
        s.client.postRaw(s.reviewer, "/api/practice/webhooks", Map.of("name", "x", "url", "http://127.0.0.1:1/x", "events", List.of("RETURN_FILED"))).andExpect(status().isForbidden());
    }

    @Test
    void anotherTenantsEventsNeverReachThisEndpoint() throws Exception {
        subscribe(startReceiver(), "RETURN_APPROVED_AND_LOCKED");
        Scenario other = new Scenario(mvc, world);
        UUID e = other.cleanEntity();
        UUID p = other.cleanJanuary(e, "1000.00", "50.00");
        other.toApproved(p);

        assertThat(dispatch()).isZero();
        assertThat(received).isEmpty();
    }
}
