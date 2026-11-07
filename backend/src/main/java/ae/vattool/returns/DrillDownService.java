package ae.vattool.returns;

import ae.vattool.ledger.TaxLine;
import ae.vattool.ledger.TaxLineRowMapper;
import ae.vattool.masterdata.TaxPeriod;
import ae.vattool.platform.web.DomainException;
import ae.vattool.rules.RulePackDefinition;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * From a box to its lines, source documents and e-invoice UUIDs (RET-02, ING-07). The query is built
 * from the same form filter the calculator uses. Field values only ever reach SQL as bind parameters.
 */
@Service
public class DrillDownService {

    public record Row(TaxLine line, String docNumber, LocalDate docDate, String docType, String counterpartyName,
                      UUID einvoiceUuid, String corner5Status, Integer sourceRowNo, String sourceFile) {}

    public record Page(String box, List<Row> rows, int page, int size, long total) {}

    public record Lineage(TaxLine line, Map<String, Object> document, Map<String, Object> sourceRow, Map<String, Object> batch,
                          List<Map<String, Object>> otherBoxes) {}

    private final JdbcClient jdbc;
    private final ReturnService returns;

    public DrillDownService(JdbcClient jdbc, ReturnService returns) {
        this.jdbc = jdbc;
        this.returns = returns;
    }

    @Transactional(readOnly = true)
    public Page lines(TaxPeriod period, String boxCode, int page, int size) {
        RulePackDefinition.Form form = returns.packFor(period).definition().form();
        RulePackDefinition.Box box = form.boxes().stream().filter(b -> b.code().equals(boxCode)).findFirst()
                .orElseThrow(() -> DomainException.notFound("Box " + boxCode));
        if (box.isComputed()) {
            throw DomainException.invalid("BOX_IS_COMPUTED", "Box " + boxCode + " is calculated from other boxes. Open those instead.");
        }
        Map<String, Object> params = new HashMap<>();
        params.put("period", period.id());
        params.put("box", boxCode);
        String current = "l.period_id = :period AND l.superseded_at IS NULL AND l.memo = false";
        String adjustments = current + " AND l.line_kind = 'ADJUSTMENT' AND l.adj_box = :box";

        List<String> normal = new ArrayList<>(List.of(current, "l.line_kind = 'NORMAL'"));
        RulePackDefinition.Filter f = box.filter();
        if (f.direction() != null) {
            normal.add("l.direction = :direction");
            params.put("direction", f.direction());
        }
        if (f.emirate() != null) {
            normal.add("l.emirate = :emirate");
            params.put("emirate", f.emirate());
        }
        if (f.blocked() != null) {
            normal.add(f.blocked() ? "l.blocked_category IS NOT NULL" : "l.blocked_category IS NULL");
        }
        String normalLines = String.join(" AND ", normal);

        String allNormal = normalLines;
        if (f.treatments() != null) {
            allNormal += " AND l.treatment IN (:treatments)";
            params.put("treatments", f.treatments());
        }
        long total = jdbc.sql("SELECT (SELECT count(*) FROM tax_line l WHERE " + allNormal + ") + (SELECT count(*) FROM tax_line l WHERE " + adjustments + ")")
                .params(params).query(Long.class).single();
        int safeSize = Math.min(Math.max(size, 1), 500);
        long offset = (long) Math.max(page, 0) * safeSize;
        if (offset >= total) {
            return new Page(boxCode, List.of(), Math.max(page, 0), safeSize, total);
        }

        // Each treatment is read on its own, in index order, because an index cannot give the order across an IN list.
        // Only the lines on the wanted page are then joined to their documents, so a large box costs no more than a small one.
        List<String> arms = new ArrayList<>();
        if (f.treatments() == null) {
            arms.add(normalLines);
        } else {
            for (int i = 0; i < f.treatments().size(); i++) {
                arms.add(normalLines + " AND l.treatment = :treatment" + i);
                params.put("treatment" + i, f.treatments().get(i));
            }
        }
        arms.add(adjustments);
        String candidates = arms.stream()
                .map(where -> "(SELECT l.id, l.tax_year, l.tax_point FROM tax_line l WHERE " + where + " ORDER BY l.tax_point, l.id LIMIT :top)")
                .reduce((a, b) -> a + " UNION ALL " + b).orElseThrow();
        String columns = java.util.Arrays.stream(TaxLine.COLUMNS.split(","))
                .map(c -> "l." + c.trim()).reduce((a, b) -> a + ", " + b).orElseThrow();
        List<Row> rows = jdbc.sql("""
                WITH wanted AS MATERIALIZED (
                    SELECT id, tax_year, tax_point FROM (%s) candidates ORDER BY tax_point, id LIMIT :limit OFFSET :offset
                )
                SELECT %s, d.doc_number, d.doc_date, d.doc_type, d.counterparty_name, d.einvoice_uuid AS doc_uuid,
                       COALESCE(m.invoice_uuid, d.einvoice_uuid) AS einvoice_uuid, m.corner5_status, r.row_no, b.file_name
                FROM wanted
                JOIN tax_line l ON l.tax_year = wanted.tax_year AND l.id = wanted.id
                LEFT JOIN source_document d ON d.id = l.document_id
                LEFT JOIN doc_match dm ON dm.document_id = d.id AND dm.status = 'CONFIRMED'
                LEFT JOIN einvoice m ON m.invoice_uuid = COALESCE(dm.invoice_uuid, d.einvoice_uuid)
                LEFT JOIN ingest_row r ON r.id = l.ingest_row_id
                LEFT JOIN ingest_batch b ON b.id = r.batch_id
                ORDER BY l.tax_point, l.id
                """.formatted(candidates, columns))
                .params(params).param("top", offset + safeSize).param("limit", safeSize).param("offset", offset)
                .query((rs, n) -> new Row(TaxLineRowMapper.INSTANCE.mapRow(rs, n), rs.getString("doc_number"),
                        rs.getObject("doc_date", LocalDate.class), rs.getString("doc_type"), rs.getString("counterparty_name"),
                        rs.getObject("einvoice_uuid", UUID.class), rs.getString("corner5_status"),
                        (Integer) rs.getObject("row_no"), rs.getString("file_name")))
                .list();
        return new Page(boxCode, rows, Math.max(page, 0), safeSize, total);
    }

    /** The full path for one line: document, the exact source row, and the file it came from. */
    @Transactional(readOnly = true)
    public Lineage lineage(UUID lineId) {
        TaxLine line = jdbc.sql("SELECT " + TaxLine.COLUMNS + " FROM tax_line WHERE id = ?").param(lineId)
                .query(TaxLineRowMapper.INSTANCE).optional()
                .orElseThrow(() -> DomainException.notFound("Tax line"));
        Map<String, Object> document = line.documentId() == null ? Map.of() : jdbc.sql("""
                SELECT id, source_system, doc_type, doc_number, doc_date, counterparty_name, counterparty_trn, einvoice_uuid
                FROM source_document WHERE id = ?
                """).param(line.documentId()).query().singleRow();
        Map<String, Object> row = Map.of();
        Map<String, Object> batch = Map.of();
        if (line.ingestRowId() != null) {
            row = jdbc.sql("SELECT id, row_no, status, raw::text AS raw, batch_id FROM ingest_row WHERE id = ?")
                    .param(line.ingestRowId()).query().listOfRows().stream().findFirst().orElse(Map.of());
            if (!row.isEmpty()) {
                batch = jdbc.sql("""
                        SELECT id, file_name, kind, channel, content_hash, storage_key, created_by, created_at
                        FROM ingest_batch WHERE id = ?
                        """).param(row.get("batch_id")).query().singleRow();
            }
        }
        return new Lineage(line, document, row, batch, List.of());
    }
}
