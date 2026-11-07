package ae.vattool.filing;

import ae.vattool.banking.BankStatementService;
import ae.vattool.masterdata.PeriodCalendar;
import ae.vattool.masterdata.TaxPeriod;
import ae.vattool.platform.audit.AuditLog;
import ae.vattool.platform.tenancy.RequestContext;
import ae.vattool.platform.web.DomainException;
import ae.vattool.returns.ReturnService;
import ae.vattool.returns.BoxCalculator.BoxValue;
import ae.vattool.returns.ReturnVersionView;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settles a filed return from a bank statement (FIL-03). The FTA's GIBAN in the narrative with the
 * amount due is exact enough to record on its own. Anything less is listed for a person to confirm,
 * and one bank line can never settle two payments, which the database enforces.
 */
@Service
public class BankPaymentMatcher {

    public record Candidate(BankStatementService.Txn txn, boolean ibanInText, boolean amountIsDue) {}

    private final JdbcClient jdbc;
    private final PeriodCalendar calendar;
    private final ReturnService returns;
    private final BankStatementService statements;
    private final AuditLog audit;

    public BankPaymentMatcher(JdbcClient jdbc, PeriodCalendar calendar, ReturnService returns, BankStatementService statements, AuditLog audit) {
        this.jdbc = jdbc;
        this.calendar = calendar;
        this.returns = returns;
        this.statements = statements;
        this.audit = audit;
    }

    /** Runs after a statement loads, over every filed return of the entity. Returns how many were recorded. */
    @Transactional
    public int autoMatch(UUID entityId) {
        String giban = giban(entityId);
        if (giban == null) {
            return 0;
        }
        int recorded = 0;
        for (TaxPeriod p : jdbc.sql(TaxPeriod.SELECT + " WHERE entity_id = ? AND state = 'FILED' ORDER BY period_start").param(entityId).query(TaxPeriod.class).list()) {
            BigDecimal outstanding = outstanding(p);
            List<Candidate> exact = candidates(p, giban, outstanding).stream().filter(c -> c.ibanInText() && c.amountIsDue()).toList();
            if (exact.size() == 1 && outstanding.signum() > 0) {
                record(p, exact.getFirst().txn(), giban, "NUMBER_AND_AMOUNT");
                recorded++;
            }
        }
        return recorded;
    }

    @Transactional(readOnly = true)
    public List<Candidate> candidates(UUID periodId) {
        TaxPeriod p = calendar.get(periodId);
        return candidates(p, giban(p.entityId()), outstanding(p));
    }

    private List<Candidate> candidates(TaxPeriod p, String giban, BigDecimal outstanding) {
        LocalDate from = jdbc.sql("SELECT submitted_on FROM filing WHERE period_id = ?").param(p.id()).query(LocalDate.class).optional().orElse(p.periodEnd());
        List<Candidate> out = new ArrayList<>();
        for (BankStatementService.Txn t : statements.unusedDebits(p.entityId(), from)) {
            boolean iban = giban != null && t.searchable().replace(" ", "").contains(giban.replace(" ", "").toUpperCase());
            boolean amount = t.amount().compareTo(outstanding) == 0;
            if (iban || amount) {
                out.add(new Candidate(t, iban, amount));
            }
        }
        return out;
    }

    /** A person confirms a bank line as this return's payment. A part payment is allowed, an overpayment is not. */
    @Transactional
    public void confirm(UUID periodId, UUID bankTxnId, String gibanReference) {
        TaxPeriod p = jdbc.sql(TaxPeriod.SELECT + " WHERE id = ? FOR UPDATE").param(periodId).query(TaxPeriod.class).optional()
                .orElseThrow(() -> DomainException.notFound("Tax period"));
        if (!"FILED".equals(p.state())) {
            throw DomainException.conflict("WRONG_STATE", "A payment is recorded against a filed return. This one is " + p.state());
        }
        BankStatementService.Txn t = statements.find(bankTxnId, p.entityId())
                .orElseThrow(() -> DomainException.invalid("BANK_TXN_UNKNOWN", "That bank transaction is not on this entity's statements"));
        if (!"DEBIT".equals(t.direction()) || !"AED".equals(t.currency())) {
            throw DomainException.invalid("BANK_TXN_MISMATCH", "Only a payment out of the account in AED can settle a return");
        }
        if (t.amount().compareTo(outstanding(p)) > 0) {
            throw DomainException.invalid("BANK_TXN_MISMATCH", "AED " + t.amount() + " is more than the AED " + outstanding(p) + " outstanding");
        }
        boolean used = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM payment WHERE bank_txn_id = :id) OR EXISTS (SELECT 1 FROM bank_doc_match WHERE bank_txn_id = :id)
                """).param("id", bankTxnId).query(Boolean.class).single();
        if (used) {
            throw DomainException.conflict("BANK_TXN_USED", "That bank transaction has already been used");
        }
        String reference = gibanReference != null && !gibanReference.isBlank() ? gibanReference : giban(p.entityId());
        record(p, t, reference == null ? t.reference() : reference, "CONFIRMED_BY_PERSON");
    }

    private void record(TaxPeriod p, BankStatementService.Txn t, String reference, String method) {
        UUID evidenceId = jdbc.sql("SELECT evidence_id FROM bank_statement WHERE id = ?").param(t.statementId()).query(UUID.class).single();
        UUID id = UUID.randomUUID();
        BigDecimal due = payable(p);
        jdbc.sql("""
                INSERT INTO payment (id, tenant_id, entity_id, period_id, amount_due_aed, amount_paid_aed, giban_reference, paid_on, bank_reference, bank_amount_aed,
                    confirmation_evidence_id, match_status, match_detail, bank_txn_id, recorded_by)
                VALUES (:id, :tenant, :entity, :period, :due, :paid, :giban, :on, :bankRef, :bankAmount, :evidence, 'MATCHED', :detail, :txn, :by)
                """)
                .param("id", id).param("tenant", RequestContext.requireTenant()).param("entity", p.entityId()).param("period", p.id()).param("due", due)
                .param("paid", t.amount()).param("giban", reference == null ? "" : reference).param("on", t.bookedOn()).param("bankRef", t.reference()).param("bankAmount", t.amount())
                .param("evidence", evidenceId).param("detail", "From bank statement: " + method).param("txn", t.id()).param("by", RequestContext.requireActor().actorId())
                .update();
        BigDecimal matched = jdbc.sql("SELECT COALESCE(SUM(amount_paid_aed), 0) FROM payment WHERE period_id = ? AND match_status = 'MATCHED'").param(p.id()).query(BigDecimal.class).single();
        boolean settled = matched.compareTo(due) >= 0;
        if (settled) {
            jdbc.sql("UPDATE tax_period SET state = 'PAID' WHERE id = ?").param(p.id()).update();
        }
        audit.record("PAYMENT_MATCHED_FROM_BANK", "PAYMENT", id, p.entityId(), Map.of("state", "FILED"), Map.of("state", settled ? "PAID" : "FILED",
                "amount", t.amount().toPlainString(), "method", method, "late", t.bookedOn().isAfter(p.dueDate())), null);
    }

    private BigDecimal outstanding(TaxPeriod p) {
        BigDecimal matched = jdbc.sql("SELECT COALESCE(SUM(amount_paid_aed), 0) FROM payment WHERE period_id = ? AND match_status = 'MATCHED'").param(p.id()).query(BigDecimal.class).single();
        return payable(p).subtract(matched).max(BigDecimal.ZERO);
    }

    private BigDecimal payable(TaxPeriod p) {
        if (p.lockedVersionId() == null) {
            return BigDecimal.ZERO;
        }
        ReturnVersionView v = returns.find(p.lockedVersionId()).orElseThrow();
        BoxValue box = ReturnService.parseValues(v.boxValues()).get("14");
        return box == null ? BigDecimal.ZERO : box.vat().max(BigDecimal.ZERO);
    }

    private String giban(UUID entityId) {
        return jdbc.sql("SELECT fta_giban FROM legal_entity WHERE id = ?").param(entityId).query(String.class).optional().orElse(null);
    }
}
