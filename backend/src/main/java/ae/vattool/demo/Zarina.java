package ae.vattool.demo;

import ae.vattool.adjustments.CorrectionService;
import ae.vattool.auditcase.AuditCaseService;
import ae.vattool.corrections.RefundService;
import ae.vattool.demo.Docs.Line;
import ae.vattool.demo.Docs.Margin;
import ae.vattool.demo.Docs.Month;
import ae.vattool.demo.Docs.Party;
import ae.vattool.demo.Docs.Purchase;
import ae.vattool.demo.Docs.Sale;
import ae.vattool.ingestion.Templates;
import ae.vattool.masterdata.MappingService;
import ae.vattool.masterdata.TaxPeriod;
import ae.vattool.rules.ClientRules;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A jeweller in the Deira Gold Souk with a Sharjah branch. Bullion is bought under the domestic reverse
 * charge and imported through customs, tourists get their VAT back at the till, finished pieces are
 * exported to India, and pre-owned jewellery is sold under the margin scheme. Last month's return is
 * approved and waiting to be filed.
 */
final class Zarina extends Client {

    static final String TRN = "100678901200003";

    private static final Party HORIZON = Party.uae("Horizon Corporate Gifts LLC", "100430400100003");
    private static final Party JAIPUR = Party.abroad("Jaipur Gems & Jewels Pvt Ltd", "IN");
    private static final Party CHENNAI = Party.abroad("Chennai Bridal Jewellers Pvt Ltd", "IN");
    private static final Party MUSCAT = Party.abroad("Muscat Jewellery House LLC", "OM");

    private static final Party AL_WASL_BULLION = Party.uae("Al Wasl Bullion Trading LLC", "100530500100003");
    private static final Party SOUK_REFINERY = Party.uae("Souk Refinery LLC", "100530500200003");
    private static final Party WORKSHOP = Party.uae("Karama Jewellery Workshop LLC", "100530500300003");
    private static final Party MANUFACTURERS = Party.uae("Deira Jewellery Manufacturers LLC", "100530500400003");
    private static final Party PACKAGING = Party.uae("Velvet Box Packaging LLC", "100530500500003");
    private static final Party DEIRA_PROPS = Party.uae("Deira Souk Properties LLC", "100530500600003");
    private static final Party SHJ_SOUK = Party.uae("Sharjah Central Souk Management", "100530500700003");
    private static final Party CARDS = Party.uae("Gulf Merchant Payments LLC", "100530500800003");
    private static final Party TAX_FREE = Party.uae("TaxFree Gateway Services LLC", "100530500900003");

    private static final List<String> PRE_OWNED = List.of("Pre-owned 21k bangle set", "Pre-owned 18k necklace and earrings", "Pre-owned antique-style choker",
            "Pre-owned wedding band pair", "Pre-owned 22k pendant", "Pre-owned bracelet with coloured stones");

    private final Story.Staff staff;
    private Kit.Person priya;
    private Sale duplicateRefund;
    private final List<Sale> exportsThisMonth = new ArrayList<>();

    Zarina(Kit kit, Timeline time, Story.Staff staff) {
        super(kit, time, "ZJB");
        this.staff = staff;
        bankFormat = "CAMT053";
        iban = "AE180440000004567890123";
        payroll = new BigDecimal("265000.00");
        balance = new BigDecimal("9800000.00");
    }

    @Override
    java.util.Set<String> currencies() {
        return java.util.Set.of("USD");
    }

    @Override
    String legalName() {
        return "Zarina Jewellery & Bullion Trading LLC";
    }

    /** Bullion is described by bar size in the trade, and exports are large enough that a lower zero-rated figure is noise. */
    @Override
    ClientRules clientRules() {
        return new ClientRules(null, List.of(pattern("CL-ZJB-BAR-SIZES", "GOLD", "\\bkilo ?bars?\\b", "\\btola bars?\\b")),
                null, null, null, new ClientRules.Analytics(null, null, new BigDecimal("250000"), null), null);
    }

    @Override
    void onboard() {
        LocalDate first = time.month(firstOffset()).atDay(1);
        filer = entity(legalName(), "زارينا للمجوهرات وتجارة السبائك ش.ذ.م.م", TRN, "STANDALONE", null, "MONTHLY", first, null, new BigDecimal("100"), new BigDecimal("150000"));
        establishment(filer.id(), "DXB-GSK", "Gold Souk showroom, Deira", "DXB", false, null);
        establishment(filer.id(), "SHJ-CSK", "Central Souk branch, Sharjah", "SHJ", false, null);
        settings(filer.id(), "AE070331000000012345604", false, 1);
        filer = kit.entity(filer.id());
        List<MappingService.EntryInput> codes = standardCodes();
        codes.add(drc("DRC-GOLD", "GOLD"));
        codes.add(blockedGl("6100", "ENTERTAINMENT"));
        mapping(filer.id(), first, "Oracle tax rate codes, with the gold reverse charge code", codes);
        register(List.of(AL_WASL_BULLION, SOUK_REFINERY, WORKSHOP, MANUFACTURERS, PACKAGING, DEIRA_PROPS, SHJ_SOUK, CARDS, TAX_FREE, DesertRose.SECURITY, Falcon.INSURANCE),
                first.minusDays(7));
        agent(filer.id(), "Rami Reviewer", "30000202", first);
        priya = kit.person("priya-menon", "priya@zarina-jewellery.example", "Priya Menon", "CLIENT");
        signatory(filer.id(), "Priya Menon", priya.email(), "Chief Financial Officer", first);
        kit.grant(filer.id(), priya, "APPROVER", null);
        kit.grant(filer.id(), staff.mariam(), "PREPARER", null);
        kit.grant(filer.id(), staff.farhan(), "TAX_REVIEWER", null);
        preparer = staff.mariam();
        reviewer = staff.farhan();
        approver = priya;
        kit.assign(filer.id(), staff.mariam(), "PREPARER");
        kit.assign(filer.id(), staff.farhan(), "REVIEWER");
        kit.assign(filer.id(), Kit.LINA, "PARTNER");
        kit.load(preparer, filer.id(), Templates.Kind.OPENING_ASSETS, "UPLOAD", "zjb-opening-capital-assets.csv", List.of(Files.row(
                "reference", "ZJB-VLT-01", "description", "Security vault, strong room and showroom fit-out, Deira", "category", "OTHER",
                "cost_aed", new BigDecimal("5600000"), "input_vat_aed", new BigDecimal("280000"), "initial_recovery_pct", new BigDecimal("100"),
                "first_used_on", time.month(-15).atDay(1))));
        Story.connector(kit, filer.id(), "ORACLE_FUSION", "zarina", "demo-erp-token", time);
    }

    @Override
    void generate(Month m) {
        Gen g = new Gen(code + m.ym);
        exportsThisMonth.clear();
        double season = 1 + 0.15 * Math.cos(2 * Math.PI * (m.ym.getMonthValue() - 12) / 12.0);
        for (LocalDate d : Gen.tradingDays(m.ym)) {
            BigDecimal dubai = g.amount(42000, 118000 * season);
            m.sales.add(pos("DXB-GSK", "DXB", d, dubai));
            m.sales.add(pos("SHJ-CSK", "SHJ", d, g.amount(14000, 43000 * season)));
            Sale refund = new Sale("TRF-DXB-" + d.toString().replace("-", ""), d, null, "B2C");
            refund.type = "TOURIST_REFUND";
            BigDecimal refunded = dubai.multiply(BigDecimal.valueOf(0.07 + g.amount(0, 0.04).doubleValue())).setScale(2, RoundingMode.HALF_UP);
            refund.lines.add(new Line("VAT refunded to tourists, daily total", refunded, Gen.vat(refunded), "TR", "GOODS", "DXB-GSK", "4900", null));
            m.sales.add(refund);
            if (m.offset == -5 && d.getDayOfMonth() == Gen.workingDay(time.day(-5, 15)).getDayOfMonth()) {
                duplicateRefund = new Sale(refund.number + "-B", d, null, "B2C");
                duplicateRefund.type = "TOURIST_REFUND";
                duplicateRefund.lines.addAll(refund.lines);
                m.sales.add(duplicateRefund);
            }
        }
        for (int i = 0, n = g.between(3, 6); i < n; i++) {
            Party c = g.pick(List.of(JAIPUR, CHENNAI, CHENNAI, MUSCAT));
            Sale s = new Sale(next("exp", "ZJB-EXP-", 6001), g.dayBetween(m.ym, 2, 24), c, "B2B");
            s.currency = "USD";
            s.rate = Story.rate("USD", s.date);
            s.lines.add(Line.coded("22k jewellery consignment, " + g.between(4, 18) + " kg", g.amount(42000, 245000), "Z0", "GOODS", "DXB-GSK", "4200"));
            s.export = true;
            s.paidOn = paid(s.date.plusDays(g.between(10, 30)));
            m.sales.add(s);
            exportsThisMonth.add(s);
        }
        if (m.offset == -1) {
            // A new export customer was set up in Oracle without a tax rate code, so the place-of-supply rules decide.
            exportsThisMonth.getFirst().lines.replaceAll(l -> l.withCode(null));
        }
        for (int i = 0, n = g.between(1, 2); i < n; i++) {
            Sale s = new Sale(next("corp", "ZJB-INV-", 8101), g.day(m.ym), HORIZON, "B2B");
            s.lines.add(Line.standard("Corporate gift watches and pens, engraved", g.amount(38000, 145000), "GOODS", "DXB-GSK", "4000"));
            // One corporate order from eight months ago is still unpaid: Horizon is disputing the engraving.
            s.paidOn = m.offset == -8 && i == 0 ? null : paid(s.date.plusDays(30));
            m.sales.add(s);
        }
        for (int i = 0, n = g.between(6, 12); i < n; i++) {
            BigDecimal bought = g.round(2000, 25000, 50);
            BigDecimal sold = bought.multiply(BigDecimal.valueOf(1.15 + g.amount(0, 0.25).doubleValue())).setScale(0, RoundingMode.HALF_UP).setScale(2);
            m.margins.add(new Margin(next("mrg", "ZJB-PO-", 1201), g.day(m.ym), "SECOND_HAND", bought, g.round(100, 600, 25), sold,
                    g.chance(0.7) ? "DXB-GSK" : "SHJ-CSK", "Walk-in customer", g.pick(PRE_OWNED)));
        }
        purchases(m, g, season);
        for (int i = 0, n = g.between(1, 2); i < n; i++) {
            m.imports.add(Docs.Import.declaration("101-" + m.ym.getYear() + "-" + g.digits(7), g.dayBetween(m.ym, 2, 26), g.round(1900000, 4800000, 1000),
                    "Alpine Precious Metals Logistics SA", "CH", "DXB-GSK", "Precious metal bars, 999.9 fineness"));
        }
        if (Math.floorMod(m.offset, 4) == 2) {
            m.imports.add(new Docs.Import("IMPORT_ADJUSTMENT", "101-" + m.ym.getYear() + "-" + g.digits(7) + "-A", g.dayBetween(m.ym, 20, 27), g.round(20000, 60000, 100),
                    null, "Alpine Precious Metals Logistics SA", "CH", "DXB-GSK", "Value adjustment after assay"));
            Docs.Import a = m.imports.getLast();
            m.imports.set(m.imports.size() - 1, new Docs.Import(a.type(), a.number(), a.date(), a.value(), Gen.vat(a.value()), a.supplier(), a.country(), a.est(), a.description()));
        }
    }

    private void purchases(Month m, Gen g, double season) {
        for (int i = 0, n = g.between(2, 3); i < n; i++) {
            Party dealer = g.pick(List.of(AL_WASL_BULLION, SOUK_REFINERY));
            Purchase p = new Purchase(next("bul-" + dealer.trn(), dealer == AL_WASL_BULLION ? "AWB-" : "SRF-", 40001), g.day(m.ym), dealer);
            p.currency = "USD";
            p.rate = Story.rate("USD", p.date);
            p.lines.add(Line.coded("Gold bars 1 kg, 999.9 fineness, for manufacturing", g.amount(330000, 980000 * season), "DRC-GOLD", "GOODS", "DXB-GSK", "5000"));
            p.paidOn = paid(p.date.plusDays(2));
            m.purchases.add(p);
        }
        m.purchases.add(bill(g, m, WORKSHOP, "KJW-", "Jewellery making charges", g.amount(60000, 150000), "SERVICES", "5100"));
        m.purchases.add(bill(g, m, MANUFACTURERS, "DJM-", "Finished jewellery for the showroom", g.amount(180000, 420000), "GOODS", "5000"));
        m.purchases.add(bill(g, m, PACKAGING, "VBP-", "Presentation boxes and bags", g.amount(7800, 19500), "GOODS", "5200"));
        m.purchases.add(bill(g, m, DEIRA_PROPS, "DSP-", "Showroom rent, Deira souk unit 14, " + m.ym, new BigDecimal("120000.00"), "SERVICES", "6200"));
        m.purchases.add(bill(g, m, SHJ_SOUK, "SCS-", "Shop rent, Central Souk, " + m.ym, new BigDecimal("45000.00"), "SERVICES", "6200"));
        m.purchases.add(bill(g, m, DesertRose.SECURITY, "SSS-", "Armed guards and cash-in-transit", new BigDecimal("28000.00"), "SERVICES", "6510"));
        m.purchases.add(bill(g, m, CARDS, "GMP-", "Card acquiring fees", g.amount(32000, 61000 * season), "SERVICES", "6900"));
        m.purchases.add(bill(g, m, TAX_FREE, "TFG-", "Tourist refund scheme operator fees", g.amount(6200, 11800), "SERVICES", "6910"));
        if (Math.floorMod(m.offset, 3) == 1) {
            m.purchases.add(bill(g, m, Falcon.INSURANCE, "GSI-", "Jewellers block insurance, quarterly", new BigDecimal("35000.00"), "SERVICES", "6800"));
        }
        if (m.offset == -7) {
            Purchase stock = new Purchase("DJM-26-0400", Gen.workingDay(time.day(-7, 6)), MANUFACTURERS);
            stock.lines.add(Line.standard("Finished 22k jewellery for the Akshaya Tritiya export orders", new BigDecimal("4200000.00"), "GOODS", "DXB-GSK", "5000"));
            stock.paidOn = paid(stock.date.plusDays(20));
            m.purchases.add(stock);
        }
    }

    /** A corporate order invoiced on the last day went to the GL in that month, while its tax point is in the next. */
    @Override
    BigDecimal timingDifference(int offset) {
        return offset == -4 ? new BigDecimal("2450.00") : null;
    }

    @Override
    String timingNote(TaxPeriod p) {
        return "Horizon corporate order posted in the GL on " + p.periodEnd() + ". Its tax point is the delivery on the 2nd of next month.";
    }

    @Override
    void beforeClose(TaxPeriod p, int offset) {
        for (Sale s : exportsThisMonth) {
            exportEvidence(p, s, time.past(Gen.workingDay(s.date.plusDays(2))), "Dubai International Airport, cargo village");
        }
        kit.as(preparer, () -> kit.adjustments.runSupplierReversal(p));
        if (offset == -9) {
            journal(p, "1b", "870.00", "Output VAT on jewellery sold to staff at a discount and not put through the till",
                    "Staff purchases were recorded in the payroll system, not the POS", true);
        }
    }

    @Override
    void afterFiling(TaxPeriod p, int offset, LocalDate filedOn, String reference) {
        if (offset != -7 || net(p.id()).signum() >= 0) {
            return;
        }
        RefundService.Position position = kit.as(reviewer, () -> kit.refunds.create(p.id(), new RefundService.CreateInput(null)));
        UUID request = position.request().id();
        BigDecimal amount = position.excessAed();
        kit.as(reviewer, () -> kit.refunds.generatePack(request));
        LocalDate submitted = Gen.nextWorkingDay(filedOn.plusDays(2));
        UUID ack = kit.evidence(reviewer, filer.id(), p.id(), "vat311-acknowledgement.pdf", "Refund request acknowledgement", null, "EmaraTax refund request acknowledgement",
                List.of("Form VAT311", "TRN " + TRN, "Refund of AED " + amount.toPlainString(), "Submitted on " + submitted));
        kit.as(reviewer, () -> kit.refunds.recordSubmission(request, new RefundService.SubmissionInput("VAT311-" + TRN.substring(9) + "-0001", submitted, ack)));
        LocalDate received = Gen.workingDay(time.day(-5, 19));
        scheduled.add(new Files.BankLine(received, amount, false, "FTA-REFUND-ZJB1", "Federal Tax Authority", "FTA VAT REFUND VAT311-" + TRN.substring(9) + "-0001"));
        at(-5, () -> {
            kit.as(reviewer, () -> kit.refunds.recordDecision(request, new RefundService.DecisionInput(true, null, Gen.workingDay(time.day(-5, 11)), "Approved in full")));
            UUID txn = bankTxn("FTA VAT REFUND VAT311-" + TRN.substring(9) + "-0001");
            kit.as(reviewer, () -> kit.refunds.recordReceipt(request, new RefundService.ReceiptInput(received, amount, txn)));
        });
    }

    @Override
    void current(TaxPeriod p) {
        beforeClose(p, -1);
        kit.load(preparer, filer.id(), Templates.Kind.AR, "UPLOAD", "zjb-sales-void-duplicate-tourist-refund.csv", List.of(Files.row(
                "doc_type", "TOURIST_REFUND", "doc_number", duplicateRefund.number, "doc_date", duplicateRefund.date,
                "net_amount", duplicateRefund.lines.getFirst().net(), "vat_amount", duplicateRefund.lines.getFirst().vat(), "line_no", 1, "void", "true")));
        TaxPeriod may = kit.period(filer.id(), duplicateRefund.date);
        UUID note = kit.evidence(reviewer, filer.id(), may.id(), "taxfree-portal-batch-report.pdf", "Tourist refund batch report from the operator", null,
                "TaxFree Gateway batch report", List.of("Batch of " + duplicateRefund.date + " exported twice, as " + duplicateRefund.number.replace("-B", "") + " and " + duplicateRefund.number,
                        "Only one batch was paid to tourists"));
        kit.as(reviewer, () -> kit.corrections.open(may.id(), new CorrectionService.CaseInput(
                "The tourist refund batch of " + duplicateRefund.date + " was exported twice from the operator's portal, so box 2 was overstated.", note, List.of())));
        suggest(p);
        gl(p, BigDecimal.ZERO);
        tidy(p, true);
        signoff(p);
        kit.pack(reviewer, p.id());
        kit.comment(reviewer, p.id(), "PERIOD", p.id().toString(), "Approved by Priya. Pack generated. File on EmaraTax before the 28th, then record the acknowledgement here.");
    }

    @Override
    void after() {
        LocalDate notified = Gen.workingDay(time.day(-9, 12));
        AuditCaseService.View v = kit.as(reviewer, () -> kit.auditCases.open(filer.id(), new AuditCaseService.CaseInput("FTA-TA-2026-0029944", notified,
                time.month(firstOffset()).atDay(1), time.month(-10).atEndOfMonth(), "Audit of the reverse charge on bullion purchases and of imports through customs.")));
        UUID id = v.auditCase().id();
        AuditCaseService.View r = kit.as(reviewer, () -> kit.auditCases.addRequest(id, new AuditCaseService.RequestInput(
                "Bullion purchase invoices with the dealers' reverse charge statements", notified, notified.plusDays(14))));
        UUID request = r.requests().getFirst().id();
        UUID file = kit.evidence(reviewer, filer.id(), null, "bullion-purchases-for-fta.pdf", "Bullion purchase invoices sent to the FTA", null,
                "Bullion purchases under the reverse charge", List.of("Invoices from Al Wasl Bullion Trading and Souk Refinery", "Each states that the recipient accounts for the VAT"));
        kit.as(reviewer, () -> kit.auditCases.respond(request, new AuditCaseService.ResponseInput("Invoices and dealer statements sent", notified.plusDays(10), List.of(file))));
        kit.as(reviewer, () -> kit.auditCases.generateFaf(id));
        kit.as(partner, () -> kit.auditCases.close(id, new AuditCaseService.CloseInput(
                "Closed on " + Gen.workingDay(time.day(-6, 25)) + ". The FTA confirmed the reverse charge treatment of bullion purchases; no penalty.", true)));
    }

    @Override
    String swingReason(String box, boolean up) {
        return switch (box) {
            case "3", "10" -> up ? "More bullion bought ahead of the wedding season." : "Less bullion bought this month.";
            case "4" -> up ? "Large export orders to India this month." : "Fewer export orders this month.";
            case "6", "7" -> up ? "More bullion imported through customs this month." : "Less bullion imported this month.";
            case "2" -> "Tourist refunds follow visitor numbers.";
            default -> super.swingReason(box, up);
        };
    }

    private Sale pos(String est, String branch, LocalDate day, BigDecimal net) {
        Sale s = new Sale("POS-" + branch + "-" + day.toString().replace("-", ""), day, null, "B2C");
        s.type = "B2C_SALE";
        s.lines.add(Line.standard("Jewellery sales, daily till total", net, "GOODS", est, "4000"));
        s.paidOn = paid(Gen.nextWorkingDay(day.plusDays(1)));
        return s;
    }

    private Purchase bill(Gen g, Month m, Party supplier, String prefix, String description, BigDecimal net, String goods, String gl) {
        Purchase p = new Purchase(next("bill-" + prefix, prefix + m.ym.getYear() % 100 + "-", 4001 + Math.abs(prefix.hashCode() % 300)), g.day(m.ym), supplier);
        p.lines.add(Line.standard(description, net, goods, "DXB-GSK", gl));
        p.paidOn = paid(p.date.plusDays(g.between(15, 35)));
        p.paidThroughBank = g.chance(0.3);
        return p;
    }

    private LocalDate paid(LocalDate d) {
        return d.isBefore(time.today) ? d : null;
    }
}
