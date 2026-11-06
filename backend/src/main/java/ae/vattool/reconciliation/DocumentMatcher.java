package ae.vattool.reconciliation;

import ae.vattool.masterdata.TaxPeriod;
import ae.vattool.platform.tenancy.RequestContext;
import ae.vattool.platform.web.DomainException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pairs ERP documents with e-invoices (spec section 5, matching approach). Deterministic keys are
 * confirmed straight away. Fuzzy matches are only ever proposals: a near match on a blocking check
 * must not clear it without a person deciding.
 */
@Service
public class DocumentMatcher {

    private static final BigDecimal PROPOSAL_THRESHOLD = new BigDecimal("0.70");

    public record Proposal(UUID id, UUID documentId, String docNumber, LocalDate docDate, BigDecimal docNet, BigDecimal docVat,
                           UUID invoiceUuid, String invoiceNumber, LocalDate issueDate, BigDecimal invoiceNet,
                           BigDecimal invoiceVat, BigDecimal score, String status) {}

    record OpenDoc(UUID id, String docNumber, LocalDate docDate, String counterpartyTrn, BigDecimal net, BigDecimal vat) {}

    record OpenInvoice(UUID invoiceUuid, String number, LocalDate issueDate, String counterpartyTrn, BigDecimal net, BigDecimal vat) {}

    private final JdbcClient jdbc;

    public DocumentMatcher(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void match(TaxPeriod period, BigDecimal tolerance, int dateWindowDays) {
        UUID tenant = RequestContext.requireTenant();
        // 1. By e-invoice UUID recorded in the ERP.
        jdbc.sql("""
                INSERT INTO doc_match (id, tenant_id, filer_entity_id, document_id, invoice_uuid, method, score, status, decided_by, decided_at)
                SELECT gen_random_uuid(), :tenant, d.filer_entity_id, d.id, e.invoice_uuid, 'UUID', 1, 'CONFIRMED', 'system', now()
                FROM source_document d
                JOIN einvoice e ON e.invoice_uuid = d.einvoice_uuid AND e.filer_entity_id = d.filer_entity_id
                WHERE d.filer_entity_id = :filer AND d.voided_at IS NULL
                  AND ((d.direction = 'OUT' AND e.direction = 'ISSUED') OR (d.direction = 'IN' AND e.direction = 'RECEIVED'))
                  AND NOT EXISTS (SELECT 1 FROM doc_match m WHERE m.document_id = d.id AND m.status = 'CONFIRMED')
                  AND NOT EXISTS (SELECT 1 FROM doc_match m WHERE m.invoice_uuid = e.invoice_uuid AND m.status = 'CONFIRMED')
                ON CONFLICT DO NOTHING
                """).param("tenant", tenant).param("filer", period.entityId()).update();
        // 2. By counterparty TRN, number and date. A key shared by two e-invoices is ambiguous, so it
        //    is left for check R10 and a person to sort out.
        jdbc.sql("""
                INSERT INTO doc_match (id, tenant_id, filer_entity_id, document_id, invoice_uuid, method, score, status, decided_by, decided_at)
                SELECT gen_random_uuid(), :tenant, d.filer_entity_id, d.id, e.invoice_uuid, 'KEY', 0.99, 'CONFIRMED', 'system', now()
                FROM source_document d
                JOIN einvoice e ON e.filer_entity_id = d.filer_entity_id AND e.number = d.doc_number AND e.issue_date = d.doc_date
                  AND ((d.direction = 'OUT' AND e.direction = 'ISSUED' AND e.buyer_trn IS NOT DISTINCT FROM d.counterparty_trn)
                    OR (d.direction = 'IN' AND e.direction = 'RECEIVED' AND e.supplier_trn IS NOT DISTINCT FROM d.counterparty_trn))
                WHERE d.filer_entity_id = :filer AND d.source_system = 'ERP' AND d.voided_at IS NULL AND e.has_invoice_data
                  AND NOT EXISTS (SELECT 1 FROM doc_match m WHERE m.document_id = d.id AND m.status = 'CONFIRMED')
                  AND NOT EXISTS (SELECT 1 FROM doc_match m WHERE m.invoice_uuid = e.invoice_uuid AND m.status = 'CONFIRMED')
                  AND (SELECT count(*) FROM einvoice x WHERE x.filer_entity_id = e.filer_entity_id AND x.number = e.number
                         AND x.issue_date = e.issue_date AND x.direction = e.direction) = 1
                ON CONFLICT DO NOTHING
                """).param("tenant", tenant).param("filer", period.entityId()).update();
        proposeFuzzy(period, tolerance, dateWindowDays);
    }

    /** 3. For what is left in this period: amount within tolerance, date near, similar reference. */
    private void proposeFuzzy(TaxPeriod period, BigDecimal tolerance, int window) {
        for (String[] dirs : new String[][] {{"OUT", "ISSUED", "buyer_trn"}, {"IN", "RECEIVED", "supplier_trn"}}) {
            List<OpenDoc> docs = jdbc.sql("""
                    SELECT d.id, d.doc_number, d.doc_date, d.counterparty_trn,
                           SUM(l.net_aed) AS net, SUM(ROUND(l.vat_doc * l.fx_rate, 2)) AS vat
                    FROM source_document d
                    JOIN tax_line l ON l.document_id = d.id AND l.superseded_at IS NULL AND l.period_id = :period
                    WHERE d.direction = :direction AND d.source_system = 'ERP' AND d.voided_at IS NULL
                      AND COALESCE(d.customer_type, 'B2B') <> 'B2C'
                      AND NOT EXISTS (SELECT 1 FROM doc_match m WHERE m.document_id = d.id AND m.status = 'CONFIRMED')
                    GROUP BY d.id
                    """).param("period", period.id()).param("direction", dirs[0]).query(OpenDoc.class).list();
            if (docs.isEmpty()) {
                continue;
            }
            List<OpenInvoice> invoices = jdbc.sql("""
                    SELECT e.invoice_uuid, e.number, e.issue_date, e.%s AS counterparty_trn, e.net_aed AS net, e.vat_aed AS vat
                    FROM einvoice e
                    WHERE e.filer_entity_id = :filer AND e.direction = :direction AND e.has_invoice_data
                      AND e.issue_date BETWEEN :from AND :to
                      AND NOT EXISTS (SELECT 1 FROM doc_match m WHERE m.invoice_uuid = e.invoice_uuid AND m.status = 'CONFIRMED')
                    """.formatted(dirs[2]))
                    .param("filer", period.entityId()).param("direction", dirs[1])
                    .param("from", period.periodStart().minusDays(window)).param("to", period.periodEnd().plusDays(window))
                    .query(OpenInvoice.class).list();
            for (OpenDoc d : docs) {
                for (OpenInvoice i : invoices) {
                    BigDecimal score = score(d, i, tolerance, window);
                    if (score.compareTo(PROPOSAL_THRESHOLD) >= 0) {
                        jdbc.sql("""
                                INSERT INTO doc_match (id, tenant_id, filer_entity_id, document_id, invoice_uuid, method, score, status)
                                VALUES (:id, :tenant, :filer, :doc, :invoice, 'FUZZY', :score, 'PROPOSED')
                                ON CONFLICT (tenant_id, document_id, invoice_uuid) DO NOTHING
                                """)
                                .param("id", UUID.randomUUID()).param("tenant", RequestContext.requireTenant())
                                .param("filer", period.entityId()).param("doc", d.id()).param("invoice", i.invoiceUuid())
                                .param("score", score).update();
                    }
                }
            }
        }
    }

    static BigDecimal score(OpenDoc d, OpenInvoice i, BigDecimal tolerance, int window) {
        if (d.net() == null || i.net() == null || d.vat() == null || i.vat() == null) {
            return BigDecimal.ZERO;
        }
        if (d.net().subtract(i.net()).abs().compareTo(tolerance) > 0 || d.vat().subtract(i.vat()).abs().compareTo(tolerance) > 0) {
            return BigDecimal.ZERO;
        }
        long days = Math.abs(ChronoUnit.DAYS.between(d.docDate(), i.issueDate()));
        if (days > window) {
            return BigDecimal.ZERO;
        }
        double date = window == 0 ? 1 : 1 - (double) days / (window + 1);
        double ref = similarity(d.docNumber(), i.number());
        boolean sameParty = d.counterpartyTrn() != null && d.counterpartyTrn().equals(i.counterpartyTrn());
        double total = 0.25 + 0.15 * date + 0.4 * ref + (sameParty ? 0.2 : 0);
        return BigDecimal.valueOf(total).setScale(3, RoundingMode.HALF_UP);
    }

    /** 1.0 for identical references once case and punctuation are removed, falling with edit distance. */
    static double similarity(String a, String b) {
        String x = normalise(a);
        String y = normalise(b);
        if (x.isEmpty() || y.isEmpty()) {
            return 0;
        }
        int max = Math.max(x.length(), y.length());
        return 1 - (double) editDistance(x, y) / max;
    }

    private static String normalise(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            int[] cur = new int[b.length() + 1];
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            prev = cur;
        }
        return prev[b.length()];
    }

    @Transactional(readOnly = true)
    public List<Proposal> proposals(TaxPeriod period) {
        return jdbc.sql("""
                SELECT m.id, m.document_id, d.doc_number, d.doc_date,
                       (SELECT SUM(l.net_aed) FROM tax_line l WHERE l.document_id = d.id AND l.superseded_at IS NULL) AS doc_net,
                       (SELECT SUM(ROUND(l.vat_doc * l.fx_rate, 2)) FROM tax_line l WHERE l.document_id = d.id AND l.superseded_at IS NULL) AS doc_vat,
                       m.invoice_uuid, e.number AS invoice_number, e.issue_date, e.net_aed AS invoice_net, e.vat_aed AS invoice_vat,
                       m.score, m.status
                FROM doc_match m
                JOIN source_document d ON d.id = m.document_id
                JOIN einvoice e ON e.invoice_uuid = m.invoice_uuid
                WHERE m.status = 'PROPOSED' AND d.filer_entity_id = :filer
                  AND EXISTS (SELECT 1 FROM tax_line l WHERE l.document_id = d.id AND l.superseded_at IS NULL AND l.period_id = :period)
                ORDER BY m.score DESC
                """).param("filer", period.entityId()).param("period", period.id()).query(Proposal.class).list();
    }

    /**
     * A person accepts or rejects a proposal. Accepting one withdraws competing proposals. The match
     * must belong to the entity the caller was checked against: row-level security only separates tenants.
     */
    @Transactional
    public void decide(UUID matchId, UUID entityId, boolean accept) {
        record M(UUID documentId, UUID invoiceUuid, String status) {}
        M m = jdbc.sql("SELECT document_id, invoice_uuid, status FROM doc_match WHERE id = ? AND filer_entity_id = ? FOR UPDATE")
                .params(matchId, entityId).query(M.class).optional()
                .orElseThrow(() -> DomainException.notFound("Match"));
        if (!"PROPOSED".equals(m.status())) {
            throw DomainException.conflict("MATCH_ALREADY_DECIDED", "This match has already been decided");
        }
        String actor = RequestContext.requireActor().actorId();
        if (accept) {
            jdbc.sql("""
                    UPDATE doc_match SET status = 'REJECTED', decided_by = :by, decided_at = now()
                    WHERE status = 'PROPOSED' AND id <> :id AND (document_id = :doc OR invoice_uuid = :invoice)
                    """).param("by", actor).param("id", matchId).param("doc", m.documentId()).param("invoice", m.invoiceUuid()).update();
        }
        jdbc.sql("UPDATE doc_match SET status = :status, decided_by = :by, decided_at = now() WHERE id = :id")
                .param("status", accept ? "CONFIRMED" : "REJECTED").param("by", actor).param("id", matchId).update();
    }
}
