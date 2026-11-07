package ae.vattool.ingestion;

import ae.vattool.platform.web.DomainException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Versioned upload templates (ING-03). A new version is added here; existing versions never change. */
public final class Templates {

    public static final String CURRENT_VERSION = "v1";

    public enum Kind { AR, AP, VAT_SUBLEDGER, GL, CUSTOMS, FX, OPENING_AP, GL_JOURNAL, MARGIN, OPENING_ASSETS, OPENING_APPORTIONMENT, UBL }

    private static final List<String> TX_REQUIRED = List.of(
            "doc_type", "doc_number", "doc_date", "net_amount", "vat_amount");
    private static final List<String> TX_OPTIONAL = List.of(
            "line_no", "currency", "tax_point_date", "supply_date", "payment_received_date", "posting_date",
            "counterparty_name", "counterparty_trn", "counterparty_country", "customer_type", "establishment_code",
            "gl_account", "tax_code", "description", "goods_or_services", "original_doc_number", "agreed_payment_date",
            "paid_date", "paid_amount", "einvoice_uuid", "has_tax_invoice", "recovery_pct", "void");

    private record Spec(List<String> required, List<String> optional) {}

    private static final Map<Kind, Spec> V1 = Map.ofEntries(
            Map.entry(Kind.AR, new Spec(TX_REQUIRED, TX_OPTIONAL)),
            Map.entry(Kind.AP, new Spec(TX_REQUIRED, TX_OPTIONAL)),
            Map.entry(Kind.VAT_SUBLEDGER, new Spec(concat(List.of("direction"), TX_REQUIRED), TX_OPTIONAL)),
            Map.entry(Kind.GL, new Spec(List.of("account_code", "account_type", "period_end", "balance_aed"), List.of())),
            Map.entry(Kind.CUSTOMS, new Spec(
                    List.of("doc_type", "declaration_number", "declaration_date", "customs_value_aed", "vat_aed"),
                    List.of("line_no", "establishment_code", "supplier_name", "supplier_country", "description", "recovery_pct", "void"))),
            Map.entry(Kind.FX, new Spec(List.of("currency", "rate_date", "aed_per_unit"), List.of("source"))),
            Map.entry(Kind.OPENING_AP, new Spec(
                    List.of("doc_number", "doc_date", "agreed_payment_date", "gross_aed", "recoverable_vat_aed"),
                    List.of("counterparty_trn", "counterparty_name", "paid_amount_aed", "paid_date"))),
            Map.entry(Kind.GL_JOURNAL, new Spec(
                    List.of("transaction_date", "account_id", "transaction_id", "debit", "credit"),
                    List.of("line_no", "account_name", "description", "name", "source_document_id", "source_type", "balance"))),
            Map.entry(Kind.MARGIN, new Spec(
                    List.of("doc_number", "doc_date", "category", "purchase_price_aed", "selling_price_aed"),
                    List.of("acquisition_costs_aed", "establishment_code", "counterparty_name", "counterparty_trn", "description", "void"))),
            Map.entry(Kind.OPENING_ASSETS, new Spec(
                    List.of("reference", "description", "category", "cost_aed", "input_vat_aed", "initial_recovery_pct", "first_used_on"), List.of())),
            Map.entry(Kind.OPENING_APPORTIONMENT, new Spec(
                    List.of("tax_year_start", "direct_recoverable_aed", "direct_exempt_aed", "taxable_supplies_aed", "total_supplies_aed", "residual_input_aed", "recovered_aed"),
                    List.of())));

    private Templates() {}

    private static List<String> concat(List<String> a, List<String> b) {
        return java.util.stream.Stream.concat(a.stream(), b.stream()).toList();
    }

    public static Kind parseKind(String kind) {
        try {
            return Kind.valueOf(kind.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw DomainException.invalid("KIND_INVALID", "Unknown data kind: " + kind);
        }
    }

    private static Spec spec(Kind kind, String version) {
        if (!CURRENT_VERSION.equals(version)) {
            throw DomainException.invalid("TEMPLATE_VERSION_UNKNOWN", "Unknown template version: " + version);
        }
        Spec spec = V1.get(kind);
        if (spec == null) {
            // UBL arrives through its own route, as XML rather than a template.
            throw DomainException.invalid("KIND_INVALID", "There is no upload template for " + kind);
        }
        return spec;
    }

    /** Refuses a file with missing columns outright, and any unknown column, since it may be a typo. */
    public static void checkHeaders(Kind kind, String version, Set<String> headers) {
        Spec spec = spec(kind, version);
        List<String> missing = spec.required().stream().filter(h -> !headers.contains(h)).toList();
        if (!missing.isEmpty()) {
            throw DomainException.invalid("TEMPLATE_COLUMNS_MISSING", "Missing required columns: " + String.join(", ", missing));
        }
        Set<String> known = new LinkedHashSet<>(spec.required());
        known.addAll(spec.optional());
        List<String> unknown = headers.stream().filter(h -> !known.contains(h)).toList();
        if (!unknown.isEmpty()) {
            throw DomainException.invalid("TEMPLATE_COLUMNS_UNKNOWN", "Columns not in the template: " + String.join(", ", unknown));
        }
    }

    public static String headerLine(Kind kind, String version) {
        Spec spec = spec(kind, version);
        return String.join(",", concat(spec.required(), spec.optional()));
    }
}
