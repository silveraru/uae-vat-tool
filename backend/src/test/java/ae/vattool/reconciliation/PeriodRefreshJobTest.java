package ae.vattool.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import ae.vattool.support.IntegrationTest;
import ae.vattool.support.Scenario;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** The background worker, which keeps returns current without anyone pressing a button (RET-03). */
class PeriodRefreshJobTest extends IntegrationTest {

    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired ReconciliationService recon;
    @Autowired MeterRegistry metrics;

    Scenario s;
    UUID entity;
    PeriodRefreshJob job;

    @BeforeEach
    void setUp() throws Exception {
        s = new Scenario(mvc, world);
        entity = s.cleanEntity();
        job = new PeriodRefreshJob(jdbc, tx, recon, metrics, 0);
    }

    @Test
    void newDataProducesANewReturnVersionAndReconciliationWithNoOneAsking() throws Exception {
        UUID period = s.cleanJanuary(entity, "1000.00", "50.00");
        assertThat(s.client.get(s.preparer, "/api/periods/" + period + "/versions")).isEmpty();

        Thread.sleep(50);
        job.tick();

        JsonNode versions = s.client.get(s.preparer, "/api/periods/" + period + "/versions");
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).get("trigger").asString()).isEqualTo("DATA_CHANGE");
        long runs = world.queryInTenant(s.tenant, () -> jdbc.sql("SELECT COUNT(*) FROM recon_run WHERE period_id = ?").param(period).query(Long.class).single());
        assertThat(runs).isEqualTo(1);
    }

    @Test
    void aFileWithManyRowsCausesOneRecompute() throws Exception {
        StringBuilder csv = new StringBuilder(Scenario.AR_HEAD);
        for (int i = 0; i < 40; i++) {
            csv.append("TAX_INVOICE,M-").append(i).append(",2027-01-10,100.00,5.00,S5,DXB-1,").append(Scenario.CUSTOMER).append(",B2B\n");
        }
        s.client.uploadCsv(entity, "AR", csv.toString());
        UUID period = s.client.periodId(entity, "2027-01-01");
        long queued = world.queryInTenant(s.tenant, () -> jdbc.sql("SELECT COUNT(*) FROM period_recompute_request WHERE period_id = ?").param(period).query(Long.class).single());
        assertThat(queued).isEqualTo(1);

        Thread.sleep(50);
        job.tick();

        assertThat(s.client.get(s.preparer, "/api/periods/" + period + "/versions")).hasSize(1);
    }

    @Test
    void theQueueIsEmptyAfterARunAndARunOnNothingChangesNothing() throws Exception {
        UUID period = s.cleanJanuary(entity, "1000.00", "50.00");
        Thread.sleep(50);
        job.tick();
        long left = world.queryInTenant(s.tenant, () -> jdbc.sql("SELECT COUNT(*) FROM period_recompute_request").query(Long.class).single());
        assertThat(left).isZero();

        job.tick();

        assertThat(s.client.get(s.preparer, "/api/periods/" + period + "/versions")).hasSize(1);
    }

    @Test
    void aLockedPeriodIsLeftAloneByTheWorker() throws Exception {
        UUID period = s.cleanJanuary(entity, "1000.00", "50.00");
        s.toApproved(period);
        String hash = s.client.get(s.preparer, "/api/periods/" + period + "/return").get("version").get("valuesHash").asString();
        s.client.uploadCsv(entity, "AR", Scenario.AR_HEAD + "TAX_INVOICE,LATE,2027-01-20,100.00,5.00,S5,DXB-1," + Scenario.CUSTOMER + ",B2B\n");

        Thread.sleep(50);
        job.tick();

        assertThat(s.client.get(s.preparer, "/api/periods/" + period + "/return").get("version").get("valuesHash").asString()).isEqualTo(hash);
    }

    @Test
    void aFailingPeriodStaysQueuedAndIsCounted() throws Exception {
        UUID period = s.cleanJanuary(entity, "1000.00", "50.00");
        ReconciliationService failing = org.mockito.Mockito.mock(ReconciliationService.class);
        org.mockito.Mockito.when(failing.run(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString())).thenThrow(new IllegalStateException("boom"));
        double before = metrics.counter("vat.recon.failed").count();
        Thread.sleep(50);

        new PeriodRefreshJob(jdbc, tx, failing, metrics, 0).tick();

        assertThat(metrics.counter("vat.recon.failed").count()).isGreaterThan(before);
        long queued = world.queryInTenant(s.tenant, () -> jdbc.sql("SELECT COUNT(*) FROM period_recompute_request WHERE period_id = ?").param(period).query(Long.class).single());
        assertThat(queued).isEqualTo(1);
    }
}
