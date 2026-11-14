package ae.vattool.demo;

import ae.vattool.demo.Docs.Month;
import ae.vattool.demo.Docs.Party;
import ae.vattool.demo.Docs.Purchase;
import ae.vattool.demo.Docs.Sale;
import ae.vattool.ingestion.Templates;
import ae.vattool.ingestion.einvoice.EinvoiceEvent;
import ae.vattool.masterdata.EntityService;
import ae.vattool.masterdata.LegalEntity;
import ae.vattool.masterdata.MappingService;
import ae.vattool.rules.ClientRules;
import ae.vattool.rules.RulePackDefinition;
import ae.vattool.masterdata.PartyService;
import ae.vattool.masterdata.TaxPeriod;
import ae.vattool.reconciliation.ReconciliationService;
import ae.vattool.returns.BoxCalculator.BoxValue;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One demo client and its year. A subclass says what the business sells and buys and what went wrong
 * along the way. This class turns that into files, loads them month by month, and closes each return
 * the way the firm would: reconcile, explain what moved, sign off in turn, generate the pack and file.
 */
abstract class Client {

    private static final Logger log = LoggerFactory.getLogger(Client.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    final Kit kit;
    final Timeline time;
    final String code;

    LegalEntity filer;
    Kit.Person preparer;
    Kit.Person reviewer;
    Kit.Person partner = Kit.LINA;
    Kit.Person approver;
    /** The name the approver types when the client approves by e-signature. */
    String signedName;

    String bankFormat = "MT940";
    String iban;
    BigDecimal balance = new BigDecimal("1850000.00");
    BigDecimal payroll = new BigDecimal("180000.00");
    int statementNo;
    String channel = "UPLOAD";

    final List<Sale> sales = new ArrayList<>();
    final List<Purchase> purchases = new ArrayList<>();
    final List<Files.BankLine> scheduled = new ArrayList<>();
    /** E-invoice events the platform holds back, for the backfill stub to send when asked. */
    final List<EinvoiceEvent> withheld = new ArrayList<>();
    private final Map<Integer, List<Runnable>> agenda = new HashMap<>();
    private final Map<String, Integer> counters = new HashMap<>();

    Client(Kit kit, Timeline time, String code) {
        this.kit = kit;
        this.time = time;
        this.code = code;
    }

    abstract String legalName();

    /** The month offset of the first period. Monthly clients start a year back. */
    int firstOffset() {
        return -12;
    }

    abstract void onboard();

    abstract void generate(Month m);

    boolean closesIn(YearMonth ym) {
        return true;
    }

    /** Scheme work and fixes a preparer does before closing a filed month. */
    void beforeClose(TaxPeriod p, int offset) {}

    void afterFiling(TaxPeriod p, int offset, LocalDate filedOn, String reference) {}

    /** The return due this month, left at whatever stage this client has reached. */
    abstract void current(TaxPeriod p);

    void after() {}

    String swingReason(String box, boolean up) {
        return switch (box) {
            case "4" -> up ? "More export shipments left this month." : "Fewer export shipments this month.";
            case "3", "10" -> "Reverse charge services are billed irregularly by the overseas suppliers.";
            case "6", "7" -> up ? "Two containers cleared customs this month." : "Only one container cleared customs this month.";
            case "5" -> "Exempt income follows lease renewals, which are uneven.";
            case "9" -> up ? "Higher purchases this month, matched to the purchase ledger." : "Lower purchases this month, matched to the purchase ledger.";
            default -> up ? "Higher sales this month, in line with the sales ledger." : "Lower sales this month, in line with the sales ledger.";
        };
    }

    // ---- The year ----

    /** Currencies the client invoices or is billed in, whose Central Bank rates it uploads. */
    java.util.Set<String> currencies() {
        return java.util.Set.of();
    }

    /** Rules this client has on top of the firm's pack from its first period, or null for none. */
    ClientRules clientRules() {
        return null;
    }

    String clientRulesNote() {
        return "Agreed with the client at onboarding";
    }

    final void run() {
        long started = System.currentTimeMillis();
        onboard();
        ClientRules own = clientRules();
        if (own != null) {
            kit.clientRules(filer.id(), kit.entity(filer.id()).firstPeriodStart(), clientRulesNote(), own,
                    "Checked against the engagement letter and the client's chart of accounts");
        }
        if (!currencies().isEmpty()) {
            LocalDate from = time.month(firstOffset()).atDay(1).minusDays(7);
            kit.load(preparer, filer.id(), Templates.Kind.FX, "UPLOAD", "cbuae-rates-" + from + "-to-" + time.today.minusDays(1) + ".csv",
                    Story.fxRows(currencies(), from, time.today.minusDays(1), true));
        }
        for (int m = firstOffset(); m <= -1; m++) {
            YearMonth ym = time.month(m);
            Month month = new Month(ym, m);
            generate(month);
            load(month);
            statement(ym);
            for (Runnable r : agenda.getOrDefault(m, List.of())) {
                r.run();
            }
            if (closesIn(ym)) {
                TaxPeriod p = kit.period(filer.id(), ym.atDay(1));
                if (m < -1) {
                    closeHistory(p, m);
                } else {
                    current(p);
                }
            }
        }
        after();
        log.info("Demo client {} ready in {} s", legalName(), (System.currentTimeMillis() - started) / 1000);
    }

    /** Something to do in a later month of the story, after that month's data and bank statement are in. */
    void at(int offset, Runnable work) {
        agenda.computeIfAbsent(offset, k -> new ArrayList<>()).add(work);
    }

    void load(Month m) {
        String tag = m.ym.toString();
        String prefix = code.toLowerCase();
        if (!m.sales.isEmpty()) {
            kit.load(preparer, filer.id(), Templates.Kind.AR, channel, prefix + "-sales-" + tag + ".csv", Docs.arRows(m.sales));
        }
        if (!m.purchases.isEmpty()) {
            List<Map<String, String>> rows = new ArrayList<>(Docs.apRows(m.purchases));
            rows.addAll(m.badPurchaseRows);
            kit.load(preparer, filer.id(), Templates.Kind.AP, channel, prefix + "-purchases-" + tag + ".csv", rows);
        }
        if (!m.imports.isEmpty()) {
            kit.load(preparer, filer.id(), Templates.Kind.CUSTOMS, "UPLOAD", prefix + "-customs-declarations-" + tag + ".csv", Docs.customsRows(m.imports));
        }
        if (!m.margins.isEmpty()) {
            kit.load(preparer, filer.id(), Templates.Kind.MARGIN, "UPLOAD", prefix + "-margin-scheme-" + tag + ".csv", Docs.marginRows(m.margins));
        }
        kit.load(preparer, filer.id(), Templates.Kind.GL_JOURNAL, channel, prefix + "-gl-journal-" + tag + ".csv", Docs.journalRows(m, "1010"));
        sales.addAll(m.sales);
        purchases.addAll(m.purchases);
        einvoices(m.sales, m.purchases);
    }

    /** What the e-invoicing platform reports for these documents, faults included where a sale has one. */
    void einvoices(List<Sale> forSales, List<Purchase> forPurchases) {
        LocalDate goLive = filer.einvoiceGoLive();
        if (goLive == null) {
            return;
        }
        List<EinvoiceEvent> events = new ArrayList<>();
        for (Sale s : forSales) {
            if (!s.needsEinvoice(goLive)) {
                continue;
            }
            List<EinvoiceEvent> issued = Docs.issued(s, filer.trn());
            switch (s.fault == null ? "" : s.fault) {
                case "MISSING" -> withheld.addAll(issued);
                case "FAILED" -> events.addAll(List.of(issued.get(0), Docs.event(EinvoiceEvent.CORNER5, s.einvoice, filer.trn(), Docs.at(s.date.plusDays(1), 9), null, "FAILED")));
                case "PENDING" -> events.add(issued.get(0));
                case "RENUMBERED" -> {
                    // The platform's copy has the number without its dashes, so only a fuzzy match can pair them.
                    EinvoiceEvent first = issued.get(0);
                    EinvoiceEvent.Invoice i = first.invoice();
                    events.add(Docs.event(EinvoiceEvent.ISSUED, s.einvoice, filer.trn(), first.occurredAt(), new EinvoiceEvent.Invoice(i.docType(),
                            i.number().replace("-", ""), i.issueDate(), i.supplierTrn(), i.buyerTrn(), i.currency(), i.netAed(), i.vatAed(), i.billingReference()), null));
                    events.add(issued.get(1));
                }
                default -> events.addAll(issued);
            }
        }
        for (Purchase p : forPurchases) {
            if (p.supplier.einvoicing() && !p.date.isBefore(goLive) && "TAX_INVOICE".equals(p.type)) {
                events.addAll(Docs.received(p, filer.trn()));
            }
        }
        kit.einvoices(events);
    }

    /** The month's bank statement: receipts, supplier payments, payroll, and whatever was scheduled. */
    void statement(YearMonth ym) {
        LocalDate from = ym.atDay(1);
        LocalDate to = ym.atEndOfMonth();
        List<Files.BankLine> lines = new ArrayList<>();
        for (Sale s : sales) {
            if (s.paidOn != null && !s.credit() && within(s.paidOn, from, to)) {
                boolean card = "B2C_SALE".equals(s.type);
                lines.add(new Files.BankLine(s.paidOn, s.grossAed(), false, s.number, card ? "CARD ACQUIRER SETTLEMENT" : s.customer.name(),
                        (card ? "POS SETTLEMENT " : "RECEIPT INV ") + s.number));
            }
        }
        for (Purchase p : purchases) {
            if (p.paidOn != null && "TAX_INVOICE".equals(p.type) && within(p.paidOn, from, to)) {
                lines.add(new Files.BankLine(p.paidOn, p.grossAed(), true, p.number, p.supplier.name(), "PAYMENT INV " + p.number));
            }
        }
        for (Files.BankLine l : scheduled) {
            if (within(l.booked(), from, to)) {
                lines.add(l);
            }
        }
        lines.add(new Files.BankLine(Gen.workingDay(ym.atDay(26)), payroll, true, "WPS" + ym.toString().replace("-", ""), "Payroll", "WPS SALARY TRANSFER " + ym));
        lines.add(new Files.BankLine(Gen.workingDay(to), new BigDecimal("262.50"), true, "CHG" + ym.toString().replace("-", ""), "Bank", "ACCOUNT MAINTENANCE AND TRANSFER CHARGES"));
        lines.sort(Comparator.comparing(Files.BankLine::booked).thenComparing(Files.BankLine::debit));
        BigDecimal opening = balance;
        for (Files.BankLine l : lines) {
            balance = l.debit() ? balance.subtract(l.amount()) : balance.add(l.amount());
        }
        statementNo++;
        String id = code + "-" + ym.toString().replace("-", "");
        byte[] file = "CAMT053".equals(bankFormat)
                ? Files.camt053(id, iban, from, to, opening, lines)
                : Files.mt940(id, iban, statementNo, from, to, opening, lines, closingOverride(ym, balance));
        String name = "CAMT053".equals(bankFormat) ? "camt053-" + code.toLowerCase() + "-" + ym + ".xml" : "mt940-" + code.toLowerCase() + "-" + ym + ".sta";
        kit.bank(preparer, filer.id(), name, file);
    }

    /** A mis-keyed closing balance, for a client whose bank once sent one. */
    BigDecimal closingOverride(YearMonth ym, BigDecimal actual) {
        return null;
    }

    private static boolean within(LocalDate d, LocalDate from, LocalDate to) {
        return !d.isBefore(from) && !d.isAfter(to);
    }

    // ---- Closing a filed month ----

    void closeHistory(TaxPeriod p, int offset) {
        beforeClose(p, offset);
        for (ae.vattool.adjustments.CapitalAssetService.Due due : kit.as(reviewer, () -> kit.assets.dueIn(p))) {
            kit.as(reviewer, () -> kit.assets.adjust(p, due.asset().id(), assetUse(due)));
        }
        BigDecimal timing = timingDifference(offset);
        gl(p, timing == null ? BigDecimal.ZERO : timing);
        if (timing != null) {
            kit.as(preparer, () -> kit.recon.addTimingItem(p, "OUTPUT", timing, timingNote(p)));
        }
        tidy(p, true);
        signoff(p);
        LocalDate filedOn = filedOn(p, offset);
        String reference = fileReturn(p, filedOn);
        BigDecimal net = net(p.id());
        if (net.signum() > 0) {
            // The bank matcher looks for the payment from the filing date on, as a real payment follows the return.
            LocalDate paid = paidOn(p, offset);
            schedulePayment(p, paid.isAfter(filedOn) ? paid : Gen.nextWorkingDay(filedOn.plusDays(1)), net);
        }
        afterFiling(p, offset, filedOn, reference);
    }

    /** Output VAT the GL took in this month that belongs to the next, explained to check R06 as a timing item. */
    BigDecimal timingDifference(int offset) {
        return null;
    }

    String timingNote(TaxPeriod p) {
        return "Invoices posted in the GL on " + p.periodEnd() + " for deliveries on the 1st and 2nd of the next month, where their tax point falls.";
    }

    LocalDate filedOn(TaxPeriod p, int offset) {
        return Gen.workingDay(p.dueDate().minusDays(3 + Math.floorMod(offset * 7 + code.hashCode(), 9)));
    }

    LocalDate paidOn(TaxPeriod p, int offset) {
        return Gen.workingDay(p.dueDate().minusDays(1 + Math.floorMod(offset * 5 + code.hashCode(), 4)));
    }

    void schedulePayment(TaxPeriod p, LocalDate on, BigDecimal amount) {
        scheduled.add(new Files.BankLine(on, amount, true, "VATPAY" + p.periodEnd().toString().replace("-", ""), "Federal Tax Authority",
                "FTA VAT PAYMENT GIBAN " + filer.ftaGiban() + " RETURN PERIOD ENDING " + p.periodEnd()));
    }

    /** GL VAT control balances as the client's accounts show them, which agree with the return to the dirham or two. */
    void gl(TaxPeriod p, BigDecimal outputDifference) {
        kit.recompute(preparer, p.id());
        Map<String, BoxValue> b = kit.boxes(p.id());
        BigDecimal output = BigDecimal.ZERO;
        for (String box : List.of("1a", "1b", "1c", "1d", "1e", "1f", "1g")) {
            output = output.add(vat(b, box)).add(adjustment(b, box));
        }
        output = output.add(vat(b, "2"));
        BigDecimal input = vat(b, "9").add(adjustment(b, "9"));
        BigDecimal noise = new BigDecimal(Math.floorMod(p.periodEnd().hashCode() + code.hashCode(), 7) - 3).setScale(2);
        List<Map<String, String>> rows = List.of(
                Files.row("account_code", "2201", "account_type", "OUTPUT", "period_end", p.periodEnd(), "balance_aed", output.add(outputDifference).add(noise)),
                Files.row("account_code", "1201", "account_type", "INPUT", "period_end", p.periodEnd(), "balance_aed", input.subtract(noise)),
                Files.row("account_code", "2202", "account_type", "REVERSE_CHARGE", "period_end", p.periodEnd(), "balance_aed", vat(b, "3")),
                Files.row("account_code", "2203", "account_type", "IMPORT", "period_end", p.periodEnd(), "balance_aed", vat(b, "6").add(vat(b, "7"))));
        kit.load(preparer, filer.id(), Templates.Kind.GL, channel, code.toLowerCase() + "-gl-vat-control-" + p.periodEnd() + ".csv", rows);
    }

    static BigDecimal vat(Map<String, BoxValue> b, String box) {
        BoxValue v = b.get(box);
        return v == null ? BigDecimal.ZERO : v.vat();
    }

    static BigDecimal adjustment(Map<String, BoxValue> b, String box) {
        BoxValue v = b.get(box);
        return v == null ? BigDecimal.ZERO : v.adjustment();
    }

    static BigDecimal amount(Map<String, BoxValue> b, String box) {
        BoxValue v = b.get(box);
        return v == null ? BigDecimal.ZERO : v.amount();
    }

    BigDecimal net(UUID periodId) {
        return vat(kit.boxes(periodId), "14");
    }

    /**
     * Reconciles, then does what a preparer would before handing over: explains each box that moved,
     * and has the reviewer confirm description-based blocks. A filed month must end with nothing blocking.
     */
    List<ReconciliationService.Exception> tidy(TaxPeriod p, boolean mustBeClean) {
        kit.reconcile(preparer, p.id());
        Set<String> explained = new HashSet<>();
        for (ReconciliationService.Exception e : kit.open(p.id())) {
            switch (e.ruleId()) {
                case "R14" -> {
                    String box = e.fingerprint().split(":")[0];
                    if (explained.add(box)) {
                        kit.comment(preparer, p.id(), "BOX", box, swingText(box, e.details()));
                    }
                }
                case "T03" -> kit.waive(reviewer, e.id(), "Checked the invoices: these are " + description(e.details())
                        + ", so the input VAT stays blocked as the description says.");
                default -> { }
            }
        }
        if (!explained.isEmpty()) {
            kit.reconcile(preparer, p.id());
        }
        List<ReconciliationService.Exception> left = kit.open(p.id());
        List<ReconciliationService.Exception> blocking = left.stream().filter(e -> "BLOCKING".equals(e.severity())).toList();
        if (mustBeClean && !blocking.isEmpty()) {
            throw new IllegalStateException(legalName() + " " + p.periodStart() + " still has blocking exceptions: "
                    + blocking.stream().map(e -> e.ruleId() + " " + e.message()).toList());
        }
        if (mustBeClean && !left.isEmpty()) {
            log.debug("{} {} keeps warnings: {}", legalName(), p.periodStart(), left.stream().map(e -> e.ruleId() + " " + e.message()).toList());
        }
        return left;
    }

    private String swingText(String box, String details) {
        JsonNode d = JSON.readTree(details);
        String previous = d.path("previous").asString("0");
        String current = d.path("current").asString("0");
        boolean up = new BigDecimal(current).compareTo(new BigDecimal(previous)) > 0;
        String pct = d.path("swingPct").isNull() || d.path("swingPct").isMissingNode() ? "" : " (" + d.path("swingPct").asString() + "%)";
        return "Box " + box + " moved from " + previous + " to " + current + pct + ". " + swingReason(box, up);
    }

    private static String description(String details) {
        JsonNode d = JSON.readTree(details);
        String gl = d.path("glAccount").asString("");
        return gl.isBlank() ? "staff and client hospitality costs" : "staff and client hospitality costs on GL " + gl;
    }

    void signoff(TaxPeriod p) {
        kit.submit(preparer, p.id(), "Prepared. Reconciled to the ledgers and the bank.");
        kit.review(reviewer, p.id(), "Reviewed the boxes, the exceptions and the movements against last month.");
        if (kit.status(p.id()).partnerRequired()) {
            kit.partner(partner, p.id(), "Partner review done.");
        }
        kit.approve(approver, p.id(), "Approved for filing.", filer.requireEsignature() ? signedName : null);
    }

    /** Generates the pack and records the submission with its acknowledgement. Returns the reference. */
    String fileReturn(TaxPeriod p, LocalDate filedOn) {
        kit.pack(reviewer, p.id());
        String reference = "VAT201-" + p.periodEnd().toString().replace("-", "").substring(0, 6) + "-" + filer.trn().substring(9);
        BigDecimal net = net(p.id());
        UUID ack = kit.evidence(reviewer, filer.id(), p.id(), "emaratax-acknowledgement-" + p.periodEnd() + ".pdf", "EmaraTax acknowledgement", null,
                "EmaraTax submission acknowledgement", List.of(
                        "Taxable person: " + filer.legalName(), "TRN: " + filer.trn(), "Form: VAT201 VAT return",
                        "Tax period: " + p.periodStart() + " to " + p.periodEnd(), "Submission reference: " + reference,
                        "Submitted on: " + filedOn, (net.signum() >= 0 ? "Payable tax: AED " : "Refundable tax: AED ") + net.abs().toPlainString(),
                        "Status: Submitted"));
        kit.file(reviewer, p.id(), reference, filedOn, ack);
        return reference;
    }

    // ---- Onboarding ----

    LegalEntity entity(String name, String nameAr, String trn, String kind, UUID rep, String frequency, LocalDate first, LocalDate goLive,
                       BigDecimal recovery, BigDecimal partnerThreshold) {
        return kit.as(Kit.OMAR, () -> kit.entities.create(new EntityService.EntityInput(name, nameAr, trn, kind, rep, frequency, null, first, goLive,
                recovery, partnerThreshold, true)));
    }

    void establishment(UUID entity, String code, String name, String emirate, boolean zone, String zoneName) {
        kit.as(Kit.OMAR, () -> kit.entities.addEstablishment(entity, new EntityService.EstablishmentInput(code, name, emirate, zone, zoneName)));
    }

    void settings(UUID entity, String giban, boolean esignature, Integer taxYearMonth) {
        LegalEntity e = kit.entity(entity);
        kit.as(Kit.OMAR, () -> kit.entities.updateSettings(entity, e.defaultRecoveryPct(), e.partnerThresholdAed(), e.requireReviewer(),
                e.einvoiceGoLive(), giban, esignature, taxYearMonth));
    }

    static RulePackDefinition.PatternRule pattern(String id, String category, String... patterns) {
        return new RulePackDefinition.PatternRule(id, category, List.of(patterns), null);
    }

    static RulePackDefinition.PatternRule blocked(String id, String category, String... patterns) {
        return new RulePackDefinition.PatternRule(id, category, List.of(patterns), "IN");
    }

    static RulePackDefinition.DecisionRule rule(String id, String description, String direction, String descriptionMatches, String treatment) {
        return new RulePackDefinition.DecisionRule(id, description,
                new RulePackDefinition.When(direction, null, null, null, null, null, null, null, descriptionMatches),
                new RulePackDefinition.Then(treatment, null, null));
    }

    void mapping(UUID entity, LocalDate from, String note, List<MappingService.EntryInput> entries) {
        kit.as(Kit.OMAR, () -> kit.mappings.publish(entity, from, note, entries));
    }

    static MappingService.EntryInput taxCode(String code, String treatment) {
        return new MappingService.EntryInput("TAX_CODE", code, treatment, null, null);
    }

    static MappingService.EntryInput drc(String code, String category) {
        return new MappingService.EntryInput("TAX_CODE", code, "DRC", null, category);
    }

    static MappingService.EntryInput blockedGl(String gl, String category) {
        return new MappingService.EntryInput("GL_ACCOUNT", gl, null, category, null);
    }

    static MappingService.EntryInput blockedVendor(String trn, String category) {
        return new MappingService.EntryInput("VENDOR_TRN", trn, null, category, null);
    }

    static List<MappingService.EntryInput> standardCodes() {
        return new ArrayList<>(List.of(taxCode("S5", "STANDARD"), taxCode("Z0", "ZERO_RATED"), taxCode("EX", "EXEMPT"), taxCode("OOS", "OUT_OF_SCOPE"),
                taxCode("RC", "REVERSE_CHARGE"), taxCode("IM", "IMPORT")));
    }

    /** Puts suppliers on the register check R08 reads, as someone who looked them up on the FTA site would. */
    void register(List<Party> parties, LocalDate checkedOn) {
        for (Party p : parties) {
            if (p.trn() != null) {
                kit.as(Kit.OMAR, () -> kit.parties.upsertCounterparty(new PartyService.CounterpartyInput(p.trn(), p.name(), p.country() == null ? "AE" : p.country(),
                        p.designatedZone(), "ACTIVE", null, null, checkedOn)));
            }
        }
    }

    void agent(UUID entity, String agentName, String taan, LocalDate from) {
        kit.as(Kit.OMAR, () -> kit.parties.linkAgent(entity, new PartyService.AgentLink("Demo Tax Advisers LLC", "20000101", agentName, taan, from)));
    }

    void signatory(UUID entity, String name, String email, String position, LocalDate from) {
        kit.as(Kit.OMAR, () -> kit.parties.addSignatory(entity, new PartyService.Signatory(null, name, email, position, from, null)));
    }

    // ---- Shared scheme work ----

    /** Taxable use of a capital asset over the interval just ended. */
    BigDecimal assetUse(ae.vattool.adjustments.CapitalAssetService.Due due) {
        return new BigDecimal("100");
    }

    /** A journal the preparer posts with its voucher as evidence, decided by the reviewer or left waiting for them. */
    void journal(TaxPeriod p, String box, String vat, String description, String reason, boolean approve) {
        UUID voucher = kit.evidence(preparer, filer.id(), p.id(), "journal-" + box + "-" + p.periodEnd() + "-" + Math.abs(description.hashCode() % 1000) + ".pdf",
                "Journal voucher: " + description, null, "Journal voucher", List.of(filer.legalName(), description, "VAT AED " + vat + " in box " + box, reason));
        ae.vattool.adjustments.AdjustmentService.Adjustment a = kit.as(preparer, () -> kit.adjustments.propose(p,
                new ae.vattool.adjustments.AdjustmentService.ManualInput("MANUAL_JOURNAL", box, null, new BigDecimal(vat), description, reason, voucher)));
        if (approve) {
            kit.as(reviewer, () -> kit.adjustments.decide(a.id(), true, "Checked to the voucher and the invoices"));
        }
    }

    /** Asks the suggestion engine about lines no mapping settled, as the preparer would before handing over. */
    void suggest(TaxPeriod p) {
        kit.as(preparer, () -> kit.suggestions.generate(p.id()));
    }

    /** Exit certificate and bill of lading for a goods export, as the preparer files them. */
    void exportEvidence(TaxPeriod p, Sale s, LocalDate exportedOn, String port) {
        UUID doc = kit.documentId(filer.id(), "OUT", s.number);
        String exit = "EX-" + s.number.replaceAll("[^0-9]", "") + "-" + exportedOn.getDayOfYear();
        UUID official = kit.evidence(preparer, filer.id(), p.id(), "exit-certificate-" + s.number + ".pdf", "Customs exit certificate for " + s.number, null,
                "Customs exit certificate", List.of("Exporter: " + filer.legalName() + " (TRN " + filer.trn() + ")", "Invoice: " + s.number + " dated " + s.date,
                        "Consignee: " + s.customer.name() + ", " + s.customer.country(), "Exit reference: " + exit, "Port of exit: " + port, "Goods left the UAE on " + exportedOn));
        UUID commercial = kit.evidence(preparer, filer.id(), p.id(), "bill-of-lading-" + s.number + ".pdf", "Bill of lading for " + s.number, null,
                "Bill of lading", List.of("Shipper: " + filer.legalName(), "Consignee: " + s.customer.name(), "Port of loading: " + port,
                        "Shipped on board: " + exportedOn, "Commercial invoice: " + s.number, "Freight: prepaid"));
        kit.as(preparer, () -> kit.exports.record(doc, new ae.vattool.classification.ExportEvidenceService.Input(exportedOn, exit, official, commercial, null, null)));
    }

    /** The adjustments a scheme proposed in this period, decided by someone other than whoever proposed them. */
    void decideProposed(TaxPeriod p, Kit.Person decider, boolean approve, String note) {
        List<UUID> proposed = kit.query(() -> kit.jdbc.sql("SELECT id FROM adjustment WHERE period_id = ? AND status = 'PROPOSED' ORDER BY created_at")
                .param(p.id()).query(UUID.class).list());
        for (UUID id : proposed) {
            kit.as(decider, () -> kit.adjustments.decide(id, approve, note));
        }
    }

    UUID bankTxn(String narrativeContains) {
        return kit.query(() -> kit.jdbc.sql("""
                SELECT id FROM bank_txn WHERE entity_id = ? AND narrative LIKE ? ORDER BY booked_on DESC LIMIT 1
                """).params(filer.id(), "%" + narrativeContains + "%").query(UUID.class).single());
    }

    // ---- Numbering ----

    /** The next number in a series, like INV-10432. Each client keeps its own counters. */
    String next(String series, String prefix, int start) {
        int n = counters.merge(series, 1, Integer::sum) + start - 1;
        return prefix + n;
    }

    static BigDecimal pct(String value) {
        return new BigDecimal(value).setScale(2, RoundingMode.HALF_UP);
    }
}
