package ae.vattool.ingestion;

import ae.vattool.ingestion.Templates.Kind;
import ae.vattool.ingestion.einvoice.EinvoiceEvent;
import ae.vattool.ingestion.einvoice.EinvoiceStore;
import ae.vattool.ledger.Ledger;
import ae.vattool.ledger.LineInput;
import ae.vattool.masterdata.LegalEntity;
import ae.vattool.masterdata.PeriodCalendar;
import ae.vattool.masterdata.TaxPeriod;
import ae.vattool.masterdata.TrnValidator;
import ae.vattool.platform.tenancy.RequestContext;
import ae.vattool.rules.RulePackDefinition;
import ae.vattool.rules.RulePackStore;
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
    private final RulePackStore packs;
    private final EinvoiceStore einvoices;

    public RowProcessor(JdbcClient jdbc, TransactionTemplate tx, Ledger ledger, PeriodCalendar calendar, TrnValidator trnValidator,
                        RulePackStore packs, EinvoiceStore einvoices) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.ledger = ledger;
        this.calendar = calendar;
        this.trnValidator = trnValidator;
        this.packs = packs;
        this.einvoices = einvoices;
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
    public void reprocessHeld() {
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
                case GL_JOURNAL -> glJournal(ctx, row);
                case MARGIN -> marginSale(ctx, row);
                case OPENING_ASSETS -> openingAsset(ctx, row);
                case OPENING_APPORTIONMENT -> openingApportionment(ctx, row);
                case UBL -> ublInvoice(ctx, row);
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

    /** The ledger listing for the FTA Audit File. It has no effect on tax, so it does not touch the return. */
    private Outcome glJournal(FileContext ctx, Map<String, String> r) {
        Errors e = new Errors();
        LocalDate date = date(r, "transaction_date", e, true);
        String account = text(r.get("account_id"));
        if (account == null) {
            e.add("ACCOUNT_ID_REQUIRED");
        }
        String transaction = text(r.get("transaction_id"));
        if (transaction == null) {
            e.add("TRANSACTION_ID_REQUIRED");
        }
        BigDecimal debit = money(r, "debit", e, true);
        BigDecimal credit = money(r, "credit", e, true);
        BigDecimal balance = money(r, "balance", e, false);
        int lineNo = lineNo(r, e);
        String key = "GL|" + transaction + "|" + lineNo;
        e.throwIfAny(key);
        if (isDuplicateInFile(ctx, key, r)) {
            return duplicateOutcome(ctx, key, r);
        }
        jdbc.sql("""
                INSERT INTO gl_journal_line (id, tenant_id, entity_id, transaction_date, account_id, account_name, description, party_name, transaction_id, line_no,
                    source_document_id, source_type, debit_aed, credit_aed, balance_aed, batch_id)
                VALUES (:id, :tenant, :entity, :date, :account, :accountName, :description, :party, :transaction, :lineNo, :source, :type, :debit, :credit, :balance, :batch)
                ON CONFLICT (tenant_id, entity_id, transaction_id, line_no) DO UPDATE SET
                    transaction_date = EXCLUDED.transaction_date, account_id = EXCLUDED.account_id, account_name = EXCLUDED.account_name,
                    description = EXCLUDED.description, party_name = EXCLUDED.party_name, source_document_id = EXCLUDED.source_document_id,
                    source_type = EXCLUDED.source_type, debit_aed = EXCLUDED.debit_aed, credit_aed = EXCLUDED.credit_aed,
                    balance_aed = EXCLUDED.balance_aed, batch_id = EXCLUDED.batch_id
                """)
                .param("id", UUID.randomUUID()).param("tenant", RequestContext.requireTenant()).param("entity", ctx.filer.id()).param("date", date)
                .param("account", account).param("accountName", text(r.get("account_name"))).param("description", text(r.get("description")))
                .param("party", text(r.get("name"))).param("transaction", transaction).param("lineNo", lineNo)
                .param("source", text(r.get("source_document_id"))).param("type", text(r.get("source_type")))
                .param("debit", debit).param("credit", credit).param("balance", balance == null ? BigDecimal.ZERO : balance).param("batch", ctx.batchId)
                .update();
        return new Outcome("LOADED", List.of(), key, null, null);
    }

    /**
     * A sale under the profit margin scheme (RET-05): VAT is the tax fraction of the margin, and no
     * VAT arises on a sale at a loss. The purchase and selling prices stay on the schedule, and the
     * ledger holds the margin, which is what reaches the box.
     */
    private Outcome marginSale(FileContext ctx, Map<String, String> r) {
        Errors e = new Errors();
        String number = text(r.get("doc_number"));
        if (number == null) {
            e.add("DOC_NUMBER_REQUIRED");
        }
        LocalDate date = date(r, "doc_date", e, true);
        String category = upper(r.get("category"));
        BigDecimal purchase = money(r, "purchase_price_aed", e, true);
        BigDecimal selling = money(r, "selling_price_aed", e, true);
        BigDecimal costs = money(r, "acquisition_costs_aed", e, false);
        Establishment est = establishment(ctx, r, e);
        String trn = text(r.get("counterparty_trn"));
        if (trn != null && !trnValidator.isValid(trn)) {
            e.add("COUNTERPARTY_TRN_INVALID");
        }
        String naturalKey = naturalKey(trn, "MARGIN_SALE", number, date);
        String rowKey = "OUT|" + naturalKey + "|1";
        RulePackDefinition def = date == null ? null : packs.forDate(date).definition();
        if (def != null && (def.marginScheme() == null || !def.marginScheme().eligibleCategories().contains(category))) {
            e.add("MARGIN_CATEGORY_INVALID");
        }
        e.throwIfAny(rowKey);
        if (isDuplicateInFile(ctx, rowKey, r)) {
            return duplicateOutcome(ctx, rowKey, r);
        }
        if (flag(r.get("void"))) {
            Outcome voided = voidLine(ctx, "OUT", "ERP", naturalKey, 1, rowKey);
            if (voided.documentId() != null) {
                jdbc.sql("DELETE FROM margin_sale WHERE document_id = ?").param(voided.documentId()).update();
            }
            return voided;
        }
        Optional<TaxPeriod> period = ledger.periodFor(ctx.filer.id(), date, ctx.cache);
        if (period.isEmpty()) {
            return new Outcome("REJECTED", List.of("BEFORE_FIRST_PERIOD"), rowKey, null, null);
        }
        BigDecimal acquisition = costs == null ? BigDecimal.ZERO : costs;
        BigDecimal margin = selling.subtract(purchase).subtract(acquisition).max(BigDecimal.ZERO);
        BigDecimal rate = def.rates().standardPct();
        BigDecimal vat = margin.multiply(rate).divide(rate.add(BigDecimal.valueOf(100)), 2, RoundingMode.HALF_UP);
        BigDecimal net = margin.subtract(vat);
        UUID member = est != null ? est.entityId() : ctx.filer.id();
        UUID documentId = jdbc.sql("""
                INSERT INTO source_document (id, tenant_id, filer_entity_id, member_entity_id, source_system, direction, doc_type, doc_number, doc_date, natural_key,
                    counterparty_trn, counterparty_name, customer_type, has_tax_invoice, gross_aed, first_batch_id, last_batch_id)
                VALUES (:id, :tenant, :filer, :member, 'ERP', 'OUT', 'MARGIN_SALE', :number, :date, :key, :trn, :name, :customerType, true, :gross, :batch, :batch)
                ON CONFLICT (tenant_id, filer_entity_id, source_system, direction, natural_key) DO UPDATE SET
                    counterparty_name = EXCLUDED.counterparty_name, gross_aed = EXCLUDED.gross_aed, last_batch_id = EXCLUDED.last_batch_id, voided_at = NULL
                RETURNING id
                """)
                .param("id", UUID.randomUUID()).param("tenant", RequestContext.requireTenant()).param("filer", ctx.filer.id()).param("member", member)
                .param("number", number).param("date", date).param("key", naturalKey).param("trn", trn).param("name", text(r.get("counterparty_name")))
                .param("customerType", trn == null ? "B2C" : "B2B").param("gross", selling).param("batch", ctx.batchId)
                .query(UUID.class).single();
        LineInput line = new LineInput(documentId, ctx.currentRowId, ctx.filer.id(), member, est == null ? null : est.id(),
                est == null ? null : est.emirate(), est != null && est.designatedZone(), "ERP", "OUT", "MARGIN_SALE", 1, "GOODS", null, null,
                text(r.get("description")), trn, null, date, "AED", BigDecimal.ONE, net, vat, net, vat, null, "STANDARD");
        Ledger.Written written = ledger.writeSourceLine(line, ctx.cache);
        jdbc.sql("""
                INSERT INTO margin_sale (id, tenant_id, entity_id, period_id, document_id, doc_number, doc_date, category, description, purchase_price_aed,
                    acquisition_costs_aed, selling_price_aed, margin_aed, vat_aed)
                VALUES (:id, :tenant, :entity, :period, :document, :number, :date, :category, :description, :purchase, :costs, :selling, :margin, :vat)
                ON CONFLICT (tenant_id, document_id) DO UPDATE SET
                    period_id = EXCLUDED.period_id, category = EXCLUDED.category, description = EXCLUDED.description, purchase_price_aed = EXCLUDED.purchase_price_aed,
                    acquisition_costs_aed = EXCLUDED.acquisition_costs_aed, selling_price_aed = EXCLUDED.selling_price_aed, margin_aed = EXCLUDED.margin_aed, vat_aed = EXCLUDED.vat_aed
                """)
                .param("id", UUID.randomUUID()).param("tenant", RequestContext.requireTenant()).param("entity", ctx.filer.id()).param("period", written.periodId())
                .param("document", documentId).param("number", number).param("date", date).param("category", category).param("description", text(r.get("description")))
                .param("purchase", purchase).param("costs", acquisition).param("selling", selling).param("margin", margin).param("vat", vat)
                .update();
        resolveEarlierProblems(ctx, rowKey);
        return new Outcome(written.result() == Ledger.WriteResult.UNCHANGED ? "UNCHANGED" : "LOADED", List.of(), rowKey, written.periodId(), documentId);
    }

    /** A capital asset already under adjustment at go-live (ONB-06). It starts its intervals like any other. */
    private Outcome openingAsset(FileContext ctx, Map<String, String> r) {
        Errors e = new Errors();
        String reference = text(r.get("reference"));
        if (reference == null) {
            e.add("REFERENCE_REQUIRED");
        }
        String description = text(r.get("description"));
        if (description == null) {
            e.add("DESCRIPTION_REQUIRED");
        }
        String category = upper(r.get("category"));
        if (category == null || !Set.of("REAL_ESTATE", "OTHER").contains(category)) {
            e.add("CATEGORY_INVALID");
        }
        BigDecimal cost = money(r, "cost_aed", e, true);
        BigDecimal vat = money(r, "input_vat_aed", e, true);
        BigDecimal initial = percent(r, "initial_recovery_pct", e);
        if (initial == null) {
            e.add("INITIAL_RECOVERY_PCT_REQUIRED");
        }
        LocalDate firstUsed = date(r, "first_used_on", e, true);
        String key = "ASSET|" + reference;
        e.throwIfAny(key);
        RulePackDefinition.CapitalAssets rule = packs.forDate(firstUsed).definition().capitalAssets();
        if (rule == null) {
            return new Outcome("REJECTED", List.of("RULE_PACK_SECTION_MISSING"), key, null, null);
        }
        if (cost.compareTo(rule.thresholdAed()) < 0) {
            return new Outcome("REJECTED", List.of("ASSET_BELOW_THRESHOLD"), key, null, null);
        }
        int inserted = jdbc.sql("""
                INSERT INTO capital_asset (id, tenant_id, entity_id, reference, description, category, cost_aed, input_vat_aed, initial_recovery_pct, first_used_on,
                    adjustment_years, opening, created_by)
                VALUES (:id, :tenant, :entity, :reference, :description, :category, :cost, :vat, :initial, :firstUsed, :years, true, :by)
                ON CONFLICT (tenant_id, entity_id, reference) DO NOTHING
                """)
                .param("id", UUID.randomUUID()).param("tenant", RequestContext.requireTenant()).param("entity", ctx.filer.id()).param("reference", reference)
                .param("description", description).param("category", category).param("cost", cost).param("vat", vat).param("initial", initial)
                .param("firstUsed", firstUsed).param("years", "REAL_ESTATE".equals(category) ? rule.realEstateYears() : rule.otherYears())
                .param("by", RequestContext.requireActor().actorId())
                .update();
        return new Outcome(inserted == 1 ? "LOADED" : "UNCHANGED", List.of(), key, null, null);
    }

    /** What the tax year held before go-live, so the annual adjustment covers the whole year (ONB-06). */
    private Outcome openingApportionment(FileContext ctx, Map<String, String> r) {
        Errors e = new Errors();
        LocalDate start = date(r, "tax_year_start", e, true);
        BigDecimal directRecoverable = money(r, "direct_recoverable_aed", e, true);
        BigDecimal directExempt = money(r, "direct_exempt_aed", e, true);
        BigDecimal taxable = money(r, "taxable_supplies_aed", e, true);
        BigDecimal total = money(r, "total_supplies_aed", e, true);
        BigDecimal residual = money(r, "residual_input_aed", e, true);
        BigDecimal recovered = money(r, "recovered_aed", e, true);
        String key = "APPORTIONMENT|" + start;
        e.throwIfAny(key);
        jdbc.sql("""
                INSERT INTO apportionment_opening (id, tenant_id, entity_id, tax_year_start, direct_recoverable_aed, direct_exempt_aed, taxable_supplies_aed,
                    total_supplies_aed, residual_input_aed, recovered_aed, batch_id)
                VALUES (:id, :tenant, :entity, :start, :recoverable, :exempt, :taxable, :total, :residual, :recovered, :batch)
                ON CONFLICT (tenant_id, entity_id, tax_year_start) DO UPDATE SET
                    direct_recoverable_aed = EXCLUDED.direct_recoverable_aed, direct_exempt_aed = EXCLUDED.direct_exempt_aed, taxable_supplies_aed = EXCLUDED.taxable_supplies_aed,
                    total_supplies_aed = EXCLUDED.total_supplies_aed, residual_input_aed = EXCLUDED.residual_input_aed, recovered_aed = EXCLUDED.recovered_aed, batch_id = EXCLUDED.batch_id
                """)
                .param("id", UUID.randomUUID()).param("tenant", RequestContext.requireTenant()).param("entity", ctx.filer.id()).param("start", start)
                .param("recoverable", directRecoverable).param("exempt", directExempt).param("taxable", taxable).param("total", total)
                .param("residual", residual).param("recovered", recovered).param("batch", ctx.batchId)
                .update();
        return new Outcome("LOADED", List.of(), key, null, null);
    }

    /**
     * An e-invoice from another ASP or a client archive (ING-02). It joins reconciliation set A through
     * the same store as the platform's events, and the event id comes from its content, so loading a
     * file again changes nothing. Reusing a UUID for different figures is refused, never overwritten.
     */
    private Outcome ublInvoice(FileContext ctx, Map<String, String> r) {
        String problem = text(r.get("parse_error"));
        String rowKey = "UBL|" + r.get("source_file") + "|" + r.get("number");
        if (problem != null) {
            return new Outcome("REJECTED", List.of("UBL_UNREADABLE:" + problem), rowKey, null, null);
        }
        Errors e = new Errors();
        UUID invoice = uuid(r, "invoice_uuid", e);
        LocalDate date = date(r, "issue_date", e, true);
        BigDecimal net = money(r, "net_aed", e, true);
        BigDecimal vat = money(r, "vat_aed", e, true);
        String number = text(r.get("number"));
        if (number == null) {
            e.add("NUMBER_REQUIRED");
        }
        String supplier = text(r.get("supplier_trn"));
        String buyer = text(r.get("buyer_trn"));
        rowKey = "UBL|" + invoice;
        e.throwIfAny(rowKey);
        // A credit note's amounts are positive in UBL. Set A holds them signed, as the platform's events do.
        if ("CREDIT_NOTE".equals(text(r.get("doc_type")))) {
            net = net.negate();
            vat = vat.negate();
        }
        String lifecycle;
        if (sameTaxpayer(supplier, ctx.filer.trn())) {
            lifecycle = EinvoiceEvent.ISSUED;
        } else if (sameTaxpayer(buyer, ctx.filer.trn())) {
            lifecycle = EinvoiceEvent.RECEIVED;
        } else {
            return new Outcome("REJECTED", List.of("UBL_TRN_NOT_THIS_ENTITY"), rowKey, null, null);
        }
        record Existing(boolean hasInvoiceData, String number, BigDecimal netAed, BigDecimal vatAed) {}
        Optional<Existing> existing = jdbc.sql("SELECT has_invoice_data, number, net_aed, vat_aed FROM einvoice WHERE invoice_uuid = ?")
                .param(invoice).query(Existing.class).optional();
        if (existing.isPresent() && existing.get().hasInvoiceData()
                && (!number.equals(existing.get().number()) || net.compareTo(existing.get().netAed()) != 0 || vat.compareTo(existing.get().vatAed()) != 0)) {
            return new Outcome("REJECTED", List.of("UBL_UUID_CONFLICT"), rowKey, null, null);
        }
        java.time.OffsetDateTime at = date.atStartOfDay().atOffset(java.time.ZoneOffset.ofHours(4));
        String content = String.join("|", String.valueOf(invoice), number, net.toPlainString(), vat.toPlainString(), lifecycle);
        EinvoiceEvent event = new EinvoiceEvent("1.0", UUID.nameUUIDFromBytes(("ubl|" + content).getBytes(java.nio.charset.StandardCharsets.UTF_8)), lifecycle, at,
                RequestContext.requireTenant(), ctx.filer.trn(), invoice,
                new EinvoiceEvent.Invoice(text(r.get("doc_type")), number, date, supplier, buyer, text(r.get("currency")), net, vat, text(r.get("billing_reference"))),
                null, null);
        EinvoiceStore.Applied applied = einvoices.apply(event);
        jdbc.sql("UPDATE einvoice SET source = 'UBL_IMPORT' WHERE invoice_uuid = ?").param(invoice).update();
        resolveEarlierProblems(ctx, rowKey);
        return new Outcome(applied == EinvoiceStore.Applied.DUPLICATE ? "UNCHANGED" : "LOADED", List.of(), rowKey, null, null);
    }

    /** E-invoices may carry a full TRN or the 10-digit TIN, which is the first ten digits of it. */
    private static boolean sameTaxpayer(String onInvoice, String filerTrn) {
        if (onInvoice == null || filerTrn == null) {
            return false;
        }
        return onInvoice.equals(filerTrn) || (onInvoice.length() == 10 && filerTrn.startsWith(onInvoice));
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
