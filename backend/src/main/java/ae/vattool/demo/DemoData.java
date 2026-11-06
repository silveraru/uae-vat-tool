package ae.vattool.demo;

import ae.vattool.ingestion.IngestionService;
import ae.vattool.ingestion.Templates;
import ae.vattool.masterdata.EntityService;
import ae.vattool.masterdata.LegalEntity;
import ae.vattool.masterdata.MappingService;
import ae.vattool.masterdata.PartyService;
import ae.vattool.masterdata.PeriodCalendar;
import ae.vattool.platform.seed.DevSeed;
import ae.vattool.platform.tenancy.RequestContext;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loads the five golden entities through the real services on first start in the dev profile, so
 * there is something to look at straight away. They have no e-invoices yet, so check R01 shows what
 * an unreconciled period looks like. Local development only.
 */
@Component
@ConditionalOnProperty(name = "app.seed.demo-data", havingValue = "true")
@Order(10)
public class DemoData implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoData.class);
    private static final List<String> FIXTURES = List.of("g1-standalone-monthly", "g2-standalone-quarterly", "g3-tax-group", "g4-importer-drc", "g5-designated-zone");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final EntityService entities;
    private final MappingService mappings;
    private final PartyService parties;
    private final PeriodCalendar calendar;
    private final IngestionService ingestion;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Path fixtures;

    public DemoData(EntityService entities, MappingService mappings, PartyService parties, PeriodCalendar calendar, IngestionService ingestion,
                    JdbcClient jdbc, TransactionTemplate tx, @Value("${app.seed.fixtures:../fixtures/golden}") String fixtures) {
        this.entities = entities;
        this.mappings = mappings;
        this.parties = parties;
        this.calendar = calendar;
        this.ingestion = ingestion;
        this.jdbc = jdbc;
        this.tx = tx;
        this.fixtures = Path.of(fixtures);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!Files.isDirectory(fixtures)) {
            log.warn("Demo data skipped: {} not found", fixtures);
            return;
        }
        RequestContext.asSystem(DevSeed.DEMO_TENANT, "demo-data", () -> {
            int existing = tx.execute(s -> jdbc.sql("SELECT COUNT(*) FROM legal_entity").query(Integer.class).single());
            if (existing > 0) {
                return;
            }
            for (String name : FIXTURES) {
                try {
                    load(fixtures.resolve(name));
                } catch (IOException e) {
                    log.warn("Could not load demo fixture {}: {}", name, e.getMessage());
                }
            }
            log.info("Demo data loaded: {} golden entities", FIXTURES.size());
        });
    }

    private void load(Path dir) throws IOException {
        JsonNode setup = JSON.readTree(Files.readString(dir.resolve("setup.json")));
        JsonNode e = setup.get("entity");
        LegalEntity filer = entities.create(new EntityService.EntityInput(e.get("legalName").asString(), null, e.get("trn").asString(),
                e.get("entityKind").asString(), null, e.get("filingFrequency").asString(), null, LocalDate.parse(e.get("firstPeriodStart").asString()),
                LocalDate.parse(e.get("einvoiceGoLive").asString()), BigDecimal.valueOf(100), null, true));
        establishments(filer.id(), setup.get("establishments"));
        if (setup.has("members")) {
            for (JsonNode m : setup.get("members")) {
                LegalEntity member = entities.create(new EntityService.EntityInput(m.get("legalName").asString(), null, null, "GROUP_MEMBER", filer.id(),
                        null, null, null, null, BigDecimal.valueOf(100), null, true));
                establishments(member.id(), m.get("establishments"));
            }
        }
        List<MappingService.EntryInput> entries = new ArrayList<>();
        for (JsonNode m : setup.get("mappings")) {
            entries.add(new MappingService.EntryInput(m.get("matchType").asString(), m.get("sourceCode").asString(), optional(m, "treatment"),
                    optional(m, "blockedCategory"), optional(m, "drcCategory")));
        }
        mappings.publish(filer.id(), LocalDate.parse("2018-01-01"), "Demo mapping", entries);
        for (JsonNode c : setup.get("counterparties")) {
            parties.upsertCounterparty(new PartyService.CounterpartyInput(c.get("trn").asString(), c.get("name").asString(), optional(c, "country"),
                    c.path("designatedZone").asBoolean(false), optional(c, "trnStatus"), null, null, null));
        }
        for (String[] f : new String[][] {{"fx.csv", "FX"}, {"ar.csv", "AR"}, {"ap.csv", "AP"}, {"customs.csv", "CUSTOMS"}, {"gl.csv", "GL"}}) {
            Path file = dir.resolve(f[0]);
            if (Files.exists(file)) {
                ingestion.loadFile(filer.id(), Templates.Kind.valueOf(f[1]), Templates.CURRENT_VERSION, "UPLOAD", f[0], Files.readAllBytes(file));
            }
        }
        calendar.ensureThrough(filer.id(), LocalDate.parse("2027-03-31"));
    }

    private void establishments(UUID entity, JsonNode list) {
        for (JsonNode x : list) {
            entities.addEstablishment(entity, new EntityService.EstablishmentInput(x.get("code").asString(), x.get("name").asString(),
                    x.get("emirate").asString(), x.path("designatedZone").asBoolean(false), optional(x, "zoneName")));
        }
    }

    private static String optional(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asString() : null;
    }
}
