package ae.vattool.load;

import static org.assertj.core.api.Assertions.assertThat;

import ae.vattool.ingestion.IngestionService;
import ae.vattool.ingestion.Templates;
import ae.vattool.platform.tenancy.RequestContext;
import ae.vattool.reconciliation.ReconciliationService;
import ae.vattool.returns.ReturnService;
import ae.vattool.rules.RulePackStore;
import ae.vattool.support.IntegrationTest;
import ae.vattool.support.TestClient;
import ae.vattool.support.TestWorld.TestUser;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
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
    @Autowired RulePackStore packs;
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
                        CASE WHEN l.g % 4 = 0 THEN 5 ELSE 0 END, 'STANDARD', 'MAPPING', 'AE-VAT-1.1', 'load', 'h'
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

        drillDown(period, "straight after the load");
        // What autovacuum does soon after a large load. Until then a count has to visit the table as well as the index.
        try (Connection c = superuserConnection(); Statement st = c.createStatement()) {
            long v0 = System.nanoTime();
            st.execute("VACUUM (PARALLEL 0, ANALYZE) tax_line");
            System.out.printf("LOAD vacuum: %.0f s%n", (System.nanoTime() - v0) / 1e9);
        }
        drillDown(period, "after vacuum");

        List<Long> page = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            long p0 = System.nanoTime();
            client.get(preparer, "/api/periods/" + period + "/return");
            page.add((System.nanoTime() - p0) / 1_000_000);
        }
        Collections.sort(page);
        System.out.printf("LOAD return page API: p50 %d ms, p95 %d ms (target p95 under 1,500 ms)%n", page.get(page.size() / 2), page.get((int) (page.size() * 0.95)));
    }

    private void drillDown(UUID period, String label) throws Exception {
        List<Long> drill = new ArrayList<>();
        Random random = new Random(2);
        String[] boxes = {"1a", "1b", "1c", "1d", "1e", "1f", "1g", "9"};
        for (int i = 0; i < 60; i++) {
            long d0 = System.nanoTime();
            client.get(preparer, "/api/periods/" + period + "/boxes/" + boxes[random.nextInt(boxes.length)] + "/lines?size=50&page=" + random.nextInt(40));
            drill.add((System.nanoTime() - d0) / 1_000_000);
        }
        System.out.printf("LOAD drill-down %s: p50 %d ms, p95 %d ms, max %d ms (target p95 under 2,000 ms)%n", label, pct(drill, 50), pct(drill, 95), pct(drill, 100));
    }

    /**
     * The NFR-09 launch design point: 5,000 entities and 500 people working at once, each pausing between
     * clicks. A second pass drops the pauses, so every person is waiting on the server at the same moment.
     * That is harder than the requirement and shows how it degrades. Entities and returns are written
     * directly, since this measures reads under load and not onboarding. Most people read one period, and
     * one in ten reads the firm-wide views, which is the shape of a deadline week.
     */
    @Test
    void designPointOf5000EntitiesAnd500Users() throws Exception {
        int entities = Integer.getInteger("load.entities", 5_000);
        int users = Integer.getInteger("load.users", 500);
        int requestsPerUser = Integer.getInteger("load.requestsPerUser", 6);
        int thinkMillis = Integer.getInteger("load.thinkMs", 5_000);
        UUID tenant = setUpTenant();
        TestUser firmUser = world.newUser(tenant, "FIRM", "FIRM_ADMIN");
        RulePackStore.ActivePack pack = world.queryInTenant(tenant, () -> packs.forDate(LocalDate.of(2027, 1, 31)));
        long start = System.nanoTime();
        try (Connection c = superuserConnection(); Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            st.execute("ALTER TABLE legal_entity DISABLE ROW LEVEL SECURITY");
            st.execute("ALTER TABLE tax_period DISABLE ROW LEVEL SECURITY");
            st.execute("ALTER TABLE return_version DISABLE ROW LEVEL SECURITY");
            st.execute("""
                    INSERT INTO legal_entity (id, tenant_id, legal_name, trn, entity_kind, filing_frequency, first_period_start)
                    SELECT gen_random_uuid(), '%s', 'Load client ' || g, lpad((500000000000000 + g)::text, 15, '0'), 'STANDALONE', 'MONTHLY', DATE '2027-01-01'
                    FROM generate_series(1, %d) g
                    """.formatted(tenant, entities));
            st.execute("""
                    INSERT INTO tax_period (id, tenant_id, entity_id, period_start, period_end, due_date)
                    SELECT gen_random_uuid(), tenant_id, id, DATE '2027-01-01', DATE '2027-01-31', DATE '2027-02-28' FROM legal_entity WHERE tenant_id = '%s'
                    """.formatted(tenant));
            st.execute("""
                    INSERT INTO return_version (id, tenant_id, entity_id, period_id, version_no, form_code, form_version, rule_pack_id, rule_pack_version, rule_pack_signed_off,
                        box_values, member_values, values_hash, data_fingerprint, line_count, trigger, created_by)
                    SELECT gen_random_uuid(), tenant_id, entity_id, id, 1, '%s', '%s', '%s', '%s', false, '{"14":{"amount":"0","vat":"100.00","adjustment":"0"}}', '{}', 'x', 'x', 0, 'LOAD', 'load'
                    FROM tax_period WHERE tenant_id = '%s'
                    """.formatted(pack.definition().form().code(), pack.definition().form().version(), pack.meta().id(), pack.meta().version(), tenant));
            st.execute("ALTER TABLE legal_entity ENABLE ROW LEVEL SECURITY");
            st.execute("ALTER TABLE legal_entity FORCE ROW LEVEL SECURITY");
            st.execute("ALTER TABLE tax_period ENABLE ROW LEVEL SECURITY");
            st.execute("ALTER TABLE tax_period FORCE ROW LEVEL SECURITY");
            st.execute("ALTER TABLE return_version ENABLE ROW LEVEL SECURITY");
            st.execute("ALTER TABLE return_version FORCE ROW LEVEL SECURITY");
            c.commit();
            st.execute("ANALYZE");
        }
        double seedSeconds = (System.nanoTime() - start) / 1e9;
        List<String> periodIds = world.queryInTenant(tenant, () -> jdbc.sql("SELECT id::text FROM tax_period LIMIT 2000").query(String.class).list());
        System.out.printf("%nLOAD design point: %d entities, %d users, %d requests each, seed %.1f s%n", entities, users, requestsPerUser, seedSeconds);

        int cold = peopleAtWork(firmUser, periodIds, users, requestsPerUser, thinkMillis, "straight after a start, with pauses of about " + thinkMillis + " ms");
        int warm = peopleAtWork(firmUser, periodIds, users, requestsPerUser, thinkMillis, "warmed up, with pauses of about " + thinkMillis + " ms");
        int burst = peopleAtWork(firmUser, periodIds, users, requestsPerUser, 0, "warmed up, all at once with no pauses");
        assertThat(cold).isZero();
        assertThat(warm).isZero();
        assertThat(burst).isZero();
    }

    /** Runs every person through their clicks, prints the latencies and returns how many requests failed. */
    private int peopleAtWork(TestUser firmUser, List<String> periodIds, int users, int requestsPerUser, int thinkMillis, String label) throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(users);
        List<java.util.concurrent.Future<?>> running = new ArrayList<>();
        List<Long> period = Collections.synchronizedList(new ArrayList<>());
        List<Long> firmWide = Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.ConcurrentSkipListMap<Long, java.util.concurrent.atomic.AtomicInteger> slowBySecond = new java.util.concurrent.ConcurrentSkipListMap<>();
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger> failed = new java.util.concurrent.ConcurrentHashMap<>();
        String[] periodPages = {"/return", "/exceptions", "/workflow"};
        long began = System.nanoTime();
        for (int u = 0; u < users; u++) {
            int user = u;
            running.add(pool.submit(() -> {
                Random r = new Random(user);
                try {
                    // People do not start in step, so the first clicks spread over one pause.
                    if (thinkMillis > 0) {
                        Thread.sleep(r.nextInt(thinkMillis));
                    }
                    String id = periodIds.get(r.nextInt(periodIds.size()));
                    for (int i = 0; i < requestsPerUser; i++) {
                        boolean firm = user % 10 == 0;
                        String path = firm ? (i % 2 == 0 ? "/api/portfolio" : "/api/deadlines") : "/api/periods/" + id + periodPages[i % periodPages.length];
                        long t = System.nanoTime();
                        try {
                            int status = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).with(firmUser.auth())).andReturn().getResponse().getStatus();
                            if (status != 200) {
                                failed.computeIfAbsent(path.replace(id, "{id}") + " HTTP " + status, k -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
                            }
                        } catch (Exception e) {
                            failed.computeIfAbsent(path.replace(id, "{id}") + " " + e.getClass().getSimpleName(), k -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
                        }
                        long took = (System.nanoTime() - t) / 1_000_000;
                        (firm ? firmWide : period).add(took);
                        if (took > 1_500) {
                            slowBySecond.computeIfAbsent((t - began) / 5_000_000_000L * 5, k -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
                        }
                        if (thinkMillis > 0) {
                            Thread.sleep(thinkMillis / 2 + r.nextInt(thinkMillis));
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        for (java.util.concurrent.Future<?> f : running) {
            f.get();
        }
        pool.shutdown();
        double wall = (System.nanoTime() - began) / 1e9;
        int failures = failed.values().stream().mapToInt(java.util.concurrent.atomic.AtomicInteger::get).sum();
        System.out.printf("LOAD %s: wall %.1f s, %.0f requests/s, failures %d %s%n", label, wall, (period.size() + firmWide.size()) / wall, failures, failed);
        System.out.printf("LOAD   period views: p50 %d ms, p95 %d ms, max %d ms (%d requests)%n", pct(period, 50), pct(period, 95), pct(period, 100), period.size());
        System.out.printf("LOAD   firm-wide views: p50 %d ms, p95 %d ms, max %d ms (%d requests)%n", pct(firmWide, 50), pct(firmWide, 95), pct(firmWide, 100), firmWide.size());
        System.out.printf("LOAD   requests over 1.5 s, by the second they started: %s%n", slowBySecond);
        return failures;
    }

    private static long pct(List<Long> values, int p) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.isEmpty() ? 0 : sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p / 100.0 * sorted.size()) - 1));
    }
}
