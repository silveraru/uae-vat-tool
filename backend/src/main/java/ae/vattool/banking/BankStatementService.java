package ae.vattool.banking;

import ae.vattool.evidence.EvidenceService;
import ae.vattool.platform.audit.AuditLog;
import ae.vattool.platform.storage.VaultStorage;
import ae.vattool.platform.tenancy.RequestContext;
import ae.vattool.platform.web.DomainException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores bank statements as evidence and as transactions (ING-06), and uses them to settle supplier
 * invoices for the six-month rule (ADJ-01, reconciliation set F). A match needs the invoice number in
 * the narrative and the exact amount, and it must be the only candidate. Anything else stays with a person.
 */
@Service
public class BankStatementService {

    public record Txn(UUID id, UUID statementId, String account, LocalDate bookedOn, LocalDate valueOn, BigDecimal amount, String currency, String direction,
                      String reference, String counterparty, String narrative) {
        public static final String SELECT = """
                SELECT id, statement_id, account, booked_on, value_on, amount, currency, direction, reference, counterparty, narrative FROM bank_txn
                """;

        /** What a person or a match reads: the reference and the free text together. */
        public String searchable() {
            return ((reference == null ? "" : reference) + " " + (narrative == null ? "" : narrative)).toUpperCase();
        }
    }

    public record Loaded(UUID id, UUID entityId, String format, String fileName, String account, String statementRef, BigDecimal openingBalance, BigDecimal closingBalance,
                         boolean balanceChecked, String balanceProblem, int txnCount, int txnNew, UUID evidenceId, String loadedBy, OffsetDateTime loadedAt) {
        static final String SELECT = """
                SELECT id, entity_id, format, file_name, account, statement_ref, opening_balance, closing_balance, balance_checked, balance_problem, txn_count, txn_new, evidence_id, loaded_by, loaded_at
                FROM bank_statement
                """;
    }

    public record ImportResult(List<Loaded> statements, boolean alreadyProcessed, int supplierInvoicesMatched) {}

    private final JdbcClient jdbc;
    private final EvidenceService evidence;
    private final AuditLog audit;

    public BankStatementService(JdbcClient jdbc, EvidenceService evidence, AuditLog audit) {
        this.jdbc = jdbc;
        this.evidence = evidence;
        this.audit = audit;
    }

    /** Reloading the same file changes nothing, and a transaction seen in an overlapping statement counts once. */
    @Transactional
    public ImportResult load(UUID entityId, String fileName, byte[] content) {
        requireEntity(entityId);
        if (content == null || content.length == 0) {
            throw DomainException.invalid("FILE_EMPTY", "The file is empty");
        }
        String hash = VaultStorage.sha256(content);
        List<Loaded> existing = jdbc.sql(Loaded.SELECT + " WHERE entity_id = ? AND content_hash = ?").params(entityId, hash).query(Loaded.class).list();
        if (!existing.isEmpty()) {
            return new ImportResult(existing, true, 0);
        }
        List<StatementParser.Statement> parsed = StatementParser.parse(content);
        EvidenceService.Evidence stored = evidence.upload(entityId, null, fileName, "text/plain", content, "Bank statement", null);
        List<Loaded> out = new ArrayList<>();
        // One hash can only be stored once, so a file with several statements is told apart by its position.
        int position = 0;
        for (StatementParser.Statement s : parsed) {
            out.add(store(entityId, fileName, position == 0 ? hash : hash + "#" + position, stored.id(), s));
            position++;
        }
        int matched = matchSupplierInvoices(entityId);
        audit.record("BANK_STATEMENT_LOADED", "BANK_STATEMENT", out.getFirst().id(), entityId, null,
                Map.of("file", String.valueOf(fileName), "sha256", hash, "statements", out.size(), "supplierInvoicesMatched", matched), null);
        return new ImportResult(out, false, matched);
    }

    /** Row-level security hides another tenant's entity, so this reports it as not found. */
    private void requireEntity(UUID entityId) {
        if (!jdbc.sql("SELECT EXISTS (SELECT 1 FROM legal_entity WHERE id = ?)").param(entityId).query(Boolean.class).single()) {
            throw DomainException.notFound("Entity");
        }
    }

    private Loaded store(UUID entityId, String fileName, String hash, UUID evidenceId, StatementParser.Statement s) {
        UUID id = UUID.randomUUID();
        BigDecimal net = s.txns().stream().map(t -> "DEBIT".equals(t.direction()) ? t.amount().negate() : t.amount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean checked = s.opening() != null && s.closing() != null;
        String problem = checked && s.opening().add(net).compareTo(s.closing()) != 0
                ? "Opening " + s.opening() + " plus movements " + net + " does not equal closing " + s.closing() : null;
        jdbc.sql("""
                INSERT INTO bank_statement (id, tenant_id, entity_id, format, file_name, content_hash, evidence_id, account, statement_ref, opening_balance, closing_balance,
                    balance_checked, balance_problem, txn_count, txn_new, loaded_by)
                VALUES (:id, :tenant, :entity, :format, :file, :hash, :evidence, :account, :ref, :opening, :closing, :checked, :problem, :count, 0, :by)
                """)
                .param("id", id).param("tenant", RequestContext.requireTenant()).param("entity", entityId).param("format", s.format()).param("file", fileName == null ? "statement" : fileName)
                .param("hash", hash).param("evidence", evidenceId).param("account", s.account()).param("ref", s.statementRef()).param("opening", s.opening()).param("closing", s.closing())
                .param("checked", checked).param("problem", problem).param("count", s.txns().size()).param("by", RequestContext.requireActor().actorId())
                .update();
        int fresh = 0;
        Map<String, Integer> seen = new HashMap<>();
        for (StatementParser.Txn t : s.txns()) {
            String base = String.join("|", s.account(), String.valueOf(t.bookedOn()), t.amount().stripTrailingZeros().toPlainString(), t.direction(), String.valueOf(t.reference()), String.valueOf(t.narrative()));
            // Two genuinely identical lines in one statement are two transactions, so they are numbered.
            int ordinal = seen.merge(base, 1, Integer::sum);
            fresh += jdbc.sql("""
                    INSERT INTO bank_txn (id, tenant_id, entity_id, statement_id, account, booked_on, value_on, amount, currency, direction, reference, counterparty, narrative, dedupe_key)
                    VALUES (:id, :tenant, :entity, :statement, :account, :booked, :value, :amount, :currency, :direction, :reference, :counterparty, :narrative, :key)
                    ON CONFLICT (tenant_id, entity_id, dedupe_key) DO NOTHING
                    """)
                    .param("id", UUID.randomUUID()).param("tenant", RequestContext.requireTenant()).param("entity", entityId).param("statement", id).param("account", s.account())
                    .param("booked", t.bookedOn()).param("value", t.valueOn()).param("amount", t.amount()).param("currency", t.currency()).param("direction", t.direction())
                    .param("reference", t.reference()).param("counterparty", t.counterparty()).param("narrative", t.narrative()).param("key", VaultStorage.sha256(base + "|" + ordinal))
                    .update();
        }
        jdbc.sql("UPDATE bank_statement SET txn_new = ? WHERE id = ?").params(fresh, id).update();
        return jdbc.sql(Loaded.SELECT + " WHERE id = ?").param(id).query(Loaded.class).single();
    }

    /**
     * Debits in AED settle a supplier invoice when the invoice number stands alone in the text and the
     * amount is what was owed. The bank's date is kept apart from the ERP's, so reloading AP cannot erase it.
     */
    private int matchSupplierInvoices(UUID entityId) {
        record Open(UUID id, String docNumber, String counterpartyName, BigDecimal owed, LocalDate docDate) {}
        List<Open> open = jdbc.sql("""
                SELECT d.id, d.doc_number, d.counterparty_name, COALESCE(d.gross_aed, SUM(l.net_aed + ROUND(l.vat_doc * l.fx_rate, 2)), 0) AS owed, d.doc_date
                FROM source_document d
                LEFT JOIN tax_line l ON l.document_id = d.id AND l.superseded_at IS NULL AND l.line_kind = 'NORMAL'
                WHERE d.filer_entity_id = ? AND d.direction = 'IN' AND d.source_system IN ('ERP','OPENING') AND d.voided_at IS NULL AND d.doc_type <> 'CREDIT_NOTE'
                  AND d.bank_paid_date IS NULL AND d.paid_date IS NULL
                GROUP BY d.id
                """).param(entityId).query(Open.class).list();
        List<Txn> debits = jdbc.sql(Txn.SELECT + """
                 WHERE entity_id = ? AND direction = 'DEBIT' AND currency = 'AED'
                   AND id NOT IN (SELECT bank_txn_id FROM bank_doc_match)
                   AND id NOT IN (SELECT bank_txn_id FROM payment WHERE bank_txn_id IS NOT NULL)
                ORDER BY booked_on
                """).param(entityId).query(Txn.class).list();
        int matched = 0;
        for (Txn t : debits) {
            String text = t.searchable();
            List<Open> candidates = open.stream()
                    .filter(o -> o.owed().compareTo(t.amount()) == 0 && !t.bookedOn().isBefore(o.docDate())
                            && Pattern.compile("(^|[^A-Z0-9-])" + Pattern.quote(o.docNumber().toUpperCase()) + "($|[^A-Z0-9-])").matcher(text).find())
                    .toList();
            if (candidates.size() != 1) {
                continue;
            }
            Open o = candidates.getFirst();
            jdbc.sql("UPDATE source_document SET bank_paid_date = ?, bank_paid_amount_aed = ? WHERE id = ?").params(t.bookedOn(), t.amount(), o.id()).update();
            jdbc.sql("""
                    INSERT INTO bank_doc_match (id, tenant_id, entity_id, bank_txn_id, document_id, amount_aed, method, score)
                    VALUES (:id, :tenant, :entity, :txn, :document, :amount, 'NUMBER_AND_AMOUNT', 1)
                    """)
                    .param("id", UUID.randomUUID()).param("tenant", RequestContext.requireTenant()).param("entity", entityId).param("txn", t.id()).param("document", o.id()).param("amount", t.amount())
                    .update();
            open = open.stream().filter(x -> !x.id().equals(o.id())).toList();
            matched++;
        }
        return matched;
    }

    @Transactional(readOnly = true)
    public List<Loaded> list(UUID entityId) {
        return jdbc.sql(Loaded.SELECT + " WHERE entity_id = ? ORDER BY loaded_at DESC LIMIT 100").param(entityId).query(Loaded.class).list();
    }

    @Transactional(readOnly = true)
    public List<Txn> transactions(UUID statementId, int limit) {
        return jdbc.sql(Txn.SELECT + " WHERE statement_id = ? ORDER BY booked_on, id LIMIT ?").params(statementId, Math.min(limit, 5000)).query(Txn.class).list();
    }

    @Transactional(readOnly = true)
    public Optional<Txn> find(UUID id, UUID entityId) {
        return jdbc.sql(Txn.SELECT + " WHERE id = ? AND entity_id = ?").params(id, entityId).query(Txn.class).optional();
    }

    /** AED debits not yet used for anything, on or after a date, newest first. */
    @Transactional(readOnly = true)
    public List<Txn> unusedDebits(UUID entityId, LocalDate from) {
        return jdbc.sql(Txn.SELECT + """
                 WHERE entity_id = ? AND direction = 'DEBIT' AND currency = 'AED' AND booked_on >= ?
                   AND id NOT IN (SELECT bank_txn_id FROM bank_doc_match)
                   AND id NOT IN (SELECT bank_txn_id FROM payment WHERE bank_txn_id IS NOT NULL)
                ORDER BY booked_on DESC LIMIT 500
                """).params(entityId, from).query(Txn.class).list();
    }
}
