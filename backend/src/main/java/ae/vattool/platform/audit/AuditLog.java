package ae.vattool.platform.audit;

import ae.vattool.platform.tenancy.RequestContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Appends to the per-tenant hash chain inside the caller's transaction, so a change and its audit
 * record commit or roll back together. The head row lock serialises writers per tenant until
 * commit: call this as the last step of a transaction, and log bulk loads once per file.
 */
@Service
public class AuditLog {

    private final JdbcClient jdbc;
    private final ObjectMapper canonical = AuditHash.canonicalMapper();

    public AuditLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Head(long lastSeq, String lastHash) {}

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String action, String objectType, Object objectId, UUID entityId,
                       Object before, Object after, String reason) {
        RequestContext.Actor actor = RequestContext.requireActor();
        UUID tenant = actor.tenantId();

        jdbc.sql("INSERT INTO audit_chain_head (tenant_id, last_seq, last_hash) VALUES (?, 0, ?) ON CONFLICT DO NOTHING")
                .params(tenant, AuditHash.GENESIS)
                .update();
        Head head = jdbc.sql("SELECT last_seq, last_hash FROM audit_chain_head WHERE tenant_id = ? FOR UPDATE")
                .param(tenant)
                .query(Head.class)
                .single();

        AuditHash.Fields f = new AuditHash.Fields(
                tenant,
                head.lastSeq() + 1,
                Instant.now().truncatedTo(ChronoUnit.MICROS),
                actor.isSystem() ? "SYSTEM" : "USER",
                actor.actorId(),
                action,
                objectType,
                String.valueOf(objectId),
                entityId,
                toJson(before),
                toJson(after),
                reason,
                head.lastHash());
        String hash = AuditHash.hash(f);

        jdbc.sql("""
                INSERT INTO audit_event (tenant_id, seq, occurred_at, actor_type, actor_id, action, object_type,
                                         object_id, entity_id, before_json, after_json, reason, prev_hash, hash)
                VALUES (:tenant, :seq, :at, :actorType, :actorId, :action, :objectType,
                        :objectId, :entity, :before, :after, :reason, :prev, :hash)
                """)
                .param("tenant", tenant)
                .param("seq", f.seq())
                .param("at", java.sql.Timestamp.from(f.occurredAt()))
                .param("actorType", f.actorType())
                .param("actorId", f.actorId())
                .param("action", f.action())
                .param("objectType", f.objectType())
                .param("objectId", f.objectId())
                .param("entity", f.entityId())
                .param("before", f.beforeJson())
                .param("after", f.afterJson())
                .param("reason", f.reason())
                .param("prev", f.prevHash())
                .param("hash", hash)
                .update();
        jdbc.sql("UPDATE audit_chain_head SET last_seq = ?, last_hash = ? WHERE tenant_id = ?")
                .params(f.seq(), hash, tenant)
                .update();
    }

    private String toJson(Object value) {
        return value == null ? null : canonical.writeValueAsString(value);
    }
}
