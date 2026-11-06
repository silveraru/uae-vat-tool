package ae.vattool.platform.audit;

import ae.vattool.platform.security.AccessControl;
import ae.vattool.platform.security.Permission;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditVerifier verifier;
    private final AccessControl access;
    private final JdbcClient jdbc;

    public AuditController(AuditVerifier verifier, AccessControl access, JdbcClient jdbc) {
        this.verifier = verifier;
        this.access = access;
        this.jdbc = jdbc;
    }

    @GetMapping("/verify")
    public AuditVerifier.Result verify() {
        access.requireTenantWide(Permission.VIEW_AUDIT_LOG);
        return verifier.verify();
    }

    @GetMapping("/events")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> events(@RequestParam(required = false) UUID entityId,
                                            @RequestParam(defaultValue = "100") int limit) {
        access.requireTenantWide(Permission.VIEW_AUDIT_LOG);
        return jdbc.sql("""
                SELECT seq, occurred_at, actor_type, actor_id, action, object_type, object_id, entity_id,
                       before_json, after_json, reason, hash
                FROM audit_event
                WHERE (CAST(:entity AS uuid) IS NULL OR entity_id = :entity)
                ORDER BY seq DESC LIMIT :limit
                """)
                .param("entity", entityId)
                .param("limit", Math.min(limit, 1000))
                .query()
                .listOfRows();
    }
}
