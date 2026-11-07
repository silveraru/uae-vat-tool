package ae.vattool.ingestion.connectors;

import ae.vattool.ingestion.IngestionService;
import ae.vattool.ingestion.TabularFile;
import ae.vattool.ingestion.Templates;
import ae.vattool.platform.audit.AuditLog;
import ae.vattool.platform.crypto.TenantKeys;
import ae.vattool.platform.tenancy.RequestContext;
import ae.vattool.platform.web.DomainException;
import ae.vattool.platform.web.OutboundUrlGuard;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pulls data from an ERP or maps its export, then hands it to normal ingestion (ING-04), so a
 * connector gets the same validation, idempotency and lineage as an upload, with no second write path.
 */
@Service
public class ConnectorService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_PAGES = 1000;
    private static final Set<String> AUTH = Set.of("NONE", "BEARER", "BASIC", "API_KEY");

    public record ProfileView(String system, String label, String status, String transport, String note, List<String> kinds) {}

    public record ConnectorInput(String system, String baseUrl, String authKind, String secret, List<String> kinds, boolean enabled) {}

    public record Connector(UUID id, UUID entityId, String system, String baseUrl, String authKind, boolean hasSecret, List<String> kinds, boolean enabled, String createdBy, OffsetDateTime createdAt) {}

    public record Run(UUID id, UUID connectorId, String kind, LocalDate fromDate, LocalDate toDate, String status, int rowsFetched, UUID batchId, String error, OffsetDateTime finishedAt) {}

    public record SyncResult(Run run, IngestionService.LoadResult load) {}

    private final Map<String, ConnectorProfile> profiles = ConnectorProfile.loadAll();
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final IngestionService ingestion;
    private final TenantKeys keys;
    private final OutboundUrlGuard urls;
    private final AuditLog audit;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();

    public ConnectorService(JdbcClient jdbc, TransactionTemplate tx, IngestionService ingestion, TenantKeys keys, OutboundUrlGuard urls, AuditLog audit) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.ingestion = ingestion;
        this.keys = keys;
        this.urls = urls;
        this.audit = audit;
    }

    public List<ProfileView> profiles() {
        return profiles.values().stream().map(p -> new ProfileView(p.system(), p.label(), p.status(), p.transport(), p.note(), p.kindNames())).toList();
    }

    private ConnectorProfile profile(String system) {
        ConnectorProfile p = profiles.get(system);
        if (p == null) {
            throw DomainException.invalid("CONNECTOR_SYSTEM_UNKNOWN", "Unknown system: " + system);
        }
        return p;
    }

    public Connector create(UUID entityId, ConnectorInput in) {
        ConnectorProfile p = profile(in.system());
        if (!p.isRest()) {
            throw DomainException.invalid("CONNECTOR_FILE_ONLY", p.label() + " is loaded from its export file, so it needs no connection");
        }
        urls.check(in.baseUrl());
        if (in.authKind() == null || !AUTH.contains(in.authKind())) {
            throw DomainException.invalid("CONNECTOR_AUTH_INVALID", "Authentication must be one of " + AUTH);
        }
        if (!"NONE".equals(in.authKind()) && (in.secret() == null || in.secret().isBlank())) {
            throw DomainException.invalid("CONNECTOR_SECRET_REQUIRED", "This authentication needs a secret");
        }
        List<String> kinds = in.kinds() == null || in.kinds().isEmpty() ? p.kindNames() : in.kinds();
        kinds.forEach(p::kind);
        UUID id = UUID.randomUUID();
        return tx.execute(s -> {
            if (!jdbc.sql("SELECT EXISTS (SELECT 1 FROM legal_entity WHERE id = ?)").param(entityId).query(Boolean.class).single()) {
                throw DomainException.notFound("Entity");
            }
            int added = jdbc.sql("""
                    INSERT INTO connector (id, tenant_id, entity_id, system, base_url, auth_kind, secret_encrypted, kinds, enabled, created_by)
                    VALUES (:id, :tenant, :entity, :system, :url, :auth, :secret, string_to_array(:kinds, ','), :enabled, :by)
                    ON CONFLICT (tenant_id, entity_id, system) DO NOTHING
                    """)
                    .param("id", id).param("tenant", RequestContext.requireTenant()).param("entity", entityId).param("system", in.system()).param("url", in.baseUrl())
                    .param("auth", in.authKind()).param("secret", in.secret() == null || in.secret().isBlank() ? null : keys.encryptText(in.secret()))
                    .param("kinds", String.join(",", kinds)).param("enabled", in.enabled()).param("by", RequestContext.requireActor().actorId())
                    .update();
            if (added == 0) {
                throw DomainException.conflict("CONNECTOR_EXISTS", "This entity already has a connection to " + p.label());
            }
            audit.record("CONNECTOR_CREATED", "CONNECTOR", id, entityId, null, Map.of("system", in.system(), "url", in.baseUrl(), "enabled", in.enabled()), null);
            return find(id);
        });
    }

    public List<Connector> list(UUID entityId) {
        return tx.execute(s -> jdbc.sql(SELECT + " WHERE entity_id = ? ORDER BY system").param(entityId).query((rs, n) -> connector(rs)).list());
    }

    public Connector get(UUID id) {
        return tx.execute(s -> find(id));
    }

    public List<Run> runs(UUID connectorId) {
        return tx.execute(s -> jdbc.sql("""
                SELECT id, connector_id, kind, from_date, to_date, status, rows_fetched, batch_id, error, finished_at FROM connector_run
                WHERE connector_id = ? ORDER BY finished_at DESC LIMIT 50
                """).param(connectorId).query(Run.class).list());
    }

    /** Fetches a date range from the ERP and loads it. A failure is recorded, then reported. */
    public SyncResult sync(UUID connectorId, String kind, LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw DomainException.invalid("RANGE_INVALID", "Give a start date on or before the end date");
        }
        Connector c = get(connectorId);
        ConnectorProfile p = profile(c.system());
        if (!c.kinds().contains(kind)) {
            throw DomainException.invalid("CONNECTOR_KIND_UNSUPPORTED", "This connection is not set up for " + kind);
        }
        OffsetDateTime started = OffsetDateTime.now();
        int fetched = 0;
        try {
            List<Map<String, String>> rows = fetch(c, p.kind(kind), from, to);
            fetched = rows.size();
            if (rows.isEmpty()) {
                return new SyncResult(recordRun(c, kind, from, to, "OK", 0, null, null, started), null);
            }
            IngestionService.LoadResult load = ingestion.loadRows(c.entityId(), Templates.parseKind(kind), Templates.CURRENT_VERSION, rows);
            return new SyncResult(recordRun(c, kind, from, to, "OK", fetched, load.batch().id(), null, started), load);
        } catch (RuntimeException e) {
            recordRun(c, kind, from, to, "FAILED", fetched, null, e.getMessage(), started);
            throw e;
        }
    }

    /** Loads a system's own export through its column mapping. */
    public IngestionService.LoadResult loadExport(UUID entityId, String system, String kind, String fileName, byte[] content) {
        ConnectorProfile.KindMap map = profile(system).kind(kind);
        TabularFile.Parsed parsed = TabularFile.read(fileName, content);
        List<String> missing = map.fields().values().stream().filter(column -> !parsed.headers().contains(column)).toList();
        if (!missing.isEmpty()) {
            throw DomainException.invalid("CONNECTOR_COLUMNS_MISSING", "This export is missing the columns " + String.join(", ", missing) + ". The mapping may need adjusting for this system.");
        }
        List<Map<String, String>> rows = parsed.rows().stream().map(r -> RowMapper.fromColumns(map, r)).toList();
        if (rows.isEmpty()) {
            throw DomainException.invalid("NO_ROWS", "The file has no rows");
        }
        return ingestion.loadRows(entityId, Templates.parseKind(kind), Templates.CURRENT_VERSION, rows);
    }

    private List<Map<String, String>> fetch(Connector c, ConnectorProfile.KindMap map, LocalDate from, LocalDate to) {
        urls.check(c.baseUrl());
        String secret = tx.execute(s -> {
            String stored = jdbc.sql("SELECT secret_encrypted FROM connector WHERE id = ?").param(c.id()).query(String.class).optional().orElse(null);
            return stored == null ? null : keys.decryptText(stored);
        });
        List<Map<String, String>> rows = new ArrayList<>();
        ConnectorProfile.Paging paging = map.paging();
        int cursor = paging == null ? 0 : paging.start();
        for (int page = 0; page < MAX_PAGES; page++) {
            JsonNode body = get(c, url(c.baseUrl(), map, from, to, paging, cursor), secret);
            JsonNode items = RowMapper.at(body, map.itemsPath());
            if (items == null || !items.isArray()) {
                throw DomainException.conflict("CONNECTOR_BAD_RESPONSE", "The response has no list at " + map.itemsPath() + ". The mapping may need adjusting for this system.");
            }
            int count = 0;
            for (JsonNode item : items) {
                rows.add(RowMapper.fromJson(map, item));
                count++;
            }
            if (paging == null || count < paging.size()) {
                return rows;
            }
            cursor += "OFFSET".equals(paging.style()) ? paging.size() : 1;
        }
        throw DomainException.conflict("CONNECTOR_TOO_MANY_PAGES", "The system returned more than " + MAX_PAGES + " pages. Narrow the date range.");
    }

    private static String url(String base, ConnectorProfile.KindMap map, LocalDate from, LocalDate to, ConnectorProfile.Paging paging, int cursor) {
        StringBuilder q = new StringBuilder();
        if (map.params() != null) {
            map.params().forEach((k, v) -> add(q, k, v.replace("{from}", from.toString()).replace("{to}", to.toString())));
        }
        if (paging != null) {
            add(q, paging.param(), String.valueOf(cursor));
            add(q, paging.sizeParam(), String.valueOf(paging.size()));
        }
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return trimmed + map.path() + (q.isEmpty() ? "" : "?" + q);
    }

    private static void add(StringBuilder q, String key, String value) {
        if (!q.isEmpty()) {
            q.append('&');
        }
        q.append(URLEncoder.encode(key, StandardCharsets.UTF_8)).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    private JsonNode get(Connector c, String url, String secret) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).header("Accept", "application/json");
        switch (c.authKind()) {
            case "BEARER" -> request.header("Authorization", "Bearer " + secret);
            case "BASIC" -> request.header("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8)));
            case "API_KEY" -> request.header("X-API-Key", secret);
            default -> { }
        }
        try {
            HttpResponse<String> response = http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw DomainException.conflict("CONNECTOR_REFUSED", "The system answered HTTP " + response.statusCode());
            }
            return JSON.readTree(response.body());
        } catch (IOException e) {
            throw DomainException.conflict("CONNECTOR_UNREACHABLE", "The system could not be reached: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw DomainException.conflict("CONNECTOR_UNREACHABLE", "Interrupted while calling the system");
        }
    }

    private Run recordRun(Connector c, String kind, LocalDate from, LocalDate to, String status, int rows, UUID batch, String error, OffsetDateTime started) {
        UUID id = UUID.randomUUID();
        return tx.execute(s -> {
            jdbc.sql("""
                    INSERT INTO connector_run (id, tenant_id, connector_id, kind, from_date, to_date, status, rows_fetched, batch_id, error, started_at)
                    VALUES (:id, :tenant, :connector, :kind, :from, :to, :status, :rows, :batch, :error, :started)
                    """)
                    .param("id", id).param("tenant", RequestContext.requireTenant()).param("connector", c.id()).param("kind", kind).param("from", from).param("to", to)
                    .param("status", status).param("rows", rows).param("batch", batch).param("error", error == null ? null : error.substring(0, Math.min(error.length(), 1000))).param("started", started)
                    .update();
            return jdbc.sql("SELECT id, connector_id, kind, from_date, to_date, status, rows_fetched, batch_id, error, finished_at FROM connector_run WHERE id = ?").param(id).query(Run.class).single();
        });
    }

    /** The day after the last good run for a kind, or the start of last month if there has been none. */
    public LocalDate nextFrom(UUID connectorId, String kind, LocalDate today) {
        return tx.execute(s -> jdbc.sql("SELECT MAX(to_date) FROM connector_run WHERE connector_id = ? AND kind = ? AND status = 'OK'").params(connectorId, kind)
                .query(LocalDate.class).optional().map(d -> d.plusDays(1)).orElse(today.minusMonths(1).withDayOfMonth(1)));
    }

    public List<Connector> enabled() {
        return tx.execute(s -> jdbc.sql(SELECT + " WHERE enabled").query((rs, n) -> connector(rs)).list());
    }

    private static final String SELECT = "SELECT id, entity_id, system, base_url, auth_kind, secret_encrypted, kinds, enabled, created_by, created_at FROM connector";

    private Connector find(UUID id) {
        return jdbc.sql(SELECT + " WHERE id = ?").param(id).query((rs, n) -> connector(rs)).optional().orElseThrow(() -> DomainException.notFound("Connector"));
    }

    private static Connector connector(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Connector(rs.getObject("id", UUID.class), rs.getObject("entity_id", UUID.class), rs.getString("system"), rs.getString("base_url"), rs.getString("auth_kind"),
                rs.getString("secret_encrypted") != null, List.of((String[]) rs.getArray("kinds").getArray()), rs.getBoolean("enabled"), rs.getString("created_by"), rs.getObject("created_at", OffsetDateTime.class));
    }
}
