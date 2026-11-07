package ae.vattool.ingestion;

import ae.vattool.ingestion.einvoice.EinvoiceEvent;
import ae.vattool.ingestion.einvoice.EinvoiceStore;
import ae.vattool.ingestion.ubl.UblParser;
import ae.vattool.masterdata.LegalEntity;
import ae.vattool.platform.tenancy.RequestContext;
import ae.vattool.platform.web.DomainException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads PINT AE UBL from another ASP or a client's own archive (ING-02), as one XML or a ZIP of them.
 * A document that cannot be read becomes a rejected row, which blocks approval like any other, and
 * never stops the rest of the file. Corner 5 status is not in the document, so it stays pending until
 * given: nobody can claim the FTA has an invoice just because a client holds a copy of it.
 */
@Service
public class UblImportService {

    private static final int MAX_ENTRIES = 50_000;
    private static final long MAX_ENTRY_BYTES = 20L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 1024L * 1024 * 1024;
    private static final Set<String> CORNER5 = Set.of("PENDING", "REPORTED", "FAILED");

    public record StatusInput(UUID invoiceUuid, String status) {}

    public record StatusResult(int applied, int unknown) {}

    private final IngestionService ingestion;
    private final EinvoiceStore einvoices;
    private final JdbcClient jdbc;

    public UblImportService(IngestionService ingestion, EinvoiceStore einvoices, JdbcClient jdbc) {
        this.ingestion = ingestion;
        this.einvoices = einvoices;
        this.jdbc = jdbc;
    }

    public IngestionService.LoadResult load(UUID filerId, String channel, String fileName, byte[] content) {
        if (content == null || content.length == 0) {
            throw DomainException.invalid("FILE_EMPTY", "The file is empty");
        }
        String name = fileName == null ? "upload.xml" : fileName;
        List<Map<String, String>> rows = new ArrayList<>();
        boolean zip = name.toLowerCase().endsWith(".zip");
        if (zip) {
            readZip(content, rows);
        } else {
            rows.add(toRow(name, content));
        }
        if (rows.isEmpty()) {
            throw DomainException.invalid("NO_ROWS", "The archive holds no XML documents");
        }
        return ingestion.loadParsed(filerId, Templates.Kind.UBL, channel, name, content, zip ? "application/zip" : "application/xml", rows);
    }

    /** Corner 5 status from the other ASP, since a UBL document does not carry it. */
    @Transactional
    public StatusResult applyStatuses(UUID filerId, List<StatusInput> statuses) {
        LegalEntity filer = jdbc.sql(LegalEntity.SELECT + " WHERE id = ?").param(filerId).query(LegalEntity.class).optional()
                .orElseThrow(() -> DomainException.notFound("Entity"));
        int applied = 0;
        int unknown = 0;
        for (StatusInput s : statuses) {
            if (s.status() == null || !CORNER5.contains(s.status())) {
                throw DomainException.invalid("CORNER5_STATUS_INVALID", "Status must be one of " + CORNER5);
            }
            boolean known = jdbc.sql("SELECT EXISTS (SELECT 1 FROM einvoice WHERE invoice_uuid = ? AND filer_entity_id = ?)")
                    .params(s.invoiceUuid(), filerId).query(Boolean.class).single();
            if (!known) {
                unknown++;
                continue;
            }
            // A new id each time: a status can be corrected and corrected back, and a repeat of the same id would be ignored.
            einvoices.apply(new EinvoiceEvent("1.0", UUID.randomUUID(), EinvoiceEvent.CORNER5, OffsetDateTime.now(), RequestContext.requireTenant(), filer.trn(), s.invoiceUuid(),
                    null, null, new EinvoiceEvent.Corner5(s.status())));
            applied++;
        }
        return new StatusResult(applied, unknown);
    }

    /** Limits are checked while reading, because the sizes a ZIP declares can lie. */
    private static void readZip(byte[] content, List<Map<String, String>> rows) {
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            int entries = 0;
            long total = 0;
            byte[] buffer = new byte[8192];
            while ((entry = z.getNextEntry()) != null) {
                if (entry.isDirectory() || !entry.getName().toLowerCase().endsWith(".xml")) {
                    continue;
                }
                if (++entries > MAX_ENTRIES) {
                    throw DomainException.invalid("ARCHIVE_TOO_LARGE", "The archive holds more than " + MAX_ENTRIES + " documents");
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                int read;
                while ((read = z.read(buffer)) > 0) {
                    total += read;
                    if (out.size() + read > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES) {
                        throw DomainException.invalid("ARCHIVE_TOO_LARGE", "The archive is larger than the limit");
                    }
                    out.write(buffer, 0, read);
                }
                rows.add(toRow(entry.getName(), out.toByteArray()));
            }
        } catch (IOException e) {
            throw DomainException.invalid("FILE_UNREADABLE", "The archive could not be read: " + e.getMessage());
        }
    }

    private static Map<String, String> toRow(String sourceFile, byte[] xml) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("source_file", sourceFile);
        try {
            UblParser.Invoice i = UblParser.parse(xml);
            row.put("invoice_uuid", i.uuid().toString());
            row.put("doc_type", i.docType());
            row.put("number", i.number());
            row.put("issue_date", i.issueDate());
            row.put("supplier_trn", i.supplierTrn());
            row.put("buyer_trn", i.buyerTrn());
            row.put("currency", i.currency());
            row.put("net_aed", i.netAed().toPlainString());
            row.put("vat_aed", i.vatAed().toPlainString());
            row.put("billing_reference", i.billingReference());
        } catch (DomainException e) {
            row.put("parse_error", e.code());
            row.put("detail", e.getMessage());
        }
        return row;
    }
}
