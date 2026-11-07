package ae.vattool.classification;

import ae.vattool.evidence.EvidenceService;
import ae.vattool.ledger.Ledger;
import ae.vattool.masterdata.PeriodCalendar;
import ae.vattool.platform.audit.AuditLog;
import ae.vattool.platform.tenancy.RequestContext;
import ae.vattool.platform.web.DomainException;
import ae.vattool.rules.RulePackDefinition;
import ae.vattool.rules.RulePackStore;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Zero-rating evidence for exports (TXC-05). Checks T07 and T08 read what is recorded here. */
@Service
public class ExportEvidenceService {

    public record Input(LocalDate exportedOn, String exitReference, UUID officialEvidenceId, UUID commercialEvidenceId, LocalDate extendedUntil, UUID extensionEvidenceId) {}

    public record Export(UUID documentId, String docNumber, LocalDate docDate, String counterpartyName, String counterpartyCountry, BigDecimal netAed, LocalDate exportedOn, String exitReference,
                         UUID officialEvidenceId, UUID commercialEvidenceId, LocalDate extendedUntil, LocalDate limitOn, String status) {}

    private record Row(UUID documentId, String docNumber, LocalDate docDate, String counterpartyName, String counterpartyCountry, BigDecimal netAed, LocalDate exportedOn, String exitReference,
                       UUID officialEvidenceId, UUID commercialEvidenceId, LocalDate extendedUntil) {}

    private final JdbcClient jdbc;
    private final EvidenceService evidence;
    private final RulePackStore packs;
    private final PeriodCalendar calendar;
    private final Ledger ledger;
    private final AuditLog audit;

    public ExportEvidenceService(JdbcClient jdbc, EvidenceService evidence, RulePackStore packs, PeriodCalendar calendar, Ledger ledger, AuditLog audit) {
        this.jdbc = jdbc;
        this.evidence = evidence;
        this.packs = packs;
        this.calendar = calendar;
        this.ledger = ledger;
        this.audit = audit;
    }

    @Transactional
    public void record(UUID documentId, Input in) {
        record Doc(UUID filerEntityId, String direction, LocalDate docDate) {}
        Doc doc = jdbc.sql("SELECT filer_entity_id, direction, doc_date FROM source_document WHERE id = ?").param(documentId).query(Doc.class).optional()
                .orElseThrow(() -> DomainException.notFound("Document"));
        if (!"OUT".equals(doc.direction())) {
            throw DomainException.invalid("NOT_AN_EXPORT", "Export evidence belongs on a sale");
        }
        if (in.exportedOn() != null && in.exportedOn().isBefore(doc.docDate().minusYears(1))) {
            throw DomainException.invalid("EXPORT_DATE_INVALID", "The export date is too far before the invoice");
        }
        if (in.extendedUntil() != null) {
            // An extension is the FTA's decision, so it needs the FTA's letter behind it.
            evidence.requireForEntity(in.extensionEvidenceId(), doc.filerEntityId());
        }
        for (UUID e : new UUID[] {in.officialEvidenceId(), in.commercialEvidenceId()}) {
            if (e != null) {
                evidence.requireForEntity(e, doc.filerEntityId());
            }
        }
        jdbc.sql("""
                INSERT INTO export_evidence (id, tenant_id, entity_id, document_id, exported_on, exit_reference, official_evidence_id, commercial_evidence_id, extended_until, extension_evidence_id, recorded_by)
                VALUES (:id, :tenant, :entity, :document, :exported, :exit, :official, :commercial, :extended, :extension, :by)
                ON CONFLICT (tenant_id, document_id) DO UPDATE SET
                    exported_on = EXCLUDED.exported_on, exit_reference = EXCLUDED.exit_reference, official_evidence_id = EXCLUDED.official_evidence_id, commercial_evidence_id = EXCLUDED.commercial_evidence_id,
                    extended_until = EXCLUDED.extended_until, extension_evidence_id = EXCLUDED.extension_evidence_id, recorded_by = EXCLUDED.recorded_by, recorded_at = now()
                """)
                .param("id", UUID.randomUUID()).param("tenant", RequestContext.requireTenant()).param("entity", doc.filerEntityId()).param("document", documentId)
                .param("exported", in.exportedOn()).param("exit", in.exitReference()).param("official", in.officialEvidenceId()).param("commercial", in.commercialEvidenceId())
                .param("extended", in.extendedUntil()).param("extension", in.extensionEvidenceId()).param("by", RequestContext.requireActor().actorId())
                .update();
        for (UUID e : new UUID[] {in.officialEvidenceId(), in.commercialEvidenceId(), in.extensionEvidenceId()}) {
            if (e != null) {
                evidence.link(e, "DOCUMENT", documentId.toString());
            }
        }
        // Reconciliation should see this at once, on the open period it can still act on.
        jdbc.sql("SELECT id FROM tax_period WHERE entity_id = ? AND state = 'OPEN' ORDER BY period_start LIMIT 1").param(doc.filerEntityId())
                .query(UUID.class).optional().ifPresent(ledger::requestRecompute);
        audit.record("EXPORT_EVIDENCE_RECORDED", "SOURCE_DOCUMENT", documentId, doc.filerEntityId(), null,
                Map.of("exportedOn", String.valueOf(in.exportedOn()), "official", in.officialEvidenceId() != null, "commercial", in.commercialEvidenceId() != null), null);
    }

    @Transactional
    public List<Export> forPeriod(UUID periodId) {
        var period = calendar.get(periodId);
        int days = RulePackDefinition.require(packs.forDate(period.periodEnd()).definition().zeroRating(), "zero rating").exportDays();
        return jdbc.sql("""
                SELECT d.id AS document_id, d.doc_number, d.doc_date, d.counterparty_name, MIN(l.counterparty_country) AS counterparty_country, SUM(l.net_aed) AS net_aed,
                       x.exported_on, x.exit_reference, x.official_evidence_id, x.commercial_evidence_id, x.extended_until
                FROM source_document d
                JOIN tax_line l ON l.document_id = d.id AND l.period_id = :period AND l.superseded_at IS NULL
                LEFT JOIN export_evidence x ON x.document_id = d.id
                WHERE d.direction = 'OUT' AND l.treatment = 'ZERO_RATED' AND l.goods_or_services = 'GOODS' AND COALESCE(l.counterparty_country, 'AE') <> 'AE'
                GROUP BY d.id, x.exported_on, x.exit_reference, x.official_evidence_id, x.commercial_evidence_id, x.extended_until
                ORDER BY d.doc_date, d.doc_number
                """).param("period", periodId).query(Row.class).list().stream().map(r -> {
            LocalDate limit = r.extendedUntil() != null ? r.extendedUntil() : r.docDate().plusDays(days);
            String status = r.exportedOn() != null && r.exportedOn().isAfter(limit) ? "LATE"
                    : r.officialEvidenceId() != null && r.commercialEvidenceId() != null && r.exportedOn() != null ? "COMPLETE" : "INCOMPLETE";
            return new Export(r.documentId(), r.docNumber(), r.docDate(), r.counterpartyName(), r.counterpartyCountry(), r.netAed(), r.exportedOn(), r.exitReference(),
                    r.officialEvidenceId(), r.commercialEvidenceId(), r.extendedUntil(), limit, status);
        }).toList();
    }

    @Transactional(readOnly = true)
    public UUID entityOf(UUID documentId) {
        return jdbc.sql("SELECT filer_entity_id FROM source_document WHERE id = ?").param(documentId).query(UUID.class).optional()
                .orElseThrow(() -> DomainException.notFound("Document"));
    }
}
