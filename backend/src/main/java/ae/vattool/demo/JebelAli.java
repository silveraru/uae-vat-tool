package ae.vattool.demo;

import ae.vattool.adjustments.CorrectionService;
import ae.vattool.auditcase.AuditCaseService;
import ae.vattool.corrections.DisclosureService;
import ae.vattool.corrections.RefundService;
import ae.vattool.demo.Docs.Line;
import ae.vattool.demo.Docs.Month;
import ae.vattool.demo.Docs.Party;
import ae.vattool.demo.Docs.Purchase;
import ae.vattool.demo.Docs.Sale;
import ae.vattool.ingestion.Templates;
import ae.vattool.masterdata.MappingService;
import ae.vattool.masterdata.TaxPeriod;
import ae.vattool.reconciliation.ReconciliationService;
import ae.vattool.rules.ClientRules;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A marine supplies trader in a JAFZA warehouse. Goods sold to other zone companies are outside the
 * scope of VAT and most of the rest is exported, so it is usually owed a refund. Its refunds are at
 * every stage, one of them under an FTA review, a duplicated supplier invoice went on Form 211, and
 * last month's return is held up by a missing rupee rate and an export past its 90 days.
 */
final class JebelAli extends Client {

    static final String TRN = "100789012300003";

    private static final Party SHIP_CHANDLERS = Party.zone("Jebel Ali Ship Chandlers FZE", "100440500100003");
    private static final Party MARITIME_SPARES = Party.zone("Dubai Maritime Spares FZCO", "100440500200003");
    private static final Party OFFSHORE = Party.zone("Gulf Offshore Supply FZE", "100440500300003");
    private static final Party SOHAR = Party.abroad("Sohar Marine Services LLC", "OM");
    private static final Party DAMMAM = Party.abroad("Dammam Vessel Supplies Co", "SA");
    private static final Party CHENNAI = Party.abroad("Chennai Ship Spares Pvt Ltd", "IN");
    private static final Party KARACHI = Party.abroad("Karachi Port Chandlers", "PK");
    private static final Party PORT_RASHID = Party.uae("Port Rashid Marine Works LLC", "100440500400003");
    private static final Party HAMRIYA = Party.uae("Al Hamriya Boat Repairs LLC", "100440500500003");

    private static final Party STEEL = Party.zone("Jafza Steel Traders FZE", "100540600100003");
    private static final Party ROPES = Party.zone("Marine Rope & Rigging FZCO", "100540600200003");
    private static final Party PARK = Party.zone("South Zone Logistics Park FZE", "100540600300003");
    private static final Party CLEARING = Party.uae("Freightway Clearing & Forwarding LLC", "100540600400003");
    private static final Party SURVEYORS = Party.uae("Gulf Marine Surveyors LLC", "100540600500003");
    private static final Party IT = Party.uae("Harbour IT Solutions LLC", "100540600600003");
    private static final Party CRANES = Party.uae("Gulf Crane & Equipment Hire LLC", "100540600700003");

    private static final List<String> STOCK = List.of("Mooring ropes and hawsers", "Anchor chain and shackles", "Marine paint and antifouling", "Life jackets and rafts",
            "Engine spares and filters", "Deck hardware and fittings", "Navigation lights and cables");

    private final Story.Staff staff;
    private Kit.Person arjun;
    private Purchase duplicate;
    private Sale lateExport;
    private final List<Sale> exportsThisMonth = new ArrayList<>();

    JebelAli(Kit kit, Timeline time, Story.Staff staff) {
        super(kit, time, "JAM");
        this.staff = staff;
        iban = "AE570230000001234567890";
        payroll = new BigDecimal("118000.00");
        balance = new BigDecimal("1650000.00");
    }

    @Override
    java.util.Set<String> currencies() {
        return java.util.Set.of("USD", "INR");
    }

    @Override
    String legalName() {
        return "Jebel Ali Marine Supplies FZCO";
    }

    /** Every establishment is in JAFZA, so the firm's mainland check never applies, and yacht charters are entertainment. */
    @Override
    ClientRules clientRules() {
        return new ClientRules(null, null,
                List.of(blocked("CL-JAM-YACHT-CHARTER", "ENTERTAINMENT", "\\byacht charters?\\b")),
                List.of(new ClientRules.SwitchedOff("DZ-GOODS-TO-MAINLAND",
                        "All of the client's establishments are in JAFZA, so goods from other zones never reach the mainland through it")),
                null, null, null);
    }

    @Override
    void onboard() {
        LocalDate first = time.month(firstOffset()).atDay(1);
        filer = entity(legalName(), "جبل علي لتوريدات البحرية م.م.ح", TRN, "STANDALONE", null, "MONTHLY", first, null, new BigDecimal("100"), null);
        establishment(filer.id(), "JAFZA-S3", "South Zone warehouse", "DXB", true, "JAFZA South");
        settings(filer.id(), "AE070331000000012345605", false, 1);
        filer = kit.entity(filer.id());
        List<MappingService.EntryInput> codes = standardCodes();
        codes.add(blockedGl("6100", "ENTERTAINMENT"));
        mapping(filer.id(), first, "Tax codes for a designated zone company", codes);
        register(List.of(SHIP_CHANDLERS, MARITIME_SPARES, OFFSHORE, STEEL, ROPES, PARK, CLEARING, SURVEYORS, IT, CRANES, AlNoor.FM, AlNoor.UTILITIES, AlNoor.GIFTS),
                first.minusDays(4));
        agent(filer.id(), "Rami Reviewer", "30000202", first);
        arjun = kit.person("arjun-nair", "arjun@jebelali-marine.example", "Arjun Nair", "CLIENT");
        signatory(filer.id(), "Arjun Nair", arjun.email(), "General Manager", first);
        kit.grant(filer.id(), arjun, "APPROVER", null);
        kit.grant(filer.id(), staff.farhan(), "TAX_REVIEWER", null);
        kit.grant(filer.id(), staff.aisha(), "PREPARER", null);
        preparer = staff.aisha();
        reviewer = staff.farhan();
        approver = arjun;
        kit.assign(filer.id(), staff.aisha(), "PREPARER");
        kit.assign(filer.id(), staff.mariam(), "PREPARER");
        kit.assign(filer.id(), staff.farhan(), "REVIEWER");
        kit.assign(filer.id(), Kit.LINA, "PARTNER");
        kit.load(preparer, filer.id(), Templates.Kind.OPENING_ASSETS, "UPLOAD", "jam-opening-capital-assets.csv", List.of(Files.row(
                "reference", "JAM-CRN-01", "description", "Overhead cranes and high-bay racking, unit S3-14", "category", "OTHER",
                "cost_aed", new BigDecimal("6200000"), "input_vat_aed", new BigDecimal("310000"), "initial_recovery_pct", new BigDecimal("100"),
                "first_used_on", time.month(-11).atDay(1))));
        Story.connector(kit, filer.id(), "ZOHO_BOOKS", "jebel-ali", "demo-erp-token", time);
    }

    @Override
    void generate(Month m) {
        Gen g = new Gen(code + m.ym);
        exportsThisMonth.clear();
        for (int i = 0, n = g.between(10, 16); i < n; i++) {
            Party c = g.pick(List.of(SHIP_CHANDLERS, MARITIME_SPARES, OFFSHORE));
            Sale s = new Sale(next("zone", "JAM-Z-", 30001), g.day(m.ym), c, "B2B");
            s.lines.add(Line.coded(g.pick(STOCK) + ", delivered within the zone", g.amount(18000, 155000), "OOS", "GOODS", "JAFZA-S3", "4000"));
            s.paidOn = paid(s.date.plusDays(g.between(20, 45)));
            m.sales.add(s);
        }
        for (int i = 0, n = g.between(6, 9); i < n; i++) {
            Party c = g.pick(List.of(SOHAR, DAMMAM, CHENNAI, KARACHI));
            Sale s = new Sale(next("exp", "JAM-X-", 7001), g.dayBetween(m.ym, 1, 25), c, "B2B");
            s.currency = "USD";
            s.rate = Story.rate("USD", s.date);
            s.lines.add(Line.coded("Export of " + g.pick(STOCK).toLowerCase(), g.amount(8000, 60000), "Z0", "GOODS", "JAFZA-S3", "4200"));
            s.export = true;
            s.paidOn = paid(s.date.plusDays(g.between(25, 50)));
            m.sales.add(s);
            exportsThisMonth.add(s);
        }
        for (int i = 0, n = g.between(2, 3); i < n; i++) {
            Party c = g.pick(List.of(PORT_RASHID, HAMRIYA));
            Sale s = new Sale(next("ml", "JAM-M-", 9001), g.day(m.ym), c, "B2B");
            s.lines.add(Line.standard(g.pick(STOCK) + ", released to the mainland", g.amount(9000, 34000), "GOODS", "JAFZA-S3", "4100"));
            s.paidOn = paid(s.date.plusDays(30));
            m.sales.add(s);
        }
        if (g.chance(0.6)) {
            Sale s = new Sale(next("ml", "JAM-M-", 9001), g.day(m.ym), PORT_RASHID, "B2B");
            s.lines.add(Line.standard("Rope inspection and load testing", g.amount(4200, 9800), "SERVICES", "JAFZA-S3", "4110"));
            s.paidOn = paid(s.date.plusDays(30));
            m.sales.add(s);
        }
        for (int i = 0, n = g.between(8, 11); i < n; i++) {
            Party supplier = g.pick(List.of(STEEL, ROPES, ROPES));
            Purchase p = bill(g, m, supplier, supplier == STEEL ? "JST-" : "MRR-", "Stock: " + g.pick(STOCK).toLowerCase(), g.amount(14000, 110000), "GOODS", "5000");
            p.lines.set(0, Line.coded(p.lines.getFirst().description(), p.lines.getFirst().net(), "OOS", "GOODS", "JAFZA-S3", "5000"));
            m.purchases.add(p);
        }
        m.purchases.add(bill(g, m, PARK, "SZL-", "Warehouse lease, South Zone unit S3-14, " + m.ym, new BigDecimal("96000.00"), "SERVICES", "6200"));
        for (int i = 0, n = g.between(4, 6); i < n; i++) {
            m.purchases.add(bill(g, m, CLEARING, "FCF-", "Customs clearance and documentation", g.amount(4200, 15800), "SERVICES", "5100"));
        }
        m.purchases.add(bill(g, m, CRANES, "GCE-", "Forklift and mobile crane hire", g.amount(38000, 61000), "SERVICES", "5200"));
        m.purchases.add(bill(g, m, SURVEYORS, "GMS-", "Cargo and condition surveys", g.amount(6500, 14000), "SERVICES", "5300"));
        m.purchases.add(bill(g, m, IT, "HIT-", "IT support and warehouse system licences", new BigDecimal("8400.00"), "SERVICES", "6600"));
        m.purchases.add(bill(g, m, AlNoor.FM, "CFM-", "Warehouse cleaning", new BigDecimal("9800.00"), "SERVICES", "6500"));
        m.purchases.add(bill(g, m, AlNoor.UTILITIES, "MPW-", "Electricity and water, South Zone unit", g.amount(11000, 17500), "SERVICES", "6300"));
        if (Math.floorMod(m.offset, 3) == 2) {
            m.purchases.add(bill(g, m, AlNoor.GIFTS, "GBX-", "Staff gifts for the crew and warehouse team", g.amount(1800, 4200), "GOODS", "6130"));
        }
        scenario(m, g);
    }

    private void scenario(Month m, Gen g) {
        if (m.offset == -8) {
            Purchase hire = bill(g, m, CRANES, "GCE-", "Heavy crane hire for a vessel refit, quarterly charge", new BigDecimal("230000.00"), "SERVICES", "5200");
            m.purchases.add(hire);
            duplicate = new Purchase(hire.number + "-A", hire.date, CRANES);
            duplicate.lines.addAll(hire.lines);
            duplicate.paidOn = null;
            m.purchases.add(duplicate);
        }
        if (m.offset == -9) {
            Sale onAccount = new Sale(next("ml", "JAM-M-", 9001), g.dayBetween(m.ym, 5, 20), HAMRIYA, "B2B");
            onAccount.lines.add(Line.standard("Boat repair spares on account", new BigDecimal("26400.00"), "GOODS", "JAFZA-S3", "4100"));
            m.sales.add(onAccount);
        }
        if (m.offset == -4) {
            lateExport = new Sale(next("exp", "JAM-X-", 7001), Gen.workingDay(time.day(-4, 10)), KARACHI, "B2B");
            lateExport.currency = "USD";
            lateExport.rate = Story.rate("USD", lateExport.date);
            lateExport.lines.add(Line.coded("Export of navigation lights and cables, consignment held at the port", new BigDecimal("41800.00"), "Z0", "GOODS", "JAFZA-S3", "4200"));
            lateExport.export = true;
            m.sales.add(lateExport);
        }
        if (m.offset == -1) {
            // Two lines exported from Zoho without a tax code. The zone and export rules settle them, for a person to confirm.
            m.sales.stream().filter(s -> s.customer.designatedZone()).findFirst().ifPresent(s -> s.lines.replaceAll(l -> l.withCode(null)));
            exportsThisMonth.getFirst().lines.replaceAll(l -> l.withCode(null));
            Sale rupees = new Sale(next("exp", "JAM-X-", 7001), Story.missingInrRateDay(time), CHENNAI, "B2B");
            rupees.currency = "INR";
            rupees.rate = BigDecimal.ONE;
            rupees.lines.add(Line.coded("Export of engine spares and filters, invoiced in rupees", new BigDecimal("1850000.00"), "Z0", "GOODS", "JAFZA-S3", "4200"));
            rupees.export = true;
            m.sales.add(rupees);
        }
    }

    @Override
    void beforeClose(TaxPeriod p, int offset) {
        for (Sale s : exportsThisMonth) {
            exportEvidence(p, s, time.past(Gen.workingDay(s.date.plusDays(3))), "Jebel Ali Port");
        }
        kit.as(preparer, () -> kit.adjustments.runSupplierReversal(p));
        if (offset == -6) {
            journal(p, "9", "960.00", "Input VAT on a survey invoice received after the cut-off", "Gulf Marine Surveyors invoiced late; posted by journal", true);
        }
    }

    @Override
    BigDecimal timingDifference(int offset) {
        return offset == -3 ? new BigDecimal("640.00") : null;
    }

    @Override
    void afterFiling(TaxPeriod p, int offset, LocalDate filedOn, String reference) {
        if (net(p.id()).signum() >= 0) {
            return;
        }
        switch (offset) {
            case -10 -> refund(p, filedOn, "0001", -8, null, true);
            case -7 -> refund(p, filedOn, "0002", -5, new BigDecimal("1250.00"), true);
            case -4 -> refund(p, filedOn, "0003", -1, null, false);
            case -3 -> refund(p, filedOn, "0004", null, null, false);
            case -2 -> {
                RefundService.Position position = kit.as(reviewer, () -> kit.refunds.create(p.id(), new RefundService.CreateInput(null)));
                kit.as(reviewer, () -> kit.refunds.generatePack(position.request().id()));
            }
            default -> { }
        }
    }

    /** A refund claimed after filing. decidedIn is the month of the FTA's decision, null while it is still with them. */
    private void refund(TaxPeriod p, LocalDate filedOn, String number, Integer decidedIn, BigDecimal disallowed, boolean receive) {
        RefundService.Position position = kit.as(reviewer, () -> kit.refunds.create(p.id(), new RefundService.CreateInput(null)));
        UUID request = position.request().id();
        BigDecimal claimed = position.excessAed();
        kit.as(reviewer, () -> kit.refunds.generatePack(request));
        LocalDate submitted = Gen.nextWorkingDay(filedOn.plusDays(3));
        String reference = "VAT311-" + TRN.substring(9) + "-" + number;
        UUID ack = kit.evidence(reviewer, filer.id(), p.id(), "vat311-acknowledgement-" + number + ".pdf", "Refund request acknowledgement", null,
                "EmaraTax refund request acknowledgement", List.of("Form VAT311", "TRN " + TRN, "Refund of AED " + claimed.toPlainString(), "Submitted on " + submitted));
        kit.as(reviewer, () -> kit.refunds.recordSubmission(request, new RefundService.SubmissionInput(reference, submitted, ack)));
        if (decidedIn == null) {
            return;
        }
        BigDecimal approved = disallowed == null ? claimed : claimed.subtract(disallowed);
        LocalDate decided = Gen.workingDay(time.day(decidedIn, 10));
        LocalDate received = Gen.workingDay(time.day(decidedIn, 18));
        if (receive) {
            scheduled.add(new Files.BankLine(received, approved, false, "FTA-REFUND-" + number, "Federal Tax Authority", "FTA VAT REFUND " + reference));
        }
        at(decidedIn, () -> {
            kit.as(reviewer, () -> kit.refunds.recordDecision(request, new RefundService.DecisionInput(true, disallowed == null ? null : approved, decided,
                    disallowed == null ? "Approved in full" : "Approved less AED " + disallowed + ": one clearing agent's invoice did not show our TRN")));
            if (receive) {
                UUID txn = bankTxn("FTA VAT REFUND " + reference);
                kit.as(reviewer, () -> kit.refunds.recordReceipt(request, new RefundService.ReceiptInput(received, approved, txn)));
            }
        });
    }

    @Override
    void current(TaxPeriod p) {
        for (Sale s : exportsThisMonth) {
            if (!"INR".equals(s.currency)) {
                exportEvidence(p, s, time.past(Gen.workingDay(s.date.plusDays(3))), "Jebel Ali Port");
            }
        }
        kit.load(preparer, filer.id(), Templates.Kind.AP, "UPLOAD", "jam-purchases-void-duplicate.csv", List.of(Files.row(
                "doc_type", "TAX_INVOICE", "doc_number", duplicate.number, "doc_date", duplicate.date, "net_amount", duplicate.lines.getFirst().net(),
                "vat_amount", duplicate.lines.getFirst().vat(), "line_no", 1, "counterparty_trn", CRANES.trn(), "void", "true")));
        TaxPeriod march = kit.period(filer.id(), duplicate.date);
        UUID statement = kit.evidence(reviewer, filer.id(), march.id(), "gulf-crane-statement.pdf", "Supplier statement showing one crane hire invoice", null,
                "Supplier statement, Gulf Crane & Equipment Hire", List.of("Invoice " + duplicate.number.replace("-A", "") + " for AED 241,500.00 (VAT AED 11,500.00)",
                        "No invoice " + duplicate.number + " exists. It was keyed twice in our purchase ledger."));
        CorrectionService.CorrectionCase c = kit.as(reviewer, () -> kit.corrections.open(march.id(), new CorrectionService.CaseInput(
                "Crane hire invoice keyed twice in the purchase ledger, so input tax of AED 11,500 was claimed twice", statement, List.of())));
        DisclosureService.View view = kit.as(reviewer, () -> kit.disclosures.create(c.id(), new DisclosureService.CreateInput(Gen.workingDay(time.day(-1, 13)),
                "Input tax of AED 11,500 claimed twice on one crane hire invoice, which reduced the refund due.")));
        UUID disclosure = view.disclosure().id();
        kit.as(partner, () -> kit.disclosures.approve(disclosure, true));
        kit.as(reviewer, () -> kit.disclosures.generatePack(disclosure));
        LocalDate submitted = Gen.workingDay(time.day(-1, 22));
        UUID ack = kit.evidence(reviewer, filer.id(), march.id(), "form-211-acknowledgement.pdf", "Form 211 acknowledgement", null, "EmaraTax voluntary disclosure acknowledgement",
                List.of("Form 211", "TRN " + TRN, "Tax difference AED 11,500.00", "Submitted on " + submitted));
        kit.as(reviewer, () -> kit.disclosures.recordSubmission(disclosure, new DisclosureService.SubmissionInput("VD-" + TRN.substring(9) + "-2026-0001", submitted, ack)));

        journal(p, "9", "410.00", "Input VAT on clearing invoices posted to the wrong account", "Reclassed from the duty account to the VAT input account", true);
        suggest(p);
        gl(p, BigDecimal.ZERO);
        tidy(p, false);
        for (ReconciliationService.Exception e : kit.open(p.id())) {
            if ("T08".equals(e.ruleId())) {
                kit.comment(preparer, p.id(), "EXCEPTION", e.id().toString(), "Karachi consignment held at the port after a documents query. Asked the forwarder for the exit certificate.");
                kit.as(Kit.OMAR, () -> kit.recon.assign(e.id(), staff.aisha().id(), time.today.plusDays(3)));
            }
            if ("D01".equals(e.ruleId())) {
                kit.comment(reviewer, p.id(), "EXCEPTION", e.id().toString(), "No rupee rate for that day in the upload. Pull the rate feed on the Practice page; the held invoice loads by itself.");
            }
        }
        kit.comment(staff.farhan(), p.id(), "PERIOD", p.id().toString(),
                "Two things stop this return: the Karachi export past its 90 days (T08) and the rupee invoice waiting for a rate (D01). Refund to be claimed once filed.");
    }

    @Override
    void after() {
        LocalDate notified = Gen.workingDay(time.day(-1, 5));
        AuditCaseService.View v = kit.as(reviewer, () -> kit.auditCases.open(filer.id(), new AuditCaseService.CaseInput("FTA-RA-2026-0060391", notified,
                time.month(-3).atDay(1), time.month(-3).atEndOfMonth(), "Review of refund claim VAT311-" + TRN.substring(9) + "-0004 before payment.")));
        UUID id = v.auditCase().id();
        AuditCaseService.View r1 = kit.as(reviewer, () -> kit.auditCases.addRequest(id, new AuditCaseService.RequestInput(
                "Exit certificates and bills of lading for the month's exports", notified, notified.plusDays(14))));
        UUID first = r1.requests().getFirst().id();
        UUID pack = kit.evidence(reviewer, filer.id(), null, "export-evidence-pack.pdf", "Export evidence sent to the FTA", null, "Export evidence pack",
                List.of("Exit certificates and bills of lading for every zero-rated export in " + time.month(-3)));
        kit.as(reviewer, () -> kit.auditCases.respond(first, new AuditCaseService.ResponseInput("All exit certificates and bills of lading sent", notified.plusDays(13), List.of(pack))));
        kit.as(reviewer, () -> kit.auditCases.addRequest(id, new AuditCaseService.RequestInput("Warehouse lease agreement and proof of rent payments",
                Gen.workingDay(time.day(-1, 24)), time.today.plusDays(2))));
        kit.as(reviewer, () -> kit.auditCases.generateFaf(id));
    }

    @Override
    String swingReason(String box, boolean up) {
        return switch (box) {
            case "4" -> up ? "More shipments left for Oman, Saudi Arabia and India." : "Fewer export shipments this month.";
            case "1b" -> up ? "More stock released to mainland repair yards." : "Less stock released to the mainland this month.";
            case "9" -> up ? "Higher crane hire and clearing costs this month." : "Lower crane hire and clearing costs this month.";
            default -> super.swingReason(box, up);
        };
    }

    private Purchase bill(Gen g, Month m, Party supplier, String prefix, String description, BigDecimal net, String goods, String gl) {
        Purchase p = new Purchase(next("bill-" + prefix, prefix + m.ym.getYear() % 100 + "-", 4101 + Math.abs(prefix.hashCode() % 500)), g.day(m.ym), supplier);
        p.lines.add(Line.standard(description, net, goods, "JAFZA-S3", gl));
        p.paidOn = paid(p.date.plusDays(g.between(20, 40)));
        p.paidThroughBank = g.chance(0.4);
        return p;
    }

    private LocalDate paid(LocalDate d) {
        return d.isBefore(time.today) ? d : null;
    }
}
