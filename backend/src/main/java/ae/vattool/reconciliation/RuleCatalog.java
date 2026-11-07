package ae.vattool.reconciliation;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Every check with its severity. R01 to R14 are from spec section 5. D checks stop data from going
 * missing silently, and T checks surface classification questions that need a person.
 */
public final class RuleCatalog {

    public record Rule(String id, String severity, String sets, String name) {}

    public static final List<Rule> RULES = List.of(
            new Rule("R01", "BLOCKING", "B→A", "In-scope sales invoice has an e-invoice accepted and reported to the FTA"),
            new Rule("R02", "BLOCKING", "A→B", "Issued e-invoice is posted in the ERP in the same period"),
            new Rule("R03", "BLOCKING", "A↔B", "Net and VAT per invoice match between e-invoice and ERP"),
            new Rule("R04", "WARNING", "A→B", "Received e-invoice is not in AP (unclaimed input)"),
            new Rule("R05", "BLOCKING", "B→A", "Input VAT claimed without a valid tax invoice or e-invoice"),
            new Rule("R06", "BLOCKING", "C↔D", "GL VAT control balances equal return totals, net of listed timing items"),
            new Rule("R07", "BLOCKING", "E↔D", "Import VAT in Box 6 equals customs declarations for the period"),
            new Rule("R08", "WARNING", "B", "Supplier TRN valid and active on the invoice date"),
            new Rule("R09", "WARNING", "A, B", "Credit or debit note references an existing original invoice"),
            new Rule("R10", "BLOCKING", "A, B", "Duplicate invoice number per supplier or issuer"),
            new Rule("R11", "BLOCKING", "B↔F", "Supplier invoice unpaid 6 months past agreed date not yet reversed"),
            new Rule("R12", "WARNING", "B", "Tax point in period but posted outside it (cut-off)"),
            new Rule("R13", "BLOCKING", "D", "Emirate split 1a to 1g sums to total standard-rated supplies"),
            new Rule("R14", "WARNING", "D", "Period-on-period box swing above threshold without a comment"),
            new Rule("D01", "BLOCKING", "Data", "Rows held because an FX rate is missing"),
            new Rule("D02", "BLOCKING", "Data", "Rows rejected at load and not yet fixed"),
            new Rule("D03", "BLOCKING", "Data", "Lines with no VAT treatment"),
            new Rule("D04", "BLOCKING", "Data", "A locked period has changes that need a correction decision"),
            new Rule("T01", "BLOCKING", "Tax", "Tax code and place-of-supply rules disagree"),
            new Rule("T02", "WARNING", "Tax", "Possible domestic reverse charge supply"),
            new Rule("T03", "WARNING", "Tax", "Input VAT blocked from the description: confirm"),
            new Rule("T04", "WARNING", "Tax", "VAT does not match the expected rate"),
            new Rule("T05", "WARNING", "Tax", "Seller-side domestic reverse charge: VAT201 box to be confirmed by the tax team"),
            new Rule("T06", "WARNING", "Tax", "Goods from abroad or a designated zone: check against customs"),
            new Rule("T07", "WARNING", "Tax", "Zero-rated export without complete official and commercial evidence"),
            new Rule("T08", "BLOCKING", "Tax", "Zero-rated goods not shown as exported within the time limit"),
            new Rule("T09", "WARNING", "Tax", "Exempt supplies in the period and residual input tax not apportioned"),
            new Rule("T10", "WARNING", "Tax", "A capital asset adjustment is due in this return"));

    public static final Map<String, Rule> BY_ID = RULES.stream().collect(Collectors.toMap(Rule::id, Function.identity()));

    private RuleCatalog() {}
}
