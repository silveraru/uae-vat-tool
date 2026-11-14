package ae.vattool.demo;

import ae.vattool.adjustments.CorrectionService;
import ae.vattool.auditcase.AuditCaseService;
import ae.vattool.classification.SuggestionService;
import ae.vattool.corrections.DisclosureService;
import ae.vattool.corrections.RefundService;
import ae.vattool.demo.Docs.Line;
import ae.vattool.demo.Docs.Month;
import ae.vattool.demo.Docs.Party;
import ae.vattool.demo.Docs.Purchase;
import ae.vattool.demo.Docs.Sale;
import ae.vattool.ingestion.Templates;
import ae.vattool.ingestion.UblImportService;
import ae.vattool.ingestion.einvoice.EinvoiceStore;
import ae.vattool.masterdata.LegalEntity;
import ae.vattool.masterdata.MappingService;
import ae.vattool.masterdata.TaxPeriod;
import ae.vattool.reconciliation.ReconciliationService;
import ae.vattool.rules.ClientRules;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A tax group of three: the Abu Dhabi holding company files for a Dubai freight company and a Sharjah
 * warehousing company. On e-invoicing since July. Its fleet is under the capital assets scheme, a refund
 * was rejected, and a supplier's warranty credit has to go on Form 211. Last month's return is still
 * with the preparer, with real work left in it.
 */
final class Falcon extends Client {

    static final String TRN = "100456789000003";

    private static final Party GULF_RETAIL = Party.uae("Gulf Retail Distribution LLC", "100410200100003");
    private static final Party OASIS_PHARMA = Party.uae("Oasis Pharma Distribution LLC", "100410200200003");
    private static final Party MAJID = Party.uae("Al Majid FMCG Trading LLC", "100410200300003");
    private static final Party CEMENT = Party.uae("Northern Cement Industries LLC", "100410200400003");
    private static final Party STEEL = Party.uae("Ajman Steel Fabrication LLC", "100410200500003");
    private static final Party UAQ_FOODS = Party.uae("Umm Al Quwain Foods LLC", "100410200600003");
    private static final Party KHALIFA_PORT = Party.uae("Capital Port Services LLC", "100410200700003");
    private static final Party MUSCAT_SHIPPING = Party.abroad("Muscat Shipping Agencies LLC", "OM");
    private static final Party KARACHI = Party.abroad("Karachi Trade Links Pvt Ltd", "PK");
    private static final Party MUMBAI = Party.abroad("Mumbai Cargo Movers Pvt Ltd", "IN");

    private static final Party FUEL = Party.onPlatform("Al Khaleej Petroleum Retail LLC", "100510300100003");
    private static final Party TRUCKS = Party.onPlatform("Desert Truck Services LLC", "100510300200003");
    private static final Party TYRES = Party.uae("Tyre Hub Trading LLC", "100510300300003");
    private static final Party TOLLS = Party.uae("Emirates Road Toll Services", "100510300400003");
    private static final Party SHJ_PARK = Party.uae("Sharjah Logistics Parks LLC", "100510300500003");
    private static final Party QUOZ_PROPS = Party.uae("Al Quoz Industrial Properties LLC", "100510300600003");
    static final Party INSURANCE = Party.uae("Gulf Shield Insurance PJSC", "100510300700003");
    private static final Party TELECOM = Party.uae("Connect Gulf Telecom PJSC", "100510300800003");
    private static final Party SUBCON = Party.onPlatform("Rapid Haul Subcontractors LLC", "100510300900003");
    private static final Party INTRALOGISTICS = Party.uae("Intralogistics Systems LLC", "100510301000003");
    private static final Party FUEL_CARD = Party.uae("Gulf Fuel Card Services LLC", "100510301100003");
    private static final Party TRACKFLEET = Party.abroad("TrackFleet GmbH", "DE");

    private static final List<String> FREIGHT = List.of("Distribution runs, Dubai to Northern Emirates", "Full truckload, Jebel Ali to Abu Dhabi",
            "Container haulage from Jebel Ali Port", "Cold chain distribution", "Last-mile deliveries, retail stores", "Project cargo transport");

    private final Story.Staff staff;
    private LegalEntity freight;
    private LegalEntity warehousing;
    private Kit.Person hessa;
    private Sale missingEinvoice;
    private Sale renumbered;
    private Purchase unpaidSubcontractor;
    private Purchase overhaul;
    private final List<Sale> exportsThisMonth = new ArrayList<>();

    Falcon(Kit kit, Timeline time, Story.Staff staff) {
        super(kit, time, "FLG");
        this.staff = staff;
        bankFormat = "CAMT053";
        iban = "AE210350000000098765432";
        payroll = new BigDecimal("640000.00");
        balance = new BigDecimal("6200000.00");
        channel = "SFTP";
    }

    @Override
    java.util.Set<String> currencies() {
        return java.util.Set.of("USD", "EUR");
    }

    @Override
    String legalName() {
        return "Falcon Logistics Holding LLC";
    }

    /** Base oils bought in bulk may be domestic reverse charge, and a logistics group's exports swing more than a shop's. */
    @Override
    ClientRules clientRules() {
        return new ClientRules(null, List.of(pattern("CL-FLC-BASE-OIL", "HYDROCARBONS", "\\bbase oils?\\b", "\\blubricants? in bulk\\b")),
                null, null, null, new ClientRules.Analytics(new BigDecimal("500000"), new BigDecimal("2"), null, null), null);
    }

    @Override
    void onboard() {
        LocalDate first = time.month(firstOffset()).atDay(1);
        filer = entity(legalName(), "فالكون القابضة للخدمات اللوجستية ش.ذ.م.م", TRN, "GROUP_REPRESENTATIVE", null, "MONTHLY", first, time.month(-4).atDay(1),
                new BigDecimal("100"), new BigDecimal("50000"));
        establishment(filer.id(), "AUH-HQ", "Head office and Musaffah yard", "AUH", false, null);
        freight = entity("Falcon Freight LLC", "فالكون للشحن ش.ذ.م.م", null, "GROUP_MEMBER", filer.id(), null, null, null, new BigDecimal("100"), null);
        establishment(freight.id(), "DXB-AQZ", "Al Quoz depot", "DXB", false, null);
        warehousing = entity("Falcon Warehousing LLC", "فالكون للتخزين ش.ذ.م.م", null, "GROUP_MEMBER", filer.id(), null, null, null, new BigDecimal("100"), null);
        establishment(warehousing.id(), "SHJ-IND", "Industrial Area 12 warehouse", "SHJ", false, null);
        settings(filer.id(), "AE070331000000012345602", false, 1);
        filer = kit.entity(filer.id());

        List<MappingService.EntryInput> codes = standardCodes();
        codes.add(blockedGl("6100", "ENTERTAINMENT"));
        mapping(filer.id(), first, "SAP tax codes for the group", codes);
        register(List.of(FUEL, TRUCKS, TYRES, TOLLS, SHJ_PARK, QUOZ_PROPS, INSURANCE, TELECOM, SUBCON, INTRALOGISTICS, FUEL_CARD, AlNoor.FM, AlNoor.UTILITIES),
                first.minusDays(5));
        for (UUID e : List.of(filer.id(), freight.id(), warehousing.id())) {
            agent(e, "Rami Reviewer", "30000202", first);
        }
        hessa = kit.person("hessa-al-falasi", "hessa@falcon-logistics.example", "Hessa Al Falasi", "CLIENT");
        signatory(filer.id(), "Hessa Al Falasi", hessa.email(), "Group Finance Director", first);
        signatory(freight.id(), "Yousef Karim", "yousef@falcon-logistics.example", "General Manager, Falcon Freight", first);
        signatory(warehousing.id(), "Meera Pillai", "meera@falcon-logistics.example", "General Manager, Falcon Warehousing", first);
        kit.grant(filer.id(), hessa, "APPROVER", null);
        kit.grant(filer.id(), staff.aisha(), "PREPARER", null);
        kit.grant(freight.id(), staff.aisha(), "PREPARER", null);
        kit.grant(warehousing.id(), staff.aisha(), "PREPARER", null);
        preparer = staff.aisha();
        reviewer = Kit.RAMI;
        approver = hessa;
        kit.assign(filer.id(), staff.aisha(), "PREPARER");
        kit.assign(filer.id(), Kit.RAMI, "REVIEWER");
        kit.assign(filer.id(), Kit.LINA, "PARTNER");

        kit.load(preparer, filer.id(), Templates.Kind.OPENING_ASSETS, "UPLOAD", "flg-opening-capital-assets.csv", List.of(Files.row(
                "reference", "FLT-2025-40", "description", "Fleet of 40 heavy tractor units with trailers", "category", "OTHER", "cost_aed", new BigDecimal("9200000"),
                "input_vat_aed", new BigDecimal("460000"), "initial_recovery_pct", new BigDecimal("100"), "first_used_on", Gen.workingDay(time.day(-13, 15)))));
        Story.connector(kit, filer.id(), "SAP", "falcon", "expired-token", time);
    }

    @Override
    void generate(Month m) {
        Gen g = new Gen(code + m.ym);
        exportsThisMonth.clear();
        double busy = 1 + 0.08 * Math.sin(2 * Math.PI * (m.ym.getMonthValue() - 3) / 12.0);
        List<Party> domestic = List.of(GULF_RETAIL, GULF_RETAIL, OASIS_PHARMA, MAJID, MAJID, CEMENT, STEEL, UAQ_FOODS, KHALIFA_PORT);
        for (int i = 0, n = (int) Math.round(g.between(40, 46) * busy); i < n; i++) {
            Party c = g.pick(domestic);
            String est = c == KHALIFA_PORT ? "AUH-HQ" : (c == MAJID || c == UAQ_FOODS) && g.chance(0.7) ? "SHJ-IND" : "DXB-AQZ";
            Sale s = new Sale(next("inv", "FLF-INV-", 70001), g.day(m.ym), c, "B2B");
            if ("SHJ-IND".equals(est)) {
                s.lines.add(Line.standard(g.pick(List.of("Warehousing and storage, pallet positions", "Pick, pack and dispatch", "Bonded storage handling")),
                        g.amount(18000, 72000), "SERVICES", est, "4100"));
            } else {
                s.lines.add(Line.standard(g.pick(FREIGHT), g.amount(9000, 64000), "SERVICES", est, "4000"));
                if (g.chance(0.3)) {
                    s.lines.add(Line.standard("Waiting time and loading charges", g.amount(600, 4800), "SERVICES", est, "4000"));
                }
            }
            s.paidOn = paid(s.date.plusDays(g.between(30, 60)));
            s.einvoice = Gen.uuid("einvoice", TRN, s.number);
            s.uuidInErp = g.chance(0.6);
            m.sales.add(s);
        }
        for (int i = 0, n = g.between(4, 7); i < n; i++) {
            Party c = g.pick(List.of(MUSCAT_SHIPPING, KARACHI, MUMBAI));
            Sale s = new Sale(next("intl", "FLF-INT-", 9101), g.day(m.ym), c, "B2B");
            s.lines.add(Line.coded("International freight forwarding, Jebel Ali to " + (c == MUSCAT_SHIPPING ? "Sohar" : c == KARACHI ? "Karachi" : "Nhava Sheva"),
                    g.amount(22000, 96000), "Z0", "SERVICES", "DXB-AQZ", "4200"));
            s.currency = "USD";
            s.rate = Story.rate("USD", s.date);
            s.paidOn = paid(s.date.plusDays(g.between(30, 50)));
            s.einvoice = Gen.uuid("einvoice", TRN, s.number);
            m.sales.add(s);
        }
        Sale pallets = new Sale(next("exp", "FLF-EXP-", 401), g.dayBetween(m.ym, 3, 22), MUSCAT_SHIPPING, "B2B");
        pallets.lines.add(Line.coded("Reusable pallets and packing materials, 2 trailer loads", g.amount(28000, 64000), "Z0", "GOODS", "DXB-AQZ", "4300"));
        pallets.export = true;
        pallets.paidOn = paid(pallets.date.plusDays(40));
        pallets.einvoice = Gen.uuid("einvoice", TRN, pallets.number);
        m.sales.add(pallets);
        exportsThisMonth.add(pallets);
        if (g.chance(0.5)) {
            Sale original = m.sales.get(g.between(0, 10));
            Sale cn = new Sale(next("cn", "FLF-CN-", 301), Gen.workingDay(original.date.plusDays(5).isAfter(m.ym.atEndOfMonth()) ? m.ym.atEndOfMonth() : original.date.plusDays(5)),
                    original.customer, "B2B");
            cn.type = "CREDIT_NOTE";
            cn.original = original.number;
            cn.currency = original.currency;
            cn.rate = original.rate;
            Line l = original.lines.getFirst();
            cn.lines.add(new Line("Credit for late delivery penalty", l.net().multiply(new BigDecimal("0.1")).setScale(2, java.math.RoundingMode.HALF_UP),
                    Gen.vat(l.net().multiply(new BigDecimal("0.1"))), l.taxCode(), l.goods(), l.est(), l.gl(), null));
            if ("Z0".equals(l.taxCode())) {
                cn.lines.set(0, Line.coded("Credit for late delivery penalty", l.net().multiply(new BigDecimal("0.1")).setScale(2, java.math.RoundingMode.HALF_UP),
                        "Z0", l.goods(), l.est(), l.gl()));
            }
            cn.einvoice = Gen.uuid("einvoice", TRN, cn.number);
            m.sales.add(cn);
        }
        purchases(m, g, busy);
        scenario(m, g);
    }

    private void purchases(Month m, Gen g, double busy) {
        m.purchases.add(bill(g, m, FUEL, "AKP-", "Fleet fuel card statement", g.amount(310000, 380000 * busy), "GOODS", "DXB-AQZ", "5400"));
        for (int i = 0, n = g.between(3, 5); i < n; i++) {
            m.purchases.add(bill(g, m, TRUCKS, "DTS-", "Truck servicing and repairs", g.amount(9000, 42000), "SERVICES", "DXB-AQZ", "5410"));
        }
        m.purchases.add(bill(g, m, TYRES, "THT-", "Truck tyres and retreading", g.amount(18000, 46000), "GOODS", "DXB-AQZ", "5420"));
        Purchase tolls = bill(g, m, TOLLS, "ERT-", "Road toll charges", g.amount(21000, 29000), "SERVICES", "DXB-AQZ", "5430");
        tolls.lines.set(0, Line.coded("Road toll charges", tolls.lines.getFirst().net(), "OOS", "SERVICES", "DXB-AQZ", "5430"));
        m.purchases.add(tolls);
        m.purchases.add(bill(g, m, SHJ_PARK, "SLP-", "Warehouse rent, Industrial Area 12, " + m.ym, new BigDecimal("168000.00"), "SERVICES", "SHJ-IND", "6200"));
        m.purchases.add(bill(g, m, QUOZ_PROPS, "AQP-", "Depot and yard rent, Al Quoz, " + m.ym, new BigDecimal("124000.00"), "SERVICES", "DXB-AQZ", "6200"));
        if (Math.floorMod(m.offset, 3) == 0) {
            m.purchases.add(bill(g, m, INSURANCE, "GSI-", "Motor fleet and goods-in-transit insurance, quarterly", new BigDecimal("96500.00"), "SERVICES", "AUH-HQ", "6800"));
        }
        m.purchases.add(bill(g, m, TELECOM, "CGT-", "Mobile, data and vehicle SIM plans", g.amount(14000, 17500), "SERVICES", "AUH-HQ", "6300"));
        for (int i = 0, n = g.between(2, 4); i < n; i++) {
            m.purchases.add(bill(g, m, SUBCON, "RHS-", "Subcontracted haulage", g.amount(22000, 68000), "SERVICES", "DXB-AQZ", "5100"));
        }
        m.purchases.add(bill(g, m, AlNoor.FM, "CFM-", "Cleaning and maintenance, warehouse and depot", new BigDecimal("14600.00"), "SERVICES", "SHJ-IND", "6500"));
        m.purchases.add(bill(g, m, AlNoor.UTILITIES, "MPW-", "Electricity and water, Sharjah warehouse", g.amount(21000, 33000), "SERVICES", "SHJ-IND", "6300"));
        Purchase telematics = new Purchase(next("trackfleet", "TF-INV-", 88001), g.dayBetween(m.ym, 1, 8), TRACKFLEET);
        telematics.currency = "EUR";
        telematics.rate = Story.rate("EUR", telematics.date);
        telematics.lines.add(Line.coded("Fleet telematics platform, 40 vehicles", new BigDecimal("3600.00"), "RC", "SERVICES", "AUH-HQ", "5300"));
        telematics.paidOn = paid(telematics.date.plusDays(14));
        m.purchases.add(telematics);
    }

    private void scenario(Month m, Gen g) {
        if (m.offset == -9) {
            Purchase automation = new Purchase("ILS-2026-0118", Gen.workingDay(time.day(-9, 9)), INTRALOGISTICS);
            automation.lines.add(Line.standard("Warehouse automation: conveyors, sorters and 12 electric forklifts", new BigDecimal("5850000.00"), "GOODS", "SHJ-IND", "1600"));
            automation.agreed = automation.date.plusDays(60);
            automation.paidOn = paid(automation.date.plusDays(55));
            m.purchases.add(automation);
        }
        if (m.offset == -8) {
            unpaidSubcontractor = bill(g, m, SUBCON, "RHS-", "Subcontracted haulage, cargo damaged in transit (disputed)", new BigDecimal("57200.00"), "SERVICES", "DXB-AQZ", "5100");
            unpaidSubcontractor.paidOn = null;
            m.purchases.add(unpaidSubcontractor);
        }
        if (m.offset == -5) {
            overhaul = bill(g, m, TRUCKS, "DTS-", "Major overhaul, 12 tractor units", new BigDecimal("270000.00"), "SERVICES", "DXB-AQZ", "5410");
            m.purchases.add(overhaul);
        }
        if (m.offset == -1) {
            List<Sale> invoices = m.sales.stream().filter(s -> "TAX_INVOICE".equals(s.type) && s.customer.trn() != null).toList();
            missingEinvoice = invoices.get(3);
            missingEinvoice.fault = "MISSING";
            invoices.get(7).fault = "FAILED";
            renumbered = invoices.get(11);
            renumbered.fault = "RENUMBERED";
            renumbered.uuidInErp = false;
            for (int i = 0; i < 3; i++) {
                Purchase fuel = new Purchase("GFC-" + (40310 + i), Gen.workingDay(time.day(-1, 6 + i * 9)), FUEL_CARD);
                fuel.lines.add(Line.standard(i == 1 ? "Diesel bulk delivery to Al Quoz depot" : "Fleet fuel card statement, new provider", g.amount(46000, 62000),
                        "GOODS", "DXB-AQZ", "5400"));
                fuel.paidOn = null;
                m.purchases.add(fuel);
            }
            for (Purchase p : m.purchases) {
                if ((p.supplier == TRUCKS || p.supplier == TYRES) && "TAX_INVOICE".equals(p.type)) {
                    p.lines.replaceAll(l -> l.withCode("V5"));
                }
            }
            m.badPurchaseRows.add(Files.row("doc_type", "TAX_INVOICE", "doc_number", "THT-26-9001", "doc_date", time.day(-1, 14), "net_amount", "8400.00",
                    "vat_amount", "420.00", "line_no", "1", "counterparty_name", TYRES.name(), "counterparty_trn", "10051030030000", "establishment_code", "DXB-AQZ",
                    "gl_account", "5420", "tax_code", "S5", "description", "Truck tyres", "goods_or_services", "GOODS", "has_tax_invoice", "true"));
            m.badPurchaseRows.add(Files.row("doc_type", "TAX_INVOICE", "doc_number", "RHS-26-9002", "doc_date", "31/" + m.ym.getMonthValue() + "/" + m.ym.getYear(),
                    "net_amount", "15250.00", "vat_amount", "762.50", "line_no", "1", "counterparty_name", SUBCON.name(), "counterparty_trn", SUBCON.trn(),
                    "establishment_code", "DXB-AQZ", "gl_account", "5100", "tax_code", "S5", "description", "Subcontracted haulage", "goods_or_services", "SERVICES",
                    "has_tax_invoice", "true"));
        }
    }

    @Override
    void beforeClose(TaxPeriod p, int offset) {
        for (Sale s : exportsThisMonth) {
            exportEvidence(p, s, time.past(Gen.workingDay(s.date.plusDays(3))), "Hatta land border");
        }
        kit.as(preparer, () -> kit.adjustments.runSupplierReversal(p));
        if (offset == -3) {
            journal(p, "9", "2350.00", "Input VAT on fuel card service fees missing from the card statement export",
                    "The provider's export left out its monthly service fee invoices", true);
        }
    }

    @Override
    BigDecimal timingDifference(int offset) {
        return offset == -5 ? new BigDecimal("1920.00") : null;
    }

    @Override
    void gl(TaxPeriod p, BigDecimal outputDifference) {
        boolean rounding = p.periodStart().equals(time.month(-6).atDay(1));
        super.gl(p, rounding ? new BigDecimal("380.00") : outputDifference);
    }

    /** Six months back the GL carried fuel card rebate rounding. The reviewer waived it, which brought the partner in. */
    @Override
    List<ReconciliationService.Exception> tidy(TaxPeriod p, boolean mustBeClean) {
        if (mustBeClean && p.periodStart().equals(time.month(-6).atDay(1))) {
            List<ReconciliationService.Exception> first = super.tidy(p, false);
            for (ReconciliationService.Exception e : first) {
                if ("R06".equals(e.ruleId())) {
                    kit.waive(reviewer, e.id(), "AED 380 of fuel card rebate rounding posted to 2201 by the card provider's interface. Not VAT. Fixed in the interface from next month.");
                }
            }
        }
        return super.tidy(p, mustBeClean);
    }

    @Override
    void afterFiling(TaxPeriod p, int offset, LocalDate filedOn, String reference) {
        if (offset != -9 || net(p.id()).signum() >= 0) {
            return;
        }
        RefundService.Position position = kit.as(reviewer, () -> kit.refunds.create(p.id(), new RefundService.CreateInput(null)));
        UUID request = position.request().id();
        kit.as(reviewer, () -> kit.refunds.generatePack(request));
        LocalDate submitted = Gen.nextWorkingDay(filedOn.plusDays(4));
        UUID ack = kit.evidence(reviewer, filer.id(), p.id(), "vat311-acknowledgement.pdf", "Refund request acknowledgement", null, "EmaraTax refund request acknowledgement",
                List.of("Form VAT311", "TRN " + TRN, "Refund of AED " + position.excessAed().toPlainString(), "Submitted on " + submitted));
        kit.as(reviewer, () -> kit.refunds.recordSubmission(request, new RefundService.SubmissionInput("VAT311-" + TRN.substring(9) + "-0001", submitted, ack)));
        at(-7, () -> kit.as(reviewer, () -> kit.refunds.recordDecision(request, new RefundService.DecisionInput(false, null, Gen.workingDay(time.day(-7, 14)),
                "Rejected: the automation supplier's tax invoice did not show the group's TRN. A corrected invoice was requested so the claim can be made again."))));
        // With the corrected invoice in hand, the claim goes in again and is paid.
        BigDecimal amount = position.excessAed();
        String second = "VAT311-" + TRN.substring(9) + "-0002";
        LocalDate received = Gen.workingDay(time.day(-4, 19));
        scheduled.add(new Files.BankLine(received, amount, false, "FTA-REFUND-FLG2", "Federal Tax Authority", "FTA VAT REFUND " + second));
        UUID[] again = new UUID[1];
        at(-5, () -> {
            again[0] = kit.as(reviewer, () -> kit.refunds.create(p.id(), new RefundService.CreateInput(null))).request().id();
            kit.as(reviewer, () -> kit.refunds.generatePack(again[0]));
            LocalDate resubmitted = Gen.workingDay(time.day(-5, 6));
            UUID corrected = kit.evidence(reviewer, filer.id(), p.id(), "ils-2026-0118-corrected.pdf", "Corrected tax invoice from Intralogistics Systems", null,
                    "Tax invoice ILS-2026-0118 (corrected)", List.of("Supplier: Intralogistics Systems LLC", "Recipient: Falcon Logistics Holding LLC, TRN " + TRN,
                            "Warehouse automation: conveyors, sorters and 12 electric forklifts", "Net AED 5,850,000.00, VAT AED 292,500.00"));
            kit.as(reviewer, () -> kit.refunds.recordSubmission(again[0], new RefundService.SubmissionInput(second, resubmitted, corrected)));
        });
        at(-4, () -> {
            kit.as(reviewer, () -> kit.refunds.recordDecision(again[0], new RefundService.DecisionInput(true, null, Gen.workingDay(time.day(-4, 11)),
                    "Approved on resubmission with the corrected tax invoice")));
            UUID txn = bankTxn("FTA VAT REFUND " + second);
            kit.as(reviewer, () -> kit.refunds.recordReceipt(again[0], new RefundService.ReceiptInput(received, amount, txn)));
        });
    }

    @Override
    void closeHistory(TaxPeriod p, int offset) {
        super.closeHistory(p, offset);
        if (offset == -2) {
            // Only half of September's tax was paid on time. The bank line is confirmed as a part payment.
            BigDecimal net = net(p.id());
            BigDecimal half = net.divide(new BigDecimal("2"), 2, java.math.RoundingMode.DOWN);
            scheduled.removeIf(l -> l.reference() != null && l.reference().equals("VATPAY" + p.periodEnd().toString().replace("-", "")));
            scheduled.add(new Files.BankLine(Gen.workingDay(p.dueDate().minusDays(2)), half, true, "VATPAY" + p.periodEnd().toString().replace("-", ""),
                    "Federal Tax Authority", "FTA VAT PAYMENT GIBAN " + filer.ftaGiban() + " PART PAYMENT RETURN " + p.periodEnd()));
            at(-1, () -> kit.as(reviewer, () -> kit.bankMatcher.confirm(p.id(), bankTxn("PART PAYMENT RETURN " + p.periodEnd()), filer.ftaGiban())));
        }
    }

    // ---- The return being closed now ----

    @Override
    void current(TaxPeriod p) {
        for (Sale s : exportsThisMonth) {
            exportEvidence(p, s, time.past(Gen.workingDay(s.date.plusDays(3))), "Hatta land border");
        }
        ubl(p);
        formTwoEleven();
        journal(p, "9", "-1180.00", "Input VAT reversed on fuel cards used privately by two managers",
                "Private use of company fuel cards is not a business expense", false);
        SuggestionService.Generated generated = kit.as(preparer, () -> kit.suggestions.generate(p.id()));
        List<SuggestionService.Suggestion> proposed = kit.as(preparer, () -> kit.suggestions.list(p.id(), "PROPOSED"));
        List<UUID> accept = proposed.stream().filter(s -> s.inputs() != null && s.inputs().contains("Truck servicing")).map(SuggestionService.Suggestion::id).toList();
        if (!accept.isEmpty()) {
            kit.as(preparer, () -> kit.suggestions.accept(accept));
        }
        gl(p, BigDecimal.ZERO);
        tidy(p, false);
        for (ReconciliationService.Exception e : kit.open(p.id())) {
            switch (e.ruleId()) {
                case "R11" -> {
                    kit.as(Kit.OMAR, () -> kit.recon.assign(e.id(), staff.aisha().id(), time.today.plusDays(5)));
                    kit.comment(preparer, p.id(), "EXCEPTION", e.id().toString(), "Rapid Haul invoice still disputed over the damaged cargo. Running the six-month reversal before submitting.");
                }
                case "D02" -> kit.comment(preparer, p.id(), "EXCEPTION", e.id().toString(), "Two rows came out of SAP with a short TRN and a day-first date. Asked the client's SAP team to re-export them.");
                case "R01" -> {
                    if (e.message().contains("no e-invoice")) {
                        kit.comment(reviewer, p.id(), "EXCEPTION", e.id().toString(), "The platform had an outage on the 14th. Ask it to resend that day's events before chasing the client.");
                    }
                }
                default -> { }
            }
        }
        kit.comment(Kit.LINA, p.id(), "PERIOD", p.id().toString(), "Fuel card provider changed this month. Check the new supplier's invoices against the UBL copies before review. "
                + generated.suggestionsAdded() + " classification suggestions came out of the new SAP tax code.");
    }

    /** The new fuel card provider uses another ASP, so its e-invoices arrive as UBL files. One status is not known yet. */
    private void ubl(TaxPeriod p) {
        Map<String, String> files = new LinkedHashMap<>();
        List<Purchase> fuel = purchases.stream().filter(x -> x.supplier == FUEL_CARD).toList();
        for (Purchase x : fuel) {
            files.put(x.number + ".xml", Files.ubl(Gen.uuid("ubl", x.number), x.number, x.date, FUEL_CARD.trn(), FUEL_CARD.name(), TRN, legalName(), x.netAed(), x.vatAed()));
        }
        kit.as(preparer, () -> kit.ubl.load(filer.id(), "UPLOAD", "gulf-fuel-card-einvoices-" + time.month(-1) + ".zip", Files.zip(files)));
        List<UblImportService.StatusInput> statuses = fuel.subList(0, 2).stream()
                .map(x -> new UblImportService.StatusInput(Gen.uuid("ubl", x.number), "REPORTED")).toList();
        kit.as(preparer, () -> kit.ubl.applyStatuses(filer.id(), statuses));
    }

    /** A warranty credit note from June turns up in October. Input tax was over-claimed, so it goes on Form 211. */
    private void formTwoEleven() {
        Purchase credit = new Purchase("DTS-CN-26-0045", Gen.workingDay(overhaul.date.plusDays(12)), TRUCKS);
        if (!credit.date.getMonth().equals(overhaul.date.getMonth())) {
            credit.date = Gen.workingDay(overhaul.date.withDayOfMonth(overhaul.date.lengthOfMonth()));
        }
        credit.type = "CREDIT_NOTE";
        credit.original = overhaul.number;
        credit.lines.add(Line.standard("Overhaul charges reversed under the manufacturer's warranty", new BigDecimal("270000.00"), "SERVICES", "DXB-AQZ", "5410"));
        kit.load(preparer, filer.id(), Templates.Kind.AP, "UPLOAD", "flg-purchases-late-credit-note-" + time.day(-1, 20) + ".csv", Docs.apRows(List.of(credit)));
        TaxPeriod june = kit.period(filer.id(), credit.date);
        UUID letter = kit.evidence(reviewer, filer.id(), june.id(), "dts-warranty-credit-note.pdf", "Warranty credit note from Desert Truck Services", null,
                "Credit note DTS-CN-26-0045", List.of("Supplier: Desert Truck Services LLC, TRN " + TRUCKS.trn(), "Against invoice " + overhaul.number,
                        "Overhaul of 12 tractor units reversed under warranty", "Net AED 270,000.00, VAT AED 13,500.00", "Received by Falcon on " + time.day(-1, 20)));
        CorrectionService.CorrectionCase c = kit.as(reviewer, () -> kit.corrections.open(june.id(), new CorrectionService.CaseInput(
                "Warranty credit note received late for the June overhaul. Input tax of AED 13,500 was over-claimed.", letter, List.of())));
        kit.as(reviewer, () -> kit.disclosures.create(c.id(), new DisclosureService.CreateInput(Gen.workingDay(time.day(-1, 20)),
                "Input tax of AED 13,500 claimed on overhaul invoice " + overhaul.number + " that the supplier later credited in full under warranty.")));
    }

    // ---- The FTA audit of the group ----

    @Override
    void after() {
        // The diesel flags in the current return were noise: fuel-card purchases at the pump are not domestic reverse charge.
        ClientRules now = clientRules();
        kit.clientRules(filer.id(), time.month(1).atDay(1), "Stop flagging fuel-card diesel as possible DRC, from next month",
                new ClientRules(now.classificationRules(), now.drcPatterns(), now.blockedPatterns(),
                        List.of(new ClientRules.SwitchedOff("DRC-HYDROCARBONS", "Diesel bought at retail pumps on fuel cards, which is not domestic reverse charge")),
                        now.reconciliation(), now.analytics(), now.suggestionKeywords()), null);
        LocalDate notified = Gen.workingDay(time.day(-1, 3));
        AuditCaseService.View v = kit.as(reviewer, () -> kit.auditCases.open(filer.id(), new AuditCaseService.CaseInput("FTA-TA-2026-0051177", notified,
                time.month(firstOffset()).atDay(1), time.month(-7).atEndOfMonth(), "Tax group audit: member registrations and intra-group recharges.")));
        UUID id = v.auditCase().id();
        AuditCaseService.View r1 = kit.as(reviewer, () -> kit.auditCases.addRequest(id, new AuditCaseService.RequestInput(
                "Tax group registration certificate and the list of members", notified, notified.plusDays(14))));
        UUID first = r1.requests().getFirst().id();
        UUID certificate = kit.evidence(reviewer, filer.id(), null, "tax-group-certificate.pdf", "Tax group registration certificate", null, "Tax group registration",
                List.of("Representative member: Falcon Logistics Holding LLC", "Members: Falcon Freight LLC, Falcon Warehousing LLC", "Group TRN " + TRN));
        kit.as(reviewer, () -> kit.auditCases.respond(first, new AuditCaseService.ResponseInput("Certificate and member list sent", notified.plusDays(11), List.of(certificate))));
        kit.as(reviewer, () -> kit.auditCases.addRequest(id, new AuditCaseService.RequestInput("Intra-group recharge agreements and invoices for "
                + time.month(firstOffset()) + " to " + time.month(-7), Gen.workingDay(time.day(-1, 20)), time.today.plusDays(12))));
        kit.as(reviewer, () -> kit.auditCases.generateFaf(id));
    }

    @Override
    String swingReason(String box, boolean up) {
        return switch (box) {
            case "1c" -> up ? "More warehousing days billed in Sharjah." : "Two storage contracts ended in Sharjah.";
            case "1b" -> up ? "Peak distribution month for retail customers." : "Quieter distribution month.";
            case "4" -> up ? "More international forwarding jobs this month." : "Fewer international forwarding jobs this month.";
            case "9" -> up ? "Higher fuel and maintenance costs this month." : "Lower fuel and maintenance costs this month.";
            default -> super.swingReason(box, up);
        };
    }

    private Purchase bill(Gen g, Month m, Party supplier, String prefix, String description, BigDecimal net, String goods, String est, String gl) {
        Purchase p = new Purchase(next("bill-" + prefix, prefix + m.ym.getYear() % 100 + "-", 2001 + Math.abs(prefix.hashCode() % 500)), g.day(m.ym), supplier);
        p.lines.add(Line.standard(description, net, goods, est, gl));
        p.paidOn = paid(p.date.plusDays(g.between(25, 45)));
        p.paidThroughBank = g.chance(0.4);
        return p;
    }

    private LocalDate paid(LocalDate d) {
        return d.isBefore(time.today) ? d : null;
    }

    EinvoiceStore store() {
        return kit.einvoices;
    }

    YearMonth month(int offset) {
        return time.month(offset);
    }
}
