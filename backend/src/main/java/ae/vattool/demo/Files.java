package ae.vattool.demo;

import ae.vattool.ingestion.Templates;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** The files a client would send: template CSVs, bank statements in both formats, and UBL e-invoices. */
final class Files {

    private Files() {}

    // ---- Template CSV ----

    /** A template file in the column order of the template, so it reads like the downloaded one. */
    static byte[] csv(Templates.Kind kind, List<Map<String, String>> rows) {
        String header = Templates.headerLine(kind, Templates.CURRENT_VERSION);
        List<String> columns = Arrays.asList(header.split(","));
        Set<String> known = Set.copyOf(columns);
        StringBuilder b = new StringBuilder(header).append('\n');
        for (Map<String, String> row : rows) {
            for (String key : row.keySet()) {
                if (!known.contains(key)) {
                    throw new IllegalArgumentException("Column " + key + " is not in the " + kind + " template");
                }
            }
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    b.append(',');
                }
                b.append(cell(row.get(columns.get(i))));
            }
            b.append('\n');
        }
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String cell(String v) {
        if (v == null) {
            return "";
        }
        return v.contains(",") || v.contains("\"") || v.contains("\n") ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }

    /** A row builder, so the generators read as a list of named fields. */
    static Map<String, String> row(Object... pairs) {
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            Object v = pairs[i + 1];
            if (v != null) {
                row.put(String.valueOf(pairs[i]), v instanceof BigDecimal d ? d.setScale(2, RoundingMode.HALF_UP).toPlainString() : String.valueOf(v));
            }
        }
        return row;
    }

    // ---- Bank statements ----

    record BankLine(LocalDate booked, BigDecimal amount, boolean debit, String reference, String counterparty, String narrative) {}

    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");
    private static final DateTimeFormatter MMDD = DateTimeFormatter.ofPattern("MMdd");

    /** SWIFT MT940 with a decimal comma. The closing balance can be given wrong on purpose, to show the balance check. */
    static byte[] mt940(String reference, String account, int sequence, LocalDate from, LocalDate to, BigDecimal opening,
                        List<BankLine> lines, BigDecimal closingOverride) {
        StringBuilder b = new StringBuilder();
        b.append(":20:").append(reference).append('\n');
        b.append(":25:").append(account).append('\n');
        b.append(":28C:").append(String.format("%05d/001", sequence)).append('\n');
        b.append(":60F:").append(balance(opening, from)).append('\n');
        BigDecimal running = opening;
        for (BankLine l : lines) {
            String ref = l.reference() == null ? "NONREF" : l.reference().replace("/", "-").replace("\n", " ");
            b.append(":61:").append(l.booked().format(YYMMDD)).append(l.booked().format(MMDD)).append(l.debit() ? 'D' : 'C')
                    .append(comma(l.amount())).append("NTRF").append(ref).append("//").append(bankRef(l)).append('\n');
            String narrative = (l.counterparty() == null ? "" : l.counterparty() + " ") + (l.narrative() == null ? "" : l.narrative());
            b.append(":86:").append(narrative.trim().replace("\n", " ")).append('\n');
            running = l.debit() ? running.subtract(l.amount()) : running.add(l.amount());
        }
        b.append(":62F:").append(balance(closingOverride != null ? closingOverride : running, to)).append('\n');
        b.append("-\n");
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String balance(BigDecimal amount, LocalDate on) {
        return (amount.signum() < 0 ? "D" : "C") + on.format(YYMMDD) + "AED" + comma(amount.abs());
    }

    private static String comma(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }

    private static String bankRef(BankLine l) {
        return "B" + Integer.toHexString((l.booked().toString() + l.amount() + l.reference() + l.narrative()).hashCode()).toUpperCase();
    }

    /** ISO 20022 CAMT.053, as the larger UAE banks send it. */
    static byte[] camt053(String id, String iban, LocalDate from, LocalDate to, BigDecimal opening, List<BankLine> lines) {
        BigDecimal closing = opening;
        StringBuilder entries = new StringBuilder();
        for (BankLine l : lines) {
            closing = l.debit() ? closing.subtract(l.amount()) : closing.add(l.amount());
            entries.append("""
                          <Ntry>
                            <Amt Ccy="AED">%s</Amt>
                            <CdtDbtInd>%s</CdtDbtInd>
                            <Sts>BOOK</Sts>
                            <BookgDt><Dt>%s</Dt></BookgDt>
                            <ValDt><Dt>%s</Dt></ValDt>
                            <AcctSvcrRef>%s</AcctSvcrRef>
                            <NtryDtls><TxDtls>
                              <Refs><EndToEndId>%s</EndToEndId></Refs>
                              <RltdPties><%s><Nm>%s</Nm></%s></RltdPties>
                              <RmtInf><Ustrd>%s</Ustrd></RmtInf>
                            </TxDtls></NtryDtls>
                          </Ntry>
                    """.formatted(plain(l.amount()), l.debit() ? "DBIT" : "CRDT", l.booked(), l.booked(), bankRef(l),
                    xml(l.reference() == null ? "NOTPROVIDED" : l.reference()), l.debit() ? "Cdtr" : "Dbtr", xml(l.counterparty() == null ? "-" : l.counterparty()),
                    l.debit() ? "Cdtr" : "Dbtr", xml(l.narrative() == null ? "" : l.narrative())));
        }
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.02">
                  <BkToCstmrStmt>
                    <GrpHdr><MsgId>%s</MsgId><CreDtTm>%sT23:59:00</CreDtTm></GrpHdr>
                    <Stmt>
                      <Id>%s</Id>
                      <CreDtTm>%sT23:59:00</CreDtTm>
                      <FrToDt><FrDtTm>%sT00:00:00</FrDtTm><ToDtTm>%sT23:59:59</ToDtTm></FrToDt>
                      <Acct><Id><IBAN>%s</IBAN></Id><Ccy>AED</Ccy></Acct>
                      <Bal><Tp><CdOrPrtry><Cd>OPBD</Cd></CdOrPrtry></Tp><Amt Ccy="AED">%s</Amt><CdtDbtInd>%s</CdtDbtInd><Dt><Dt>%s</Dt></Dt></Bal>
                      <Bal><Tp><CdOrPrtry><Cd>CLBD</Cd></CdOrPrtry></Tp><Amt Ccy="AED">%s</Amt><CdtDbtInd>%s</CdtDbtInd><Dt><Dt>%s</Dt></Dt></Bal>
                %s    </Stmt>
                  </BkToCstmrStmt>
                </Document>
                """.formatted(id, to, id, to, from, to, iban, plain(opening.abs()), opening.signum() < 0 ? "DBIT" : "CRDT", from,
                plain(closing.abs()), closing.signum() < 0 ? "DBIT" : "CRDT", to, entries);
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    private static String plain(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ---- UBL ----

    /** A PINT AE style invoice from another ASP, with the fields reconciliation reads. */
    static String ubl(UUID uuid, String number, LocalDate issued, String supplierTrn, String supplierName, String buyerTrn, String buyerName,
                      BigDecimal net, BigDecimal vat) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <Invoice xmlns="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2"
                         xmlns:cac="urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2"
                         xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2">
                  <cbc:CustomizationID>urn:peppol:pint:billing-1@ae-1</cbc:CustomizationID>
                  <cbc:ID>%s</cbc:ID>
                  <cbc:UUID>%s</cbc:UUID>
                  <cbc:IssueDate>%s</cbc:IssueDate>
                  <cbc:InvoiceTypeCode>380</cbc:InvoiceTypeCode>
                  <cbc:DocumentCurrencyCode>AED</cbc:DocumentCurrencyCode>
                  <cac:AccountingSupplierParty><cac:Party>
                    <cbc:EndpointID schemeID="0235">%s</cbc:EndpointID>
                    <cac:PartyName><cbc:Name>%s</cbc:Name></cac:PartyName>
                    <cac:PartyTaxScheme><cbc:CompanyID>%s</cbc:CompanyID><cac:TaxScheme><cbc:ID>VAT</cbc:ID></cac:TaxScheme></cac:PartyTaxScheme>
                  </cac:Party></cac:AccountingSupplierParty>
                  <cac:AccountingCustomerParty><cac:Party>
                    <cac:PartyName><cbc:Name>%s</cbc:Name></cac:PartyName>
                    <cac:PartyTaxScheme><cbc:CompanyID>%s</cbc:CompanyID><cac:TaxScheme><cbc:ID>VAT</cbc:ID></cac:TaxScheme></cac:PartyTaxScheme>
                  </cac:Party></cac:AccountingCustomerParty>
                  <cac:TaxTotal><cbc:TaxAmount currencyID="AED">%s</cbc:TaxAmount></cac:TaxTotal>
                  <cac:LegalMonetaryTotal>
                    <cbc:TaxExclusiveAmount currencyID="AED">%s</cbc:TaxExclusiveAmount>
                    <cbc:TaxInclusiveAmount currencyID="AED">%s</cbc:TaxInclusiveAmount>
                    <cbc:PayableAmount currencyID="AED">%s</cbc:PayableAmount>
                  </cac:LegalMonetaryTotal>
                </Invoice>
                """.formatted(xml(number), uuid, issued, supplierTrn.substring(0, 10), xml(supplierName), supplierTrn, xml(buyerName), buyerTrn,
                plain(vat), plain(net), plain(net.add(vat)), plain(net.add(vat)));
    }

    static byte[] zip(Map<String, String> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
