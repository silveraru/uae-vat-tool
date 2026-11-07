package ae.vattool.banking;

import ae.vattool.platform.web.DomainException;
import ae.vattool.platform.xml.SafeXml;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Reads MT940 and CAMT.053 into one shape (ING-06). A pure function, so both formats can be tested without a bank. */
public final class StatementParser {

    public record Txn(LocalDate bookedOn, LocalDate valueOn, BigDecimal amount, String currency, String direction, String reference, String counterparty, String narrative) {}

    public record Statement(String format, String account, String statementRef, BigDecimal opening, BigDecimal closing, String currency, List<Txn> txns) {}

    private static final DateTimeFormatter MT_DATE = DateTimeFormatter.ofPattern("yyMMdd");

    private StatementParser() {}

    /** Picks the format from the content, not the file name, since banks name these files many ways. */
    public static List<Statement> parse(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8).stripLeading();
        if (text.startsWith("﻿")) {
            text = text.substring(1).stripLeading();
        }
        if (text.startsWith("<")) {
            return parseCamt(content);
        }
        if (text.contains(":61:") || text.contains(":60F:") || text.contains(":25:")) {
            return parseMt940(text);
        }
        throw DomainException.invalid("STATEMENT_FORMAT_UNKNOWN", "This is not an MT940 or CAMT.053 statement");
    }

    // ---- MT940 ----

    static List<Statement> parseMt940(String text) {
        List<Statement> out = new ArrayList<>();
        // A block starts at :20:. Tags may wrap over lines, so each field runs to the next tag.
        // A dash on its own line ends a block, and would otherwise stick to the last field.
        String[] blocks = text.replace("\r\n", "\n").replaceAll("(?m)^-\\s*$", "").split("(?m)(?=^:20:)");
        for (String block : blocks) {
            if (block.contains(":25:")) {
                out.add(mtBlock(block));
            }
        }
        if (out.isEmpty()) {
            throw DomainException.invalid("STATEMENT_UNREADABLE", "No statement was found in this MT940 file");
        }
        return out;
    }

    private static Statement mtBlock(String block) {
        String reference = null;
        String account = null;
        BigDecimal opening = null;
        BigDecimal closing = null;
        String currency = null;
        List<Txn> txns = new ArrayList<>();
        String[] fields = block.split("(?m)(?=^:[0-9]{2}[A-Z]?:)");
        for (int i = 0; i < fields.length; i++) {
            String f = fields[i].trim();
            if (f.startsWith(":20:")) {
                reference = f.substring(4).trim();
            } else if (f.startsWith(":25:")) {
                account = f.substring(4).trim();
            } else if (f.startsWith(":60F:") || f.startsWith(":60M:")) {
                if (opening == null) {
                    currency = f.substring(12, 15);
                    opening = mtBalance(f.substring(5));
                }
            } else if (f.startsWith(":62F:") || f.startsWith(":62M:")) {
                closing = mtBalance(f.substring(5));
            } else if (f.startsWith(":61:")) {
                String narrative = i + 1 < fields.length && fields[i + 1].trim().startsWith(":86:")
                        ? fields[i + 1].trim().substring(4).replace('\n', ' ').trim() : null;
                txns.add(mtLine(f.substring(4), currency, narrative));
            }
        }
        if (account == null) {
            throw DomainException.invalid("STATEMENT_UNREADABLE", "The statement has no account in tag 25");
        }
        return new Statement("MT940", account, reference, opening, closing, currency == null ? "AED" : currency, txns);
    }

    /** C or D, a six-digit date, three letters of currency, then the amount with a decimal comma. */
    private static BigDecimal mtBalance(String v) {
        try {
            BigDecimal amount = new BigDecimal(v.substring(10).trim().replace(',', '.'));
            return v.startsWith("D") ? amount.negate() : amount;
        } catch (RuntimeException e) {
            throw DomainException.invalid("STATEMENT_UNREADABLE", "A balance could not be read: " + v);
        }
    }

    /** :61: is value date, optional entry date, C/D with optional reversal mark, amount, type code, then references. */
    private static Txn mtLine(String raw, String currency, String narrative) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(\\d{6})(\\d{4})?(R?[CD])[A-Z]?([0-9,]+)[A-Z][A-Z0-9]{3}([^\\n/]*)(?://([^\\n]*))?", java.util.regex.Pattern.DOTALL)
                .matcher(raw);
        if (!m.find()) {
            throw DomainException.invalid("STATEMENT_UNREADABLE", "A transaction line could not be read: " + raw);
        }
        LocalDate value = date(m.group(1));
        LocalDate booked = value;
        if (m.group(2) != null) {
            booked = value.withMonth(Integer.parseInt(m.group(2).substring(0, 2))).withDayOfMonth(Integer.parseInt(m.group(2).substring(2)));
            // An entry date just after the new year belongs to the next year, and just before it to the last.
            if (booked.isBefore(value.minusMonths(6))) {
                booked = booked.plusYears(1);
            } else if (booked.isAfter(value.plusMonths(6))) {
                booked = booked.minusYears(1);
            }
        }
        String mark = m.group(3);
        // A reversal of a credit takes money out, and a reversal of a debit puts it back.
        String direction = mark.equals("D") || mark.equals("RC") ? "DEBIT" : "CREDIT";
        BigDecimal amount = new BigDecimal(m.group(4).replace(',', '.'));
        String reference = m.group(5) == null || m.group(5).isBlank() || "NONREF".equalsIgnoreCase(m.group(5).trim()) ? null : m.group(5).trim();
        return new Txn(booked, value, amount, currency == null ? "AED" : currency, direction, reference, null, narrative);
    }

    private static LocalDate date(String yymmdd) {
        try {
            return LocalDate.parse(yymmdd, MT_DATE);
        } catch (DateTimeParseException e) {
            throw DomainException.invalid("STATEMENT_UNREADABLE", "A date could not be read: " + yymmdd);
        }
    }

    // ---- CAMT.053 ----

    private static List<Statement> parseCamt(byte[] content) {
        Document doc = SafeXml.parse(content);
        Element stmtRoot = SafeXml.child(doc.getDocumentElement(), "BkToCstmrStmt");
        if (stmtRoot == null) {
            throw DomainException.invalid("STATEMENT_FORMAT_UNKNOWN", "This XML is not a CAMT.053 statement");
        }
        List<Statement> out = new ArrayList<>();
        for (Element stmt : SafeXml.children(stmtRoot, "Stmt")) {
            String account = SafeXml.text(stmt, "Acct", "Id", "IBAN");
            if (account == null) {
                account = SafeXml.text(stmt, "Acct", "Id", "Othr", "Id");
            }
            if (account == null) {
                throw DomainException.invalid("STATEMENT_UNREADABLE", "The statement has no account");
            }
            String accountCurrency = SafeXml.text(stmt, "Acct", "Ccy");
            BigDecimal opening = null;
            BigDecimal closing = null;
            for (Element bal : SafeXml.children(stmt, "Bal")) {
                String type = SafeXml.text(bal, "Tp", "CdOrPrtry", "Cd");
                BigDecimal amount = signed(bal);
                if ("OPBD".equals(type) || "PRCD".equals(type)) {
                    opening = amount;
                } else if ("CLBD".equals(type)) {
                    closing = amount;
                }
            }
            List<Txn> txns = new ArrayList<>();
            for (Element ntry : SafeXml.children(stmt, "Ntry")) {
                Element amt = SafeXml.child(ntry, "Amt");
                if (amt == null) {
                    throw DomainException.invalid("STATEMENT_UNREADABLE", "An entry has no amount");
                }
                boolean reversal = "true".equalsIgnoreCase(SafeXml.text(ntry, "RvslInd"));
                boolean debit = "DBIT".equals(SafeXml.text(ntry, "CdtDbtInd"));
                String direction = debit != reversal ? "DEBIT" : "CREDIT";
                String booked = SafeXml.text(ntry, "BookgDt", "Dt");
                if (booked == null) {
                    throw DomainException.invalid("STATEMENT_UNREADABLE", "An entry has no booking date");
                }
                String valued = SafeXml.text(ntry, "ValDt", "Dt");
                Element tx = SafeXml.path(ntry, "NtryDtls", "TxDtls");
                String reference = SafeXml.text(ntry, "AcctSvcrRef");
                String counterparty = null;
                String narrative = SafeXml.text(ntry, "AddtlNtryInf");
                if (tx != null) {
                    String endToEnd = SafeXml.text(tx, "Refs", "EndToEndId");
                    if (endToEnd != null && !"NOTPROVIDED".equalsIgnoreCase(endToEnd)) {
                        reference = endToEnd;
                    }
                    counterparty = SafeXml.text(tx, "RltdPties", debit ? "Cdtr" : "Dbtr", "Nm");
                    if (counterparty == null) {
                        counterparty = SafeXml.text(tx, "RltdPties", debit ? "Cdtr" : "Dbtr", "Pty", "Nm");
                    }
                    String remittance = SafeXml.text(tx, "RmtInf", "Ustrd");
                    if (remittance != null) {
                        narrative = remittance;
                    }
                }
                String ccy = amt.getAttribute("Ccy");
                txns.add(new Txn(LocalDate.parse(booked.substring(0, 10)), valued == null ? null : LocalDate.parse(valued.substring(0, 10)),
                        new BigDecimal(amt.getTextContent().trim()), ccy.isEmpty() ? (accountCurrency == null ? "AED" : accountCurrency) : ccy, direction, reference, counterparty, narrative));
            }
            out.add(new Statement("CAMT053", account, SafeXml.text(stmt, "Id"), opening, closing, accountCurrency == null ? "AED" : accountCurrency, txns));
        }
        if (out.isEmpty()) {
            throw DomainException.invalid("STATEMENT_UNREADABLE", "No statement was found in this CAMT.053 file");
        }
        return out;
    }

    private static BigDecimal signed(Element bal) {
        BigDecimal amount = new BigDecimal(SafeXml.text(bal, "Amt"));
        return "DBIT".equals(SafeXml.text(bal, "CdtDbtInd")) ? amount.negate() : amount;
    }
}
