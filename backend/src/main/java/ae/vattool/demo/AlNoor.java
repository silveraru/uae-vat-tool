package ae.vattool.demo;

import ae.vattool.adjustments.BadDebtService;
import ae.vattool.adjustments.CorrectionService;
import ae.vattool.auditcase.AuditCaseService;
import ae.vattool.corrections.DisclosureService;
import ae.vattool.corrections.RefundService;
import ae.vattool.demo.Docs.Line;
import ae.vattool.demo.Docs.Margin;
import ae.vattool.demo.Docs.Month;
import ae.vattool.demo.Docs.Party;
import ae.vattool.demo.Docs.Purchase;
import ae.vattool.demo.Docs.Sale;
import ae.vattool.ingestion.Templates;
import ae.vattool.ingestion.einvoice.EinvoiceEvent;
import ae.vattool.ledger.LineController;
import ae.vattool.masterdata.MappingService;
import ae.vattool.masterdata.TaxPeriod;
import ae.vattool.reconciliation.ReconciliationService;
import ae.vattool.rules.ClientRules;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A furniture and carpet trader with a Dubai showroom and an Abu Dhabi warehouse, on e-invoicing since
 * July as a pilot. Its year has a refund month after a warehouse fit-out, a hotel that went into
 * liquidation, a disputed supplier invoice, a hand-written invoice found after filing, and an FTA audit.
 * Last month's return is with the reviewer, waiting for the partner and the client's e-signature.
 */
final class AlNoor extends Client {

    static final String TRN = "100345678900003";

    private static final Party PALM = Party.uae("Palm Crescent Hospitality LLC", "100400100200003");
    private static final Party MARINA = Party.uae("Marina Interiors Contracting LLC", "100400100300003");
    private static final Party SAADIYAT = Party.uae("Saadiyat Living Residences LLC", "100400100400003");
    private static final Party CREEK = Party.uae("Creekside Serviced Apartments LLC", "100400100500003");
    private static final Party REEM = Party.uae("Al Reem Office Fitout LLC", "100400100600003");
    private static final Party SEAVIEW = Party.uae("Seaview Boutique Hotel LLC", "100400100700003");
    private static final Party HOUSING = Party.uae("Federal Housing Programme Office", "100400100800003");
    private static final Party RIYADH = Party.abroad("Riyadh Home Gallery Co", "SA");
    private static final Party MUSCAT = Party.abroad("Muscat Living Trading LLC", "OM");
    private static final Party MANAMA = Party.abroad("Manama Decor WLL", "BH");

    private static final Party TIMBER = Party.onPlatform("Gulf Timber & Board LLC", "100500200100003");
    private static final Party UPHOLSTERY = Party.onPlatform("Al Quoz Upholstery Workshop LLC", "100500200200003");
    private static final Party SZR_RENT = Party.uae("Sheikh Zayed Road Properties LLC", "100500200300003");
    private static final Party MUSSAFAH_RENT = Party.uae("Mussafah Industrial Estates LLC", "100500200400003");
    static final Party UTILITIES = Party.uae("Metro Power & Water Services", "100500200500003");
    private static final Party HAUL = Party.onPlatform("Desert Haul Transport LLC", "100500200600003");
    private static final Party MARKETING = Party.uae("Skyline Marketing Agency LLC", "100500200700003");
    static final Party FM = Party.onPlatform("Coastal Facility Management LLC", "100500200800003");
    private static final Party OFFICE = Party.uae("Prime Office Supplies LLC", "100500200900003");
    private static final Party LEGAL = Party.uae("Al Safa Legal Consultants", "100500201000003");
    private static final Party DHOW = Party.uae("Marina Dhow Dinners LLC", "100500201100003");
    static final Party GIFTS = Party.uae("Gift Box Trading LLC", "100500201200003");
    private static final Party RACKING = Party.uae("Warehouse Systems & Racking LLC", "100500201300003");
    private static final Party MILANO = Party.abroad("Studio Milano Design Srl", "IT");

    private static final List<String> ROOMS = List.of("Guest room furniture package", "Lobby sofas and lounge chairs", "Custom fitted wardrobes",
            "Dining tables and chairs", "Executive office desks", "Bedroom sets with headboards", "Outdoor teak terrace furniture", "Reception counters");
    private static final List<String> CARPETS = List.of("Antique Kashan rug, about 1920", "Vintage Heriz carpet, 3 x 4 m", "Antique Tabriz runner",
            "Silk Qom prayer rug, about 1950", "Antique Kazak rug", "Bakhtiari garden carpet, about 1930");
    private static final String BAD_DEBT_INVOICE = "ANH-INV-23987";

    private Kit.Person khalid;
    private Sale missedInvoice;
    private Sale failedEinvoice;
    private Sale exportWithoutEvidence;
    private final List<Sale> exportsThisMonth = new ArrayList<>();

    AlNoor(Kit kit, Timeline time) {
        super(kit, time, "ANH");
        iban = "AE460260001015833475901";
        payroll = new BigDecimal("212000.00");
        balance = new BigDecimal("2340000.00");
    }

    @Override
    java.util.Set<String> currencies() {
        return java.util.Set.of("EUR");
    }

    @Override
    String legalName() {
        return "Al Noor Home Furnishings Trading LLC";
    }

    /** Staff meals are a benefit to employees, and new suppliers are only worth a look at a higher figure for a retailer this size. */
    @Override
    ClientRules clientRules() {
        return new ClientRules(null, null,
                List.of(blocked("CL-ANH-STAFF-MEALS", "EMPLOYEE_BENEFIT", "\\bstaff meals?\\b", "\\bstaff iftar\\b")),
                null, null, new ClientRules.Analytics(new BigDecimal("150000"), null, null, null), null);
    }

    @Override
    void onboard() {
        LocalDate first = time.month(firstOffset()).atDay(1);
        filer = entity(legalName(), "النور للمفروشات المنزلية ش.ذ.م.م", TRN, "STANDALONE", null, "MONTHLY", first, time.month(-4).atDay(1),
                new BigDecimal("100"), new BigDecimal("75000"));
        establishment(filer.id(), "DXB-SZR", "Sheikh Zayed Road showroom", "DXB", false, null);
        establishment(filer.id(), "AUH-MUS", "Mussafah warehouse and showroom", "AUH", false, null);
        settings(filer.id(), "AE070331000000012345601", true, 1);
        filer = kit.entity(filer.id());

        List<MappingService.EntryInput> codes = standardCodes();
        codes.add(blockedGl("6100", "ENTERTAINMENT"));
        codes.add(blockedVendor(DHOW.trn(), "ENTERTAINMENT"));
        mapping(filer.id(), first, "Tax codes from the ERP chart, as agreed at onboarding", codes);
        List<MappingService.EntryInput> v2 = new ArrayList<>(codes);
        v2.add(blockedGl("6120", "EMPLOYEE_BENEFIT"));
        mapping(filer.id(), time.month(-4).atDay(1), "Staff welfare account 6120 blocked after the July review", v2);

        register(List.of(TIMBER, UPHOLSTERY, SZR_RENT, MUSSAFAH_RENT, UTILITIES, HAUL, MARKETING, FM, OFFICE, LEGAL, DHOW, GIFTS, RACKING), first.minusDays(10));
        agent(filer.id(), "Lina Partner", "30000201", first);
        khalid = kit.person("khalid-al-noor", "khalid@alnoor-furnishings.example", "Khalid Al Noor", "CLIENT");
        signatory(filer.id(), "Khalid Al Noor", khalid.email(), "Managing Director", first);
        signatory(filer.id(), Kit.NOOR.name(), Kit.NOOR.email(), "Finance Director", first);
        kit.grant(filer.id(), khalid, "APPROVER", null);
        preparer = Kit.DANA;
        reviewer = Kit.RAMI;
        approver = khalid;
        signedName = "Khalid Al Noor";
        kit.assign(filer.id(), Kit.DANA, "PREPARER");
        kit.assign(filer.id(), Kit.RAMI, "REVIEWER");
        kit.assign(filer.id(), Kit.LINA, "PARTNER");
        kit.load(preparer, filer.id(), Templates.Kind.OPENING_ASSETS, "UPLOAD", "anh-opening-capital-assets.csv", List.of(Files.row(
                "reference", "AUH-WH-26", "description", "Second warehouse and upholstery workshop, Mussafah M-26", "category", "REAL_ESTATE",
                "cost_aed", new BigDecimal("18500000"), "input_vat_aed", new BigDecimal("925000"), "initial_recovery_pct", new BigDecimal("100"),
                "first_used_on", time.month(-20).atDay(1))));
        Story.connector(kit, filer.id(), "ZOHO_BOOKS", "al-noor", "demo-erp-token", time);
    }

    /** Part of the second warehouse was used as staff accommodation, which is exempt residential use. */
    @Override
    BigDecimal assetUse(ae.vattool.adjustments.CapitalAssetService.Due due) {
        return new BigDecimal("96");
    }

    private static double season(YearMonth ym) {
        return 1 + 0.12 * Math.cos(2 * Math.PI * (ym.getMonthValue() - 1) / 12.0);
    }

    @Override
    void generate(Month m) {
        Gen g = new Gen(code + m.ym);
        double season = season(m.ym) * (m.offset == -8 ? 0.82 : 1);
        exportsThisMonth.clear();
        int invoices = (int) Math.round(g.between(30, 36) * season);
        List<Party> b2b = List.of(PALM, PALM, MARINA, MARINA, SAADIYAT, CREEK, REEM, HOUSING, CREEK, MARINA);
        List<Sale> recent = new ArrayList<>();
        for (int i = 0; i < invoices; i++) {
            Party c = g.pick(b2b);
            boolean abuDhabi = c == SAADIYAT || c == REEM || c == HOUSING;
            Sale s = new Sale(next("inv", "ANH-INV-", 24001), g.day(m.ym), c, c == HOUSING ? "B2G" : "B2B");
            int lines = g.between(1, 3);
            for (int l = 0; l < lines; l++) {
                boolean service = g.chance(0.25);
                s.lines.add(Line.standard(service ? g.pick(List.of("Installation and fitting", "Interior design consultation", "Upholstery repair service"))
                                : g.pick(ROOMS), g.amount(service ? 2500 : 9000, service ? 18000 : 95000 * season), service ? "SERVICES" : "GOODS",
                        abuDhabi ? "AUH-MUS" : "DXB-SZR", service ? "4010" : "4000"));
            }
            s.paidOn = paid(s.date.plusDays(g.between(28, 55)));
            einvoice(s, g);
            m.sales.add(s);
            recent.add(s);
        }
        if (m.offset == firstOffset()) {
            Sale bad = new Sale(BAD_DEBT_INVOICE, Gen.workingDay(time.day(m.offset, 6)), SEAVIEW, "B2B");
            bad.lines.add(Line.standard("Boutique hotel bedroom furniture, 16 rooms", new BigDecimal("64000.00"), "GOODS", "DXB-SZR", "4000"));
            m.sales.add(bad);
        }
        for (LocalDate d : Gen.tradingDays(m.ym)) {
            m.sales.add(pos("DXB", d, g.amount(6000, 17500 * season)));
            m.sales.add(pos("AUH", d, g.amount(2500, 8800 * season)));
        }
        int exports = g.between(2, 4);
        for (int i = 0; i < exports; i++) {
            Party c = g.pick(List.of(RIYADH, MUSCAT, MANAMA));
            Sale s = new Sale(next("exp", "ANH-EXP-", 3101), g.dayBetween(m.ym, 1, 24), c, "B2B");
            s.lines.add(Line.coded("Export of " + g.pick(ROOMS).toLowerCase(), g.amount(38000, 175000), "Z0", "GOODS", "DXB-SZR", "4020"));
            s.export = true;
            s.paidOn = paid(s.date.plusDays(g.between(35, 60)));
            einvoice(s, g);
            m.sales.add(s);
            exportsThisMonth.add(s);
        }
        if (g.chance(0.6) && recent.size() > 3) {
            Sale original = recent.get(g.between(0, recent.size() - 1));
            LocalDate on = original.date.plusDays(g.between(3, 9));
            Sale cn = new Sale(next("cn", "ANH-CN-", 801), Gen.workingDay(on.isAfter(m.ym.atEndOfMonth()) ? m.ym.atEndOfMonth().minusDays(1) : on),
                    original.customer, original.customerType);
            cn.type = "CREDIT_NOTE";
            cn.original = original.number;
            Line first = original.lines.getFirst();
            cn.lines.add(Line.standard("Credit for damaged item returned: " + first.description().toLowerCase(), g.amount(1800, 8500), first.goods(), first.est(), first.gl()));
            einvoice(cn, g);
            m.sales.add(cn);
        }
        int carpets = g.between(2, 4);
        for (int i = 0; i < carpets; i++) {
            BigDecimal bought = g.round(9000, 42000, 250);
            BigDecimal sold = bought.multiply(BigDecimal.valueOf(1.3 + g.amount(0, 0.5).doubleValue())).setScale(0, RoundingMode.HALF_UP).setScale(2);
            m.margins.add(new Margin(next("mrg", "ANH-MS-", 501), g.day(m.ym), "ANTIQUE", bought, g.round(300, 1500, 50), sold, "DXB-SZR",
                    "Private collector", g.pick(CARPETS)));
        }
        purchases(m, g, season);
        if (g.chance(0.85)) {
            m.imports.add(Docs.Import.declaration("101-" + m.ym.getYear() + "-" + g.digits(7), g.dayBetween(m.ym, 3, 26), g.round(140000, 420000, 100),
                    "Foshan Lianhua Furniture Manufacturing Co", "CN", "AUH-MUS", "Flat-pack furniture, one 40 ft container"));
        }
        scenario(m, g);
    }

    private void purchases(Month m, Gen g, double season) {
        for (int i = 0, n = g.between(4, 6); i < n; i++) {
            m.purchases.add(bill(g, m, TIMBER, "GTB-", "Hardwood boards, veneers and MDF", g.amount(14000, 58000 * season), "GOODS", "AUH-MUS", "5000"));
        }
        for (int i = 0, n = g.between(3, 4); i < n; i++) {
            m.purchases.add(bill(g, m, UPHOLSTERY, "AQU-", "Upholstery and curtain making", g.amount(7500, 24000), "SERVICES", "DXB-SZR", "5100"));
        }
        m.purchases.add(bill(g, m, SZR_RENT, "SZRP-", "Showroom rent, Sheikh Zayed Road, " + m.ym, new BigDecimal("95000.00"), "SERVICES", "DXB-SZR", "6200"));
        m.purchases.add(bill(g, m, MUSSAFAH_RENT, "MIE-", "Warehouse rent, Mussafah M-14, " + m.ym, new BigDecimal("42000.00"), "SERVICES", "AUH-MUS", "6200"));
        m.purchases.add(bill(g, m, UTILITIES, "MPW-", "Electricity and water, Dubai showroom", g.amount(8200, 15800), "SERVICES", "DXB-SZR", "6300"));
        m.purchases.add(bill(g, m, UTILITIES, "MPW-", "Electricity and water, Mussafah warehouse", g.amount(5200, 9800), "SERVICES", "AUH-MUS", "6300"));
        for (int i = 0, n = g.between(2, 3); i < n; i++) {
            m.purchases.add(bill(g, m, HAUL, "DHT-", "Delivery and installation transport", g.amount(5800, 13800), "SERVICES", "AUH-MUS", "5200"));
        }
        m.purchases.add(bill(g, m, MARKETING, "SKY-", "Social media and outdoor campaign", g.amount(11000, 29500), "SERVICES", "DXB-SZR", "6400"));
        m.purchases.add(bill(g, m, FM, "CFM-", "Cleaning and maintenance contract", new BigDecimal("9800.00"), "SERVICES", "DXB-SZR", "6500"));
        m.purchases.add(bill(g, m, OFFICE, "PRO-", "Stationery and printer consumables", g.amount(1400, 3900), "GOODS", "DXB-SZR", "6600"));
        if (Math.floorMod(m.offset, 3) == 0) {
            m.purchases.add(bill(g, m, LEGAL, "ASL-", "Legal fees, supply contract review", g.amount(6000, 15000), "SERVICES", "DXB-SZR", "6700"));
        }
        if (g.chance(0.45)) {
            m.purchases.add(bill(g, m, DHOW, "MDD-", "Client dinner cruise for hotel buyers", g.amount(3200, 7800), "SERVICES", "DXB-SZR", "6100"));
        }
        if (Math.floorMod(m.offset, 4) == 1) {
            m.purchases.add(bill(g, m, GIFTS, "GBX-", "Staff gifts for the season", g.amount(2400, 6400), "GOODS", "DXB-SZR", "6120"));
        }
        if (Math.floorMod(m.offset, 2) == 0) {
            Purchase p = new Purchase(next("milano", "SMD-", 26141), g.dayBetween(m.ym, 2, 20), MILANO);
            p.currency = "EUR";
            p.rate = Story.rate("EUR", p.date);
            p.lines.add(Line.coded("Interior design concepts for hotel projects", g.amount(5800, 11800), "RC", "SERVICES", "DXB-SZR", "5300"));
            p.paidOn = paid(p.date.plusDays(30));
            m.purchases.add(p);
        }
    }

    private void scenario(Month m, Gen g) {
        if (m.offset == -11) {
            Purchase disputed = bill(g, m, UPHOLSTERY, "AQU-", "Hotel project upholstery, workmanship disputed", new BigDecimal("48600.00"), "SERVICES", "DXB-SZR", "5100");
            disputed.date = Gen.workingDay(time.day(m.offset, 10));
            disputed.agreed = disputed.date.plusDays(30);
            disputed.paidOn = Gen.workingDay(time.day(-3, 20));
            disputed.paidThroughBank = false;
            m.purchases.add(disputed);
        }
        if (m.offset == -9) {
            Purchase freight = bill(g, m, HAUL, "DHT-", "International freight to Muscat, cross-border trucking", new BigDecimal("18500.00"), "SERVICES", "AUH-MUS", "5200");
            freight.lines.set(0, new Line("International freight to Muscat, cross-border trucking", new BigDecimal("18500.00"), BigDecimal.ZERO.setScale(2), "S5",
                    "SERVICES", "AUH-MUS", "5200", null));
            m.purchases.add(freight);
        }
        if (m.offset == -8) {
            Purchase fitout = new Purchase("WSR-26-0042", Gen.workingDay(time.day(m.offset, 12)), RACKING);
            fitout.lines.add(Line.standard("Pallet racking, mezzanine floor and fit-out, Mussafah warehouse", new BigDecimal("3400000.00"), "GOODS", "AUH-MUS", "1600"));
            fitout.agreed = fitout.date.plusDays(45);
            fitout.paidOn = paid(fitout.date.plusDays(40));
            m.purchases.add(fitout);
        }
        if (m.offset == -6) {
            missedInvoice = new Sale("ANH-M-0007", Gen.workingDay(time.day(m.offset, 23)), PALM, "B2B");
            missedInvoice.lines.add(Line.standard("Hotel refurbishment, 48 guest rooms of furniture, fixtures and equipment", new BigDecimal("286000.00"),
                    "GOODS", "DXB-SZR", "4000"));
            missedInvoice.paidOn = Gen.workingDay(time.day(-1, 9));
            scheduled.add(new Files.BankLine(missedInvoice.paidOn, missedInvoice.grossAed(), false, "PCH-PAY-7781", PALM.name(),
                    "RECEIPT PALM CRESCENT HOSPITALITY REF ANH-M-0007 ROOMS REFURBISHMENT"));
        }
        if (m.offset == -2) {
            scheduled.add(new Files.BankLine(Gen.workingDay(time.day(-2, 14)), new BigDecimal("16800.00"), false, "LIQ-SBH-01", "Seaview Boutique Hotel (in liquidation)",
                    "FIRST LIQUIDATOR DIVIDEND 25 PCT ON INV " + BAD_DEBT_INVOICE));
        }
        if (m.offset == -1) {
            m.sales.stream().filter(s -> "TAX_INVOICE".equals(s.type) && !s.export && s.date.getDayOfMonth() > 25).findFirst()
                    .ifPresent(s -> s.postingDate = Gen.nextWorkingDay(m.ym.plusMonths(1).atDay(2)));
            Purchase concepts = new Purchase("SMD-26-0207", Gen.workingDay(time.day(-1, 13)), MILANO);
            concepts.currency = "EUR";
            concepts.rate = Story.rate("EUR", concepts.date);
            concepts.lines.add(Line.coded("Design concepts, Saadiyat villa project", new BigDecimal("7400.00"), null, "SERVICES", "DXB-SZR", "5300"));
            m.purchases.add(concepts);
            Sale firstExport = exportsThisMonth.getFirst();
            firstExport.lines.replaceAll(l -> l.withCode(null));
            failedEinvoice = m.sales.stream().filter(s -> s.customer == MARINA && "TAX_INVOICE".equals(s.type)).findFirst().orElseThrow();
            failedEinvoice.fault = "FAILED";
            exportWithoutEvidence = exportsThisMonth.getLast();
        }
    }

    // ---- Closing ----

    @Override
    void beforeClose(TaxPeriod p, int offset) {
        for (Sale s : exportsThisMonth) {
            if (s != exportWithoutEvidence) {
                exportEvidence(p, s, time.past(Gen.workingDay(s.date.plusDays(4))), "Jebel Ali Port");
            }
        }
        kit.as(preparer, () -> kit.adjustments.runSupplierReversal(p));
        if (offset == -9) {
            List<UUID> freight = kit.currentLines(p.id(), "description LIKE ?", "International freight%");
            kit.as(preparer, () -> kit.lines.reclassify(p.id(), new LineController.ReclassifyInput(freight, "ZERO_RATED", null, null, "WRONG_TAX_CODE",
                    "Cross-border freight to Muscat is zero-rated, and the carrier charged no VAT")));
        }
        if (offset == -6) {
            badDebtRelief(p);
        }
        if (offset == -2) {
            badDebtRecovery(p);
        }
    }

    private void badDebtRelief(TaxPeriod p) {
        UUID doc = kit.documentId(filer.id(), "OUT", BAD_DEBT_INVOICE);
        LocalDate writtenOff = Gen.workingDay(time.day(-6, 18));
        LocalDate notified = Gen.workingDay(time.day(-6, 20));
        UUID journal = kit.evidence(reviewer, filer.id(), p.id(), "seaview-write-off-journal.pdf", "Bad debt written off in the accounts", null,
                "Journal JV-BD-0006: bad debt written off", List.of("Customer: Seaview Boutique Hotel LLC (in liquidation)", "Invoice " + BAD_DEBT_INVOICE + ", AED 67,200.00 gross",
                        "Dr 6900 Bad debts 67,200.00 / Cr 1100 Trade receivables 67,200.00", "Posted on " + writtenOff + " by the finance team"));
        UUID notice = kit.evidence(reviewer, filer.id(), p.id(), "seaview-bad-debt-notice.pdf", "Notice to the customer of bad debt relief", null,
                "Notice of bad debt relief", List.of("To: The liquidator of Seaview Boutique Hotel LLC", "We have written off invoice " + BAD_DEBT_INVOICE
                        + " and will claim bad debt relief on its output tax.", "Sent by email and courier on " + notified));
        kit.as(reviewer, () -> kit.badDebts.claimRelief(p, new BadDebtService.ReliefInput(doc, new BigDecimal("67200.00"), writtenOff, notified, journal, notice,
                "Seaview Boutique Hotel went into liquidation. Unpaid for more than six months.")));
        decideProposed(p, approver, true, "Agreed with the write-off approved by the board.");
    }

    private void badDebtRecovery(TaxPeriod p) {
        UUID doc = kit.documentId(filer.id(), "OUT", BAD_DEBT_INVOICE);
        UUID letter = kit.evidence(reviewer, filer.id(), p.id(), "seaview-liquidator-dividend.pdf", "Liquidator's first dividend", null,
                "Liquidator's statement of first dividend", List.of("Seaview Boutique Hotel LLC (in liquidation)", "First dividend to unsecured creditors: 25%",
                        "Paid to Al Noor Home Furnishings Trading LLC: AED 16,800.00"));
        kit.as(reviewer, () -> kit.badDebts.recordRecovery(p, new BadDebtService.RecoveryInput(doc, new BigDecimal("16800.00"), letter,
                "First liquidator dividend received, so a quarter of the relieved VAT is due again")));
        decideProposed(p, approver, true, "Matches the dividend on the bank statement.");
    }

    @Override
    BigDecimal timingDifference(int offset) {
        return offset == -7 ? new BigDecimal("3180.00") : null;
    }

    @Override
    LocalDate filedOn(TaxPeriod p, int offset) {
        return offset == -5 ? Gen.nextWorkingDay(p.dueDate().plusDays(2)) : super.filedOn(p, offset);
    }

    @Override
    LocalDate paidOn(TaxPeriod p, int offset) {
        return offset == -5 ? Gen.nextWorkingDay(p.dueDate().plusDays(3)) : super.paidOn(p, offset);
    }

    @Override
    void afterFiling(TaxPeriod p, int offset, LocalDate filedOn, String reference) {
        if (offset != -8 || net(p.id()).signum() >= 0) {
            return;
        }
        RefundService.Position position = kit.as(reviewer, () -> kit.refunds.create(p.id(), new RefundService.CreateInput(null)));
        UUID request = position.request().id();
        BigDecimal amount = position.excessAed();
        kit.as(reviewer, () -> kit.refunds.generatePack(request));
        LocalDate submitted = Gen.nextWorkingDay(filedOn.plusDays(3));
        UUID ack = kit.evidence(reviewer, filer.id(), p.id(), "vat311-acknowledgement.pdf", "Refund request acknowledgement", null, "EmaraTax refund request acknowledgement",
                List.of("Form VAT311", "TRN " + TRN, "Refund of AED " + amount.toPlainString() + " for the period ending " + p.periodEnd(), "Submitted on " + submitted));
        kit.as(reviewer, () -> kit.refunds.recordSubmission(request, new RefundService.SubmissionInput("VAT311-" + TRN.substring(9) + "-0003", submitted, ack)));
        LocalDate decided = Gen.workingDay(time.day(-6, 8));
        LocalDate received = Gen.workingDay(time.day(-6, 16));
        scheduled.add(new Files.BankLine(received, amount, false, "FTA-REFUND-0003", "Federal Tax Authority", "FTA VAT REFUND VAT311-" + TRN.substring(9) + "-0003"));
        at(-6, () -> {
            kit.as(reviewer, () -> kit.refunds.recordDecision(request, new RefundService.DecisionInput(true, null, decided, "Approved in full after document review")));
            UUID txn = bankTxn("FTA VAT REFUND VAT311-" + TRN.substring(9) + "-0003");
            kit.as(reviewer, () -> kit.refunds.recordReceipt(request, new RefundService.ReceiptInput(received, amount, txn)));
        });
    }

    // ---- The return being closed now ----

    @Override
    void current(TaxPeriod p) {
        beforeClose(p, -1);
        reportLostCorner5();
        disclosure(p);

        EinvoiceEvent.Invoice unbooked = new EinvoiceEvent.Invoice("INVOICE", "GTB-26-9917", Gen.workingDay(time.day(-1, 29)), TIMBER.trn(), TRN, "AED",
                new BigDecimal("31250.00"), new BigDecimal("1562.50"), null);
        UUID unbookedId = Gen.uuid("received", TIMBER.trn(), "GTB-26-9917");
        kit.einvoices(List.of(Docs.event(EinvoiceEvent.RECEIVED, unbookedId, TRN, Docs.at(unbooked.issueDate(), 15), unbooked, null),
                Docs.event(EinvoiceEvent.CORNER5, unbookedId, TRN, Docs.at(unbooked.issueDate().plusDays(1), 9), null, "REPORTED")));

        journal(p, "9", "1240.00", "Input VAT on October courier invoices booked late", "Courier invoices arrived after the AP cut-off and were posted by journal", true);
        suggest(p);
        gl(p, new BigDecimal("640.00"));
        tidy(p, false);
        for (ReconciliationService.Exception e : kit.open(p.id())) {
            if ("R06".equals(e.ruleId())) {
                kit.comment(preparer, p.id(), "EXCEPTION", e.id().toString(),
                        "GL 2201 includes an FX revaluation journal of AED 640 on the USD receivable from Riyadh Home Gallery. It is not VAT.");
                kit.comment(reviewer, p.id(), "EXCEPTION", e.id().toString(), "Agreed, waiving. Lina to confirm at partner sign-off.");
                kit.waive(reviewer, e.id(), "FX revaluation of AED 640 posted to the VAT control account. Not a VAT difference; reclass journal raised for November.");
            }
            if ("T07".equals(e.ruleId())) {
                kit.as(Kit.OMAR, () -> kit.recon.assign(e.id(), Kit.DANA.id(), time.today.plusDays(7)));
                kit.comment(preparer, p.id(), "EXCEPTION", e.id().toString(), "Bill of lading requested from the forwarder. Exit certificate expected next week.");
            }
        }
        kit.reconcile(preparer, p.id());
        kit.submit(preparer, p.id(), "Prepared. R06 waived by Rami for the FX revaluation, so this needs the partner.");
        kit.comment(reviewer, p.id(), "PERIOD", p.id().toString(), "Looking at it today. Box 4 is up on the Riyadh hotel order; export evidence for one shipment still to come.");
    }

    /** The platform's first Corner 5 attempt failed for one invoice. It retried and the FTA accepted it. */
    private void reportLostCorner5() {
        TaxPeriod p = kit.period(filer.id(), failedEinvoice.date);
        kit.reconcile(preparer, p.id());
        kit.einvoices(List.of(Docs.event(EinvoiceEvent.CORNER5, failedEinvoice.einvoice, TRN, Docs.at(failedEinvoice.date.plusDays(3), 10), null, "REPORTED")));
    }

    /** A hand-written invoice from six months ago comes to light, is posted to its own month, and goes to the FTA on Form 211. */
    private void disclosure(TaxPeriod open) {
        kit.load(preparer, filer.id(), Templates.Kind.AR, "UPLOAD", "anh-sales-late-posting-" + time.day(-1, 12) + ".csv", Docs.arRows(List.of(missedInvoice)));
        kit.reconcile(preparer, open.id());
        TaxPeriod original = kit.period(filer.id(), missedInvoice.date);
        UUID memo = kit.evidence(reviewer, filer.id(), original.id(), "anh-m-0007-copy.pdf", "Copy of the hand-written invoice ANH-M-0007", null,
                "Invoice ANH-M-0007 (raised outside the ERP)", List.of("Customer: Palm Crescent Hospitality LLC, TRN " + PALM.trn(),
                        "Hotel refurbishment, 48 guest rooms of furniture, fixtures and equipment", "Net AED 286,000.00, VAT AED 14,300.00, total AED 300,300.00",
                        "Dated " + missedInvoice.date + ". Never posted to the ERP; found on " + time.day(-1, 12) + " when the customer paid."));
        CorrectionService.CorrectionCase c = kit.as(reviewer, () -> kit.corrections.open(original.id(), new CorrectionService.CaseInput(
                "Invoice ANH-M-0007 for AED 286,000 was raised by hand outside the ERP and never declared", memo, List.of())));
        LocalDate discovered = Gen.workingDay(time.day(-1, 12));
        DisclosureService.View view = kit.as(reviewer, () -> kit.disclosures.create(c.id(), new DisclosureService.CreateInput(discovered,
                "A sales invoice of AED 286,000 plus VAT of AED 14,300 was issued by hand and left out of the return for the period ending " + original.periodEnd()
                        + ". Output tax was understated by AED 14,300.")));
        UUID disclosure = view.disclosure().id();
        kit.as(partner, () -> kit.disclosures.approve(disclosure, true));
        kit.as(reviewer, () -> kit.disclosures.generatePack(disclosure));
        LocalDate submitted = Gen.workingDay(time.day(-1, 26));
        UUID ack = kit.evidence(reviewer, filer.id(), original.id(), "form-211-acknowledgement.pdf", "Form 211 acknowledgement", null,
                "EmaraTax voluntary disclosure acknowledgement", List.of("Form 211 voluntary disclosure", "TRN " + TRN, "Tax period ending " + original.periodEnd(),
                        "Tax difference AED 14,300.00", "Submitted on " + submitted, "Reference VD-" + TRN.substring(9) + "-2026-0001"));
        DisclosureService.View sent = kit.as(reviewer, () -> kit.disclosures.recordSubmission(disclosure,
                new DisclosureService.SubmissionInput("VD-" + TRN.substring(9) + "-2026-0001", submitted, ack)));
        LocalDate settled = time.past(Gen.workingDay(time.day(0, 3)));
        BigDecimal paid = new BigDecimal("14300.00").add(sent.penalty().total());
        kit.as(reviewer, () -> kit.disclosures.recordSettlement(disclosure, new DisclosureService.SettlementInput(settled, paid, filer.ftaGiban())));
        kit.reconcile(preparer, open.id());
    }

    // ---- The FTA audit ----

    @Override
    void after() {
        LocalDate notified = Gen.workingDay(time.day(-2, 15));
        AuditCaseService.View v = kit.as(reviewer, () -> kit.auditCases.open(filer.id(), new AuditCaseService.CaseInput("FTA-TA-2026-0045812", notified,
                time.month(firstOffset()).atDay(1), time.month(-7).atEndOfMonth(),
                "Field audit of VAT returns for the first half of the tax year. Focus on imports and the refund claimed after the warehouse fit-out.")));
        UUID id = v.auditCase().id();
        UUID r1 = request(id, "Sales listing with copies of tax invoices for " + time.month(firstOffset()) + " to " + time.month(-7), notified, notified.plusDays(14));
        UUID r2 = request(id, "Customs declarations and supplier invoices for the imports from China", Gen.workingDay(time.day(-1, 6)), Gen.workingDay(time.day(-1, 20)));
        request(id, "Bank statements and proof of supplier payments for " + time.month(-11) + " to " + time.month(-9), Gen.workingDay(time.day(-1, 27)), time.today.plusDays(3));
        UUID listing = kit.evidence(reviewer, filer.id(), null, "sales-listing-for-fta.pdf", "Sales listing given to the FTA", null, "Sales listing for the FTA audit",
                List.of("Period " + time.month(firstOffset()) + " to " + time.month(-7), "All tax invoices, credit notes and daily till totals", "Exported from the ERP and checked to the returns"));
        kit.as(reviewer, () -> kit.auditCases.respond(r1, new AuditCaseService.ResponseInput("Sales listing and invoice copies sent through the FTA portal",
                notified.plusDays(12), List.of(listing))));
        UUID customs = kit.evidence(reviewer, filer.id(), null, "customs-pack-for-fta.pdf", "Customs declarations given to the FTA", null, "Customs declarations pack",
                List.of("Import declarations for flat-pack furniture from Foshan", "Matched to supplier invoices and to box 6 of each return"));
        kit.as(reviewer, () -> kit.auditCases.respond(r2, new AuditCaseService.ResponseInput("Declarations, commercial invoices and bills of lading sent",
                Gen.workingDay(time.day(-1, 19)), List.of(customs))));
        kit.as(reviewer, () -> kit.auditCases.generateFaf(id));
        kit.as(reviewer, () -> kit.auditCases.bundle(id));
    }

    private UUID request(UUID caseId, String what, LocalDate on, LocalDate due) {
        AuditCaseService.View v = kit.as(reviewer, () -> kit.auditCases.addRequest(caseId, new AuditCaseService.RequestInput(what, on, due)));
        return v.requests().stream().filter(r -> r.description().equals(what)).findFirst().orElseThrow().id();
    }

    @Override
    String swingReason(String box, boolean up) {
        return switch (box) {
            case "1a" -> up ? "Abu Dhabi hotel and housing projects delivered this month." : "Fewer Abu Dhabi project deliveries this month.";
            case "1b" -> up ? "Dubai hotel fit-out deliveries and the showroom season." : "Quieter Dubai showroom month, as every summer.";
            case "4" -> up ? "More GCC export shipments this month." : "Fewer GCC export shipments this month.";
            case "6" -> up ? "A larger container from Foshan cleared customs." : "No container or a smaller one cleared customs this month.";
            case "3", "10" -> "Studio Milano bills its design work every second month.";
            default -> super.swingReason(box, up);
        };
    }

    // ---- Helpers ----

    private Sale pos(String branch, LocalDate day, BigDecimal net) {
        Sale s = new Sale("POS-" + branch + "-" + day.toString().replace("-", ""), day, null, "B2C");
        s.type = "B2C_SALE";
        s.lines.add(Line.standard("Showroom sales, daily till total", net, "GOODS", "DXB".equals(branch) ? "DXB-SZR" : "AUH-MUS", "4000"));
        s.paidOn = paid(Gen.nextWorkingDay(day.plusDays(1)));
        return s;
    }

    private Purchase bill(Gen g, Month m, Party supplier, String prefix, String description, BigDecimal net, String goods, String est, String gl) {
        Purchase p = new Purchase(next("bill-" + prefix, prefix + m.ym.getYear() % 100 + "-", 1001 + Math.abs(prefix.hashCode() % 700)), g.day(m.ym), supplier);
        p.lines.add(Line.standard(description, net, goods, est, gl));
        p.paidOn = paid(p.date.plusDays(g.between(24, 40)));
        p.paidThroughBank = g.chance(0.35);
        return p;
    }

    private void einvoice(Sale s, Gen g) {
        s.einvoice = Gen.uuid("einvoice", TRN, s.number);
        s.uuidInErp = g.chance(0.5);
    }

    private LocalDate paid(LocalDate d) {
        return d.isBefore(time.today) ? d : null;
    }
}
