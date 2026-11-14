package ae.vattool.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ae.vattool.platform.observability.TenantInfoMetric;
import ae.vattool.support.IntegrationTest;
import ae.vattool.support.TestWorld.TestUser;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** NFR-14: what each firm does can be told apart in the metrics, and Prometheus can read them. */
class MetricsTest extends IntegrationTest {

    @Autowired MeterRegistry metrics;
    @Autowired TenantInfoMetric tenantInfo;

    @Test
    void apiRequestsAreCountedPerTenantByOutcome() throws Exception {
        UUID tenant = world.newTenant("Metrics firm " + UUID.randomUUID());
        TestUser user = world.newUser(tenant, "FIRM", "FIRM_ADMIN");

        mvc.perform(get("/api/me").with(user.auth())).andExpect(status().isOk());
        mvc.perform(get("/api/entities/" + UUID.randomUUID()).with(user.auth())).andExpect(status().is4xxClientError());

        Timer ok = metrics.find("vat.api.requests").tag("tenant", tenant.toString()).tag("outcome", "SUCCESS").timer();
        Timer refused = metrics.find("vat.api.requests").tag("tenant", tenant.toString()).tag("outcome", "CLIENT_ERROR").timer();
        assertThat(ok).isNotNull();
        assertThat(ok.count()).isEqualTo(1);
        assertThat(refused).isNotNull();
        assertThat(refused.count()).isEqualTo(1);
    }

    @Test
    void eachTenantIsNamedOnceForDashboards() {
        UUID a = world.newTenant("Named firm " + UUID.randomUUID());
        UUID b = world.newTenant("Other firm " + UUID.randomUUID());

        tenantInfo.refresh();

        Gauge named = metrics.find("vat.tenant.info").tag("tenant", a.toString()).gauge();
        assertThat(named).isNotNull();
        assertThat(named.value()).isEqualTo(1.0);
        assertThat(named.getId().getTag("name")).startsWith("Named firm ");
        assertThat(metrics.find("vat.tenant.info").tag("tenant", b.toString()).gauge().getId().getTag("name")).startsWith("Other firm ");
    }

    @Test
    void metricsNeedATokenOnTheApiPortButNotOnTheManagementPort() throws Exception {
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        // The management server is not started in this test, so nothing answers there, but security lets the request through.
        mvc.perform(get("/actuator/prometheus").with(r -> {
            r.setLocalPort(18091);
            return r;
        })).andExpect(status().isNotFound());
    }
}
