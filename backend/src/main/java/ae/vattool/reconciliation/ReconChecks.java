package ae.vattool.reconciliation;

import ae.vattool.ledger.UnpaidSuppliers;
import ae.vattool.masterdata.LegalEntity;
import ae.vattool.masterdata.TaxPeriod;
import ae.vattool.platform.storage.VaultStorage;
import ae.vattool.rules.RulePackDefinition;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** The rule set from spec section 5, plus data and tax checks. Each check returns what it found. */
@Component
class ReconChecks {

    /** Box values as they stand in the return being checked, read from the stored version. */
    record Context(TaxPeriod period, LegalEntity entity, RulePackDefinition pack, Map<String, Map<String, String>> boxes,
                   boolean earliestOpen) {
        BigDecimal box(String ref) {
            int dot = ref.indexOf('.');
            String code = dot < 0 ? ref : ref.substring(0, dot);
            String column = dot < 0 ? "vat" : ref.substring(dot + 1);
            Map<String, String> b = boxes.get(code);
            return b == null ? BigDecimal.ZERO : new BigDecimal(b.getOrDefault(column, "0"));
        }
    }

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;
    private final UnpaidSuppliers unpaid;
    private final ParticipantLookup participants;

    ReconChecks(JdbcClient jdbc, UnpaidSuppliers unpaid, ParticipantLookup participants) {
        this.jdbc = jdbc;
        this.unpaid = unpaid;
        this.participants = participants;
    }

    List<Finding> runAll(Context c) {
        List<Finding> all = new ArrayList<>();
        all.addAll(r01(c));
        all.addAll(r02(c));
        all.addAll(r03(c));
        all.addAll(r04(c));
        all.addAll(r05(c));
        all.addAll(r06(c));
        all.addAll(r07(c));
        all.addAll(r08(c));
        all.addAll(r09(c));
        all.addAll(r10(c));
        all.addAll(r11(c));
        all.addAll(r12(c));
        all.addAll(r13(c));
        all.addAll(r14(c));
        all.addAll(d01(c));
        all.addAll(d02(c));
        all.addAll(d03(c));
        all.addAll(d04(c));
        all.addAll(lineFlags(c));
        return all;
    }

    private static String rows(long n) {
        return n + (n == 1 ? " row" : " rows");
    }

    private static String lines(long n) {
        return n + (n == 1 ? " line" : " lines");
    }

    private static Finding finding(String rule, String fingerprint, String message, BigDecimal amount, Object... detail) {
        Map<String, Object> details = new LinkedHashMap<>();
        for (int i = 0; i + 1 < detail.length; i += 2) {
            details.put(String.valueOf(detail[i]), detail[i + 1]);
        }
        return new Finding(rule, fingerprint, message, details, amount, VaultStorage.sha256(JSON.writeValueAsString(details)));
    }

    /** R01: applies from the entity's e-invoicing go-live date. Before that there is nothing to expect. */
    private List<Finding> r01(Context c) {
        LocalDate goLive = c.entity().einvoiceGoLive();
        if (goLive == null) {
            return List.of();
        }
        record Row(UUID id, String docNumber, LocalDate docDate, String counterpartyTrn, BigDecimal vat,
                   UUID invoiceUuid, String lifecycleStatus, String corner5Status) {}
        return jdbc.sql("""
                SELECT d.id, d.doc_number, d.doc_date, d.counterparty_trn, SUM(l.vat_aed) AS vat,
                       e.invoice_uuid, e.lifecycle_status, e.corner5_status
                FROM source_document d
                JOIN tax_line l ON l.document_id = d.id AND l.period_id = :period AND l.superseded_at IS NULL
                LEFT JOIN doc_match m ON m.document_id = d.id AND m.status = 'CONFIRMED'
                LEFT JOIN einvoice e ON e.invoice_uuid = m.invoice_uuid
                WHERE d.direction = 'OUT' AND d.source_system = 'ERP' AND d.customer_type IN ('B2B', 'B2G')
                  AND d.doc_type IN ('TAX_INVOICE', 'CREDIT_NOTE', 'DEBIT_NOTE') AND d.doc_date >= :goLive
                  AND (e.invoice_uuid IS NULL OR e.lifecycle_status IS DISTINCT FROM 'ISSUED' OR e.corner5_status <> 'REPORTED')
                GROUP BY d.id, e.invoice_uuid, e.lifecycle_status, e.corner5_status
                """).param("period", c.period().id()).param("goLive", goLive)
                .query(Row.class).list().stream().map(r -> {
                    String problem = r.invoiceUuid() == null ? "no e-invoice found"
                            : "REJECTED".equals(r.lifecycleStatus()) ? "its e-invoice was rejected"
                            : "its e-invoice is not reported to the FTA (Corner 5 status " + r.corner5Status() + ")";
                    return finding("R01", r.id().toString(), "Sales invoice " + r.docNumber() + ": " + problem, r.vat(),
                            "docNumber", r.docNumber(), "docDate", String.valueOf(r.docDate()), "counterpartyTrn", r.counterpartyTrn(),
                            "invoiceUuid", String.valueOf(r.invoiceUuid()), "lifecycle", r.lifecycleStatus(), "corner5", r.corner5Status());
                }).toList();
    }

    private List<Finding> r02(Context c) {
        record Row(UUID invoiceUuid, String number, LocalDate issueDate, BigDecimal vatAed, UUID documentId) {}
        return jdbc.sql("""
                SELECT e.invoice_uuid, e.number, e.issue_date, e.vat_aed, m.document_id
                FROM einvoice e
                LEFT JOIN doc_match m ON m.invoice_uuid = e.invoice_uuid AND m.status = 'CONFIRMED'
                WHERE e.filer_entity_id = :filer AND e.direction = 'ISSUED' AND e.lifecycle_status = 'ISSUED' AND e.has_invoice_data
                  AND e.issue_date BETWEEN :from AND :to
                  AND (m.document_id IS NULL OR NOT EXISTS (
                        SELECT 1 FROM tax_line l WHERE l.document_id = m.document_id AND l.period_id = :period AND l.superseded_at IS NULL))
                """).param("filer", c.entity().id()).param("from", c.period().periodStart()).param("to", c.period().periodEnd())
                .param("period", c.period().id()).query(Row.class).list().stream()
                .map(r -> finding("R02", r.invoiceUuid().toString(),
                        "Issued e-invoice " + r.number() + (r.documentId() == null ? " is not in the ERP" : " is posted in the ERP in a different period"),
                        r.vatAed(), "number", r.number(), "issueDate", String.valueOf(r.issueDate()),
                        "invoiceUuid", r.invoiceUuid().toString(), "matchedDocument", String.valueOf(r.documentId())))
                .toList();
    }

    private List<Finding> r03(Context c) {
        record Row(UUID id, String docNumber, UUID invoiceUuid, BigDecimal net, BigDecimal vat, BigDecimal netAed, BigDecimal vatAed) {}
        return jdbc.sql("""
                SELECT d.id, d.doc_number, e.invoice_uuid, SUM(l.net_aed) AS net, SUM(ROUND(l.vat_doc * l.fx_rate, 2)) AS vat,
                       e.net_aed, e.vat_aed
                FROM doc_match m
                JOIN source_document d ON d.id = m.document_id
                JOIN einvoice e ON e.invoice_uuid = m.invoice_uuid
                JOIN tax_line l ON l.document_id = d.id AND l.period_id = :period AND l.superseded_at IS NULL
                WHERE m.status = 'CONFIRMED' AND d.filer_entity_id = :filer AND e.has_invoice_data
                GROUP BY d.id, e.invoice_uuid, e.net_aed, e.vat_aed
                HAVING ABS(SUM(l.net_aed) - e.net_aed) > :tolerance OR ABS(SUM(ROUND(l.vat_doc * l.fx_rate, 2)) - e.vat_aed) > :tolerance
                """).param("period", c.period().id()).param("filer", c.entity().id())
                .param("tolerance", c.pack().reconciliation().perInvoiceToleranceAed())
                .query(Row.class).list().stream()
                .map(r -> finding("R03", r.id().toString(),
                        "Invoice " + r.docNumber() + " differs: ERP net " + r.net() + " VAT " + r.vat() + ", e-invoice net " + r.netAed() + " VAT " + r.vatAed(),
                        r.vat().subtract(r.vatAed()), "docNumber", r.docNumber(), "erpNet", r.net().toPlainString(), "erpVat", r.vat().toPlainString(),
                        "einvoiceNet", r.netAed().toPlainString(), "einvoiceVat", r.vatAed().toPlainString(), "invoiceUuid", r.invoiceUuid().toString()))
                .toList();
    }

    private List<Finding> r04(Context c) {
        record Row(UUID invoiceUuid, String number, String supplierTrn, BigDecimal vatAed) {}
        return jdbc.sql("""
                SELECT e.invoice_uuid, e.number, e.supplier_trn, e.vat_aed FROM einvoice e
                LEFT JOIN doc_match m ON m.invoice_uuid = e.invoice_uuid AND m.status = 'CONFIRMED'
                WHERE e.filer_entity_id = :filer AND e.direction = 'RECEIVED' AND e.lifecycle_status = 'RECEIVED'
                  AND e.has_invoice_data AND e.issue_date BETWEEN :from AND :to AND m.id IS NULL
                """).param("filer", c.entity().id()).param("from", c.period().periodStart()).param("to", c.period().periodEnd())
                .query(Row.class).list().stream()
                .map(r -> finding("R04", r.invoiceUuid().toString(),
                        "Received e-invoice " + r.number() + " from " + r.supplierTrn() + " is not in AP, so its input VAT is unclaimed",
                        r.vatAed(), "number", r.number(), "supplierTrn", r.supplierTrn(), "invoiceUuid", r.invoiceUuid().toString()))
                .toList();
    }

    private List<Finding> r05(Context c) {
        record Row(UUID id, String docNumber, String counterpartyTrn, BigDecimal recoverable) {}
        return jdbc.sql("""
                SELECT d.id, d.doc_number, d.counterparty_trn, SUM(l.recoverable_vat_aed) AS recoverable
                FROM source_document d
                JOIN tax_line l ON l.document_id = d.id AND l.period_id = :period AND l.superseded_at IS NULL
                     AND l.direction = 'IN' AND l.treatment = 'STANDARD' AND l.line_kind = 'NORMAL'
                LEFT JOIN doc_match m ON m.document_id = d.id AND m.status = 'CONFIRMED'
                LEFT JOIN einvoice e ON e.invoice_uuid = m.invoice_uuid
                WHERE d.source_system = 'ERP' AND d.has_tax_invoice = false
                  AND (e.invoice_uuid IS NULL OR e.lifecycle_status = 'REJECTED')
                GROUP BY d.id
                HAVING SUM(l.recoverable_vat_aed) > 0
                """).param("period", c.period().id()).query(Row.class).list().stream()
                .map(r -> finding("R05", r.id().toString(),
                        "Input VAT " + r.recoverable() + " claimed on " + r.docNumber() + " with no valid tax invoice or e-invoice",
                        r.recoverable(), "docNumber", r.docNumber(), "counterpartyTrn", r.counterpartyTrn(), "recoverable", r.recoverable().toPlainString()))
                .toList();
    }

    private List<Finding> r06(Context c) {
        record Gl(String accountType, BigDecimal balance) {}
        List<Gl> balances = jdbc.sql("""
                SELECT account_type, SUM(balance_aed) AS balance FROM gl_balance WHERE period_id = ? GROUP BY account_type
                """).param(c.period().id()).query(Gl.class).list();
        if (balances.isEmpty()) {
            return List.of(finding("R06", "GL_NOT_LOADED", "GL VAT control balances have not been loaded for this period", null));
        }
        Map<String, BigDecimal> gl = new LinkedHashMap<>();
        balances.forEach(b -> gl.put(b.accountType(), b.balance()));
        record Timing(String accountType, BigDecimal amount) {}
        Map<String, BigDecimal> timing = new LinkedHashMap<>();
        jdbc.sql("SELECT account_type, SUM(amount_aed) AS amount FROM recon_timing_item WHERE period_id = ? GROUP BY account_type")
                .param(c.period().id()).query(Timing.class).list().forEach(t -> timing.put(t.accountType(), t.amount()));

        RulePackDefinition.Reconciliation cfg = c.pack().reconciliation();
        List<Finding> out = new ArrayList<>();
        cfg.glAccountBoxes().forEach((type, refs) -> {
            BigDecimal returnValue = refs.stream().map(c::box).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal ledger = gl.getOrDefault(type, BigDecimal.ZERO);
            BigDecimal timed = timing.getOrDefault(type, BigDecimal.ZERO);
            BigDecimal difference = ledger.subtract(timed).subtract(returnValue);
            if (difference.abs().compareTo(cfg.glToleranceAed()) > 0) {
                out.add(finding("R06", type, "GL " + type + " control balance " + ledger + " (timing items " + timed
                        + ") differs from the return's " + returnValue + " by " + difference, difference,
                        "accountType", type, "gl", ledger.toPlainString(), "timingItems", timed.toPlainString(),
                        "returnValue", returnValue.toPlainString(), "difference", difference.toPlainString()));
            }
        });
        return out;
    }

    private List<Finding> r07(Context c) {
        BigDecimal declared = jdbc.sql("""
                SELECT COALESCE(SUM(declared_vat_aed), 0) FROM source_document
                WHERE filer_entity_id = :filer AND source_system = 'CUSTOMS' AND doc_type = 'IMPORT_DECLARATION'
                  AND voided_at IS NULL AND doc_date BETWEEN :from AND :to
                """).param("filer", c.entity().id()).param("from", c.period().periodStart()).param("to", c.period().periodEnd())
                .query(BigDecimal.class).single();
        BigDecimal box6 = c.box("6.vat");
        if (declared.compareTo(box6) == 0) {
            return List.of();
        }
        return List.of(finding("R07", "BOX6", "Import VAT in Box 6 is " + box6 + " but customs declarations show " + declared,
                box6.subtract(declared), "box6", box6.toPlainString(), "customsDeclared", declared.toPlainString()));
    }

    private List<Finding> r08(Context c) {
        record Row(String counterpartyTrn, String name, String trnStatus, LocalDate activeFrom, LocalDate activeTo, LocalDate firstDate, LocalDate lastDate) {}
        List<Finding> out = new ArrayList<>();
        jdbc.sql("""
                SELECT d.counterparty_trn, MIN(d.counterparty_name) AS name, cp.trn_status, cp.active_from, cp.active_to,
                       MIN(d.doc_date) AS first_date, MAX(d.doc_date) AS last_date
                FROM source_document d
                JOIN tax_line l ON l.document_id = d.id AND l.period_id = :period AND l.superseded_at IS NULL
                LEFT JOIN counterparty cp ON cp.trn = d.counterparty_trn
                WHERE d.direction = 'IN' AND d.source_system = 'ERP' AND d.counterparty_trn IS NOT NULL
                GROUP BY d.counterparty_trn, cp.trn_status, cp.active_from, cp.active_to
                """).param("period", c.period().id()).query(Row.class).list().forEach(r -> {
            String problem = null;
            if (r.trnStatus() == null || "UNKNOWN".equals(r.trnStatus())) {
                problem = "is not verified in the counterparty register";
            } else if ("INACTIVE".equals(r.trnStatus())) {
                problem = "is marked inactive in the counterparty register";
            } else if ((r.activeFrom() != null && r.firstDate().isBefore(r.activeFrom()))
                    || (r.activeTo() != null && r.lastDate().isAfter(r.activeTo()))) {
                problem = "was not active on the invoice date";
            }
            Optional<Boolean> peppol = participants.isRegistered(r.counterpartyTrn());
            if (problem != null) {
                out.add(finding("R08", r.counterpartyTrn(), "Supplier TRN " + r.counterpartyTrn() + " (" + r.name() + ") " + problem, null,
                        "trn", r.counterpartyTrn(), "name", r.name(), "registerStatus", String.valueOf(r.trnStatus()),
                        "peppol", peppol.map(String::valueOf).orElse("NOT_CHECKED")));
            }
        });
        return out;
    }

    private List<Finding> r09(Context c) {
        List<Finding> out = new ArrayList<>();
        record Doc(UUID id, String docNumber, String docType, String originalDocNumber) {}
        jdbc.sql("""
                SELECT DISTINCT d.id, d.doc_number, d.doc_type, d.original_doc_number
                FROM source_document d
                JOIN tax_line l ON l.document_id = d.id AND l.period_id = :period AND l.superseded_at IS NULL
                WHERE d.doc_type IN ('CREDIT_NOTE', 'DEBIT_NOTE') AND d.source_system = 'ERP'
                  AND (d.original_doc_number IS NULL OR NOT EXISTS (
                        SELECT 1 FROM source_document o WHERE o.filer_entity_id = d.filer_entity_id AND o.direction = d.direction
                          AND o.doc_number = d.original_doc_number AND o.doc_type NOT IN ('CREDIT_NOTE', 'DEBIT_NOTE')
                          AND o.counterparty_trn IS NOT DISTINCT FROM d.counterparty_trn))
                """).param("period", c.period().id()).query(Doc.class).list()
                .forEach(d -> out.add(finding("R09", "ERP:" + d.id(), d.docType() + " " + d.docNumber() + " refers to "
                        + (d.originalDocNumber() == null ? "no original invoice" : "original " + d.originalDocNumber() + ", which was not found"),
                        null, "docNumber", d.docNumber(), "original", d.originalDocNumber())));
        record Inv(UUID invoiceUuid, String number, String docType, String billingReference) {}
        jdbc.sql("""
                SELECT e.invoice_uuid, e.number, e.doc_type, e.billing_reference FROM einvoice e
                WHERE e.filer_entity_id = :filer AND e.doc_type IN ('CREDIT_NOTE', 'DEBIT_NOTE') AND e.issue_date BETWEEN :from AND :to
                  AND (e.billing_reference IS NULL OR NOT EXISTS (
                        SELECT 1 FROM einvoice o WHERE o.filer_entity_id = e.filer_entity_id AND o.direction = e.direction
                          AND o.number = e.billing_reference AND o.doc_type = 'INVOICE'))
                """).param("filer", c.entity().id()).param("from", c.period().periodStart()).param("to", c.period().periodEnd())
                .query(Inv.class).list()
                .forEach(i -> out.add(finding("R09", "EINV:" + i.invoiceUuid(), "E-invoice " + i.docType() + " " + i.number() + " refers to "
                        + (i.billingReference() == null ? "no original invoice" : "original " + i.billingReference() + ", which was not found"),
                        null, "number", i.number(), "billingReference", i.billingReference())));
        return out;
    }

    private List<Finding> r10(Context c) {
        List<Finding> out = new ArrayList<>();
        record Dup(String direction, String counterpartyTrn, String docType, String docNumber, long copies) {}
        jdbc.sql("""
                SELECT d.direction, d.counterparty_trn, d.doc_type, d.doc_number, COUNT(DISTINCT d.id) AS copies
                FROM source_document d
                WHERE d.filer_entity_id = :filer AND d.source_system = 'ERP' AND d.voided_at IS NULL
                  AND EXISTS (SELECT 1 FROM source_document x
                              JOIN tax_line l ON l.document_id = x.id AND l.period_id = :period AND l.superseded_at IS NULL
                              WHERE x.filer_entity_id = d.filer_entity_id AND x.doc_number = d.doc_number AND x.direction = d.direction)
                GROUP BY d.direction, d.counterparty_trn, d.doc_type, d.doc_number
                HAVING COUNT(DISTINCT d.id) > 1
                """).param("filer", c.entity().id()).param("period", c.period().id()).query(Dup.class).list()
                .forEach(d -> out.add(finding("R10", "ERP:" + d.direction() + ":" + d.counterpartyTrn() + ":" + d.docType() + ":" + d.docNumber(),
                        "Document number " + d.docNumber() + " appears " + d.copies() + " times in the ERP"
                                + (d.counterpartyTrn() == null ? "" : " for " + d.counterpartyTrn()), null,
                        "docNumber", d.docNumber(), "counterpartyTrn", d.counterpartyTrn(), "copies", d.copies())));
        record InvDup(String direction, String issuer, String number, long copies) {}
        jdbc.sql("""
                SELECT e.direction, e.supplier_trn AS issuer, e.number, COUNT(*) AS copies FROM einvoice e
                WHERE e.filer_entity_id = :filer AND e.has_invoice_data
                  AND EXISTS (SELECT 1 FROM einvoice x WHERE x.filer_entity_id = e.filer_entity_id AND x.number = e.number
                              AND x.direction = e.direction AND x.issue_date BETWEEN :from AND :to)
                GROUP BY e.direction, e.supplier_trn, e.number
                HAVING COUNT(*) > 1
                """).param("filer", c.entity().id()).param("from", c.period().periodStart()).param("to", c.period().periodEnd())
                .query(InvDup.class).list()
                .forEach(d -> out.add(finding("R10", "EINV:" + d.direction() + ":" + d.issuer() + ":" + d.number(),
                        "E-invoice number " + d.number() + " appears " + d.copies() + " times from issuer " + d.issuer(), null,
                        "number", d.number(), "issuer", d.issuer(), "copies", d.copies())));
        return out;
    }

    private List<Finding> r11(Context c) {
        return unpaid.outstanding(c.entity().id(), c.period().periodEnd(), c.pack().filing().unpaidSupplierMonths()).stream()
                .filter(p -> p.delta().signum() > 0)
                .map(p -> finding("R11", p.documentId().toString(),
                        "Supplier invoice " + p.docNumber() + " has been unpaid more than " + c.pack().filing().unpaidSupplierMonths()
                                + " months past " + p.paymentDueOn() + ": reverse input VAT of " + p.delta(),
                        p.delta(), "docNumber", p.docNumber(), "paymentDueOn", p.paymentDueOn().toString(),
                        "agreedDateMissing", p.agreedDateMissing(), "toReverse", p.delta().toPlainString()))
                .toList();
    }

    private List<Finding> r12(Context c) {
        record Row(UUID id, String docNumber, LocalDate postingDate, LocalDate taxPoint, BigDecimal vat) {}
        return jdbc.sql("""
                SELECT d.id, d.doc_number, d.posting_date, MIN(l.tax_point) AS tax_point, SUM(l.vat_aed) AS vat
                FROM source_document d
                JOIN tax_line l ON l.document_id = d.id AND l.period_id = :period AND l.superseded_at IS NULL
                WHERE d.posting_date IS NOT NULL AND (d.posting_date < :from OR d.posting_date > :to)
                GROUP BY d.id
                """).param("period", c.period().id()).param("from", c.period().periodStart()).param("to", c.period().periodEnd())
                .query(Row.class).list().stream()
                .map(r -> finding("R12", r.id().toString(), "Document " + r.docNumber() + " has its tax point in this period ("
                        + r.taxPoint() + ") but was posted on " + r.postingDate(), r.vat(),
                        "docNumber", r.docNumber(), "postingDate", r.postingDate().toString(), "taxPoint", r.taxPoint().toString()))
                .toList();
    }

    /** R13 compares the boxes with the ledger, so standard-rated lines with no emirate cannot slip out. */
    private List<Finding> r13(Context c) {
        BigDecimal emirateBoxes = BigDecimal.ZERO;
        for (RulePackDefinition.Box b : c.pack().form().boxes()) {
            if (b.filter() != null && b.filter().emirate() != null) {
                emirateBoxes = emirateBoxes.add(c.box(b.code() + ".amount"));
            }
        }
        record Total(BigDecimal net, long missing) {}
        Total total = jdbc.sql("""
                SELECT COALESCE(SUM(net_aed), 0) AS net, COUNT(*) FILTER (WHERE emirate IS NULL) AS missing FROM tax_line
                WHERE period_id = ? AND superseded_at IS NULL AND memo = false AND line_kind = 'NORMAL'
                  AND direction = 'OUT' AND treatment = 'STANDARD'
                """).param(c.period().id()).query(Total.class).single();
        if (emirateBoxes.compareTo(total.net()) == 0) {
            return List.of();
        }
        return List.of(finding("R13", "EMIRATE_SPLIT", "Emirate boxes total " + emirateBoxes + " but standard-rated supplies total "
                + total.net() + ". " + lines(total.missing()) + " with no emirate.", total.net().subtract(emirateBoxes),
                "emirateBoxes", emirateBoxes.toPlainString(), "standardRatedTotal", total.net().toPlainString(), "linesWithoutEmirate", total.missing()));
    }

    private List<Finding> r14(Context c) {
        Optional<String> previous = jdbc.sql("""
                SELECT v.box_values::text FROM return_version v
                JOIN tax_period p ON p.id = v.period_id
                WHERE p.entity_id = :entity AND p.period_end < :start
                ORDER BY p.period_end DESC, v.version_no DESC LIMIT 1
                """).param("entity", c.entity().id()).param("start", c.period().periodStart()).query(String.class).optional();
        if (previous.isEmpty()) {
            return List.of();
        }
        Map<String, Map<String, String>> prior = JSON.readValue(previous.get(), Map.class);
        BigDecimal threshold = c.pack().reconciliation().boxSwingPct();
        List<String> commented = jdbc.sql("""
                SELECT DISTINCT object_id FROM comment WHERE period_id = ? AND object_type = 'BOX'
                """).param(c.period().id()).query(String.class).list();
        List<Finding> out = new ArrayList<>();
        for (RulePackDefinition.Box b : c.pack().form().boxes()) {
            if (b.isComputed() || commented.contains(b.code())) {
                continue;
            }
            for (String column : b.columns()) {
                if ("adjustment".equals(column)) {
                    continue;
                }
                BigDecimal now = c.box(b.code() + "." + column);
                Map<String, String> old = prior.get(b.code());
                BigDecimal before = old == null ? BigDecimal.ZERO : new BigDecimal(old.getOrDefault(column, "0"));
                if (now.compareTo(before) == 0) {
                    continue;
                }
                BigDecimal swing = before.signum() == 0 ? null
                        : now.subtract(before).abs().multiply(BigDecimal.valueOf(100)).divide(before.abs(), 2, RoundingMode.HALF_UP);
                if (swing == null || swing.compareTo(threshold) > 0) {
                    out.add(finding("R14", b.code() + ":" + column, "Box " + b.code() + " " + column + " moved from " + before + " to " + now
                            + (swing == null ? "" : " (" + swing + "%)") + " with no comment", now.subtract(before),
                            "box", b.code(), "column", column, "previous", before.toPlainString(), "current", now.toPlainString(),
                            "swingPct", swing == null ? null : swing.toPlainString()));
                }
            }
        }
        return out;
    }

    private List<Finding> d01(Context c) {
        record Row(String reason, long rows) {}
        return jdbc.sql("""
                SELECT errors[1] AS reason, COUNT(*) AS rows FROM ingest_row
                WHERE status = 'HELD' AND resolved_at IS NULL AND period_id = ?
                GROUP BY errors[1]
                """).param(c.period().id()).query(Row.class).list().stream()
                .map(r -> finding("D01", r.reason(), rows(r.rows()) + " held: " + r.reason().replace("FX_RATE_MISSING:", "no FX rate for "), null,
                        "reason", r.reason(), "rows", r.rows()))
                .toList();
    }

    private List<Finding> d02(Context c) {
        record Row(UUID batchId, String fileName, long rows, String errors) {}
        return jdbc.sql("""
                SELECT r.batch_id, b.file_name, COUNT(*) AS rows, MIN(array_to_string(r.errors, ', ')) AS errors
                FROM ingest_row r JOIN ingest_batch b ON b.id = r.batch_id
                WHERE r.status = 'REJECTED' AND r.resolved_at IS NULL AND r.entity_id = :entity
                  AND (r.period_id = :period OR (r.period_id IS NULL AND :earliest))
                GROUP BY r.batch_id, b.file_name
                """).param("entity", c.entity().id()).param("period", c.period().id()).param("earliest", c.earliestOpen())
                .query(Row.class).list().stream()
                .map(r -> finding("D02", r.batchId().toString(), rows(r.rows()) + " rejected in " + r.fileName() + ", for example " + r.errors(), null,
                        "file", r.fileName(), "rows", r.rows(), "example", r.errors(), "batchId", r.batchId().toString()))
                .toList();
    }

    private List<Finding> d03(Context c) {
        record Row(String direction, String taxCode, String glAccount, long lines, BigDecimal net, BigDecimal vat) {}
        return jdbc.sql("""
                SELECT direction, tax_code, gl_account, COUNT(*) AS lines, SUM(net_aed) AS net, SUM(vat_aed) AS vat FROM tax_line
                WHERE period_id = ? AND superseded_at IS NULL AND treatment = 'UNCLASSIFIED'
                GROUP BY direction, tax_code, gl_account
                """).param(c.period().id()).query(Row.class).list().stream()
                .map(r -> finding("D03", r.direction() + ":" + r.taxCode() + ":" + r.glAccount(), lines(r.lines()) + " " + r.direction()
                        + " with tax code " + r.taxCode() + " and GL " + r.glAccount() + " have no VAT treatment, so they are in no box",
                        r.vat(), "taxCode", r.taxCode(), "glAccount", r.glAccount(), "lines", r.lines(), "net", r.net().toPlainString(), "vat", r.vat().toPlainString()))
                .toList();
    }

    /** Raised on the earliest open period, because the correction would land there. */
    private List<Finding> d04(Context c) {
        if (!c.earliestOpen()) {
            return List.of();
        }
        record Row(UUID id, LocalDate periodStart, LocalDate periodEnd, long changes) {}
        return jdbc.sql("""
                SELECT p.id, p.period_start, p.period_end,
                       (SELECT COUNT(*) FROM tax_line l WHERE l.period_id = p.id AND l.line_kind = 'NORMAL'
                          AND (l.created_at > h.since OR l.superseded_at > h.since)) AS changes
                FROM tax_period p
                CROSS JOIN LATERAL (SELECT GREATEST(p.locked_at, (SELECT MAX(handled_through) FROM correction_case cc WHERE cc.period_id = p.id)) AS since) h
                WHERE p.entity_id = :entity AND p.locked_at IS NOT NULL
                """).param("entity", c.entity().id()).query(Row.class).list().stream()
                .filter(r -> r.changes() > 0)
                .map(r -> finding("D04", r.id().toString(), "Locked period " + r.periodStart() + " to " + r.periodEnd() + " has " + r.changes()
                        + " changed lines. Open a correction to route the tax difference.", null,
                        "periodId", r.id().toString(), "changes", r.changes()))
                .toList();
    }

    private static final Map<String, String> FLAG_RULES = Map.of(
            "CLASSIFICATION_CONFLICT", "T01",
            "DRC_POSSIBLE", "T02",
            "BLOCKED_BY_DESCRIPTION", "T03",
            "VAT_RATE_MISMATCH", "T04",
            "DRC_SELLER_BOX_UNCONFIRMED", "T05",
            "IMPORT_CHECK_CUSTOMS", "T06",
            "DZ_GOODS_TO_MAINLAND", "T06");

    /** Grouped by code, because a data problem usually affects many lines the same way. */
    private List<Finding> lineFlags(Context c) {
        record Row(String flag, String direction, String taxCode, String glAccount, long lines, BigDecimal vat, BigDecimal net, String[] sample) {}
        return jdbc.sql("""
                SELECT f.flag, l.direction, l.tax_code, l.gl_account, COUNT(*) AS lines, SUM(l.vat_aed) AS vat, SUM(l.net_aed) AS net,
                       (ARRAY_AGG(l.id::text ORDER BY l.id))[1:20] AS sample
                FROM tax_line l CROSS JOIN LATERAL unnest(l.review_flags) AS f(flag)
                WHERE l.period_id = ? AND l.superseded_at IS NULL
                GROUP BY f.flag, l.direction, l.tax_code, l.gl_account
                """).param(c.period().id())
                .query((rs, n) -> new Row(rs.getString("flag"), rs.getString("direction"), rs.getString("tax_code"), rs.getString("gl_account"),
                        rs.getLong("lines"), rs.getBigDecimal("vat"), rs.getBigDecimal("net"), (String[]) rs.getArray("sample").getArray()))
                .list().stream()
                .filter(r -> FLAG_RULES.containsKey(r.flag()))
                .map(r -> finding(FLAG_RULES.get(r.flag()), r.flag() + ":" + r.direction() + ":" + r.taxCode() + ":" + r.glAccount(),
                        lines(r.lines()) + " " + r.direction() + " (tax code " + r.taxCode() + ", GL " + r.glAccount() + "): " + r.flag(), r.vat(),
                        "flag", r.flag(), "taxCode", r.taxCode(), "glAccount", r.glAccount(), "lines", r.lines(),
                        "net", r.net().toPlainString(), "vat", r.vat().toPlainString(), "sampleLineIds", List.of(r.sample())))
                .toList();
    }
}
