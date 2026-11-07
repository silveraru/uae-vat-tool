package ae.vattool.practice;

import ae.vattool.platform.crypto.TenantKeys;
import ae.vattool.platform.tenancy.RequestContext;
import ae.vattool.platform.web.OutboundUrlGuard;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends what the audit log says happened to each subscribed endpoint. Each event is queued once per
 * endpoint and retried with growing gaps, so delivery is at least once and the receiver dedupes on the
 * event id. The network call happens outside any transaction, so a slow receiver holds no locks.
 */
@Component
public class WebhookDispatcher {
    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_ATTEMPTS = 8;
    private static final int BATCH = 100;
    private static final int LEASE_MINUTES = 2;

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final TenantKeys keys;
    private final OutboundUrlGuard urls;
    private final MeterRegistry metrics;
    private final boolean enabled;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();

    public WebhookDispatcher(JdbcClient jdbc, TransactionTemplate tx, TenantKeys keys, OutboundUrlGuard urls, MeterRegistry metrics, @Value("${app.worker.enabled}") boolean enabled) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.keys = keys;
        this.urls = urls;
        this.metrics = metrics;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${app.worker.webhook-delay-ms:30000}")
    public void tick() {
        if (!enabled) {
            return;
        }
        for (UUID tenant : jdbc.sql("SELECT list_tenant_ids()").query(UUID.class).list()) {
            RequestContext.asSystem(tenant, "webhooks", () -> {
                run();
            });
        }
    }

    private record Endpoint(UUID id, String url, String secretEncrypted, String[] events, long auditCursor) {}

    private record Event(long seq, String action, String objectType, String objectId, UUID entityId, String occurredAt, String afterJson) {}

    record Pending(UUID id, UUID endpointId, long auditSeq, String eventType, String payload, int attempts, String url, String secretEncrypted) {}

    /** One pass for the tenant in context. Returns how many deliveries succeeded. */
    public int run() {
        tx.executeWithoutResult(s -> queue());
        int delivered = 0;
        for (Pending p : claimDue()) {
            delivered += send(p) ? 1 : 0;
        }
        return delivered;
    }

    /**
     * Takes what is due on a short lease, so two workers never send one delivery at the same time. If a
     * worker dies mid-send, the lease runs out and the delivery goes again, which is why it carries an event id.
     */
    List<Pending> claimDue() {
        List<Pending> due = tx.execute(s -> jdbc.sql("""
                UPDATE webhook_delivery d SET next_attempt_at = now() + make_interval(mins => :lease)
                FROM webhook_endpoint e
                WHERE e.id = d.endpoint_id AND d.id IN (
                    SELECT x.id FROM webhook_delivery x JOIN webhook_endpoint y ON y.id = x.endpoint_id
                    WHERE x.status = 'PENDING' AND x.next_attempt_at <= now() AND y.active
                    ORDER BY x.audit_seq LIMIT :batch FOR UPDATE OF x SKIP LOCKED)
                RETURNING d.id, d.endpoint_id, d.audit_seq, d.event_type, d.payload, d.attempts, e.url, e.secret_encrypted
                """).param("lease", LEASE_MINUTES).param("batch", BATCH).query(Pending.class).list());
        return due.stream().sorted(Comparator.comparingLong(Pending::auditSeq)).toList();
    }

    private void queue() {
        List<Endpoint> endpoints = jdbc.sql("SELECT id, url, secret_encrypted, events, audit_cursor FROM webhook_endpoint WHERE active")
                .query((rs, n) -> new Endpoint(rs.getObject("id", UUID.class), rs.getString("url"), rs.getString("secret_encrypted"), (String[]) rs.getArray("events").getArray(), rs.getLong("audit_cursor")))
                .list();
        for (Endpoint e : endpoints) {
            List<Event> events = jdbc.sql("""
                    SELECT seq, action, object_type, object_id, entity_id, occurred_at::text AS occurred_at, after_json FROM audit_event
                    WHERE seq > :cursor ORDER BY seq LIMIT 1000
                    """).param("cursor", e.auditCursor()).query(Event.class).list();
            long last = e.auditCursor();
            List<String> wanted = List.of(e.events());
            for (Event ev : events) {
                last = ev.seq();
                if (!wanted.contains(ev.action())) {
                    continue;
                }
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("eventId", RequestContext.requireTenant() + ":" + ev.seq());
                body.put("type", ev.action());
                body.put("occurredAt", ev.occurredAt());
                body.put("entityId", ev.entityId());
                body.put("objectType", ev.objectType());
                body.put("objectId", ev.objectId());
                body.put("data", ev.afterJson() == null ? null : JSON.readTree(ev.afterJson()));
                jdbc.sql("""
                        INSERT INTO webhook_delivery (id, tenant_id, endpoint_id, audit_seq, event_type, payload, status)
                        VALUES (:id, :tenant, :endpoint, :seq, :type, :payload, 'PENDING') ON CONFLICT (tenant_id, endpoint_id, audit_seq) DO NOTHING
                        """)
                        .param("id", UUID.randomUUID()).param("tenant", RequestContext.requireTenant()).param("endpoint", e.id()).param("seq", ev.seq())
                        .param("type", ev.action()).param("payload", JSON.writeValueAsString(body))
                        .update();
            }
            if (last != e.auditCursor()) {
                jdbc.sql("UPDATE webhook_endpoint SET audit_cursor = ? WHERE id = ?").params(last, e.id()).update();
            }
        }
    }

    private boolean send(Pending p) {
        Integer code = null;
        String error = null;
        try {
            // Checked again on every send, because a name can start resolving to an internal address later.
            urls.check(p.url());
            String secret = tx.execute(s -> keys.decryptText(p.secretEncrypted()));
            long timestamp = Instant.now().getEpochSecond();
            HttpRequest request = HttpRequest.newBuilder(URI.create(p.url())).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("X-VatTool-Event", p.eventType())
                    .header("X-VatTool-Signature", "t=" + timestamp + ",v1=" + sign(secret, timestamp + "." + p.payload()))
                    .POST(HttpRequest.BodyPublishers.ofString(p.payload(), StandardCharsets.UTF_8)).build();
            code = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (code >= 200 && code < 300) {
                record(p, "DELIVERED", code, null);
                metrics.counter("vat.webhook.deliveries", "result", "delivered").increment();
                return true;
            }
            error = "HTTP " + code;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = "Interrupted";
        } catch (Exception e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.debug("Webhook delivery failed", e);
        }
        boolean giveUp = p.attempts() + 1 >= MAX_ATTEMPTS;
        record(p, giveUp ? "FAILED" : "PENDING", code, error);
        metrics.counter("vat.webhook.deliveries", "result", giveUp ? "failed" : "retry").increment();
        return false;
    }

    private void record(Pending p, String status, Integer code, String error) {
        // Doubles from one minute, so eight attempts cover about four hours.
        OffsetDateTime next = OffsetDateTime.now().plusMinutes(1L << Math.min(p.attempts(), 16));
        tx.executeWithoutResult(s -> jdbc.sql("""
                UPDATE webhook_delivery SET status = :status, attempts = attempts + 1, last_status = :code, last_error = :error, next_attempt_at = :next,
                    delivered_at = CASE WHEN :status = 'DELIVERED' THEN now() ELSE delivered_at END
                WHERE id = :id
                """).param("status", status).param("code", code).param("error", error == null ? null : error.substring(0, Math.min(error.length(), 500))).param("next", next).param("id", p.id()).update());
    }

    static String sign(String secret, String content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
