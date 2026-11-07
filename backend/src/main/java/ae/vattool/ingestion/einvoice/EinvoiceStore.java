package ae.vattool.ingestion.einvoice;

import ae.vattool.ledger.Ledger;
import ae.vattool.masterdata.LegalEntity;
import ae.vattool.masterdata.PeriodCalendar;
import ae.vattool.platform.tenancy.RequestContext;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Holds reconciliation set A: e-invoices as the ASP reports them. Only a reference and key values
 * are kept, not the UBL itself. Events may repeat or arrive in any order, and the outcome is the
 * same: repeats are ignored by event id, and each status takes its latest value by event time.
 */
@Service
public class EinvoiceStore {

    public record Einvoice(UUID invoiceUuid, UUID filerEntityId, String direction, String docType, String number,
                           LocalDate issueDate, String supplierTrn, String buyerTrn, String currency,
                           java.math.BigDecimal netAed, java.math.BigDecimal vatAed, String billingReference,
                           String lifecycleStatus, String rejectionReason, String corner5Status,
                           OffsetDateTime corner5At, boolean hasInvoiceData, String source) {}

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;
    private final Ledger ledger;
    private final PeriodCalendar calendar;

    public EinvoiceStore(JdbcClient jdbc, Ledger ledger, PeriodCalendar calendar) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.calendar = calendar;
    }

    public enum Applied { APPLIED, DUPLICATE, UNKNOWN_ENTITY }

    @Transactional
    public Applied apply(EinvoiceEvent e) {
        int fresh = jdbc.sql("""
                INSERT INTO einvoice_event (tenant_id, event_id, invoice_uuid, event_type, occurred_at, payload)
                VALUES (:tenant, :event, :invoice, :type, :at, CAST(:payload AS jsonb))
                ON CONFLICT (tenant_id, event_id) DO NOTHING
                """)
                .param("tenant", RequestContext.requireTenant()).param("event", e.eventId()).param("invoice", e.invoiceUuid())
                .param("type", e.eventType()).param("at", e.occurredAt()).param("payload", JSON.writeValueAsString(e))
                .update();
        if (fresh == 0) {
            return Applied.DUPLICATE;
        }
        Optional<LegalEntity> filer = jdbc.sql(LegalEntity.SELECT + " WHERE trn = ? AND entity_kind <> 'GROUP_MEMBER'")
                .param(e.entityTrn()).query(LegalEntity.class).optional();
        if (filer.isEmpty()) {
            return Applied.UNKNOWN_ENTITY;
        }
        // A status event can overtake its invoice, so the row may start as an empty stub.
        jdbc.sql("""
                INSERT INTO einvoice (id, tenant_id, filer_entity_id, invoice_uuid)
                VALUES (:id, :tenant, :filer, :invoice) ON CONFLICT (tenant_id, invoice_uuid) DO NOTHING
                """)
                .param("id", UUID.randomUUID()).param("tenant", RequestContext.requireTenant())
                .param("filer", filer.get().id()).param("invoice", e.invoiceUuid())
                .update();

        switch (e.eventType()) {
            case EinvoiceEvent.ISSUED -> applyInvoice(e, "ISSUED");
            case EinvoiceEvent.RECEIVED -> applyInvoice(e, "RECEIVED");
            case EinvoiceEvent.REJECTED -> jdbc.sql("""
                    UPDATE einvoice SET lifecycle_status = 'REJECTED', rejection_reason = :reason, lifecycle_at = :at
                    WHERE invoice_uuid = :invoice AND (lifecycle_at IS NULL OR lifecycle_at <= :at)
                    """)
                    .param("reason", e.rejection() == null ? null : e.rejection().reason())
                    .param("at", e.occurredAt()).param("invoice", e.invoiceUuid()).update();
            case EinvoiceEvent.CORNER5 -> jdbc.sql("""
                    UPDATE einvoice SET corner5_status = :status, corner5_at = :at
                    WHERE invoice_uuid = :invoice AND (corner5_at IS NULL OR corner5_at <= :at)
                    """)
                    .param("status", e.corner5().status()).param("at", e.occurredAt()).param("invoice", e.invoiceUuid()).update();
            default -> throw new IllegalArgumentException("Unknown event type " + e.eventType());
        }
        // New e-invoice data can change reconciliation results, so the period is marked for a re-run.
        LocalDate issueDate = jdbc.sql("SELECT issue_date FROM einvoice WHERE invoice_uuid = ?")
                .param(e.invoiceUuid()).query(LocalDate.class).optional().orElse(null);
        if (issueDate != null) {
            calendar.periodFor(filer.get().id(), issueDate).ifPresent(p -> ledger.requestRecompute(p.id()));
        }
        return Applied.APPLIED;
    }

    private void applyInvoice(EinvoiceEvent e, String lifecycle) {
        EinvoiceEvent.Invoice i = e.invoice();
        jdbc.sql("""
                UPDATE einvoice SET direction = :direction, doc_type = :docType, number = :number, issue_date = :date,
                    supplier_trn = :supplier, buyer_trn = :buyer, currency = :currency, net_aed = :net, vat_aed = :vat,
                    billing_reference = :reference, has_invoice_data = true
                WHERE invoice_uuid = :invoice
                """)
                .param("direction", lifecycle).param("docType", i.docType()).param("number", i.number())
                .param("date", i.issueDate()).param("supplier", i.supplierTrn()).param("buyer", i.buyerTrn())
                .param("currency", i.currency()).param("net", i.netAed()).param("vat", i.vatAed())
                .param("reference", i.billingReference()).param("invoice", e.invoiceUuid())
                .update();
        jdbc.sql("""
                UPDATE einvoice SET lifecycle_status = :status, lifecycle_at = :at
                WHERE invoice_uuid = :invoice AND (lifecycle_at IS NULL OR lifecycle_at <= :at)
                """)
                .param("status", lifecycle).param("at", e.occurredAt()).param("invoice", e.invoiceUuid()).update();
    }

    @Transactional(readOnly = true)
    public List<Einvoice> list(UUID filerId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                SELECT invoice_uuid, filer_entity_id, direction, doc_type, number, issue_date, supplier_trn, buyer_trn, currency,
                       net_aed, vat_aed, billing_reference, lifecycle_status, rejection_reason, corner5_status, corner5_at, has_invoice_data, source
                FROM einvoice WHERE filer_entity_id = :filer AND issue_date BETWEEN :from AND :to
                ORDER BY issue_date, number LIMIT 5000
                """)
                .param("filer", filerId).param("from", from).param("to", to).query(Einvoice.class).list();
    }
}
