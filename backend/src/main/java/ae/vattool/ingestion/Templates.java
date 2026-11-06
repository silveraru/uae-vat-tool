package ae.vattool.ingestion;

import ae.vattool.platform.web.DomainException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Versioned upload templates (ING-03). A new version is added here; existing versions never change. */
public final class Templates {

    public static final String CURRENT_VERSION = "v1";

    public enum Kind { AR, AP, VAT_SUBLEDGER, GL, CUSTOMS, FX, OPENING_AP }

    private static final List<String> TX_REQUIRED = List.of(
            "doc_type", "doc_number", "doc_date", "net_amount", "vat_amount");
    private static final List<String> TX_OPTIONAL = List.of(
            "line_no", "currency", "tax_point_date", "supply_date", "payment_received_date", "posting_date",
            "counterparty_name", "counterparty_trn", "counterparty_country", "customer_type", "establishment_code",
            "gl_account", "tax_code", "description", "goods_or_services", "original_doc_number", "agreed_payment_date",
            "paid_date", "paid_amount", "einvoice_uuid", "has_tax_invoice", "recovery_pct", "void");

    private record Spec(List<String> required, List<String> optional) {}

    private static final Map<Kind, Spec> V1 = Map.of(
            Kind.AR, new Spec(TX_REQUIRED, TX_OPTIONAL),
            Kind.AP, new Spec(TX_REQUIRED, TX_OPTIONAL),
            Kind.VAT_SUBLEDGER, new Spec(concat(List.of("direction"), TX_REQUIRED), TX_OPTIONAL),
            Kind.GL, new Spec(List.of("account_code", "account_type", "period_end", "balance_aed"), List.of()),
            Kind.CUSTOMS, new Spec(
                    List.of("doc_type", "declaration_number", "declaration_date", "customs_value_aed", "vat_aed"),
                    List.of("line_no", "establishment_code", "supplier_name", "supplier_country", "description", "recovery_pct", "void")),
            Kind.FX, new Spec(List.of("currency", "rate_date", "aed_per_unit"), List.of("source")),
            Kind.OPENING_AP, new Spec(
                    List.of("doc_number", "doc_date", "agreed_payment_date", "gross_aed", "recoverable_vat_aed"),
                    List.of("counterparty_trn", "counterparty_name", "paid_amount_aed", "paid_date")));

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
        return V1.get(kind);
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
