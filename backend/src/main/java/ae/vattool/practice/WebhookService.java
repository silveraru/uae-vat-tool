package ae.vattool.practice;

import ae.vattool.platform.audit.AuditLog;
import ae.vattool.platform.crypto.TenantKeys;
import ae.vattool.platform.tenancy.RequestContext;
import ae.vattool.platform.web.DomainException;
import ae.vattool.platform.web.OutboundUrlGuard;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tells a firm's practice system when billable work happens (PRC-03). Endpoints subscribe to named
 * events, which the dispatcher reads from the audit log, so a webhook can only follow something that
 * actually committed. The signing secret is shown once and stored encrypted.
 */
@Service
public class WebhookService {

    public static final Set<String> EVENTS = Set.of("RETURN_SUBMITTED", "RETURN_APPROVED_AND_LOCKED", "RETURN_FILED", "PAYMENT_RECORDED", "PAYMENT_MATCHED_FROM_BANK", "CORRECTION_OPENED",
            "DISCLOSURE_SUBMITTED", "DISCLOSURE_SETTLED", "REFUND_SUBMITTED", "REFUND_RECEIVED", "AUDIT_CASE_OPENED", "AUDIT_CASE_CLOSED", "ENTITY_CREATED");

    public record EndpointInput(String name, String url, List<String> events) {}

    public record Endpoint(UUID id, String name, String url, List<String> events, boolean active, String createdBy, OffsetDateTime createdAt) {}

    public record Created(Endpoint endpoint, String secret) {}

    public record Delivery(UUID id, UUID endpointId, long auditSeq, String eventType, String status, int attempts, OffsetDateTime nextAttemptAt, Integer lastStatus, String lastError, OffsetDateTime deliveredAt, OffsetDateTime createdAt) {}

    private final JdbcClient jdbc;
    private final TenantKeys keys;
    private final OutboundUrlGuard urls;
    private final AuditLog audit;
    private final SecureRandom random = new SecureRandom();

    public WebhookService(JdbcClient jdbc, TenantKeys keys, OutboundUrlGuard urls, AuditLog audit) {
        this.jdbc = jdbc;
        this.keys = keys;
        this.urls = urls;
        this.audit = audit;
    }

    @Transactional
    public Created create(EndpointInput in) {
        if (in.name() == null || in.name().isBlank()) {
            throw DomainException.invalid("NAME_REQUIRED", "A name is required");
        }
        urls.check(in.url());
        if (in.events() == null || in.events().isEmpty() || !EVENTS.containsAll(in.events())) {
            throw DomainException.invalid("WEBHOOK_EVENTS_INVALID", "Choose at least one event from " + EVENTS);
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        UUID id = UUID.randomUUID();
        // Starts at the current end of the log, so a new endpoint is not sent everything that ever happened.
        long cursor = jdbc.sql("SELECT COALESCE(MAX(seq), 0) FROM audit_event").query(Long.class).single();
        jdbc.sql("""
                INSERT INTO webhook_endpoint (id, tenant_id, name, url, secret_encrypted, events, audit_cursor, created_by)
                VALUES (:id, :tenant, :name, :url, :secret, string_to_array(:events, ','), :cursor, :by)
                """)
                .param("id", id).param("tenant", RequestContext.requireTenant()).param("name", in.name()).param("url", in.url())
                .param("secret", keys.encryptText(secret)).param("events", String.join(",", in.events())).param("cursor", cursor)
                .param("by", RequestContext.requireActor().actorId())
                .update();
        audit.record("WEBHOOK_CREATED", "WEBHOOK_ENDPOINT", id, null, null, Map.of("name", in.name(), "url", in.url(), "events", in.events()), null);
        return new Created(find(id), secret);
    }

    @Transactional
    public Endpoint setActive(UUID id, boolean active) {
        int changed = jdbc.sql("UPDATE webhook_endpoint SET active = ? WHERE id = ?").params(active, id).update();
        if (changed == 0) {
            throw DomainException.notFound("Webhook");
        }
        audit.record(active ? "WEBHOOK_ENABLED" : "WEBHOOK_DISABLED", "WEBHOOK_ENDPOINT", id, null, null, null, null);
        return find(id);
    }

    @Transactional(readOnly = true)
    public List<Endpoint> list() {
        return jdbc.sql("SELECT id, name, url, events, active, created_by, created_at FROM webhook_endpoint ORDER BY created_at")
                .query((rs, n) -> endpoint(rs)).list();
    }

    @Transactional(readOnly = true)
    public List<Delivery> deliveries(UUID endpointId) {
        return jdbc.sql("""
                SELECT id, endpoint_id, audit_seq, event_type, status, attempts, next_attempt_at, last_status, last_error, delivered_at, created_at
                FROM webhook_delivery WHERE endpoint_id = ? ORDER BY created_at DESC LIMIT 100
                """).param(endpointId).query(Delivery.class).list();
    }

    private Endpoint find(UUID id) {
        return jdbc.sql("SELECT id, name, url, events, active, created_by, created_at FROM webhook_endpoint WHERE id = ?").param(id)
                .query((rs, n) -> endpoint(rs)).optional().orElseThrow(() -> DomainException.notFound("Webhook"));
    }

    private static Endpoint endpoint(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Endpoint(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("url"), List.of((String[]) rs.getArray("events").getArray()),
                rs.getBoolean("active"), rs.getString("created_by"), rs.getObject("created_at", OffsetDateTime.class));
    }
}
