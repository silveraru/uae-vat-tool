package ae.vattool.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ae.vattool.platform.audit.AuditLog;
import ae.vattool.platform.audit.AuditVerifier;
import ae.vattool.platform.tenancy.RequestContext;
import ae.vattool.support.IntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

class AuditChainTest extends IntegrationTest {

    @Autowired AuditLog audit;
    @Autowired AuditVerifier verifier;
    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;

    private UUID tenantWithEvents(int count) {
        UUID tenant = world.newTenant("Audit " + UUID.randomUUID());
        for (int i = 0; i < count; i++) {
            int n = i;
            world.inTenant(tenant, () -> audit.record("TEST_ACTION", "THING", "id-" + n, null,
                    Map.of("value", n), Map.of("value", n + 1), "reason " + n));
        }
        return tenant;
    }

    private AuditVerifier.Result verify(UUID tenant) {
        return RequestContext.asSystem(tenant, "test", () -> verifier.verify());
    }

    @Test
    void anUntouchedChainVerifies() {
        UUID tenant = tenantWithEvents(5);

        AuditVerifier.Result result = verify(tenant);

        assertThat(result.intact()).isTrue();
        assertThat(result.eventsChecked()).isEqualTo(5);
    }

    @Test
    void eachTenantHasItsOwnChain() {
        UUID a = tenantWithEvents(3);
        UUID b = tenantWithEvents(2);

        assertThat(verify(a).eventsChecked()).isEqualTo(3);
        assertThat(verify(b).eventsChecked()).isEqualTo(2);
        assertThat(verify(a).intact()).isTrue();
        assertThat(verify(b).intact()).isTrue();
    }

    @Test
    void theApplicationRoleCannotChangeOrRemoveEvents() {
        UUID tenant = tenantWithEvents(1);

        assertThatThrownBy(() -> world.inTenant(tenant,
                () -> jdbc.sql("UPDATE audit_event SET reason = 'changed'").update()))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> world.inTenant(tenant,
                () -> jdbc.sql("DELETE FROM audit_event").update()))
                .rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void theAppendOnlyTriggerStopsEvenAPrivilegedUpdate() throws Exception {
        UUID tenant = tenantWithEvents(1);

        try (Connection c = superuserConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE audit_event SET reason = 'changed' WHERE tenant_id = ?")) {
            ps.setObject(1, tenant);
            assertThatThrownBy(ps::executeUpdate).hasMessageContaining("append-only");
        }
    }

    @Test
    void aTamperedEventIsDetectedAtTheExactRow() throws Exception {
        UUID tenant = tenantWithEvents(5);

        // An attacker with database control removes the trigger and edits a value.
        try (Connection c = superuserConnection(); Statement s = c.createStatement()) {
            s.execute("ALTER TABLE audit_event DISABLE TRIGGER audit_event_no_change");
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE audit_event SET after_json = '{\"value\":999}' WHERE tenant_id = ? AND seq = 3")) {
                ps.setObject(1, tenant);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }
            s.execute("ALTER TABLE audit_event ENABLE TRIGGER audit_event_no_change");
        }

        AuditVerifier.Result result = verify(tenant);

        assertThat(result.intact()).isFalse();
        assertThat(result.firstBrokenSeq()).isEqualTo(3);
    }

    @Test
    void eventsRemovedFromTheMiddleAreDetected() throws Exception {
        UUID tenant = tenantWithEvents(5);
        deleteAsAttacker(tenant, "seq = 2");

        AuditVerifier.Result result = verify(tenant);

        assertThat(result.intact()).isFalse();
        assertThat(result.firstBrokenSeq()).isEqualTo(2);
    }

    @Test
    void eventsRemovedFromTheEndAreDetected() throws Exception {
        UUID tenant = tenantWithEvents(5);
        deleteAsAttacker(tenant, "seq > 3");

        AuditVerifier.Result result = verify(tenant);

        assertThat(result.intact()).isFalse();
        assertThat(result.problem()).contains("removed");
    }

    @Test
    void anAuditRecordRollsBackWithItsTransaction() {
        UUID tenant = tenantWithEvents(1);

        assertThatThrownBy(() -> world.inTenant(tenant, () -> {
            audit.record("TEST_ACTION", "THING", "doomed", null, null, null, null);
            throw new IllegalStateException("business failure");
        })).isInstanceOf(IllegalStateException.class);

        AuditVerifier.Result result = verify(tenant);
        assertThat(result.intact()).isTrue();
        assertThat(result.eventsChecked()).isEqualTo(1);
    }

    private void deleteAsAttacker(UUID tenant, String where) throws Exception {
        try (Connection c = superuserConnection(); Statement s = c.createStatement()) {
            s.execute("ALTER TABLE audit_event DISABLE TRIGGER audit_event_no_change");
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM audit_event WHERE tenant_id = ? AND " + where)) {
                ps.setObject(1, tenant);
                ps.executeUpdate();
            }
            s.execute("ALTER TABLE audit_event ENABLE TRIGGER audit_event_no_change");
        }
    }
}
