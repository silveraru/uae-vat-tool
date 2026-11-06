package ae.vattool.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ae.vattool.platform.tenancy.RequestContext;
import ae.vattool.support.IntegrationTest;
import ae.vattool.support.TestSecurityConfig;
import ae.vattool.support.TestWorld.TestUser;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

class TenancyIsolationTest extends IntegrationTest {

    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;

    @Test
    void oneTenantCannotReadAnothersRows() {
        UUID a = world.newTenant("Tenant A");
        UUID b = world.newTenant("Tenant B");
        TestUser userA = world.newUser(a, "FIRM");
        TestUser userB = world.newUser(b, "FIRM");

        List<UUID> seenByA = world.queryInTenant(a,
                () -> jdbc.sql("SELECT id FROM app_user").query(UUID.class).list());

        assertThat(seenByA).contains(userA.id()).doesNotContain(userB.id());
    }

    @Test
    void oneTenantCannotWriteRowsIntoAnother() {
        UUID a = world.newTenant("Writer");
        UUID b = world.newTenant("Victim");

        assertThatThrownBy(() -> world.inTenant(a, () -> jdbc.sql("""
                INSERT INTO app_user (id, tenant_id, subject, email, display_name, side)
                VALUES (?, ?, 'planted', 'x@test.example', 'Planted', 'FIRM')
                """).params(UUID.randomUUID(), b).update()))
                .rootCause().hasMessageContaining("row-level security");
    }

    @Test
    void withoutATenantInContextNothingIsVisible() {
        UUID a = world.newTenant("Hidden");
        world.newUser(a, "FIRM");

        // No RequestContext at all, in a transaction and outside one.
        int inTx = tx.execute(s -> jdbc.sql("SELECT count(*) FROM app_user").query(Integer.class).single());
        int noTx = jdbc.sql("SELECT count(*) FROM app_user").query(Integer.class).single();

        assertThat(inTx).isZero();
        assertThat(noTx).isZero();
    }

    @Test
    void tenantSettingDoesNotLeakToTheNextUserOfAPooledConnection() {
        UUID a = world.newTenant("Leaky");
        world.newUser(a, "FIRM");
        // Exercise the pool repeatedly: after each tenant transaction, a tenant-less read must see nothing.
        for (int i = 0; i < 50; i++) {
            int visibleInTenant = world.queryInTenant(a,
                    () -> jdbc.sql("SELECT count(*) FROM app_user").query(Integer.class).single());
            int visibleAfter = tx.execute(s -> jdbc.sql("SELECT count(*) FROM app_user").query(Integer.class).single());
            assertThat(visibleInTenant).isPositive();
            assertThat(visibleAfter).isZero();
        }
    }

    @Test
    void changingTenantInsideAnOpenTransactionIsRefused() {
        UUID a = world.newTenant("First");
        UUID b = world.newTenant("Second");

        assertThatThrownBy(() -> world.inTenant(a, () -> RequestContext.asSystem(b, "test", () -> { })))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void apiShowsOnlyTheCallersOwnTenant() throws Exception {
        UUID a = world.newTenant("API Tenant");
        TestUser user = world.newUser(a, "FIRM", "PREPARER");

        mvc.perform(get("/api/me").with(user.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(a.toString()))
                .andExpect(jsonPath("$.tenantName").value("API Tenant"))
                .andExpect(jsonPath("$.grants[0].role").value("PREPARER"));
    }

    @Test
    void missingTokenIsUnauthorized() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenForAnUnknownSubjectIsForbidden() throws Exception {
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + TestSecurityConfig.tokenFor("nobody-here")))
                .andExpect(status().isForbidden());
    }

    @Test
    void deactivatedUserIsForbidden() throws Exception {
        UUID a = world.newTenant("Deactivation");
        TestUser user = world.newUser(a, "FIRM", "PREPARER");
        world.inTenant(a, () -> jdbc.sql("UPDATE app_user SET active = false WHERE id = ?").param(user.id()).update());

        mvc.perform(get("/api/me").with(user.auth())).andExpect(status().isForbidden());
    }
}
