package ae.vattool.practice;

import ae.vattool.platform.audit.AuditLog;
import ae.vattool.platform.tenancy.RequestContext;
import ae.vattool.platform.web.DomainException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who in the firm looks after which client, and how loaded each person is (PRC-02). An assignment says
 * who is responsible. It grants nothing: access stays with entity grants, and the view shows any gap.
 */
@Service
public class PracticeService {

    private static final Set<String> DUTIES = Set.of("PREPARER", "REVIEWER", "PARTNER");
    private static final int SOON_DAYS = 14;

    public record AssignmentInput(UUID userId, String duty) {}

    public record Assignment(UUID id, UUID entityId, UUID userId, String displayName, String duty, boolean hasAccess, String assignedBy, OffsetDateTime assignedAt) {}

    public record Workload(UUID userId, String displayName, long entities, long openPeriods, long dueSoon, long overdue, long openExceptions, long blockingExceptions) {}

    public record Unassigned(UUID entityId, String legalName, LocalDate nextDue) {}

    public record Overview(List<Workload> people, List<Unassigned> unassigned) {}

    private final JdbcClient jdbc;
    private final AuditLog audit;

    public PracticeService(JdbcClient jdbc, AuditLog audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional
    public List<Assignment> assign(UUID entityId, AssignmentInput in) {
        if (!jdbc.sql("SELECT EXISTS (SELECT 1 FROM legal_entity WHERE id = ?)").param(entityId).query(Boolean.class).single()) {
            throw DomainException.notFound("Entity");
        }
        if (in.duty() == null || !DUTIES.contains(in.duty())) {
            throw DomainException.invalid("DUTY_INVALID", "The duty must be one of " + DUTIES);
        }
        boolean known = jdbc.sql("SELECT EXISTS (SELECT 1 FROM app_user WHERE id = ? AND active)").param(in.userId()).query(Boolean.class).single();
        if (!known) {
            throw DomainException.invalid("USER_UNKNOWN", "That person is not an active user");
        }
        UUID id = UUID.randomUUID();
        int added = jdbc.sql("""
                INSERT INTO staff_assignment (id, tenant_id, entity_id, user_id, duty, assigned_by)
                VALUES (:id, :tenant, :entity, :user, :duty, :by) ON CONFLICT (tenant_id, entity_id, user_id, duty) DO NOTHING
                """)
                .param("id", id).param("tenant", RequestContext.requireTenant()).param("entity", entityId).param("user", in.userId()).param("duty", in.duty())
                .param("by", RequestContext.requireActor().actorId())
                .update();
        if (added > 0) {
            audit.record("STAFF_ASSIGNED", "STAFF_ASSIGNMENT", id, entityId, null, Map.of("user", in.userId().toString(), "duty", in.duty()), null);
        }
        return assignments(entityId);
    }

    @Transactional
    public List<Assignment> unassign(UUID entityId, UUID assignmentId) {
        int removed = jdbc.sql("DELETE FROM staff_assignment WHERE id = ? AND entity_id = ?").params(assignmentId, entityId).update();
        if (removed == 0) {
            throw DomainException.notFound("Assignment");
        }
        audit.record("STAFF_UNASSIGNED", "STAFF_ASSIGNMENT", assignmentId, entityId, null, null, null);
        return assignments(entityId);
    }

    @Transactional(readOnly = true)
    public List<Assignment> assignments(UUID entityId) {
        return jdbc.sql("""
                SELECT a.id, a.entity_id, a.user_id, u.display_name, a.duty,
                       EXISTS (SELECT 1 FROM entity_grant g WHERE g.user_id = a.user_id AND (g.entity_id = a.entity_id OR g.entity_id IS NULL)
                               AND g.valid_from <= now() AND (g.valid_to IS NULL OR g.valid_to > now())) AS has_access,
                       a.assigned_by, a.assigned_at
                FROM staff_assignment a JOIN app_user u ON u.id = a.user_id
                WHERE a.entity_id = ? ORDER BY a.duty, u.display_name
                """).param(entityId).query(Assignment.class).list();
    }

    /** One row per person assigned to anything, plus the clients nobody is looking after. */
    @Transactional(readOnly = true)
    public Overview overview(LocalDate today) {
        List<Workload> people = jdbc.sql("""
                SELECT u.id AS user_id, u.display_name,
                       COUNT(DISTINCT a.entity_id) AS entities,
                       COUNT(DISTINCT p.id) FILTER (WHERE p.state IN ('OPEN','IN_REVIEW','APPROVED')) AS open_periods,
                       COUNT(DISTINCT p.id) FILTER (WHERE p.state IN ('OPEN','IN_REVIEW','APPROVED') AND p.due_date BETWEEN :today AND :soon) AS due_soon,
                       COUNT(DISTINCT p.id) FILTER (WHERE p.state IN ('OPEN','IN_REVIEW','APPROVED') AND p.due_date < :today) AS overdue,
                       (SELECT COUNT(*) FROM recon_exception x WHERE x.owner_id = u.id AND x.status IN ('OPEN','ASSIGNED')) AS open_exceptions,
                       (SELECT COUNT(*) FROM recon_exception x WHERE x.owner_id = u.id AND x.status IN ('OPEN','ASSIGNED') AND x.severity = 'BLOCKING') AS blocking_exceptions
                FROM app_user u
                JOIN staff_assignment a ON a.user_id = u.id
                LEFT JOIN tax_period p ON p.entity_id = a.entity_id
                GROUP BY u.id, u.display_name
                ORDER BY overdue DESC, due_soon DESC, u.display_name
                """).param("today", today).param("soon", today.plusDays(SOON_DAYS)).query(Workload.class).list();
        List<Unassigned> unassigned = jdbc.sql("""
                SELECT e.id AS entity_id, e.legal_name,
                       (SELECT MIN(p.due_date) FROM tax_period p WHERE p.entity_id = e.id AND p.state IN ('OPEN','IN_REVIEW','APPROVED')) AS next_due
                FROM legal_entity e
                WHERE e.entity_kind <> 'GROUP_MEMBER' AND NOT EXISTS (SELECT 1 FROM staff_assignment a WHERE a.entity_id = e.id)
                ORDER BY next_due NULLS LAST, e.legal_name LIMIT 200
                """).query(Unassigned.class).list();
        return new Overview(people, unassigned);
    }
}
