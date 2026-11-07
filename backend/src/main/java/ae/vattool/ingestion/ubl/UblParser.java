package ae.vattool.ingestion.ubl;

import ae.vattool.platform.xml.SafeXml;
import ae.vattool.platform.web.DomainException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Reads the fields reconciliation needs from a PINT AE invoice or credit note. Amounts become AED: the
 * document's own totals when it is in AED, otherwise the AED tax total and the stated exchange rate.
 * If neither is there the document is refused, because a guessed rate would corrupt the comparison.
 */
public final class UblParser {

    public record Invoice(UUID uuid, String docType, String number, String issueDate, String supplierTrn, String buyerTrn, String currency,
                          BigDecimal netAed, BigDecimal vatAed, String billingReference) {}

    private UblParser() {}

    public static Invoice parse(byte[] xml) {
        Document doc = SafeXml.parse(xml);
        Element root = doc.getDocumentElement();
        String kind = root.getLocalName();
        if (!"Invoice".equals(kind) && !"CreditNote".equals(kind)) {
            throw DomainException.invalid("UBL_NOT_AN_INVOICE", "Expected an Invoice or CreditNote document, found " + kind);
        }
        String number = SafeXml.text(root, "ID");
        String issueDate = SafeXml.text(root, "IssueDate");
        if (number == null || issueDate == null) {
            throw DomainException.invalid("UBL_FIELD_MISSING", "The document has no number or issue date");
        }
        String supplier = taxId(SafeXml.path(root, "AccountingSupplierParty", "Party"));
        String buyer = taxId(SafeXml.path(root, "AccountingCustomerParty", "Party"));
        String currency = SafeXml.text(root, "DocumentCurrencyCode");
        if (currency == null) {
            throw DomainException.invalid("UBL_FIELD_MISSING", "The document has no currency");
        }
        BigDecimal net = amount(SafeXml.text(root, "LegalMonetaryTotal", "TaxExclusiveAmount"), "total without tax");
        BigDecimal netAed;
        BigDecimal vatAed = aedTax(root, currency);
        if ("AED".equals(currency)) {
            netAed = net;
        } else {
            String rate = SafeXml.text(root, "TaxExchangeRate", "CalculationRate");
            if (rate == null || vatAed == null) {
                throw DomainException.invalid("UBL_AED_AMOUNTS_MISSING", "A " + currency + " document needs an exchange rate and an AED tax total");
            }
            netAed = net.multiply(new BigDecimal(rate)).setScale(2, RoundingMode.HALF_UP);
        }
        if (vatAed == null) {
            throw DomainException.invalid("UBL_FIELD_MISSING", "The document has no tax total in AED");
        }
        String reference = SafeXml.text(root, "BillingReference", "InvoiceDocumentReference", "ID");
        return new Invoice(uuid(SafeXml.text(root, "UUID"), supplier, number, issueDate), docType(root, kind), number, issueDate, supplier, buyer, currency,
                netAed.setScale(2, RoundingMode.HALF_UP), vatAed.setScale(2, RoundingMode.HALF_UP), reference);
    }

    private static String docType(Element root, String kind) {
        if ("CreditNote".equals(kind)) {
            return "CREDIT_NOTE";
        }
        return "383".equals(SafeXml.text(root, "InvoiceTypeCode")) ? "DEBIT_NOTE" : "INVOICE";
    }

    /** The UUID is the deterministic key. Without one, the same document always yields the same id. */
    private static UUID uuid(String stated, String supplier, String number, String issueDate) {
        if (stated != null) {
            try {
                return UUID.fromString(stated);
            } catch (IllegalArgumentException e) {
                // Not a real UUID, so fall through to one derived from the document itself.
            }
        }
        return UUID.nameUUIDFromBytes((supplier + "|" + number + "|" + issueDate).getBytes(StandardCharsets.UTF_8));
    }

    /** The VAT scheme's registration number when present, or the Peppol endpoint, which holds the TIN. */
    private static String taxId(Element party) {
        if (party == null) {
            return null;
        }
        List<Element> schemes = SafeXml.children(party, "PartyTaxScheme");
        for (Element s : schemes) {
            String scheme = SafeXml.text(s, "TaxScheme", "ID");
            String id = SafeXml.text(s, "CompanyID");
            if (id != null && (scheme == null || "VAT".equalsIgnoreCase(scheme))) {
                return id;
            }
        }
        return SafeXml.text(party, "EndpointID");
    }

    private static BigDecimal aedTax(Element root, String documentCurrency) {
        BigDecimal fallback = null;
        for (Element total : SafeXml.children(root, "TaxTotal")) {
            Element amount = SafeXml.child(total, "TaxAmount");
            if (amount == null) {
                continue;
            }
            String currency = amount.getAttribute("currencyID");
            if ("AED".equals(currency)) {
                return amount(amount.getTextContent(), "tax total");
            }
            if ((currency == null || currency.isEmpty()) && "AED".equals(documentCurrency)) {
                fallback = amount(amount.getTextContent(), "tax total");
            }
        }
        return fallback;
    }

    private static BigDecimal amount(String text, String what) {
        if (text == null || text.isBlank()) {
            throw DomainException.invalid("UBL_FIELD_MISSING", "The document has no " + what);
        }
        try {
            return new BigDecimal(text.trim());
        } catch (NumberFormatException e) {
            throw DomainException.invalid("UBL_FIELD_INVALID", "The " + what + " is not a number: " + text);
        }
    }
}
