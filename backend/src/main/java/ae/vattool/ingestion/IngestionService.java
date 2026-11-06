package ae.vattool.ingestion;

import ae.vattool.ingestion.Templates.Kind;
import ae.vattool.masterdata.LegalEntity;
import ae.vattool.platform.audit.AuditLog;
import ae.vattool.platform.storage.VaultStorage;
import ae.vattool.platform.tenancy.RequestContext;
import ae.vattool.platform.web.DomainException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loads a file or API payload into the ledger (ING-03, ING-07). Work is split into short
 * transactions so a large file does not hold locks for its whole duration. If a load is
 * interrupted it can be resent: finished rows are recognised and skipped.
 */
@Service
public class IngestionService {

    /** Raw source files must stay available for at least the 5-year retention rule. */
    private static final int RAW_RETENTION_YEARS = 5;
    private static final int CHUNK_SIZE = 500;
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    public record Batch(UUID id, UUID entityId, String kind, String templateVersion, String channel, String fileName,
                        String contentHash, String storageKey, String status, int rowsTotal, int rowsLoaded,
                        int rowsUnchanged, int rowsRejected, int rowsHeld, String createdBy, OffsetDateTime createdAt,
                        OffsetDateTime finishedAt) {
        static final String SELECT = """
                SELECT id, entity_id, kind, template_version, channel, file_name, content_hash, storage_key, status,
                       rows_total, rows_loaded, rows_unchanged, rows_rejected, rows_held, created_by, created_at, finished_at
                FROM ingest_batch
                """;
    }

    public record LoadResult(Batch batch, boolean alreadyProcessed) {}

    public record RowView(UUID id, int rowNo, String status, String[] errors, String raw, UUID documentId) {}

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final AuditLog audit;
    private final VaultStorage storage;
    private final RowProcessor rows;

    public IngestionService(JdbcClient jdbc, TransactionTemplate tx, AuditLog audit, VaultStorage storage, RowProcessor rows) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.audit = audit;
        this.storage = storage;
        this.rows = rows;
    }

    public LoadResult loadFile(UUID filerId, Kind kind, String templateVersion, String channel, String fileName, byte[] content) {
        TabularFile.Parsed parsed = TabularFile.read(fileName, content);
        Templates.checkHeaders(kind, templateVersion, parsed.headers());
        String key = "t/" + RequestContext.requireTenant() + "/raw/" + filerId + "/" + kind + "/"
                + VaultStorage.sha256(content) + "/" + sanitise(fileName);
        return load(filerId, kind, templateVersion, channel, fileName, VaultStorage.sha256(content), parsed.rows(),
                () -> storage.put(key, content, "application/octet-stream", LocalDate.now().plusYears(RAW_RETENTION_YEARS)).key());
    }

    public LoadResult loadRows(UUID filerId, Kind kind, String templateVersion, List<Map<String, String>> payload) {
        if (payload == null || payload.isEmpty()) {
            throw DomainException.invalid("NO_ROWS", "The payload has no rows");
        }
        java.util.Set<String> headers = new java.util.LinkedHashSet<>();
        payload.forEach(r -> headers.addAll(r.keySet()));
        Templates.checkHeaders(kind, templateVersion, headers);
        byte[] raw = JSON.writeValueAsBytes(payload);
        String hash = VaultStorage.sha256(raw);
        String key = "t/" + RequestContext.requireTenant() + "/raw/" + filerId + "/" + kind + "/" + hash + "/payload.json";
        return load(filerId, kind, templateVersion, "API", "api-" + hash.substring(0, 12) + ".json", hash, payload,
                () -> storage.put(key, raw, "application/json", LocalDate.now().plusYears(RAW_RETENTION_YEARS)).key());
    }

    private LoadResult load(UUID filerId, Kind kind, String version, String channel, String fileName, String hash,
                            List<Map<String, String>> parsedRows, java.util.function.Supplier<String> storeRaw) {
        LegalEntity filer = requireFiler(filerId);
        Optional<Batch> existing = findByHash(filerId, kind, hash);
        if (existing.isPresent() && "PROCESSED".equals(existing.get().status())) {
            // ING-07: reloading a file already processed changes nothing.
            return new LoadResult(existing.get(), true);
        }
        String storageKey = storeRaw.get();
        UUID batchId = existing.map(Batch::id).orElseGet(() -> createBatch(filerId, kind, version, channel, fileName, hash, storageKey));

        RowProcessor.Counts counts = new RowProcessor.Counts();
        RowProcessor.FileContext context = new RowProcessor.FileContext(filer, kind, batchId);
        for (int start = 0; start < parsedRows.size(); start += CHUNK_SIZE) {
            int from = start;
            int to = Math.min(start + CHUNK_SIZE, parsedRows.size());
            tx.executeWithoutResult(s -> rows.processChunk(context, parsedRows.subList(from, to), from + 1, counts));
        }
        Batch done = tx.execute(s -> finish(batchId, filerId, kind, fileName, hash, parsedRows.size(), counts));
        if (kind == Kind.FX) {
            rows.reprocessHeld(filer);
        }
        return new LoadResult(done, false);
    }

    private UUID createBatch(UUID filerId, Kind kind, String version, String channel, String fileName, String hash, String storageKey) {
        UUID id = UUID.randomUUID();
        tx.executeWithoutResult(s -> jdbc.sql("""
                INSERT INTO ingest_batch (id, tenant_id, entity_id, kind, template_version, channel, file_name, content_hash,
                                          storage_key, status, created_by)
                VALUES (:id, :tenant, :entity, :kind, :version, :channel, :file, :hash, :key, 'PROCESSING', :by)
                """)
                .param("id", id).param("tenant", RequestContext.requireTenant()).param("entity", filerId)
                .param("kind", kind.name()).param("version", version).param("channel", channel)
                .param("file", fileName).param("hash", hash).param("key", storageKey)
                .param("by", RequestContext.requireActor().actorId())
                .update());
        return id;
    }

    private Batch finish(UUID batchId, UUID filerId, Kind kind, String fileName, String hash, int total, RowProcessor.Counts c) {
        jdbc.sql("""
                UPDATE ingest_batch SET status = 'PROCESSED', finished_at = now(), rows_total = :total, rows_loaded = :loaded,
                       rows_unchanged = :unchanged, rows_rejected = :rejected, rows_held = :held
                WHERE id = :id
                """)
                .param("total", total).param("loaded", c.loaded).param("unchanged", c.unchanged)
                .param("rejected", c.rejected).param("held", c.held).param("id", batchId)
                .update();
        // One audit event per file, not per row, so the chain does not throttle ingestion.
        audit.record("DATA_LOADED", "INGEST_BATCH", batchId, filerId, null, Map.of(
                "kind", kind.name(), "file", fileName, "sha256", hash, "rows", total, "loaded", c.loaded,
                "unchanged", c.unchanged, "rejected", c.rejected, "held", c.held), null);
        return get(batchId);
    }

    @Transactional(readOnly = true)
    public Batch get(UUID id) {
        return jdbc.sql(Batch.SELECT + " WHERE id = ?").param(id).query(Batch.class).optional()
                .orElseThrow(() -> DomainException.notFound("Data load"));
    }

    @Transactional(readOnly = true)
    public List<Batch> list(UUID entityId) {
        return jdbc.sql(Batch.SELECT + " WHERE entity_id = ? ORDER BY created_at DESC LIMIT 200")
                .param(entityId).query(Batch.class).list();
    }

    @Transactional(readOnly = true)
    public List<RowView> rows(UUID batchId, String status, int limit) {
        return jdbc.sql("""
                SELECT id, row_no, status, errors, raw::text AS raw, document_id FROM ingest_row
                WHERE batch_id = :batch AND (CAST(:status AS text) IS NULL OR status = :status)
                ORDER BY row_no LIMIT :limit
                """)
                .param("batch", batchId).param("status", status).param("limit", Math.min(limit, 5000))
                .query((rs, n) -> new RowView(rs.getObject("id", UUID.class), rs.getInt("row_no"), rs.getString("status"),
                        (String[]) rs.getArray("errors").getArray(), rs.getString("raw"), rs.getObject("document_id", UUID.class)))
                .list();
    }

    private Optional<Batch> findByHash(UUID filerId, Kind kind, String hash) {
        return tx.execute(s -> jdbc.sql(Batch.SELECT + " WHERE entity_id = ? AND kind = ? AND content_hash = ?")
                .params(filerId, kind.name(), hash).query(Batch.class).optional());
    }

    private LegalEntity requireFiler(UUID id) {
        LegalEntity entity = tx.execute(s -> jdbc.sql(LegalEntity.SELECT + " WHERE id = ?").param(id)
                .query(LegalEntity.class).optional().orElseThrow(() -> DomainException.notFound("Entity")));
        if (!entity.isFiler()) {
            throw DomainException.invalid("LOAD_TO_FILER", "Load data to the group representative, not a member");
        }
        return entity;
    }

    private static String sanitise(String name) {
        return name == null ? "file" : name.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
