package ae.vattool.platform.seed;

import ae.vattool.platform.tenancy.RequestContext;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates the demo firm and one user per persona in the dev profile. Subjects match the fixed
 * user ids in infra/keycloak/uaevat-realm.json. Never enabled outside local development.
 */
@Component
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true")
public class DevSeed implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DevSeed.class);

    public static final UUID DEMO_TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");

    public record DemoUser(UUID id, String email, String name, String side, String role) {}

    public static final List<DemoUser> USERS = List.of(
            new DemoUser(UUID.fromString("a0000000-0000-4000-8000-000000000001"), "preparer@demo.test", "Dana Preparer", "CLIENT", "PREPARER"),
            new DemoUser(UUID.fromString("a0000000-0000-4000-8000-000000000002"), "approver@demo.test", "Noor Approver", "CLIENT", "APPROVER"),
            new DemoUser(UUID.fromString("a0000000-0000-4000-8000-000000000003"), "reviewer@demo.test", "Rami Reviewer", "FIRM", "TAX_REVIEWER"),
            new DemoUser(UUID.fromString("a0000000-0000-4000-8000-000000000004"), "partner@demo.test", "Lina Partner", "FIRM", "ENGAGEMENT_PARTNER"),
            new DemoUser(UUID.fromString("a0000000-0000-4000-8000-000000000005"), "admin@demo.test", "Omar Admin", "FIRM", "FIRM_ADMIN"),
            new DemoUser(UUID.fromString("a0000000-0000-4000-8000-000000000006"), "auditor@demo.test", "Sami Auditor", "EXTERNAL", "AUDITOR"));

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public DevSeed(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public void run(ApplicationArguments args) {
        RequestContext.asSystem(DEMO_TENANT, "dev-seed", () -> tx.executeWithoutResult(s -> {
            jdbc.sql("INSERT INTO tenant (id, name, kind) VALUES (?, 'Demo Tax Advisers LLC', 'FIRM') ON CONFLICT DO NOTHING")
                    .param(DEMO_TENANT).update();
            for (DemoUser u : USERS) {
                jdbc.sql("""
                        INSERT INTO app_user (id, tenant_id, subject, email, display_name, side)
                        VALUES (:id, :tenant, :subject, :email, :name, :side) ON CONFLICT DO NOTHING
                        """)
                        .param("id", u.id()).param("tenant", DEMO_TENANT).param("subject", u.id().toString())
                        .param("email", u.email()).param("name", u.name()).param("side", u.side())
                        .update();
                jdbc.sql("SELECT register_login(?, ?)").params(u.id().toString(), u.id()).query().listOfRows();
                // Demo users hold their role across the tenant so every seeded entity is reachable.
                int existing = jdbc.sql("SELECT count(*) FROM entity_grant WHERE user_id = ? AND entity_id IS NULL")
                        .param(u.id()).query(Integer.class).single();
                if (existing == 0) {
                    jdbc.sql("""
                            INSERT INTO entity_grant (id, tenant_id, user_id, entity_id, role, granted_by)
                            VALUES (:id, :tenant, :user, NULL, :role, 'dev-seed')
                            """)
                            .param("id", UUID.randomUUID()).param("tenant", DEMO_TENANT)
                            .param("user", u.id()).param("role", u.role())
                            .update();
                }
            }
        }));
        log.info("Dev seed ready: {} users in tenant {}", USERS.size(), DEMO_TENANT);
    }
}
