package ae.vattool.demo;

import ae.vattool.analytics.RegistrationWatchService;
import ae.vattool.ingestion.connectors.ConnectorService;
import ae.vattool.ingestion.einvoice.EinvoiceEvent;
import ae.vattool.masterdata.PartyService;
import ae.vattool.practice.WebhookService;
import ae.vattool.rules.RulePackStore;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The demo firm's year: who works there, the prospects it watches, its webhooks, and five clients
 * whose sections all have something in them. Each client runs in turn and a failure in one is logged
 * without stopping the rest, so a partial demo is still usable.
 */
final class Story {

    private static final Logger log = LoggerFactory.getLogger(Story.class);

    record Staff(Kit.Person aisha, Kit.Person farhan, Kit.Person mariam, Kit.Person hamid) {}

    private final Kit kit;
    private final Timeline time;
    private final DemoServer server;

    Story(Kit kit, Timeline time, DemoServer server) {
        this.kit = kit;
        this.time = time;
        this.server = server;
    }

    void run() {
        Staff staff = new Staff(
                kit.person("aisha-rahman", "aisha.rahman@demo-tax-advisers.example", "Aisha Rahman", "FIRM"),
                kit.person("farhan-siddiqui", "farhan.siddiqui@demo-tax-advisers.example", "Farhan Siddiqui", "FIRM"),
                kit.person("mariam-al-suwaidi", "mariam.alsuwaidi@demo-tax-advisers.example", "Mariam Al Suwaidi", "FIRM"),
                kit.person("hamid-qureshi", "hamid@qureshi-audit.example", "Hamid Qureshi (external auditor)", "EXTERNAL"));
        UUID billing = webhook("Practice billing: time and fees", "billing", List.of("RETURN_FILED", "PAYMENT_RECORDED", "PAYMENT_MATCHED_FROM_BANK",
                "DISCLOSURE_SUBMITTED", "REFUND_SUBMITTED", "REFUND_RECEIVED", "AUDIT_CASE_OPENED", "AUDIT_CASE_CLOSED", "ENTITY_CREATED"));
        prospects();
        register();
        rulePack();

        List<Client> clients = new ArrayList<>(List.of(new AlNoor(kit, time), new Falcon(kit, time, staff), new DesertRose(kit, time, staff), new Zarina(kit, time, staff)));
        List<EinvoiceEvent> backlog = new ArrayList<>();
        for (Client c : clients) {
            build(c, backlog);
        }
        UUID portal = webhook("Client portal sync", "client-portal", List.of("RETURN_SUBMITTED", "RETURN_APPROVED_AND_LOCKED", "RETURN_FILED"));
        build(new JebelAli(kit, time, staff), backlog);

        server.backlog(backlog);
        kit.system("deadline-alerts", () -> kit.deadlineAlerts.run(time.today));
        kit.system("audit-request-alerts", () -> kit.auditRequestAlerts.run(time.today));
        kit.as(Kit.OMAR, () -> kit.webhooks.setActive(portal, false));
        log.info("Demo webhooks: billing {} and the paused client portal {}", billing, portal);
    }

    private void build(Client c, List<EinvoiceEvent> backlog) {
        try {
            c.run();
            backlog.addAll(c.withheld);
        } catch (RuntimeException e) {
            log.error("Demo client {} stopped part way: {}", c.legalName(), e.getMessage(), e);
        }
    }

    private UUID webhook(String name, String path, List<String> events) {
        return kit.as(Kit.OMAR, () -> kit.webhooks.create(new WebhookService.EndpointInput(name, server.url("/hooks/" + path), events))).endpoint().id();
    }

    /**
     * The tax team signs off the pack shipped with the product, then the firm adds a policy of its own by
     * editing a copy, signed off too. Both happen before any client is loaded, so every line uses the edit.
     */
    private void rulePack() {
        kit.as(Kit.OMAR, () -> {
            RulePackStore.StoredPack shipped = kit.rulePacks.forDate(time.today).meta();
            kit.rulePacks.signOff(shipped.id(), "Reviewed by the tax team against the 2026 baseline. Open questions are tracked in the tax team questions log.");
            String yaml = kit.rulePacks.copy(shipped.id()).yaml();
            int at = yaml.indexOf("    - { id: BLOCK-EMPLOYEE");
            int end = yaml.indexOf('\n', at) + 1;
            String edited = yaml.substring(0, end)
                    + "    # Firm policy: golf and club memberships are entertainment unless a person shows otherwise.\n"
                    + "    - { id: BLOCK-CLUB-MEMBERSHIP, category: ENTERTAINMENT, direction: IN, patterns: [\"\\\\bgolf\\\\b\", \"\\\\bclub memberships?\\\\b\"] }\n"
                    + yaml.substring(end);
            RulePackStore.StoredPack firm = kit.rulePacks.saveEdit(edited, shipped.id(),
                    "Firm policy: golf and club memberships are entertainment, so their input VAT is blocked until a person confirms otherwise");
            kit.rulePacks.signOff(firm.id(), "Approved by the head of tax");
        });
    }

    /** Businesses the firm advises before they register, with their monthly figures. */
    private void prospects() {
        record Prospect(String name, String notes, double monthly, double growth, double expected) {}
        List<Prospect> prospects = List.of(
                new Prospect("Sahara Coffee Roasters", "Two cafes and wholesale to offices. Growing fast.", 21500, 0.035, 0),
                new Prospect("Blue Lagoon Diving Centre", "Dive trips and equipment. A hotel contract starts next month.", 30500, 0.02, 410000),
                new Prospect("Nomad Design Studio", "Freelance interior design. May register voluntarily.", 16800, 0.0, 0),
                new Prospect("Little Sprouts Nursery Supplies", "Online shop. Well below the threshold.", 8200, 0.01, 0));
        for (Prospect p : prospects) {
            UUID id = kit.as(Kit.OMAR, () -> kit.watches.add(new RegistrationWatchService.WatchInput(p.name(), p.notes()))).watch().id();
            for (int m = -12; m <= -1; m++) {
                double value = p.monthly() * Math.pow(1 + p.growth(), m + 12) * (1 + 0.06 * Math.sin(m));
                BigDecimal taxable = BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
                BigDecimal expected = m == -1 && p.expected() > 0 ? BigDecimal.valueOf(p.expected()).setScale(2) : null;
                LocalDate month = time.month(m).atDay(1);
                kit.as(Kit.OMAR, () -> kit.watches.recordMonth(id, new RegistrationWatchService.MonthInput(month, taxable, BigDecimal.ZERO, expected)));
            }
        }
    }

    /** Register entries a person checked that no client buys from, to show what inactive and unknown look like. */
    private void register() {
        kit.as(Kit.OMAR, () -> kit.parties.upsertCounterparty(new PartyService.CounterpartyInput("100599900100003", "Old Town Printing Press LLC", "AE", false, "INACTIVE",
                time.month(-40).atDay(1), time.month(-5).atDay(14), time.month(-4).atDay(2))));
        kit.as(Kit.OMAR, () -> kit.parties.upsertCounterparty(new PartyService.CounterpartyInput("100599900200003", "Star Events Management LLC", "AE", false, "UNKNOWN",
                null, null, null)));
    }

    // ---- Shared by the clients ----

    /** A connection to the client's ERP, pointed at the demo stub, with one pull already made. */
    static void connector(Kit kit, UUID entity, String system, String slug, String token, Timeline time) {
        ConnectorService.Connector c = kit.as(Kit.OMAR, () -> kit.connectors.create(entity, new ConnectorService.ConnectorInput(system,
                "http://localhost:" + DemoServer.port() + "/erp/" + slug, "BEARER", token, null, false)));
        LocalDate day = time.today.minusDays(1);
        try {
            kit.as(Kit.DANA, () -> kit.connectors.sync(c.id(), c.kinds().getFirst(), day, day));
        } catch (RuntimeException e) {
            log.info("Demo pull from {} recorded as failed: {}", system, e.getMessage());
        }
    }

    /** The day the demo's rupee rate is missing from the upload, so a held row has something to wait for. */
    static LocalDate missingInrRateDay(Timeline time) {
        return Gen.workingDay(time.day(-1, 17));
    }

    private static final Map<String, String> SOURCES = Map.of("USD", "CBUAE", "SAR", "CBUAE", "EUR", "CBUAE", "GBP", "CBUAE", "INR", "CBUAE", "CNY", "CBUAE");

    /** Made-up but steady rates: the dirham's dollar peg, and gentle movement for the rest. */
    static BigDecimal rate(String currency, LocalDate d) {
        double t = d.toEpochDay();
        return switch (currency) {
            case "USD" -> new BigDecimal("3.6725");
            case "SAR" -> new BigDecimal("0.9793");
            case "EUR" -> BigDecimal.valueOf(3.98 + 0.12 * Math.sin(t / 37.0) + 0.04 * Math.sin(t / 7.3)).setScale(4, RoundingMode.HALF_UP);
            case "GBP" -> BigDecimal.valueOf(4.64 + 0.10 * Math.sin(t / 41.0) + 0.03 * Math.sin(t / 5.9)).setScale(4, RoundingMode.HALF_UP);
            case "INR" -> BigDecimal.valueOf(0.0438 + 0.0006 * Math.sin(t / 29.0)).setScale(6, RoundingMode.HALF_UP);
            case "CNY" -> BigDecimal.valueOf(0.5085 + 0.006 * Math.sin(t / 33.0)).setScale(5, RoundingMode.HALF_UP);
            default -> throw new IllegalArgumentException("No demo rate for " + currency);
        };
    }

    /** Daily rates as the FX template wants them. The upload leaves out one rupee day, which the feed has. */
    static List<Map<String, String>> fxRows(Set<String> currencies, LocalDate from, LocalDate to, boolean upload) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (String currency : currencies.stream().sorted().toList()) {
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                if (upload && "INR".equals(currency) && d.equals(missingInrRateDay(new Timeline(to.plusDays(1))))) {
                    continue;
                }
                rows.add(Files.row("currency", currency, "rate_date", d, "aed_per_unit", rate(currency, d).toPlainString(), "source", SOURCES.get(currency)));
            }
        }
        return rows;
    }
}
