package ae.vattool.demo;

import static ae.vattool.demo.Files.row;

import ae.vattool.ingestion.einvoice.EinvoiceEvent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The documents a month produces, kept as objects until they are written out. The same objects then
 * give the ERP rows, the e-invoices the platform would report, the general ledger journal and the
 * bank lines, so all of them agree with each other the way a real client's records would.
 */
final class Docs {

    private Docs() {}

    /** A customer or supplier. A null TRN means unregistered or abroad, and the country says which. */
    record Party(String name, String trn, String country, boolean designatedZone, boolean einvoicing) {
        static Party uae(String name, String trn) {
            return new Party(name, trn, null, false, false);
        }

        static Party onPlatform(String name, String trn) {
            return new Party(name, trn, null, false, true);
        }

        static Party zone(String name, String trn) {
            return new Party(name, trn, "AE", true, false);
        }

        static Party abroad(String name, String country) {
            return new Party(name, null, country, false, false);
        }
    }

    record Line(String description, BigDecimal net, BigDecimal vat, String taxCode, String goods, String est, String gl, BigDecimal recoveryPct) {
        static Line standard(String description, BigDecimal net, String goods, String est, String gl) {
            return new Line(description, net, Gen.vat(net), "S5", goods, est, gl, null);
        }

        static Line coded(String description, BigDecimal net, String code, String goods, String est, String gl) {
            return new Line(description, net, BigDecimal.ZERO.setScale(2), code, goods, est, gl, null);
        }

        Line withRecovery(BigDecimal pct) {
            return new Line(description, net, vat, taxCode, goods, est, gl, pct);
        }

        Line withCode(String code) {
            return new Line(description, net, vat, code, goods, est, gl, recoveryPct);
        }
    }

    /** A sales document: invoice, credit note, daily till total, or tourist refund. */
    static final class Sale {
        String type = "TAX_INVOICE";
        String number;
        LocalDate date;
        Party customer;
        String customerType;
        final List<Line> lines = new ArrayList<>();
        String currency = "AED";
        BigDecimal rate = BigDecimal.ONE;
        LocalDate paidOn;
        String original;
        UUID einvoice;
        boolean uuidInErp;
        LocalDate postingDate;
        boolean export;
        String revenueGl = "4000";
        /** What goes wrong with this invoice's e-invoice: MISSING, FAILED or PENDING. Null when nothing does. */
        String fault;

        Sale(String number, LocalDate date, Party customer, String customerType) {
            this.number = number;
            this.date = date;
            this.customer = customer;
            this.customerType = customerType;
        }

        BigDecimal netAed() {
            return lines.stream().map(l -> Gen.aed(l.net(), rate)).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        BigDecimal vatAed() {
            return lines.stream().map(l -> Gen.aed(l.vat(), rate)).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        BigDecimal grossAed() {
            return netAed().add(vatAed());
        }

        boolean credit() {
            return "CREDIT_NOTE".equals(type) || "TOURIST_REFUND".equals(type);
        }

        boolean needsEinvoice(LocalDate goLive) {
            return goLive != null && !date.isBefore(goLive) && ("B2B".equals(customerType) || "B2G".equals(customerType))
                    && ("TAX_INVOICE".equals(type) || "CREDIT_NOTE".equals(type));
        }
    }

    /** A supplier invoice, paid through the ERP's own status or found later on a bank statement. */
    static final class Purchase {
        String type = "TAX_INVOICE";
        String number;
        LocalDate date;
        Party supplier;
        final List<Line> lines = new ArrayList<>();
        String currency = "AED";
        BigDecimal rate = BigDecimal.ONE;
        LocalDate agreed;
        LocalDate paidOn;
        boolean paidThroughBank;
        boolean hasTaxInvoice = true;
        String original;
        String payableGl = "2100";

        Purchase(String number, LocalDate date, Party supplier) {
            this.number = number;
            this.date = date;
            this.supplier = supplier;
            this.agreed = date.plusDays(30);
        }

        BigDecimal netAed() {
            return lines.stream().map(l -> Gen.aed(l.net(), rate)).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        BigDecimal vatAed() {
            return lines.stream().map(l -> Gen.aed(l.vat(), rate)).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        BigDecimal grossAed() {
            return netAed().add(vatAed());
        }
    }

    record Import(String type, String number, LocalDate date, BigDecimal value, BigDecimal vat, String supplier, String country, String est, String description) {
        static Import declaration(String number, LocalDate date, BigDecimal value, String supplier, String country, String est, String description) {
            return new Import("IMPORT_DECLARATION", number, date, value, Gen.vat(value), supplier, country, est, description);
        }
    }

    record Margin(String number, LocalDate date, String category, BigDecimal purchase, BigDecimal costs, BigDecimal selling, String est, String buyer, String description) {}

    /** One month of a client's documents. */
    static final class Month {
        final YearMonth ym;
        final int offset;
        final List<Sale> sales = new ArrayList<>();
        final List<Purchase> purchases = new ArrayList<>();
        final List<Import> imports = new ArrayList<>();
        final List<Margin> margins = new ArrayList<>();
        /** Rows the ERP exported wrongly, appended to the purchase file as they arrived. */
        final List<Map<String, String>> badPurchaseRows = new ArrayList<>();

        Month(YearMonth ym, int offset) {
            this.ym = ym;
            this.offset = offset;
        }
    }

    // ---- Rows ----

    static List<Map<String, String>> arRows(List<Sale> sales) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (Sale s : sales) {
            int n = 1;
            for (Line l : s.lines) {
                rows.add(row("doc_type", s.type, "doc_number", s.number, "doc_date", s.date, "net_amount", l.net(), "vat_amount", l.vat(),
                        "line_no", n++, "currency", "AED".equals(s.currency) ? null : s.currency,
                        "counterparty_name", s.customer == null ? null : s.customer.name(), "counterparty_trn", s.customer == null ? null : s.customer.trn(),
                        "counterparty_country", s.customer == null ? null : s.customer.country(), "customer_type", s.customerType,
                        "establishment_code", l.est(), "gl_account", l.gl() == null ? s.revenueGl : l.gl(), "tax_code", l.taxCode(),
                        "description", l.description(), "goods_or_services", l.goods(), "original_doc_number", s.original,
                        "paid_date", s.paidOn, "einvoice_uuid", s.uuidInErp ? s.einvoice : null, "posting_date", s.postingDate));
            }
        }
        return rows;
    }

    static List<Map<String, String>> apRows(List<Purchase> purchases) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (Purchase p : purchases) {
            int n = 1;
            for (Line l : p.lines) {
                rows.add(row("doc_type", p.type, "doc_number", p.number, "doc_date", p.date, "net_amount", l.net(), "vat_amount", l.vat(),
                        "line_no", n++, "currency", "AED".equals(p.currency) ? null : p.currency,
                        "counterparty_name", p.supplier.name(), "counterparty_trn", p.supplier.trn(), "counterparty_country", p.supplier.country(),
                        "establishment_code", l.est(), "gl_account", l.gl(), "tax_code", l.taxCode(), "description", l.description(),
                        "goods_or_services", l.goods(), "original_doc_number", p.original, "agreed_payment_date", "CREDIT_NOTE".equals(p.type) ? null : p.agreed,
                        "paid_date", p.paidThroughBank ? null : p.paidOn, "has_tax_invoice", p.hasTaxInvoice ? "true" : "false",
                        "recovery_pct", l.recoveryPct()));
            }
        }
        return rows;
    }

    static List<Map<String, String>> customsRows(List<Import> imports) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (Import i : imports) {
            rows.add(row("doc_type", i.type(), "declaration_number", i.number(), "declaration_date", i.date(), "customs_value_aed", i.value(),
                    "vat_aed", i.vat(), "establishment_code", i.est(), "supplier_name", i.supplier(), "supplier_country", i.country(),
                    "description", i.description()));
        }
        return rows;
    }

    static List<Map<String, String>> marginRows(List<Margin> margins) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (Margin m : margins) {
            rows.add(row("doc_number", m.number(), "doc_date", m.date(), "category", m.category(), "purchase_price_aed", m.purchase(),
                    "selling_price_aed", m.selling(), "acquisition_costs_aed", m.costs(), "establishment_code", m.est(),
                    "counterparty_name", m.buyer(), "description", m.description()));
        }
        return rows;
    }

    /** The double entry behind each document, for the general ledger section of the FTA Audit File. */
    static List<Map<String, String>> journalRows(Month m, String bankAccount) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (Sale s : m.sales) {
            String id = "SJ-" + s.number;
            String party = s.customer == null ? "Walk-in customers" : s.customer.name();
            boolean credit = s.credit();
            String debitAccount = "B2C_SALE".equals(s.type) ? "1150" : "1100";
            rows.add(journal(id, 1, s.date, debitAccount, "B2C_SALE".equals(s.type) ? "Card settlements receivable" : "Trade receivables", s.number, party,
                    credit ? null : s.grossAed(), credit ? s.grossAed() : null));
            rows.add(journal(id, 2, s.date, s.revenueGl, "Sales", s.number, party, credit ? s.netAed() : null, credit ? null : s.netAed()));
            if (s.vatAed().signum() != 0) {
                rows.add(journal(id, 3, s.date, "2201", "VAT output", s.number, party, credit ? s.vatAed() : null, credit ? null : s.vatAed()));
            }
        }
        for (Purchase p : m.purchases) {
            String id = "PJ-" + p.number + "-" + Math.abs(p.supplier.name().hashCode() % 1000);
            boolean credit = "CREDIT_NOTE".equals(p.type);
            String gl = p.lines.getFirst().gl() == null ? "5000" : p.lines.getFirst().gl();
            rows.add(journal(id, 1, p.date, gl, "Expense and stock", p.number, p.supplier.name(), credit ? null : p.netAed(), credit ? p.netAed() : null));
            if (p.vatAed().signum() != 0) {
                rows.add(journal(id, 2, p.date, "1201", "VAT input", p.number, p.supplier.name(), credit ? null : p.vatAed(), credit ? p.vatAed() : null));
            }
            rows.add(journal(id, 3, p.date, p.payableGl, "Trade payables", p.number, p.supplier.name(), credit ? p.grossAed() : null, credit ? null : p.grossAed()));
        }
        for (Import i : m.imports) {
            String id = "CJ-" + i.number();
            rows.add(journal(id, 1, i.date(), "1300", "Inventory in transit", i.number(), i.supplier(), i.value(), null));
            rows.add(journal(id, 2, i.date(), "2110", "Customs and freight payable", i.number(), i.supplier(), null, i.value()));
        }
        for (Margin g : m.margins) {
            String id = "MJ-" + g.number();
            rows.add(journal(id, 1, g.date(), bankAccount, "Bank", g.number(), g.buyer(), g.selling(), null));
            rows.add(journal(id, 2, g.date(), "4100", "Sales under the margin scheme", g.number(), g.buyer(), null, g.selling()));
        }
        return rows;
    }

    private static Map<String, String> journal(String transaction, int line, LocalDate date, String account, String accountName, String document, String party,
                                               BigDecimal debit, BigDecimal credit) {
        return row("transaction_date", date, "account_id", account, "transaction_id", transaction, "debit", debit == null ? BigDecimal.ZERO : debit,
                "credit", credit == null ? BigDecimal.ZERO : credit, "line_no", line, "account_name", accountName, "description", document,
                "name", party, "source_document_id", document, "source_type", transaction.substring(0, 2));
    }

    // ---- E-invoices as the ASP platform reports them ----

    static List<EinvoiceEvent> issued(Sale s, String filerTrn) {
        int sign = "CREDIT_NOTE".equals(s.type) ? -1 : 1;
        BigDecimal net = s.netAed().multiply(BigDecimal.valueOf(sign));
        BigDecimal vat = s.vatAed().multiply(BigDecimal.valueOf(sign));
        EinvoiceEvent.Invoice invoice = new EinvoiceEvent.Invoice("CREDIT_NOTE".equals(s.type) ? "CREDIT_NOTE" : "INVOICE", s.number, s.date, filerTrn,
                s.customer == null ? null : s.customer.trn(), s.currency, net, vat, s.original);
        return List.of(event(EinvoiceEvent.ISSUED, s.einvoice, filerTrn, at(s.date, 10), invoice, null),
                event(EinvoiceEvent.CORNER5, s.einvoice, filerTrn, at(s.date.plusDays(1), 9), null, "REPORTED"));
    }

    static List<EinvoiceEvent> received(Purchase p, String filerTrn) {
        UUID id = Gen.uuid("received", p.supplier.trn(), p.number);
        EinvoiceEvent.Invoice invoice = new EinvoiceEvent.Invoice("INVOICE", p.number, p.date, p.supplier.trn(), filerTrn, p.currency, p.netAed(), p.vatAed(), null);
        return List.of(event(EinvoiceEvent.RECEIVED, id, filerTrn, at(p.date, 11), invoice, null),
                event(EinvoiceEvent.CORNER5, id, filerTrn, at(p.date.plusDays(1), 8), null, "REPORTED"));
    }

    static EinvoiceEvent event(String type, UUID invoice, String filerTrn, OffsetDateTime at, EinvoiceEvent.Invoice data, String corner5) {
        return new EinvoiceEvent("1.0", Gen.uuid("event", type, String.valueOf(invoice), at.toString(), String.valueOf(corner5)), type, at, Kit.TENANT, filerTrn, invoice,
                data, null, corner5 == null ? null : new EinvoiceEvent.Corner5(corner5));
    }

    static OffsetDateTime at(LocalDate day, int hour) {
        return day.atTime(hour, 0).atOffset(ZoneOffset.ofHours(4));
    }
}
