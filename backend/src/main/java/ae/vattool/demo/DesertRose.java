package ae.vattool.demo;

import ae.vattool.adjustments.AdjustmentService;
import ae.vattool.adjustments.CapitalAssetService;
import ae.vattool.adjustments.CorrectionService;
import ae.vattool.auditcase.AuditCaseService;
import ae.vattool.corrections.RefundService;
import ae.vattool.demo.Docs.Line;
import ae.vattool.demo.Docs.Month;
import ae.vattool.demo.Docs.Party;
import ae.vattool.demo.Docs.Purchase;
import ae.vattool.demo.Docs.Sale;
import ae.vattool.ingestion.Templates;
import ae.vattool.masterdata.MappingService;
import ae.vattool.masterdata.TaxPeriod;
import ae.vattool.portfolio.RetentionController;
import ae.vattool.rules.ClientRules;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A landlord with a commercial tower and a residential block in Al Barsha, filing quarterly. Commercial
 * rent is standard-rated and residential rent exempt, so input tax is apportioned and the tower sits in
 * the capital assets scheme. A lobby refurbishment made one quarter refundable, the FTA reviewed the
 * apportionment and closed it, and a late supplier credit note was corrected in the next return.
 */
final class DesertRose extends Client {

    static final String TRN = "100567890100003";

    private record Tenant(Party party, String unit, BigDecimal rent) {}

    private static final List<Tenant> TENANTS = List.of(
            tenant("Brightpath Consulting LLC", "100420300100003", "Unit 1203", "84000"),
            tenant("Al Wasl Clinics LLC", "100420300200003", "Units 201 to 204", "176000"),
            tenant("Nexus Engineering Consultants LLC", "100420300300003", "Unit 1507", "62000"),
            tenant("Harbour Insurance Brokers LLC", "100420300400003", "Unit 902", "48500"),
            tenant("Sundune Travel & Tours LLC", "100420300500003", "Shop G-04", "39000"),
            tenant("Meridian Legal Advocates", "100420300600003", "Unit 1801", "71000"),
            tenant("Cedar Dental Centre LLC", "100420300700003", "Unit 305", "58000"),
            tenant("Atlas IT Services LLC", "100420300800003", "Unit 1102", "66500"),
            tenant("Blue Palm Cafe LLC", "100420300900003", "Shop G-01", "27500"),
            tenant("Orion Recruitment LLC", "100420301000003", "Unit 704", "35000"),
            tenant("Silverline Architects LLC", "100420301100003", "Unit 1605", "92000"),
            tenant("Gulf Learning Institute LLC", "100420301200003", "Units 401 and 402", "118000"),
            tenant("Prime Pharmacy LLC", "100420301300003", "Shop G-02", "44000"),
            tenant("Zenith Advertising LLC", "100420301400003", "Unit 1004", "53500"),
            tenant("Al Barsha Fitness Club LLC", "100420301500003", "Level 2 podium", "131000"),
            tenant("Unity Freight Brokers LLC", "100420301600003", "Unit 808", "41000"),
            tenant("Vertex Accounting LLC", "100420301700003", "Unit 1406", "57500"),
            tenant("Crescent Opticals LLC", "100420301800003", "Shop G-03", "31000"));

    private static final Party FITTINGS_BUYER = Party.abroad("Muscat Second Hand Furniture Trading LLC", "OM");
    private static final Party ELEVATORS = Party.uae("Al Barsha Elevator Services LLC", "100520400100003");
    static final Party SECURITY = Party.uae("Shield Security Services LLC", "100520400200003");
    private static final Party BUILDRIGHT = Party.uae("BuildRight Contracting LLC", "100520400300003");
    private static final Party FRESH_COAT = Party.uae("Fresh Coat Painting Services LLC", "100520400400003");
    private static final Party LEGAL = Party.uae("Al Safa Legal Consultants", "100500201000003");
    private static final Party MARKETING = Party.uae("Skyline Marketing Agency LLC", "100500200700003");
    private static final Party LANDSCAPING = Party.uae("Al Barsha Landscaping LLC", "100520400500003");
    private static final Party PROPTECH = Party.abroad("PropTech Cloud Ltd", "GB");

    private final Story.Staff staff;
    private Kit.Person rashid;
    private Purchase overbilled;
    private final List<Sale> exports = new ArrayList<>();
    private final List<Integer> annualDone = new ArrayList<>();

    DesertRose(Kit kit, Timeline time, Story.Staff staff) {
        super(kit, time, "DRR");
        this.staff = staff;
        iban = "AE330330000019100345678";
        payroll = new BigDecimal("96000.00");
        balance = new BigDecimal("4100000.00");
    }

    private static Tenant tenant(String name, String trn, String unit, String rent) {
        return new Tenant(Party.uae(name, trn), unit, new BigDecimal(rent).setScale(2));
    }

    @Override
    java.util.Set<String> currencies() {
        return java.util.Set.of("GBP");
    }

    @Override
    String legalName() {
        return "Desert Rose Real Estate LLC";
    }

    @Override
    int firstOffset() {
        return -15;
    }

    @Override
    boolean closesIn(YearMonth ym) {
        long offset = ChronoUnit.MONTHS.between(time.current, ym);
        return Math.floorMod(offset - firstOffset() + 1, 3) == 0;
    }

    /** Sales of bare land are exempt, and the GL is kept by a property manager whose rounding runs wider. */
    @Override
    ClientRules clientRules() {
        return new ClientRules(List.of(rule("CL-DRB-BARE-LAND", "Sales and leases of bare land are exempt", "OUT", "\\bbare land\\b", "EXEMPT")),
                null, null, null, new ClientRules.Reconciliation(null, new BigDecimal("250"), null, null), null, null);
    }

    @Override
    void onboard() {
        LocalDate first = time.month(firstOffset()).atDay(1);
        filer = entity(legalName(), "وردة الصحراء للعقارات ش.ذ.م.م", TRN, "STANDALONE", null, "QUARTERLY", first, null, new BigDecimal("75"), new BigDecimal("100000"));
        establishment(filer.id(), "DXB-BRS", "Al Barsha head office and Desert Rose Business Tower", "DXB", false, null);
        settings(filer.id(), "AE070331000000012345603", false, 1);
        filer = kit.entity(filer.id());
        List<MappingService.EntryInput> codes = standardCodes();
        codes.add(blockedGl("6100", "ENTERTAINMENT"));
        mapping(filer.id(), first, "Dynamics 365 tax groups", codes);
        register(List.of(AlNoor.FM, AlNoor.UTILITIES, ELEVATORS, SECURITY, BUILDRIGHT, FRESH_COAT, LEGAL, MARKETING), first.minusDays(3));
        agent(filer.id(), "Lina Partner", "30000201", first);
        rashid = kit.person("rashid-al-mehairi", "rashid@desertrose-realestate.example", "Rashid Al Mehairi", "CLIENT");
        signatory(filer.id(), "Rashid Al Mehairi", rashid.email(), "Chief Executive", first);
        kit.grant(filer.id(), rashid, "APPROVER", null);
        kit.grant(filer.id(), staff.farhan(), "TAX_REVIEWER", null);
        kit.grant(filer.id(), staff.hamid(), "AUDITOR", time.today.plusMonths(3).withDayOfMonth(1).minusDays(1));
        preparer = Kit.DANA;
        reviewer = staff.farhan();
        approver = rashid;
        kit.assign(filer.id(), Kit.DANA, "PREPARER");
        kit.assign(filer.id(), staff.farhan(), "REVIEWER");
        kit.assign(filer.id(), Kit.LINA, "PARTNER");

        LocalDate firstUsed = time.month(-21).atDay(1);
        kit.load(preparer, filer.id(), Templates.Kind.OPENING_ASSETS, "UPLOAD", "drr-opening-capital-assets.csv", List.of(Files.row("reference", "DRBT-01",
                "description", "Desert Rose Business Tower, Al Barsha (offices, shops and residences)", "category", "REAL_ESTATE", "cost_aed", new BigDecimal("42000000"),
                "input_vat_aed", new BigDecimal("2100000"), "initial_recovery_pct", new BigDecimal("68"), "first_used_on", firstUsed)));
        kit.evidence(preparer, filer.id(), null, "title-deed-desert-rose-business-tower.pdf", "Title deed, Desert Rose Business Tower", "REAL_ESTATE",
                "Title deed (copy)", List.of("Plot 373-0412, Al Barsha South", "Owner: Desert Rose Real Estate LLC", "Building completion certificate dated " + firstUsed.minusDays(20)));
        LocalDate taxYear = LocalDate.of(first.getYear(), 1, 1);
        kit.load(preparer, filer.id(), Templates.Kind.OPENING_APPORTIONMENT, "UPLOAD", "drr-opening-apportionment-" + taxYear.getYear() + ".csv", List.of(Files.row(
                "tax_year_start", taxYear, "direct_recoverable_aed", new BigDecimal("31480.00"), "direct_exempt_aed", new BigDecimal("6920.00"),
                "taxable_supplies_aed", new BigDecimal("10180000.00"), "total_supplies_aed", new BigDecimal("13240000.00"),
                "residual_input_aed", new BigDecimal("168400.00"), "recovered_aed", new BigDecimal("126300.00"))));
        Story.connector(kit, filer.id(), "DYNAMICS_365", "desert-rose", "demo-erp-token", time);
    }

    @Override
    void generate(Month m) {
        Gen g = new Gen(code + m.ym);
        LocalDate billed = Gen.nextWorkingDay(m.ym.atDay(1));
        for (Tenant t : TENANTS) {
            Sale s = new Sale(next("com", "DRR-C-", 50001), billed, t.party(), "B2B");
            s.lines.add(Line.standard("Office rent, Desert Rose Business Tower, " + t.unit() + ", " + m.ym, t.rent(), "SERVICES", "DXB-BRS", "4000"));
            s.lines.add(Line.standard("Service charge, " + t.unit() + ", " + m.ym, t.rent().multiply(new BigDecimal("0.08")).setScale(2, java.math.RoundingMode.HALF_UP),
                    "SERVICES", "DXB-BRS", "4010"));
            s.paidOn = paid(billed.plusDays(t.unit().startsWith("Shop G-01") ? 74 : g.between(3, 12)));
            m.sales.add(s);
        }
        for (int apt = 0; apt < 40; apt++) {
            int number = 101 + (apt / 4) * 100 + apt % 4;
            Sale s = new Sale(next("res", "DRR-R-", 80001), billed, Party.uae("Resident of apartment " + number, null), "B2C");
            s.lines.add(Line.coded("Residential lease, Desert Rose Residences apartment " + number + ", " + m.ym,
                    new BigDecimal(6500 + (number * 37) % 11500).setScale(2), "EX", "SERVICES", "DXB-BRS", "4100"));
            s.paidOn = paid(billed.plusDays(g.between(0, 4)));
            m.sales.add(s);
        }
        long offset = m.offset;
        if (Math.floorMod(offset - firstOffset(), 3) == 1) {
            Sale s = new Sale(next("exp", "DRR-X-", 301), g.dayBetween(m.ym, 8, 20), FITTINGS_BUYER, "B2B");
            s.lines.add(Line.coded("Used furniture and fittings from refurbished apartments, one container", g.amount(42000, 88000), "Z0", "GOODS", "DXB-BRS", "4300"));
            s.export = true;
            s.paidOn = paid(s.date.plusDays(20));
            m.sales.add(s);
            exports.add(s);
        }
        m.purchases.add(bill(g, m, AlNoor.FM, "CFM-", "Facility management, Business Tower and Residences", new BigDecimal("85000.00"), null));
        m.purchases.add(bill(g, m, AlNoor.UTILITIES, "MPW-", "Common area electricity, water and district cooling", g.amount(22000, 35000), null));
        m.purchases.add(bill(g, m, ELEVATORS, "ABE-", "Lift maintenance contract", new BigDecimal("12500.00"), null));
        m.purchases.add(bill(g, m, SECURITY, "SSS-", "Security guards, 24 hours", new BigDecimal("18000.00"), null));
        m.purchases.add(bill(g, m, MARKETING, "SKY-", "Leasing campaign and listings", g.amount(9000, 16000), null));
        for (int i = 0, n = g.between(1, 2); i < n; i++) {
            m.purchases.add(bill(g, m, BUILDRIGHT, "BRC-", "Fit-out of a commercial unit for a new tenant", g.amount(40000, 140000), new BigDecimal("100")));
        }
        for (int i = 0, n = g.between(1, 2); i < n; i++) {
            m.purchases.add(bill(g, m, FRESH_COAT, "FCP-", "Repainting an apartment between tenancies", g.amount(12000, 30000), BigDecimal.ZERO));
        }
        if (Math.floorMod(offset, 3) == 0) {
            m.purchases.add(bill(g, m, LEGAL, "ASL-", "Legal fees, lease renewals and rent disputes", g.amount(8000, 16000), null));
        }
        Purchase software = new Purchase(next("proptech", "PTC-", 50101), g.dayBetween(m.ym, 1, 5), PROPTECH);
        software.currency = "GBP";
        software.rate = Story.rate("GBP", software.date);
        // The new Dynamics tax group for overseas services has no tax code yet, so the place-of-supply rules decide.
        software.lines.add(Line.coded("Property management software subscription", new BigDecimal("2400.00"), m.offset == -1 ? null : "RC", "SERVICES", "DXB-BRS", "6610"));
        software.paidOn = paid(software.date.plusDays(10));
        m.purchases.add(software);
        if (m.offset == -11) {
            Purchase lobby = new Purchase("BRC-2025-0907", Gen.workingDay(time.day(-11, 15)), BUILDRIGHT);
            lobby.lines.add(Line.standard("Lobby, podium and facade refurbishment, Business Tower", new BigDecimal("7200000.00"), "SERVICES", "DXB-BRS", "1610"));
            lobby.agreed = lobby.date.plusDays(60);
            lobby.paidOn = paid(lobby.date.plusDays(58));
            m.purchases.add(lobby);
        }
        if (m.offset == -5) {
            overbilled = bill(g, m, BUILDRIGHT, "BRC-", "Fit-out of Units 401 and 402, final account", new BigDecimal("184000.00"), new BigDecimal("100"));
            m.purchases.add(overbilled);
        }
        if (m.offset == -2) {
            Purchase landscaping = bill(g, m, LANDSCAPING, "ABL-", "Podium landscaping and irrigation", new BigDecimal("23800.00"), null);
            m.purchases.add(landscaping);
        }
    }

    @Override
    BigDecimal closingOverride(YearMonth ym, BigDecimal actual) {
        return ym.equals(time.month(-6)) ? actual.add(new BigDecimal("900.00")) : null;
    }

    @Override
    void beforeClose(TaxPeriod p, int offset) {
        for (Sale s : exports) {
            exportEvidence(p, s, time.past(Gen.workingDay(s.date.plusDays(6))), "Mina Rashid");
        }
        exports.clear();
        kit.as(preparer, () -> kit.adjustments.runSupplierReversal(p));
        kit.as(reviewer, () -> kit.apportionment.runPeriod(p, null, true));
        LocalDate yearEnd = filer.taxYearStart(p.periodStart().minusDays(1)).plusYears(1).minusDays(1);
        if (p.periodEnd().isAfter(yearEnd) && !p.periodStart().minusDays(1).isAfter(yearEnd) && !annualDone.contains(yearEnd.getYear())) {
            kit.as(reviewer, () -> kit.apportionment.runAnnual(p, null, true));
            annualDone.add(yearEnd.getYear());
        }
    }

    private void realEstateRecords(TaxPeriod p) {
        kit.as(Kit.OMAR, () -> kit.retention.markRealEstate(p.id(), new RetentionController.RealEstateInput(true,
                "Records about the Business Tower and Residences are kept for 15 years")));
    }

    /** The tower's offices were let more than expected, so taxable use rose above the 68% claimed at first. */
    @Override
    BigDecimal assetUse(CapitalAssetService.Due due) {
        return new BigDecimal("74");
    }

    @Override
    void afterFiling(TaxPeriod p, int offset, LocalDate filedOn, String reference) {
        realEstateRecords(p);
        if (net(p.id()).signum() >= 0) {
            return;
        }
        RefundService.Position position = kit.as(reviewer, () -> kit.refunds.create(p.id(), new RefundService.CreateInput(null)));
        UUID request = position.request().id();
        kit.as(reviewer, () -> kit.refunds.generatePack(request));
        LocalDate submitted = Gen.nextWorkingDay(filedOn.plusDays(4));
        UUID ack = kit.evidence(reviewer, filer.id(), p.id(), "vat311-acknowledgement.pdf", "Refund request acknowledgement", null, "EmaraTax refund request acknowledgement",
                List.of("Form VAT311", "TRN " + TRN, "Refund of AED " + position.excessAed().toPlainString(), "Submitted on " + submitted));
        kit.as(reviewer, () -> kit.refunds.recordSubmission(request, new RefundService.SubmissionInput("VAT311-" + TRN.substring(9) + "-0002", submitted, ack)));
        at(-1, () -> kit.as(reviewer, () -> kit.refunds.recordDecision(request, new RefundService.DecisionInput(true, null, Gen.workingDay(time.day(-1, 25)),
                "Approved after the FTA closed its review of the apportionment"))));
    }

    @Override
    void current(TaxPeriod p) {
        beforeClose(p, -1);
        realEstateRecords(p);
        Purchase credit = new Purchase("BRC-CN-26-0019", Gen.workingDay(overbilled.date.plusDays(9)), BUILDRIGHT);
        if (!credit.date.getMonth().equals(overbilled.date.getMonth())) {
            credit.date = Gen.workingDay(overbilled.date.withDayOfMonth(overbilled.date.lengthOfMonth()));
        }
        credit.type = "CREDIT_NOTE";
        credit.original = overbilled.number;
        credit.lines.add(Line.standard("Final account agreed lower: credit for Units 401 and 402 fit-out", new BigDecimal("84000.00"), "SERVICES", "DXB-BRS", "1610")
                .withRecovery(new BigDecimal("100")));
        kit.load(preparer, filer.id(), Templates.Kind.AP, "UPLOAD", "drr-purchases-late-credit-note.csv", Docs.apRows(List.of(credit)));
        TaxPeriod q4 = kit.period(filer.id(), credit.date);
        kit.as(reviewer, () -> kit.corrections.open(q4.id(), new CorrectionService.CaseInput(
                "BuildRight's final account for Units 401 and 402 came in AED 84,000 lower. Their credit note arrived after the quarter was filed.", null, List.of())));

        UUID photos = kit.evidence(preparer, filer.id(), p.id(), "tenant-incentive-furniture.pdf", "Furniture given to new tenants as a leasing incentive", null,
                "Leasing incentive: furniture packages", List.of("Four furniture packages from stock given free to new commercial tenants", "Cost AED 36,000", "Approved by the leasing committee"));
        kit.as(preparer, () -> kit.adjustments.propose(p, new AdjustmentService.ManualInput("DEEMED_SUPPLY", "1b", new BigDecimal("36000.00"), new BigDecimal("1800.00"),
                "Furniture given free to tenants as a leasing incentive", "A free supply of business assets is a deemed supply", photos)));
        suggest(p);
        gl(p, new BigDecimal("3960.00"));
        kit.as(preparer, () -> kit.recon.addTimingItem(p, "OUTPUT", new BigDecimal("3960.00"),
                "November service charges for the podium shops raised and posted on " + p.periodEnd() + ". Their tax point is 1 November."));
        tidy(p, false);
        kit.comment(preparer, p.id(), "PERIOD", p.id().toString(), "Apportionment run for the quarter. Waiting for Farhan to approve the deemed supply before submitting.");
    }

    @Override
    void after() {
        LocalDate notified = Gen.workingDay(time.day(-8, 5));
        AuditCaseService.View v = kit.as(reviewer, () -> kit.auditCases.open(filer.id(), new AuditCaseService.CaseInput("FTA-TA-2026-0038220", notified,
                time.month(firstOffset()).atDay(1), time.month(-10).atEndOfMonth(),
                "Desk review of the input tax apportionment for the tax year and the refund claimed after the lobby refurbishment.")));
        UUID id = v.auditCase().id();
        respond(id, "Apportionment workings for the tax year, with the opening figures", notified, notified.plusDays(14), notified.plusDays(13),
                "Ratio workings, opening figures and the quarterly runs sent");
        respond(id, "Capital asset register and the title deed for the Business Tower", Gen.workingDay(time.day(-7, 2)), Gen.workingDay(time.day(-7, 16)),
                Gen.workingDay(time.day(-7, 15)), "Register extract and title deed sent");
        respond(id, "Contract, interim certificates and invoices for the lobby refurbishment", Gen.workingDay(time.day(-6, 10)), Gen.workingDay(time.day(-6, 24)),
                Gen.workingDay(time.day(-6, 22)), "Contract, certificates 1 to 6 and the final invoice sent");
        kit.as(reviewer, () -> kit.auditCases.generateFaf(id));
        kit.as(reviewer, () -> kit.auditCases.bundle(id));
        kit.as(partner, () -> kit.auditCases.close(id, new AuditCaseService.CloseInput(
                "Closed with no adjustment on " + Gen.workingDay(time.day(-4, 20)) + ". The FTA accepted the input-based ratio and the capital asset schedule.", true)));
    }

    private void respond(UUID caseId, String what, LocalDate on, LocalDate due, LocalDate answered, String note) {
        AuditCaseService.View v = kit.as(reviewer, () -> kit.auditCases.addRequest(caseId, new AuditCaseService.RequestInput(what, on, due)));
        UUID request = v.requests().stream().filter(r -> r.description().equals(what)).findFirst().orElseThrow().id();
        UUID file = kit.evidence(reviewer, filer.id(), null, what.toLowerCase().replaceAll("[^a-z]+", "-") + ".pdf", "Sent to the FTA: " + what, "REAL_ESTATE",
                what, List.of("Prepared for FTA case FTA-TA-2026-0038220", note));
        kit.as(reviewer, () -> kit.auditCases.respond(request, new AuditCaseService.ResponseInput(note, answered, List.of(file))));
    }

    @Override
    String swingReason(String box, boolean up) {
        return switch (box) {
            case "1b" -> up ? "New commercial leases started in the quarter." : "Two commercial units were vacant for part of the quarter.";
            case "5" -> "Residential rents follow the tenancy calendar.";
            case "9" -> up ? "Fit-out work for new tenants and the refurbishment." : "Less fit-out work this quarter.";
            default -> super.swingReason(box, up);
        };
    }

    private Purchase bill(Gen g, Month m, Party supplier, String prefix, String description, BigDecimal net, BigDecimal recovery) {
        Purchase p = new Purchase(next("bill-" + prefix, prefix + m.ym.getYear() % 100 + "-", 3001 + Math.abs(prefix.hashCode() % 400)), g.day(m.ym), supplier);
        Line line = Line.standard(description, net, "SERVICES", "DXB-BRS", supplier == BUILDRIGHT ? "1610" : "6500");
        p.lines.add(recovery == null ? line : line.withRecovery(recovery));
        p.paidOn = paid(p.date.plusDays(g.between(20, 40)));
        p.paidThroughBank = g.chance(0.5);
        return p;
    }

    private LocalDate paid(LocalDate d) {
        return d.isBefore(time.today) ? d : null;
    }
}
