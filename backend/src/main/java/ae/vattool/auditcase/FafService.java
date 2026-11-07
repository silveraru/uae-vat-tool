package ae.vattool.auditcase;

import ae.vattool.masterdata.LegalEntity;
import ae.vattool.platform.documents.GeneratedDocuments;
import ae.vattool.platform.web.DomainException;
import ae.vattool.returns.ReturnService;
import ae.vattool.returns.BoxCalculator.BoxValue;
import ae.vattool.rules.RulePackDefinition;
import ae.vattool.rules.RulePackStore;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The FTA Audit File (AUD-01). Its tables and field order come from Appendix 5 of the FTA's
 * Requirements Document for Tax Accounting Software, and what the document leaves open is in the rule
 * pack. A person must see what the file is missing or disagrees with, so warnings come back with it.
 */
@Service
public class FafService {

    static final String PRODUCT_VERSION = "UAE VAT Tool 0.1.0";
    private static final String MISSING_DATE = "31-12-9999";

    public record Result(GeneratedDocuments.Doc document, int purchaseRows, int supplyRows, int ledgerRows, BigDecimal purchaseVat, BigDecimal supplyVat,
                         List<String> warnings) {}

    private record Agent(String agencyName, String tan, String agentName, String taan) {}

    private record Line(String partyName, String partyCountry, String partyTrn, LocalDate docDate, String docNumber, String sourceSystem, UUID documentId,
                        int lineNo, String description, BigDecimal netAed, BigDecimal vatAed, String treatment, String lineKind, String direction,
                        String currency, BigDecimal netDoc, BigDecimal vatDoc) {}

    private record Ledger(LocalDate transactionDate, String accountId, String accountName, String description, String partyName, String transactionId,
                          String sourceDocumentId, String sourceType, BigDecimal debitAed, BigDecimal creditAed, BigDecimal balanceAed) {}

    private final JdbcClient jdbc;
    private final RulePackStore packs;
    private final GeneratedDocuments documents;

    public FafService(JdbcClient jdbc, RulePackStore packs, GeneratedDocuments documents) {
        this.jdbc = jdbc;
        this.packs = packs;
        this.documents = documents;
    }

    /** Builds the file for whole tax periods within the range. objectId says what the file was for: a case, or the entity. */
    @Transactional
    public Result generate(UUID entityId, LocalDate from, LocalDate to, Object objectId) {
        if (from == null || to == null || to.isBefore(from)) {
            throw DomainException.invalid("RANGE_INVALID", "Give a start date on or before the end date");
        }
        LegalEntity e = jdbc.sql(LegalEntity.SELECT + " WHERE id = ?").param(entityId).query(LegalEntity.class).optional()
                .orElseThrow(() -> DomainException.notFound("Entity"));
        if (!e.isFiler()) {
            throw DomainException.invalid("LOAD_TO_FILER", "An audit file is produced for the group representative, not a member");
        }
        RulePackStore.ActivePack pack = packs.forDate(to);
        RulePackDefinition.Faf faf = RulePackDefinition.require(pack.definition().faf(), "FAF");
        DateTimeFormatter dates = DateTimeFormatter.ofPattern(faf.dateFormat());
        Agent agent = jdbc.sql("""
                SELECT ay.name AS agency_name, ay.tan, a.name AS agent_name, a.taan
                FROM entity_tax_agent l JOIN tax_agent a ON a.id = l.agent_id JOIN tax_agency ay ON ay.id = a.agency_id
                WHERE l.entity_id = :entity AND l.valid_from <= :to AND (l.valid_to IS NULL OR l.valid_to >= :from)
                ORDER BY l.valid_from DESC LIMIT 1
                """).param("entity", entityId).param("to", to).param("from", from).query(Agent.class).optional().orElse(new Agent(null, null, null, null));

        List<Line> lines = lines(entityId, from, to);
        List<Ledger> ledger = jdbc.sql("""
                SELECT transaction_date, account_id, account_name, description, party_name, transaction_id, source_document_id, source_type, debit_aed, credit_aed, balance_aed
                FROM gl_journal_line WHERE entity_id = :entity AND transaction_date BETWEEN :from AND :to
                ORDER BY transaction_date, account_id, transaction_id, line_no
                """).param("entity", entityId).param("from", from).param("to", to).query(Ledger.class).list();

        List<String> warnings = new ArrayList<>();
        StringBuilder csv = new StringBuilder();
        section(csv, faf, "company", true);
        row(csv, "TaxablePersonNameEn", "TaxablePersonNameAr", "TRN", "TaxAgencyName", "TAN", "TaxAgentName", "TAAN", "PeriodStart", "PeriodEnd", "FAFCreationDate", "ProductVersion", "FAFVersion");
        row(csv, str(e.legalName(), 100), str(e.legalNameAr(), 100), str(e.trn(), 15), str(agent.agencyName(), 100), str(agent.tan(), 20), str(agent.agentName(), 100), str(agent.taan(), 20),
                from.format(dates), to.format(dates), LocalDate.now().format(dates), PRODUCT_VERSION, faf.version());
        section(csv, faf, "company", false);

        section(csv, faf, "purchases", true);
        row(csv, "SupplierName", "SupplierCountry", "SupplierTRN", "InvoiceDate", "InvoiceNo", "PermitNo", "TransactionID", "LineNo", "ProductDescription", "PurchaseValueAED", "VATValueAED", "TaxCode", "FCYCode", "PurchaseFCY", "VATFCY");
        BigDecimal purchaseNet = BigDecimal.ZERO;
        BigDecimal purchaseVat = BigDecimal.ZERO;
        int purchaseRows = 0;
        for (Line l : lines) {
            if (!"IN".equals(l.direction())) {
                continue;
            }
            boolean foreign = !"AED".equals(l.currency());
            row(csv, str(l.partyName(), 100), str(country(l.partyCountry(), l.partyTrn()), 50), str(l.partyTrn(), 15), date(l.docDate(), dates), str(number(l), 50),
                    "CUSTOMS".equals(l.sourceSystem()) ? str(l.docNumber(), 20) : "", transactionId(l), String.valueOf(l.lineNo()), str(l.description(), 250),
                    money(l.netAed()), money(l.vatAed()), taxCode(faf, l), foreign ? l.currency() : "XXX", foreign ? money(l.netDoc()) : "0.00", foreign ? money(l.vatDoc()) : "0.00");
            purchaseNet = purchaseNet.add(l.netAed());
            purchaseVat = purchaseVat.add(l.vatAed());
            purchaseRows++;
        }
        row(csv, "PurchaseTotalAED", "VATTotalAED", "TransactionCountTotal");
        row(csv, money(purchaseNet), money(purchaseVat), String.valueOf(purchaseRows));
        section(csv, faf, "purchases", false);

        section(csv, faf, "supplies", true);
        row(csv, "CustomerName", "CustomerCountry", "CustomerTRN", "InvoiceDate", "InvoiceNo", "TransactionID", "LineNo", "ProductDescription", "SupplyValueAED", "VATValueAED", "TaxCode", "Country", "FCYCode", "SupplyFCY", "VATFCY");
        BigDecimal supplyNet = BigDecimal.ZERO;
        BigDecimal supplyVat = BigDecimal.ZERO;
        int supplyRows = 0;
        for (Line l : lines) {
            if (!"OUT".equals(l.direction())) {
                continue;
            }
            boolean foreign = !"AED".equals(l.currency());
            String partyCountry = country(l.partyCountry(), l.partyTrn());
            row(csv, str(l.partyName(), 100), str(partyCountry, 50), str(l.partyTrn(), 15), date(l.docDate(), dates), str(number(l), 50), transactionId(l), String.valueOf(l.lineNo()),
                    str(l.description(), 250), money(l.netAed()), money(l.vatAed()), taxCode(faf, l), "AE".equals(partyCountry) ? "" : str(partyCountry, 50),
                    foreign ? l.currency() : "XXX", foreign ? money(l.netDoc()) : "0.00", foreign ? money(l.vatDoc()) : "0.00");
            supplyNet = supplyNet.add(l.netAed());
            supplyVat = supplyVat.add(l.vatAed());
            supplyRows++;
        }
        row(csv, "SupplyTotalAED", "VATTotalAED", "TransactionCountTotal");
        row(csv, money(supplyNet), money(supplyVat), String.valueOf(supplyRows));
        section(csv, faf, "supplies", false);

        section(csv, faf, "ledger", true);
        row(csv, "TransactionDate", "AccountID", "AccountName", "TransactionDescription", "Name", "TransactionID", "SourceDocumentID", "SourceType", "Debit", "Credit", "Balance");
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        for (Ledger g : ledger) {
            row(csv, date(g.transactionDate(), dates), str(g.accountId(), 20), str(g.accountName(), 100), str(g.description(), 250), str(g.partyName(), 100), str(g.transactionId(), 50),
                    str(g.sourceDocumentId(), 50), str(g.sourceType(), 20), money(g.debitAed()), money(g.creditAed()), money(g.balanceAed()));
            debit = debit.add(g.debitAed());
            credit = credit.add(g.creditAed());
        }
        row(csv, "TotalDebit", "TotalCredit", "TransactionCountTotal", "GLTCurrency");
        row(csv, money(debit), money(credit), String.valueOf(ledger.size()), "AED");
        section(csv, faf, "ledger", false);

        if (ledger.isEmpty()) {
            warnings.add("NO_GENERAL_LEDGER");
        } else if (debit.compareTo(credit) != 0) {
            warnings.add("LEDGER_NOT_BALANCED:" + debit.toPlainString() + ":" + credit.toPlainString());
        }
        warnings.addAll(checkAgainstReturns(entityId, from, to, supplyVat, purchaseVat));

        boolean draft = !pack.meta().signedOff();
        String name = "FAF_" + e.trn() + "_" + from + "_" + to + ".csv";
        GeneratedDocuments.Doc doc = documents.store(entityId, null, "FAF", objectId, name, "text/csv", csv.toString().getBytes(StandardCharsets.UTF_8), draft);
        return new Result(doc, purchaseRows, supplyRows, ledger.size(), purchaseVat, supplyVat, warnings);
    }

    private List<Line> lines(UUID entityId, LocalDate from, LocalDate to) {
        // Sorted by invoice date, then line number within an invoice, as the FTA document asks.
        return jdbc.sql("""
                SELECT COALESCE(d.counterparty_name, cp.name) AS party_name, COALESCE(l.counterparty_country, d.counterparty_country) AS party_country,
                       COALESCE(l.counterparty_trn, d.counterparty_trn) AS party_trn, COALESCE(d.doc_date, l.tax_point) AS doc_date, d.doc_number,
                       l.source_system, l.document_id, l.line_no, l.description, l.net_aed, l.vat_aed, l.treatment, l.line_kind, l.direction, l.currency, l.net_doc, l.vat_doc
                FROM tax_line l
                JOIN tax_period p ON p.id = l.period_id
                LEFT JOIN source_document d ON d.id = l.document_id
                LEFT JOIN counterparty cp ON cp.trn = COALESCE(l.counterparty_trn, d.counterparty_trn)
                WHERE l.filer_entity_id = :entity AND l.superseded_at IS NULL AND l.memo = false
                  AND p.period_start >= :from AND p.period_end <= :to
                ORDER BY COALESCE(d.doc_date, l.tax_point), d.doc_number, l.line_no, l.id
                """).param("entity", entityId).param("from", from).param("to", to).query(Line.class).list();
    }

    /**
     * Whatever the file lists should add up to the returns for the same periods, unless a correction
     * changed the ledger afterwards. A difference does not stop the file: the reviewer has to know.
     */
    private List<String> checkAgainstReturns(UUID entityId, LocalDate from, LocalDate to, BigDecimal supplyVat, BigDecimal purchaseVat) {
        record Row(UUID periodId, LocalDate periodStart, String boxValues) {}
        List<Row> rows = jdbc.sql("""
                SELECT p.id AS period_id, p.period_start,
                       (SELECT v.box_values::text FROM return_version v WHERE v.period_id = p.id
                        ORDER BY (v.id = p.locked_version_id) DESC, v.version_no DESC LIMIT 1) AS box_values
                FROM tax_period p WHERE p.entity_id = :entity AND p.period_start >= :from AND p.period_end <= :to
                """).param("entity", entityId).param("from", from).param("to", to).query(Row.class).list();
        List<String> warnings = new ArrayList<>();
        if (rows.isEmpty()) {
            warnings.add("NO_PERIODS_IN_RANGE");
            return warnings;
        }
        BigDecimal returnSupplyVat = BigDecimal.ZERO;
        int openPeriods = 0;
        for (Row r : rows) {
            if (r.boxValues() == null) {
                openPeriods++;
                continue;
            }
            Map<String, BoxValue> boxes = ReturnService.parseValues(r.boxValues());
            for (Map.Entry<String, BoxValue> b : boxes.entrySet()) {
                if (b.getKey().matches("1[a-g]")) {
                    returnSupplyVat = returnSupplyVat.add(b.getValue().vat()).add(b.getValue().adjustment());
                } else if ("2".equals(b.getKey())) {
                    returnSupplyVat = returnSupplyVat.add(b.getValue().vat());
                }
            }
        }
        if (openPeriods > 0) {
            // With a period missing, the totals cannot agree, so that is the only thing worth saying.
            warnings.add("PERIODS_WITHOUT_RETURN:" + openPeriods);
        } else if (returnSupplyVat.compareTo(supplyVat) != 0) {
            warnings.add("SUPPLY_VAT_DIFFERS_FROM_RETURNS:" + supplyVat.toPlainString() + ":" + returnSupplyVat.toPlainString());
        }
        return warnings;
    }

    private static void section(StringBuilder csv, RulePackDefinition.Faf faf, String name, boolean start) {
        List<String> marker = faf.markers() == null ? null : faf.markers().get(name);
        if (marker != null && marker.size() == 2) {
            csv.append(marker.get(start ? 0 : 1)).append("\r\n");
        }
    }

    private static void row(StringBuilder csv, String... cells) {
        csv.append(String.join(",", cells)).append("\r\n");
    }

    /** The format has no quoting: a comma in a value would shift the columns, so it is replaced. */
    static String str(String value, int max) {
        if (value == null) {
            return "";
        }
        String clean = value.replace(',', ';').replace("\r", " ").replace("\n", " ").trim();
        return clean.length() > max ? clean.substring(0, max) : clean;
    }

    private static String date(LocalDate d, DateTimeFormatter f) {
        return d == null ? MISSING_DATE : d.format(f);
    }

    private static String money(BigDecimal b) {
        return b == null ? "0.00" : b.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static String number(Line l) {
        return l.docNumber() != null ? l.docNumber() : l.description();
    }

    private static String transactionId(Line l) {
        return l.documentId() == null ? "" : l.documentId().toString().substring(0, 20);
    }

    private static String country(String country, String trn) {
        if (country != null && !country.isBlank()) {
            return country;
        }
        return trn != null ? "AE" : "";
    }

    private static String taxCode(RulePackDefinition.Faf faf, Line l) {
        if ("ADJUSTMENT".equals(l.lineKind())) {
            return "IN".equals(l.direction()) ? faf.inputAmendmentCode() : faf.outputAmendmentCode();
        }
        return faf.taxCodes().getOrDefault(l.treatment(), "");
    }
}
