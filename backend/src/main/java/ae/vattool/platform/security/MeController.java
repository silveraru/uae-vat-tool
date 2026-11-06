package ae.vattool.platform.security;

import ae.vattool.platform.tenancy.RequestContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeController {

    private final JdbcClient jdbc;

    public MeController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Grant(UUID entityId, String role) {}

    public record Me(UUID id, UUID tenantId, String tenantName, String email, String displayName, String side,
                     String locale, List<Grant> grants) {}

    @GetMapping("/api/me")
    @Transactional(readOnly = true)
    public Me me() {
        UUID userId = RequestContext.requireUserId();
        Map<String, Object> row = jdbc.sql("""
                SELECT u.email, u.display_name, u.side, u.locale, t.name AS tenant_name
                FROM app_user u JOIN tenant t ON t.id = u.tenant_id
                WHERE u.id = ?
                """).param(userId).query().singleRow();
        List<Grant> grants = jdbc.sql("""
                SELECT entity_id, role FROM entity_grant
                WHERE user_id = ? AND valid_from <= now() AND (valid_to IS NULL OR valid_to > now())
                """).param(userId).query(Grant.class).list();
        return new Me(userId, RequestContext.requireTenant(), (String) row.get("tenant_name"),
                (String) row.get("email"), (String) row.get("display_name"), (String) row.get("side"),
                (String) row.get("locale"), grants);
    }
}
