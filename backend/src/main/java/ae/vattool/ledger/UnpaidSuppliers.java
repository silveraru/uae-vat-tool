package ae.vattool.ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Where each supplier invoice stands under the 6-month rule (ADJ-01). Check R11 and the adjustments
 * service both read this, so "needs reversing" cannot mean different things in each. Payment comes
 * from ERP AP status until bank statement import exists (Phase 2).
 */
@Service
public class UnpaidSuppliers {

    /** targetReversed is what should stand reversed as of the period end; delta is what is left to post. */
    public record Position(UUID documentId, String docNumber, String counterpartyTrn, LocalDate paymentDueOn,
                           boolean agreedDateMissing, BigDecimal gross, BigDecimal paid, BigDecimal recoverable,
                           BigDecimal currentlyReversed, BigDecimal targetReversed, BigDecimal delta) {}

    private record Row(UUID id, String docNumber, String counterpartyTrn, LocalDate agreedPaymentDate, LocalDate docDate,
                       LocalDate paidDate, BigDecimal paidAmountAed, BigDecimal gross, BigDecimal recoverable,
                       BigDecimal reversed) {}

    private final JdbcClient jdbc;

    public UnpaidSuppliers(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Invoices where the amount that should be reversed differs from what is reversed today. */
    @Transactional(readOnly = true)
    public List<Position> outstanding(UUID filerId, LocalDate periodEnd, int months) {
        // An invoice with no agreed date falls back to its own date, which is flagged for review.
        List<Row> rows = jdbc.sql("""
                SELECT d.id, d.doc_number, d.counterparty_trn, d.agreed_payment_date, d.doc_date, d.paid_date, d.paid_amount_aed,
                       COALESCE(d.gross_aed, SUM(l.net_aed + ROUND(l.vat_doc * l.fx_rate, 2)), 0) AS gross,
                       COALESCE(d.opening_recoverable_vat_aed, SUM(l.recoverable_vat_aed), 0) AS recoverable,
                       COALESCE((SELECT SUM(r.vat_aed) FROM supplier_reversal r WHERE r.document_id = d.id), 0) AS reversed
                FROM source_document d
                LEFT JOIN tax_line l ON l.document_id = d.id AND l.superseded_at IS NULL AND l.line_kind = 'NORMAL'
                WHERE d.filer_entity_id = :filer AND d.direction = 'IN' AND d.source_system IN ('ERP', 'OPENING')
                  AND d.voided_at IS NULL AND d.doc_type <> 'CREDIT_NOTE'
                  AND COALESCE(d.agreed_payment_date, d.doc_date) <= :cutoff
                GROUP BY d.id
                """)
                .param("filer", filerId).param("cutoff", periodEnd.minusMonths(months))
                .query(Row.class).list();
        List<Position> out = new ArrayList<>();
        for (Row r : rows) {
            if (r.recoverable().signum() <= 0 && r.reversed().signum() == 0) {
                continue;
            }
            BigDecimal paid = paidAsOf(r, periodEnd);
            BigDecimal unpaid = r.gross().subtract(paid).max(BigDecimal.ZERO);
            BigDecimal target = r.gross().signum() <= 0
                    ? BigDecimal.ZERO.setScale(2)
                    : r.recoverable().multiply(unpaid).divide(r.gross(), 2, RoundingMode.HALF_UP);
            BigDecimal delta = target.subtract(r.reversed());
            if (delta.signum() != 0) {
                out.add(new Position(r.id(), r.docNumber(), r.counterpartyTrn(),
                        r.agreedPaymentDate() != null ? r.agreedPaymentDate() : r.docDate(), r.agreedPaymentDate() == null,
                        r.gross(), paid, r.recoverable(), r.reversed(), target, delta));
            }
        }
        return out;
    }

    /** Payment counts only if made by the period end. A paid date with no amount means paid in full. */
    private static BigDecimal paidAsOf(Row r, LocalDate periodEnd) {
        if (r.paidDate() == null || r.paidDate().isAfter(periodEnd)) {
            return BigDecimal.ZERO;
        }
        return r.paidAmountAed() != null ? r.paidAmountAed() : r.gross();
    }
}
