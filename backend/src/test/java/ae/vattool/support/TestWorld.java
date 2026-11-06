package ae.vattool.support;

import ae.vattool.platform.tenancy.RequestContext;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

/** Creates tenants and users for a test, and signs requests as them. */
@TestComponent
public class TestWorld {

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public TestWorld(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public record TestUser(UUID tenantId, UUID id, String subject) {
        public RequestPostProcessor auth() {
            return request -> {
                request.addHeader("Authorization", "Bearer " + TestSecurityConfig.tokenFor(subject));
                return request;
            };
        }
    }

    public UUID newTenant(String name) {
        UUID id = UUID.randomUUID();
        inTenant(id, () -> jdbc.sql("INSERT INTO tenant (id, name, kind) VALUES (?, ?, 'FIRM')")
                .params(id, name).update());
        return id;
    }

    /** Creates a user with one tenant-wide grant per role. Pass no roles for a user with no access. */
    public TestUser newUser(UUID tenantId, String side, String... roles) {
        UUID id = UUID.randomUUID();
        String subject = "sub-" + id;
        inTenant(tenantId, () -> {
            jdbc.sql("""
                    INSERT INTO app_user (id, tenant_id, subject, email, display_name, side)
                    VALUES (:id, :tenant, :subject, :email, :name, :side)
                    """)
                    .param("id", id).param("tenant", tenantId).param("subject", subject)
                    .param("email", id + "@test.example").param("name", "User " + id).param("side", side)
                    .update();
            jdbc.sql("SELECT register_login(?, ?)").params(subject, id).query().listOfRows();
            for (String role : roles) {
                grant(tenantId, id, null, role);
            }
        });
        return new TestUser(tenantId, id, subject);
    }

    public void grantOnEntity(TestUser user, UUID entityId, String role) {
        inTenant(user.tenantId(), () -> grant(user.tenantId(), user.id(), entityId, role));
    }

    private void grant(UUID tenantId, UUID userId, UUID entityId, String role) {
        jdbc.sql("""
                INSERT INTO entity_grant (id, tenant_id, user_id, entity_id, role, granted_by)
                VALUES (:id, :tenant, :user, :entity, :role, 'test')
                """)
                .param("id", UUID.randomUUID()).param("tenant", tenantId).param("user", userId)
                .param("entity", entityId).param("role", role)
                .update();
    }

    public void inTenant(UUID tenantId, Runnable work) {
        RequestContext.asSystem(tenantId, "test", () -> tx.executeWithoutResult(s -> work.run()));
    }

    public <T> T queryInTenant(UUID tenantId, java.util.function.Supplier<T> work) {
        return RequestContext.asSystem(tenantId, "test", () -> tx.execute(s -> work.get()));
    }
}
