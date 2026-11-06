package ae.vattool.load;

import static org.assertj.core.api.Assertions.assertThat;

import ae.vattool.ingestion.IngestionService;
import ae.vattool.ingestion.Templates;
import ae.vattool.platform.tenancy.RequestContext;
import ae.vattool.reconciliation.ReconciliationService;
import ae.vattool.returns.ReturnService;
import ae.vattool.support.IntegrationTest;
import ae.vattool.support.TestClient;
import ae.vattool.support.TestWorld.TestUser;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * NFR-07 measurements. Not part of the normal build. Run with
 * ./mvnw -pl backend -am test -Dgroups=load -DexcludedGroups= -Dsurefire.failIfNoSpecifiedTests=false
 * Sizes come from -Dload.ingestRows and -Dload.ledgerLines. Results from one laptop with PostgreSQL
 * in a container are indicative only; they do not show production performance.
 */
@Tag("load")
class LoadTest extends IntegrationTest {

    @Autowired IngestionService ingestion;
    @Autowired ReturnService returns;
    @Autowired ReconciliationService recon;
    @Autowired JdbcClient jdbc;

    private TestClient client;
    private TestUser preparer;

    private UUID setUpTenant() {
        UUID tenant = world.newTenant("Load " + UUID.randomUUID());
        TestUser admin = world.newUser(tenant, "FIRM", "FIRM_ADMIN");
        preparer = world.newUser(tenant, "CLIENT", "PREPARER");
        client = new TestClient(mvc, admin, preparer);
        return tenant;
    }

    @Test
    void ingestionRate() throws Exception {
        int rows = Integer.getInteger("load.ingestRows", 100_000);
        UUID tenant = setUpTenant();
        UUID entity = client.simpleEntity();
        StringBuilder csv = new StringBuilder("doc_type,doc_number,doc_date,net_amount,vat_amount,line_no,tax_code,establishment_code,counterparty_trn,customer_type,goods_or_services,description\n");
        Random random = new Random(1);
        for (int i = 0; i < rows; i++) {
            int day = 1 + random.nextInt(28);
            int doc = i / 5;
            csv.append("TAX_INVOICE,LOAD-").append(doc).append(",2027-01-").append(String.format("%02d", 1 + doc % 28)).append(',')
                    .append(100 + day).append(".00,").append(String.format("%d.%02d", (100 + day) / 20, ((100 + day) * 5) % 100)).append(',').append(i % 5 + 1)
                    .append(",S5,DXB-1,100000000000901,B2B,GOODS,Load test item ").append(i).append('\n');
        }
        byte[] file = csv.toString().getBytes(StandardCharsets.UTF_8);
        long start = System.nanoTime();
        IngestionService.LoadResult result = RequestContext.asSystem(tenant, "load-test",
                () -> ingestion.loadFile(entity, Templates.Kind.AR, "v1", "UPLOAD", "load.csv", file));
        double seconds = (System.nanoTime() - start) / 1e9;
        double perHour = rows / seconds * 3600;
        System.out.printf("LOAD ingest: %d rows, %d loaded, %d rejected, in %.1f s = %,.0f lines/hour (target 1,000,000)%n",
                rows, result.batch().rowsLoaded(), result.batch().rowsRejected(), seconds, perHour);
        assertThat(result.batch().rowsRejected()).isZero();
        assertThat(result.batch().rowsLoaded()).isEqualTo(rows);
    }

    @Test
    void recomputeAndDrillDownOnALargeLedger() throws Exception {
        int lines = Integer.getInteger("load.ledgerLines", 5_000_000);
        UUID tenant = setUpTenant();
        UUID entity = client.simpleEntity();
        UUID period = client.periodId(entity, "2027-01-01");
        // Lines are written directly because this measures reading them, not loading them.
        long bulkStart = System.nanoTime();
        try (Connection c = superuserConnection(); Statement s = c.createStatement()) {
            s.execute("SET synchronous_commit = off");
            try (PreparedStatement docs = c.prepareStatement("""
                    INSERT INTO source_document (id, tenant_id, filer_entity_id, member_entity_id, source_system, direction, doc_type, doc_number, doc_date,
                        natural_key, counterparty_trn, customer_type)
                    SELECT gen_random_uuid(), ?, ?, ?, 'ERP', 'OUT', 'TAX_INVOICE', 'LD-' || g, DATE '2027-01-01' + (g % 28), 'k' || g, '100000000000901', 'B2C'
                    FROM generate_series(1, ?) g
                    """)) {
                docs.setObject(1, tenant);
                docs.setObject(2, entity);
                docs.setObject(3, entity);
                docs.setInt(4, lines / 5);
                docs.executeUpdate();
            }
            try (PreparedStatement ins = c.prepareStatement("""
                    INSERT INTO tax_line (id, tenant_id, tax_year, logical_line_id, revision, filer_entity_id, member_entity_id, emirate, period_id, document_id,
                        line_no, line_kind, source_system, direction, description, tax_point, currency, fx_rate, net_doc, vat_doc, net_aed, vat_aed, recoverable_vat_aed,
                        treatment, classified_by, rule_pack_version, changed_by, tax_hash)
                    SELECT gen_random_uuid(), ?, 2027, gen_random_uuid(), 1, ?, ?, (ARRAY['AUH','DXB','SHJ','AJM','UAQ','RAK','FUJ'])[(1 + (l.g % 7))::int], ?, d.id,
                        l.n, 'NORMAL', 'ERP', CASE WHEN l.g % 4 = 0 THEN 'IN' ELSE 'OUT' END, 'Load line', DATE '2027-01-01' + (l.g % 28)::int, 'AED', 1, 100 + (l.g % 50), 5, 100 + (l.g % 50), 5,
                        CASE WHEN l.g % 4 = 0 THEN 5 ELSE 0 END, 'STANDARD', 'MAPPING', 'AE-VAT-1.0', 'load', 'h'
                    FROM (SELECT id, row_number() OVER () AS rn FROM source_document WHERE filer_entity_id = ?) d
                    CROSS JOIN LATERAL (SELECT (d.rn - 1) * 5 + n AS g, n FROM generate_series(1, 5) n) l
                    """)) {
                ins.setObject(1, tenant);
                ins.setObject(2, entity);
                ins.setObject(3, entity);
                ins.setObject(4, period);
                ins.setObject(5, entity);
                ins.executeUpdate();
            }
            s.execute("ANALYZE tax_line");
            s.execute("ANALYZE source_document");
        }
        System.out.printf("LOAD setup: %,d lines written in %.0f s%n", lines, (System.nanoTime() - bulkStart) / 1e9);

        long t0 = System.nanoTime();
        RequestContext.asSystem(tenant, "load-test", () -> returns.recompute(period, "LOAD"));
        double recompute = (System.nanoTime() - t0) / 1e9;
        System.out.printf("LOAD return recompute on %,d lines: %.1f s (target under 600 s)%n", lines, recompute);

        long t1 = System.nanoTime();
        RequestContext.asSystem(tenant, "load-test", () -> recon.run(period, "LOAD"));
        double full = (System.nanoTime() - t1) / 1e9;
        System.out.printf("LOAD recompute with all reconciliation checks: %.1f s%n", full);

        List<Long> drill = new ArrayList<>();
        Random random = new Random(2);
        String[] boxes = {"1a", "1b", "1c", "1d", "1e", "1f", "1g", "9"};
        for (int i = 0; i < 60; i++) {
            long d0 = System.nanoTime();
            client.get(preparer, "/api/periods/" + period + "/boxes/" + boxes[random.nextInt(boxes.length)] + "/lines?size=50&page=" + random.nextInt(40));
            drill.add((System.nanoTime() - d0) / 1_000_000);
        }
        Collections.sort(drill);
        System.out.printf("LOAD drill-down: p50 %d ms, p95 %d ms (target p95 under 2,000 ms)%n", drill.get(drill.size() / 2), drill.get((int) (drill.size() * 0.95)));

        List<Long> page = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            long p0 = System.nanoTime();
            client.get(preparer, "/api/periods/" + period + "/return");
            page.add((System.nanoTime() - p0) / 1_000_000);
        }
        Collections.sort(page);
        System.out.printf("LOAD return page API: p50 %d ms, p95 %d ms (target p95 under 1,500 ms)%n", page.get(page.size() / 2), page.get((int) (page.size() * 0.95)));
    }
}
