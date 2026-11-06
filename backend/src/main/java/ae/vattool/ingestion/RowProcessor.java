package ae.vattool.ingestion;

import ae.vattool.ingestion.Templates.Kind;
import ae.vattool.ledger.Ledger;
import ae.vattool.ledger.LineInput;
import ae.vattool.masterdata.LegalEntity;
import ae.vattool.masterdata.PeriodCalendar;
import ae.vattool.masterdata.TaxPeriod;
import ae.vattool.masterdata.TrnValidator;
import ae.vattool.platform.tenancy.RequestContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Validates each source row and writes it to the ledger. A bad row never stops the file, and is
 * never silently dropped: it is recorded with its errors and blocks approval through reconciliation
 * check D02 until fixed or waived.
 */
@Component
public class RowProcessor {

    static final class Counts {
        int loaded;
        int unchanged;
        int rejected;
        int held;
    }

    /** State shared by the rows of one file, including what is needed to spot in-file duplicates. */
    static final class FileContext {
        final LegalEntity filer;
        final Kind kind;
        final UUID batchId;
        final Map<String, Integer> seenRows = new HashMap<>();
        Map<String, Establishment> establishments;
        UUID currentRowId;
        final Ledger.WriteCache cache = new Ledger.WriteCache();
        final Map<String, Optional<BigDecimal>> fxRates = new HashMap<>();
        final Map<String, ChunkDoc> chunkDocs = new HashMap<>();
        boolean openProblems;

        FileContext(LegalEntity filer, Kind kind, UUID batchId) {
            this.filer = filer;
            this.kind = kind;
            this.batchId = batchId;
        }
    }

    record Establishment(UUID id, UUID entityId, String code, String emirate, boolean designatedZone) {}

    /** A document already written in this transaction, with the header values it was written with. */
    private record ChunkDoc(UUID id, String header) {}

    private record UpsertedDoc(UUID id, boolean inserted) {}

    private record Outcome(String status, List<String> errors, String rowKey, UUID periodId, UUID documentId) {}

    private static final Set<String> OUT_TYPES = Set.of("TAX_INVOICE", "CREDIT_NOTE", "DEBIT_NOTE", "B2C_SALE", "TOURIST_REFUND");
    private static final Set<String> IN_TYPES = Set.of("TAX_INVOICE", "CREDIT_NOTE", "DEBIT_NOTE", "EXPENSE");
    private static final Set<String> CUSTOMS_TYPES = Set.of("IMPORT_DECLARATION", "IMPORT_ADJUSTMENT");
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Ledger ledger;
    private final PeriodCalendar calendar;
    private final TrnValidator trnValidator;

    public RowProcessor(JdbcClient jdbc, TransactionTemplate tx, Ledger ledger, PeriodCalendar calendar, TrnValidator trnValidator) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.ledger = ledger;
        this.calendar = calendar;
        this.trnValidator = trnValidator;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void processChunk(FileContext ctx, List<Map<String, String>> rows, int firstRowNo, Counts counts) {
        ctx.cache.newTransaction();
        ctx.chunkDocs.clear();
        ctx.openProblems = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM ingest_row WHERE entity_id = ? AND status IN ('REJECTED', 'HELD') AND resolved_at IS NULL)
                """).param(ctx.filer.id()).query(Boolean.class).single();
        int rowNo = firstRowNo;
        for (Map<String, String> row : rows) {
            ctx.currentRowId = UUID.randomUUID();
            Outcome outcome = process(ctx, row);
            record(ctx, row, rowNo++, outcome);
            switch (outcome.status()) {
                case "LOADED" -> counts.loaded++;
                case "UNCHANGED" -> counts.unchanged++;
                case "HELD" -> counts.held++;
                default -> counts.rejected++;
            }
        }
    }

    /** Retries rows that were waiting for an FX rate, after new rates arrive. */
    public void reprocessHeld(LegalEntity filer) {
        record Held(UUID id, UUID batchId, String kind, String raw) {}
        List<Held> held = tx.execute(s -> jdbc.sql("""
                SELECT r.id, r.batch_id, b.kind, r.raw::text AS raw FROM ingest_row r
                JOIN ingest_batch b ON b.id = r.batch_id
                WHERE r.status = 'HELD' AND r.resolved_at IS NULL
                ORDER BY r.created_at
                """).query(Held.class).list());
        for (Held h : held) {
            tx.executeWithoutResult(s -> {
                Map<String, String> row = readRaw(h.raw());
                FileContext ctx = new FileContext(entityOfBatch(h.batchId()), Kind.valueOf(h.kind()), h.batchId());
                ctx.currentRowId = h.id();
                ctx.openProblems = true;
                Outcome o = process(ctx, row);
                if ("LOADED".equals(o.status()) || "UNCHANGED".equals(o.status())) {
                    jdbc.sql("""
                            UPDATE ingest_row SET status = :status, errors = '{}', document_id = :doc, period_id = :period,
                                   resolved_at = now() WHERE id = :id
                            """)
                            .param("status", o.status()).param("doc", o.documentId()).param("period", o.periodId())
                            .param("id", h.id()).update();
                    jdbc.sql("""
                            UPDATE ingest_batch SET rows_held = rows_held - 1, rows_loaded = rows_loaded + 1 WHERE id = ?
                            """).param(h.batchId()).update();
                }
            });
        }
    }

    private LegalEntity entityOfBatch(UUID batchId) {
        return jdbc.sql(LegalEntity.SELECT + " WHERE id = (SELECT entity_id FROM ingest_batch WHERE id = ?)")
                .param(batchId).query(LegalEntity.class).single();
    }

    private Outcome process(FileContext ctx, Map<String, String> row) {
        try {
            return switch (ctx.kind) {
                case AR -> transaction(ctx, row, "OUT");
                case AP -> transaction(ctx, row, "IN");
                case VAT_SUBLEDGER -> transaction(ctx, row, upper(row.get("direction")));
                case CUSTOMS -> customs(ctx, row);
                case GL -> glBalance(ctx, row);
                case FX -> fxRate(row);
                case OPENING_AP -> openingAp(ctx, row);
            };
        } catch (RowError e) {
            return new Outcome("REJECTED", e.errors, e.rowKey, null, null);
        }
    }

    private Outcome transaction(FileContext ctx, Map<String, String> r, String direction) {
        Errors e = new Errors();
        if (!Set.of("OUT", "IN").contains(String.valueOf(direction))) {
            e.add("DIRECTION_INVALID");
            direction = "OUT";
        }
        String docType = upper(r.get("doc_type"));
        if (docType == null || !("OUT".equals(direction) ? OUT_TYPES : IN_TYPES).contains(docType)) {
            e.add("DOC_TYPE_INVALID");
        }
        String docNumber = text(r.get("doc_number"));
        if (docNumber == null) {
            e.add("DOC_NUMBER_REQUIRED");
        }
        LocalDate docDate = date(r, "doc_date", e, true);
        LocalDate supplyDate = date(r, "supply_date", e, false);
        LocalDate paymentDate = date(r, "payment_received_date", e, false);
        LocalDate explicitTaxPoint = date(r, "tax_point_date", e, false);
        LocalDate postingDate = date(r, "posting_date", e, false);
        LocalDate agreedDate = date(r, "agreed_payment_date", e, false);
        LocalDate paidDate = date(r, "paid_date", e, false);
        BigDecimal net = money(r, "net_amount", e, true);
        BigDecimal vat = money(r, "vat_amount", e, true);
        BigDecimal paidAmount = money(r, "paid_amount", e, false);
        BigDecimal recovery = percent(r, "recovery_pct", e);
        int lineNo = lineNo(r, e);
        String currency = currency(r, e);
        String trn = text(r.get("counterparty_trn"));
        if (trn != null && !trnValidator.isValid(trn)) {
            e.add("COUNTERPARTY_TRN_INVALID");
        }
        String country = upper(r.get("counterparty_country"));
        if (country != null && !country.matches("[A-Z]{2}")) {
            e.add("COUNTRY_INVALID");
        }
        String goods = upper(r.get("goods_or_services"));
        if (goods != null && !Set.of("GOODS", "SERVICES").contains(goods)) {
            e.add("GOODS_OR_SERVICES_INVALID");
        }
        String customerType = upper(r.get("customer_type"));
        if (customerType != null && !Set.of("B2B", "B2G", "B2C").contains(customerType)) {
            e.add("CUSTOMER_TYPE_INVALID");
        }
        UUID uuid = uuid(r, "einvoice_uuid", e);
        Establishment est = establishment(ctx, r, e);
        // Credit notes arrive as positive amounts and reduce the return, so the sign is applied here once.
        if (("CREDIT_NOTE".equals(docType) || "TOURIST_REFUND".equals(docType)) && net != null && net.signum() < 0) {
            e.add("AMOUNT_MUST_BE_POSITIVE");
        }

        String naturalKey = naturalKey(trn, docType, docNumber, docDate);
        String rowKey = direction + "|" + naturalKey + "|" + lineNo;
        e.throwIfAny(rowKey);

        if (isDuplicateInFile(ctx, rowKey, r)) {
            return duplicateOutcome(ctx, rowKey, r);
        }

        if (flag(r.get("void"))) {
            return voidLine(ctx, direction, "ERP", naturalKey, lineNo, rowKey);
        }

        // A credit note reduces tax in the period it is issued, whatever the original's date. Input
        // tax is claimable once the invoice is held, so a purchase follows its invoice date; only
        // sales use the earliest of supply, invoice and payment. An explicit tax point overrides both,
        // for continuous supplies, goods on approval, and reverse charge timing (tax team question 12).
        boolean creditOrDebit = "CREDIT_NOTE".equals(docType) || "DEBIT_NOTE".equals(docType);
        LocalDate taxPoint = explicitTaxPoint != null ? explicitTaxPoint
                : creditOrDebit || "IN".equals(direction) ? docDate
                : earliest(docDate, supplyDate, paymentDate);

        Optional<BigDecimal> rate = ctx.fxRates.computeIfAbsent(currency + "|" + taxPoint, k -> fx(currency, taxPoint));
        Optional<TaxPeriod> period = ledger.periodFor(ctx.filer.id(), taxPoint, ctx.cache);
        if (period.isEmpty()) {
            return new Outcome("REJECTED", List.of("BEFORE_FIRST_PERIOD"), rowKey, null, null);
        }
        if (rate.isEmpty()) {
            // Not remembered, so a rate loaded later in the same run is seen when the row is retried.
            ctx.fxRates.remove(currency + "|" + taxPoint);
            return new Outcome("HELD", List.of("FX_RATE_MISSING:" + currency + ":" + taxPoint), rowKey, period.get().id(), null);
        }
        int sign = "CREDIT_NOTE".equals(docType) ? -1 : 1;
        BigDecimal signedNet = net.multiply(BigDecimal.valueOf(sign));
        BigDecimal signedVat = vat.multiply(BigDecimal.valueOf(sign));
        BigDecimal netAed = toAed(signedNet, rate.get());
        BigDecimal vatAed = toAed(signedVat, rate.get());
        String resolvedCustomerType = customerType != null ? customerType
                : "B2C_SALE".equals(docType) ? "B2C"
                : "OUT".equals(direction) ? (trn == null ? "B2C" : "B2B") : null;
        UUID member = est != null ? est.entityId() : ctx.filer.id();

        // Lines of one document repeat its header, so it is written again only if the header differs.
        BigDecimal paidAed = paidAmount == null ? null : toAed(paidAmount, rate.get());
        String header = String.join("|", String.valueOf(postingDate), String.valueOf(flag(r.get("has_tax_invoice"))), String.valueOf(agreedDate),
                String.valueOf(paidDate), String.valueOf(paidAed), String.valueOf(uuid), String.valueOf(r.get("original_doc_number")),
                String.valueOf(r.get("counterparty_name")));
        String docKey = direction + "|" + naturalKey;
        ChunkDoc known = ctx.chunkDocs.get(docKey);
        UUID documentId;
        if (known != null && known.header().equals(header)) {
            documentId = known.id();
        } else {
            UpsertedDoc doc = jdbc.sql("""
                    INSERT INTO source_document (id, tenant_id, filer_entity_id, member_entity_id, source_system, direction, doc_type,
                        doc_number, doc_date, natural_key, counterparty_trn, counterparty_name, counterparty_country, customer_type,
                        original_doc_number, einvoice_uuid, posting_date, has_tax_invoice, agreed_payment_date, paid_date,
                        paid_amount_aed, first_batch_id, last_batch_id)
                    VALUES (:id, :tenant, :filer, :member, 'ERP', :direction, :docType, :number, :date, :key, :trn, :name, :country,
                        :customerType, :original, :uuid, :posting, :hasInvoice, :agreed, :paidDate, :paidAmount, :batch, :batch)
                    ON CONFLICT (tenant_id, filer_entity_id, source_system, direction, natural_key) DO UPDATE SET
                        posting_date = EXCLUDED.posting_date, has_tax_invoice = EXCLUDED.has_tax_invoice,
                        agreed_payment_date = EXCLUDED.agreed_payment_date, paid_date = EXCLUDED.paid_date,
                        paid_amount_aed = EXCLUDED.paid_amount_aed, einvoice_uuid = COALESCE(EXCLUDED.einvoice_uuid, source_document.einvoice_uuid),
                        original_doc_number = EXCLUDED.original_doc_number, counterparty_name = EXCLUDED.counterparty_name,
                        last_batch_id = EXCLUDED.last_batch_id, voided_at = NULL
                    RETURNING id, (xmax = 0) AS inserted
                    """)
                    .param("id", UUID.randomUUID()).param("tenant", RequestContext.requireTenant()).param("filer", ctx.filer.id())
                    .param("member", member).param("direction", direction).param("docType", docType).param("number", docNumber)
                    .param("date", docDate).param("key", naturalKey).param("trn", trn).param("name", text(r.get("counterparty_name")))
                    .param("country", country).param("customerType", resolvedCustomerType)
                    .param("original", text(r.get("original_doc_number"))).param("uuid", uuid).param("posting", postingDate)
                    .param("hasInvoice", flag(r.get("has_tax_invoice"))).param("agreed", agreedDate).param("paidDate", paidDate)
                    .param("paidAmount", paidAed).param("batch", ctx.batchId)
                    .query(UpsertedDoc.class).single();
            documentId = doc.id();
            if (doc.inserted()) {
                ctx.cache.documentCreated(documentId);
            }
            ctx.chunkDocs.put(docKey, new ChunkDoc(documentId, header));
        }

        LineInput line = new LineInput(documentId, ctx.currentRowId, ctx.filer.id(), member, est == null ? null : est.id(),
                est == null ? null : est.emirate(), est != null && est.designatedZone(), "ERP", direction, docType, lineNo,
                goods, text(r.get("gl_account")), text(r.get("tax_code")), text(r.get("description")), trn, country, taxPoint,
                currency, rate.get(), signedNet, signedVat, netAed, vatAed, recovery,
                "TOURIST_REFUND".equals(docType) ? "TOURIST_REFUND" : null);
        Ledger.Written written = ledger.writeSourceLine(line, ctx.cache);
        resolveEarlierProblems(ctx, rowKey);
        return new Outcome(written.result() == Ledger.WriteResult.UNCHANGED ? "UNCHANGED" : "LOADED", List.of(), rowKey, written.periodId(), documentId);
    }

    private Outcome customs(FileContext ctx, Map<String, String> r) {
        Errors e = new Errors();
        String docType = upper(r.get("doc_type"));
        if (docType == null || !CUSTOMS_TYPES.contains(docType)) {
            e.add("DOC_TYPE_INVALID");
        }
        String number = text(r.get("declaration_number"));
        if (number == null) {
            e.add("DECLARATION_NUMBER_REQUIRED");
        }
        LocalDate date = date(r, "declaration_date", e, true);
        BigDecimal value = money(r, "customs_value_aed", e, true);
        BigDecimal vat = money(r, "vat_aed", e, true);
        BigDecimal recovery = percent(r, "recovery_pct", e);
        int lineNo = lineNo(r, e);
        Establishment est = establishment(ctx, r, e);
        String country = upper(r.get("supplier_country"));
        String naturalKey = naturalKey(null, docType, number, date);
        String rowKey = "IN|CUSTOMS|" + naturalKey + "|" + lineNo;
        e.throwIfAny(rowKey);

        if (isDuplicateInFile(ctx, rowKey, r)) {
            return duplicateOutcome(ctx, rowKey, r);
        }
        if (flag(r.get("void"))) {
            return voidLine(ctx, "IN", "CUSTOMS", naturalKey, lineNo, rowKey);
        }
        Optional<TaxPeriod> period = ledger.periodFor(ctx.filer.id(), date, ctx.cache);
        if (period.isEmpty()) {
            return new Outcome("REJECTED", List.of("BEFORE_FIRST_PERIOD"), rowKey, null, null);
        }
        UUID member = est != null ? est.entityId() : ctx.filer.id();
        UUID documentId = jdbc.sql("""
                INSERT INTO source_document (id, tenant_id, filer_entity_id, member_entity_id, source_system, direction, doc_type,
                    doc_number, doc_date, natural_key, counterparty_name, counterparty_country, has_tax_invoice, first_batch_id, last_batch_id)
                VALUES (:id, :tenant, :filer, :member, 'CUSTOMS', 'IN', :docType, :number, :date, :key, :supplier, :country, true, :batch, :batch)
                ON CONFLICT (tenant_id, filer_entity_id, source_system, direction, natural_key) DO UPDATE SET
                    last_batch_id = EXCLUDED.last_batch_id, voided_at = NULL
                RETURNING id
                """)
                .param("id", UUID.randomUUID()).param("tenant", RequestContext.requireTenant()).param("filer", ctx.filer.id())
                .param("member", member).param("docType", docType).param("number", number).param("date", date).param("key", naturalKey)
                .param("supplier", text(r.get("supplier_name"))).param("country", country).param("batch", ctx.batchId)
                .query(UUID.class).single();

        String treatment = "IMPORT_ADJUSTMENT".equals(docType) ? "IMPORT_ADJUSTMENT" : "IMPORT";
        LineInput line = new LineInput(documentId, ctx.currentRowId, ctx.filer.id(), member, est == null ? null : est.id(),
                est == null ? null : est.emirate(), est != null && est.designatedZone(), "CUSTOMS", "IN", docType, lineNo,
                "GOODS", null, null, text(r.get("description")), null, country, date, "AED", BigDecimal.ONE, value, vat,
                value.setScale(2, RoundingMode.HALF_UP), vat.setScale(2, RoundingMode.HALF_UP), recovery, treatment);
        Ledger.Written written = ledger.writeSourceLine(line, ctx.cache);
        // Set E for check R07: what the customs declarations say, kept apart from the ledger lines.
        jdbc.sql("""
                UPDATE source_document SET declared_vat_aed = (
                    SELECT COALESCE(SUM(vat_doc), 0) FROM tax_line WHERE document_id = :doc AND superseded_at IS NULL AND source_system = 'CUSTOMS')
                WHERE id = :doc
                """).param("doc", documentId).update();
        resolveEarlierProblems(ctx, rowKey);
        return new Outcome(written.result() == Ledger.WriteResult.UNCHANGED ? "UNCHANGED" : "LOADED", List.of(), rowKey, written.periodId(), documentId);
    }

    private Outcome glBalance(FileContext ctx, Map<String, String> r) {
        Errors e = new Errors();
        String code = text(r.get("account_code"));
        if (code == null) {
            e.add("ACCOUNT_CODE_REQUIRED");
        }
        String type = upper(r.get("account_type"));
        if (type == null || !Set.of("OUTPUT", "INPUT", "REVERSE_CHARGE", "IMPORT").contains(type)) {
            e.add("ACCOUNT_TYPE_INVALID");
        }
        LocalDate end = date(r, "period_end", e, true);
        BigDecimal balance = money(r, "balance_aed", e, true);
        e.throwIfAny(null);
        Optional<TaxPeriod> period = calendar.periodFor(ctx.filer.id(), end);
        if (period.isEmpty() || !period.get().periodEnd().equals(end)) {
            return new Outcome("REJECTED", List.of("PERIOD_END_NOT_A_TAX_PERIOD"), null, null, null);
        }
        jdbc.sql("""
                INSERT INTO gl_balance (id, tenant_id, entity_id, period_id, account_code, account_type, balance_aed, batch_id)
                VALUES (:id, :tenant, :entity, :period, :code, :type, :balance, :batch)
                ON CONFLICT (tenant_id, period_id, account_code) DO UPDATE SET
                    account_type = EXCLUDED.account_type, balance_aed = EXCLUDED.balance_aed, batch_id = EXCLUDED.batch_id
                """)
                .param("id", UUID.randomUUID()).param("tenant", RequestContext.requireTenant()).param("entity", ctx.filer.id())
                .param("period", period.get().id()).param("code", code).param("type", type)
                .param("balance", balance.setScale(2, RoundingMode.HALF_UP)).param("batch", ctx.batchId)
                .update();
        ledger.requestRecompute(period.get().id());
        return new Outcome("LOADED", List.of(), null, period.get().id(), null);
    }

    private Outcome fxRate(Map<String, String> r) {
        Errors e = new Errors();
        String currency = upper(r.get("currency"));
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            e.add("CURRENCY_INVALID");
        }
        LocalDate date = date(r, "rate_date", e, true);
        BigDecimal rate = null;
        try {
            rate = new BigDecimal(String.valueOf(r.get("aed_per_unit")).trim());
            if (rate.signum() <= 0) {
                e.add("RATE_INVALID");
            }
        } catch (NumberFormatException ex) {
            e.add("RATE_INVALID");
        }
        e.throwIfAny(null);
        // An existing rate is never replaced by a load: changing a rate that lines already used
        // would silently put those lines out of step with their stored values.
        int inserted = jdbc.sql("""
                INSERT INTO fx_rate (tenant_id, currency, rate_date, aed_per_unit, source, loaded_by)
                VALUES (:tenant, :currency, :date, :rate, :source, :by)
                ON CONFLICT (tenant_id, currency, rate_date) DO NOTHING
                """)
                .param("tenant", RequestContext.requireTenant()).param("currency", currency).param("date", date)
                .param("rate", rate).param("source", text(r.get("source")) == null ? "UPLOAD" : text(r.get("source")))
                .param("by", RequestContext.requireActor().actorId())
                .update();
        return new Outcome(inserted == 1 ? "LOADED" : "UNCHANGED", List.of(), null, null, null);
    }

    private Outcome openingAp(FileContext ctx, Map<String, String> r) {
        Errors e = new Errors();
        String number = text(r.get("doc_number"));
        if (number == null) {
            e.add("DOC_NUMBER_REQUIRED");
        }
        LocalDate docDate = date(r, "doc_date", e, true);
        LocalDate agreed = date(r, "agreed_payment_date", e, true);
        LocalDate paidDate = date(r, "paid_date", e, false);
        BigDecimal gross = money(r, "gross_aed", e, true);
        BigDecimal recoverable = money(r, "recoverable_vat_aed", e, true);
        BigDecimal paid = money(r, "paid_amount_aed", e, false);
        String trn = text(r.get("counterparty_trn"));
        if (trn != null && !trnValidator.isValid(trn)) {
            e.add("COUNTERPARTY_TRN_INVALID");
        }
        String key = naturalKey(trn, "OPENING_AP", number, docDate);
        e.throwIfAny("IN|OPENING|" + key);
        UUID documentId = jdbc.sql("""
                INSERT INTO source_document (id, tenant_id, filer_entity_id, member_entity_id, source_system, direction, doc_type,
                    doc_number, doc_date, natural_key, counterparty_trn, counterparty_name, agreed_payment_date, paid_date,
                    paid_amount_aed, gross_aed, opening_recoverable_vat_aed, first_batch_id, last_batch_id)
                VALUES (:id, :tenant, :filer, :filer, 'OPENING', 'IN', 'OPENING_AP', :number, :date, :key, :trn, :name, :agreed, :paidDate,
                    :paid, :gross, :recoverable, :batch, :batch)
                ON CONFLICT (tenant_id, filer_entity_id, source_system, direction, natural_key) DO UPDATE SET
                    agreed_payment_date = EXCLUDED.agreed_payment_date, paid_date = EXCLUDED.paid_date,
                    paid_amount_aed = EXCLUDED.paid_amount_aed, gross_aed = EXCLUDED.gross_aed,
                    opening_recoverable_vat_aed = EXCLUDED.opening_recoverable_vat_aed, last_batch_id = EXCLUDED.last_batch_id
                RETURNING id
                """)
                .param("id", UUID.randomUUID()).param("tenant", RequestContext.requireTenant()).param("filer", ctx.filer.id())
                .param("number", number).param("date", docDate).param("key", key).param("trn", trn)
                .param("name", text(r.get("counterparty_name"))).param("agreed", agreed).param("paidDate", paidDate)
                .param("paid", paid).param("gross", gross).param("recoverable", recoverable).param("batch", ctx.batchId)
                .query(UUID.class).single();
        return new Outcome("LOADED", List.of(), "IN|OPENING|" + key, null, documentId);
    }

    private Outcome voidLine(FileContext ctx, String direction, String source, String naturalKey, int lineNo, String rowKey) {
        Optional<UUID> doc = jdbc.sql("""
                SELECT id FROM source_document
                WHERE filer_entity_id = ? AND source_system = ? AND direction = ? AND natural_key = ?
                """).params(ctx.filer.id(), source, direction, naturalKey).query(UUID.class).optional();
        if (doc.isEmpty()) {
            return new Outcome("REJECTED", List.of("VOID_TARGET_NOT_FOUND"), rowKey, null, null);
        }
        int removed = ledger.voidDocumentLine(doc.get(), lineNo);
        jdbc.sql("""
                UPDATE source_document SET voided_at = now(), last_batch_id = :batch
                WHERE id = :doc AND NOT EXISTS (SELECT 1 FROM tax_line WHERE document_id = :doc AND superseded_at IS NULL)
                """).param("batch", ctx.batchId).param("doc", doc.get()).update();
        return new Outcome(removed > 0 ? "LOADED" : "UNCHANGED", List.of(), rowKey, null, doc.get());
    }

    /** Once a row loads cleanly, earlier rejections or holds of the same row are no longer open problems. */
    private void resolveEarlierProblems(FileContext ctx, String rowKey) {
        if (!ctx.openProblems) {
            return;
        }
        jdbc.sql("""
                UPDATE ingest_row SET resolved_at = now()
                WHERE entity_id = ? AND row_key = ? AND status IN ('REJECTED', 'HELD') AND resolved_at IS NULL
                """).params(ctx.filer.id(), rowKey).update();
    }

    private boolean isDuplicateInFile(FileContext ctx, String rowKey, Map<String, String> row) {
        Integer previous = ctx.seenRows.putIfAbsent(rowKey, row.hashCode());
        return previous != null;
    }

    /** The same row twice is harmless. Two different rows claiming one key must not pick a winner silently. */
    private Outcome duplicateOutcome(FileContext ctx, String rowKey, Map<String, String> row) {
        if (ctx.seenRows.get(rowKey) == row.hashCode()) {
            return new Outcome("UNCHANGED", List.of(), rowKey, null, null);
        }
        return new Outcome("REJECTED", List.of("DUPLICATE_KEY_CONFLICT"), rowKey + "|conflict", null, null);
    }

    private void record(FileContext ctx, Map<String, String> row, int rowNo, Outcome o) {
        jdbc.sql("""
                INSERT INTO ingest_row (id, tenant_id, batch_id, entity_id, row_no, raw, status, errors, row_key, period_id, document_id)
                VALUES (:id, :tenant, :batch, :entity, :rowNo, CAST(:raw AS jsonb), :status, string_to_array(:errors, '|'),
                        :key, :period, :document)
                """)
                .param("id", ctx.currentRowId).param("tenant", RequestContext.requireTenant()).param("batch", ctx.batchId)
                .param("entity", ctx.filer.id()).param("rowNo", rowNo).param("raw", JSON.writeValueAsString(row))
                .param("status", o.status()).param("errors", String.join("|", o.errors())).param("key", o.rowKey())
                .param("period", o.periodId()).param("document", o.documentId())
                .update();
    }

    private Establishment establishment(FileContext ctx, Map<String, String> r, Errors e) {
        String code = text(r.get("establishment_code"));
        if (code == null) {
            return null;
        }
        if (ctx.establishments == null) {
            ctx.establishments = new HashMap<>();
            jdbc.sql("""
                    SELECT e.id, e.entity_id, e.code, e.emirate, e.designated_zone FROM establishment e
                    JOIN legal_entity le ON le.id = e.entity_id
                    WHERE le.id = :filer OR le.group_rep_id = :filer
                    """)
                    .param("filer", ctx.filer.id()).query(Establishment.class).list()
                    .forEach(x -> ctx.establishments.put(x.code().toUpperCase(Locale.ROOT), x));
        }
        Establishment found = ctx.establishments.get(code.toUpperCase(Locale.ROOT));
        if (found == null) {
            e.add("ESTABLISHMENT_UNKNOWN");
        }
        return found;
    }

    private Optional<BigDecimal> fx(String currency, LocalDate date) {
        if ("AED".equals(currency)) {
            return Optional.of(BigDecimal.ONE);
        }
        return jdbc.sql("SELECT aed_per_unit FROM fx_rate WHERE currency = ? AND rate_date = ?")
                .params(currency, date).query(BigDecimal.class).optional();
    }

    private static BigDecimal toAed(BigDecimal amount, BigDecimal rate) {
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    private static String naturalKey(String trn, String docType, String number, LocalDate date) {
        return (trn == null ? "" : trn) + "|" + docType + "|" + number + "|" + date;
    }

    private static LocalDate earliest(LocalDate... dates) {
        LocalDate min = null;
        for (LocalDate d : dates) {
            if (d != null && (min == null || d.isBefore(min))) {
                min = d;
            }
        }
        return min;
    }

    private static String text(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String upper(String s) {
        String t = text(s);
        return t == null ? null : t.toUpperCase(Locale.ROOT);
    }

    private static boolean flag(String s) {
        String t = text(s);
        return t != null && Set.of("TRUE", "YES", "Y", "1").contains(t.toUpperCase(Locale.ROOT));
    }

    private static LocalDate date(Map<String, String> r, String field, Errors e, boolean required) {
        String v = text(r.get(field));
        if (v == null) {
            if (required) {
                e.add(field.toUpperCase(Locale.ROOT) + "_REQUIRED");
            }
            return null;
        }
        try {
            return LocalDate.parse(v);
        } catch (DateTimeParseException ex) {
            e.add(field.toUpperCase(Locale.ROOT) + "_INVALID");
            return null;
        }
    }

    private static BigDecimal money(Map<String, String> r, String field, Errors e, boolean required) {
        String v = text(r.get(field));
        if (v == null) {
            if (required) {
                e.add(field.toUpperCase(Locale.ROOT) + "_REQUIRED");
            }
            return null;
        }
        try {
            BigDecimal d = new BigDecimal(v);
            if (d.scale() > 2) {
                // More precision than fils would be silently rounded away, so ask for clean data.
                e.add(field.toUpperCase(Locale.ROOT) + "_TOO_PRECISE");
                return null;
            }
            return d;
        } catch (NumberFormatException ex) {
            e.add(field.toUpperCase(Locale.ROOT) + "_INVALID");
            return null;
        }
    }

    private static BigDecimal percent(Map<String, String> r, String field, Errors e) {
        String v = text(r.get(field));
        if (v == null) {
            return null;
        }
        try {
            BigDecimal d = new BigDecimal(v);
            if (d.signum() < 0 || d.compareTo(BigDecimal.valueOf(100)) > 0) {
                e.add("RECOVERY_PCT_INVALID");
                return null;
            }
            return d;
        } catch (NumberFormatException ex) {
            e.add("RECOVERY_PCT_INVALID");
            return null;
        }
    }

    private static int lineNo(Map<String, String> r, Errors e) {
        String v = text(r.get("line_no"));
        if (v == null) {
            return 1;
        }
        try {
            int n = Integer.parseInt(v);
            if (n < 1) {
                e.add("LINE_NO_INVALID");
            }
            return n;
        } catch (NumberFormatException ex) {
            e.add("LINE_NO_INVALID");
            return 1;
        }
    }

    private static String currency(Map<String, String> r, Errors e) {
        String c = upper(r.get("currency"));
        if (c == null) {
            return "AED";
        }
        if (!c.matches("[A-Z]{3}")) {
            e.add("CURRENCY_INVALID");
        }
        return c;
    }

    private static UUID uuid(Map<String, String> r, String field, Errors e) {
        String v = text(r.get(field));
        if (v == null) {
            return null;
        }
        try {
            return UUID.fromString(v);
        } catch (IllegalArgumentException ex) {
            e.add(field.toUpperCase(Locale.ROOT) + "_INVALID");
            return null;
        }
    }

    private Map<String, String> readRaw(String raw) {
        Map<String, Object> parsed = JSON.readValue(raw, Map.class);
        Map<String, String> row = new LinkedHashMap<>();
        parsed.forEach((k, v) -> row.put(k, v == null ? null : String.valueOf(v)));
        return row;
    }

    private static final class Errors {
        private final List<String> errors = new ArrayList<>();

        void add(String code) {
            errors.add(code);
        }

        void throwIfAny(String rowKey) {
            if (!errors.isEmpty()) {
                throw new RowError(errors, rowKey);
            }
        }
    }

    private static final class RowError extends RuntimeException {
        final transient List<String> errors;
        final String rowKey;

        RowError(List<String> errors, String rowKey) {
            super(String.join(",", errors), null, false, false);
            this.errors = errors;
            this.rowKey = rowKey;
        }
    }
}
