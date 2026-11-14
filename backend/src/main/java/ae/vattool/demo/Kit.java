package ae.vattool.demo;

import ae.vattool.adjustments.AdjustmentService;
import ae.vattool.adjustments.ApportionmentService;
import ae.vattool.adjustments.BadDebtService;
import ae.vattool.adjustments.CapitalAssetService;
import ae.vattool.adjustments.CorrectionService;
import ae.vattool.analytics.RegistrationWatchService;
import ae.vattool.auditcase.AuditCaseService;
import ae.vattool.auditcase.AuditRequestDueJob;
import ae.vattool.banking.BankStatementService;
import ae.vattool.classification.ExportEvidenceService;
import ae.vattool.classification.SuggestionService;
import ae.vattool.corrections.DisclosureService;
import ae.vattool.corrections.RefundService;
import ae.vattool.evidence.EvidenceService;
import ae.vattool.filing.BankPaymentMatcher;
import ae.vattool.filing.FilingService;
import ae.vattool.filing.ReturnPackService;
import ae.vattool.ingestion.IngestionService;
import ae.vattool.ingestion.Templates;
import ae.vattool.ingestion.UblImportService;
import ae.vattool.ingestion.connectors.ConnectorService;
import ae.vattool.ingestion.einvoice.EinvoiceEvent;
import ae.vattool.ingestion.einvoice.EinvoiceStore;
import ae.vattool.ledger.LineController;
import ae.vattool.masterdata.EntityService;
import ae.vattool.masterdata.LegalEntity;
import ae.vattool.masterdata.MappingService;
import ae.vattool.rules.ClientRuleService;
import ae.vattool.rules.ClientRuleVersion;
import ae.vattool.rules.ClientRules;
import ae.vattool.rules.RulePackStore;
import ae.vattool.masterdata.PartyService;
import ae.vattool.masterdata.PeriodCalendar;
import ae.vattool.masterdata.TaxPeriod;
import ae.vattool.platform.seed.DevSeed;
import ae.vattool.platform.tenancy.RequestContext;
import ae.vattool.portfolio.RetentionController;
import ae.vattool.practice.PracticeService;
import ae.vattool.practice.WebhookService;
import ae.vattool.reconciliation.DocumentMatcher;
import ae.vattool.reconciliation.ReconciliationService;
import ae.vattool.returns.BoxCalculator.BoxValue;
import ae.vattool.returns.ReturnService;
import ae.vattool.workflow.CommentService;
import ae.vattool.workflow.DeadlineAlertJob;
import ae.vattool.workflow.WorkflowService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Everything the demo does, done the way a person would: through the real services, as a named user,
 * so sign-offs, maker-checker rules, audit events and webhooks all behave as they do for real work.
 */
@Component
class Kit {

    static final UUID TENANT = DevSeed.DEMO_TENANT;

    record Person(UUID id, String name, String email) {}

    static final Person DANA = persona(0);
    static final Person NOOR = persona(1);
    static final Person RAMI = persona(2);
    static final Person LINA = persona(3);
    static final Person OMAR = persona(4);
    static final Person SAMI = persona(5);

    private static Person persona(int i) {
        DevSeed.DemoUser u = DevSeed.USERS.get(i);
        return new Person(u.id(), u.name(), u.email());
    }

    /** Client rules published by the firm admin, and signed off by them unless left as a draft (signOff null). */
    ClientRuleVersion clientRules(UUID entity, java.time.LocalDate from, String note, ClientRules rules, String signOff) {
        ClientRuleVersion published = as(OMAR, () -> clientRules.publish(entity, from, note, rules));
        return signOff == null ? published : as(OMAR, () -> clientRules.signOff(entity, published.id(), signOff));
    }

    final JdbcClient jdbc;
    final TransactionTemplate tx;
    final EntityService entities;
    final MappingService mappings;
    final PartyService parties;
    final PeriodCalendar calendar;
    final IngestionService ingestion;
    final UblImportService ubl;
    final EinvoiceStore einvoices;
    final ReturnService returns;
    final ReconciliationService recon;
    final DocumentMatcher matcher;
    final WorkflowService workflow;
    final CommentService comments;
    final ReturnPackService packs;
    final FilingService filings;
    final BankStatementService statements;
    final BankPaymentMatcher bankMatcher;
    final EvidenceService evidence;
    final AdjustmentService adjustments;
    final CorrectionService corrections;
    final ApportionmentService apportionment;
    final BadDebtService badDebts;
    final CapitalAssetService assets;
    final DisclosureService disclosures;
    final RefundService refunds;
    final AuditCaseService auditCases;
    final SuggestionService suggestions;
    final ExportEvidenceService exports;
    final PracticeService practice;
    final WebhookService webhooks;
    final RegistrationWatchService watches;
    final ConnectorService connectors;
    final LineController lines;
    final RetentionController retention;
    final DeadlineAlertJob deadlineAlerts;
    final AuditRequestDueJob auditRequestAlerts;
    final RulePackStore rulePacks;
    final ClientRuleService clientRules;

    Kit(JdbcClient jdbc, TransactionTemplate tx, EntityService entities, MappingService mappings, PartyService parties, PeriodCalendar calendar,
        IngestionService ingestion, UblImportService ubl, EinvoiceStore einvoices, ReturnService returns, ReconciliationService recon,
        DocumentMatcher matcher, WorkflowService workflow, CommentService comments, ReturnPackService packs, FilingService filings,
        BankStatementService statements, BankPaymentMatcher bankMatcher, EvidenceService evidence, AdjustmentService adjustments,
        CorrectionService corrections, ApportionmentService apportionment, BadDebtService badDebts, CapitalAssetService assets,
        DisclosureService disclosures, RefundService refunds, AuditCaseService auditCases, SuggestionService suggestions,
        ExportEvidenceService exports, PracticeService practice, WebhookService webhooks, RegistrationWatchService watches,
        ConnectorService connectors, LineController lines, RetentionController retention, DeadlineAlertJob deadlineAlerts,
        AuditRequestDueJob auditRequestAlerts, RulePackStore rulePacks, ClientRuleService clientRules) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.entities = entities;
        this.mappings = mappings;
        this.parties = parties;
        this.calendar = calendar;
        this.ingestion = ingestion;
        this.ubl = ubl;
        this.einvoices = einvoices;
        this.returns = returns;
        this.recon = recon;
        this.matcher = matcher;
        this.workflow = workflow;
        this.comments = comments;
        this.packs = packs;
        this.filings = filings;
        this.statements = statements;
        this.bankMatcher = bankMatcher;
        this.evidence = evidence;
        this.adjustments = adjustments;
        this.corrections = corrections;
        this.apportionment = apportionment;
        this.badDebts = badDebts;
        this.assets = assets;
        this.disclosures = disclosures;
        this.refunds = refunds;
        this.auditCases = auditCases;
        this.suggestions = suggestions;
        this.exports = exports;
        this.practice = practice;
        this.webhooks = webhooks;
        this.watches = watches;
        this.connectors = connectors;
        this.lines = lines;
        this.retention = retention;
        this.rulePacks = rulePacks;
        this.clientRules = clientRules;
        this.deadlineAlerts = deadlineAlerts;
        this.auditRequestAlerts = auditRequestAlerts;
    }

    // ---- Who is acting ----

    <T> T as(Person who, Supplier<T> work) {
        return RequestContext.runAs(new RequestContext.Actor(TENANT, who.id(), null), work);
    }

    void as(Person who, Runnable work) {
        as(who, () -> {
            work.run();
            return null;
        });
    }

    <T> T system(String name, Supplier<T> work) {
        return RequestContext.asSystem(TENANT, name, work);
    }

    /** A read or a small write straight against the database, in the tenant, outside any person's name. */
    <T> T query(Supplier<T> work) {
        return system("demo-data", () -> tx.execute(s -> work.get()));
    }

    // ---- People ----

    Person person(String slug, String email, String name, String side) {
        PartyService.User u = system("demo-data", () -> parties.createUser(new PartyService.UserInput("demo:" + slug, email, name, side)));
        return new Person(u.id(), name, email);
    }

    void grant(UUID entity, Person who, String role, LocalDate until) {
        as(OMAR, () -> parties.grant(entity, new PartyService.GrantInput(who.id(), role,
                until == null ? null : until.atTime(23, 59, 59).atOffset(java.time.ZoneOffset.ofHours(4)))));
    }

    void assign(UUID entity, Person who, String duty) {
        as(LINA, () -> practice.assign(entity, new PracticeService.AssignmentInput(who.id(), duty)));
    }

    // ---- Data in ----

    IngestionService.LoadResult load(Person who, UUID filer, Templates.Kind kind, String channel, String fileName, List<Map<String, String>> rows) {
        byte[] content = Files.csv(kind, rows);
        return as(who, () -> ingestion.loadFile(filer, kind, Templates.CURRENT_VERSION, channel, fileName, content));
    }

    void einvoices(List<EinvoiceEvent> events) {
        system("asp-platform", () -> {
            events.forEach(einvoices::apply);
            return null;
        });
    }

    /** Loads a statement and lets the bank lines settle what they can, as the upload screen does. */
    int bank(Person who, UUID entity, String fileName, byte[] content) {
        return as(who, () -> {
            BankStatementService.ImportResult r = statements.load(entity, fileName, content);
            return r.alreadyProcessed() ? 0 : bankMatcher.autoMatch(entity);
        });
    }

    UUID evidence(Person who, UUID entity, UUID period, String fileName, String description, String retentionClass, String title, List<String> body) {
        byte[] pdf = Pdf.page(title, body);
        return as(who, () -> evidence.upload(entity, period, fileName, "application/pdf", pdf, description, retentionClass).id());
    }

    // ---- Periods and returns ----

    TaxPeriod period(UUID filer, LocalDate day) {
        return system("demo-data", () -> calendar.periodFor(filer, day).orElseThrow());
    }

    TaxPeriod reload(UUID periodId) {
        return system("demo-data", () -> calendar.get(periodId));
    }

    LegalEntity entity(UUID id) {
        return system("demo-data", () -> entities.get(id));
    }

    Map<String, BoxValue> boxes(UUID periodId) {
        return system("demo-data", () -> returns.latest(periodId).map(v -> ReturnService.parseValues(v.boxValues())).orElse(Map.of()));
    }

    void recompute(Person who, UUID periodId) {
        as(who, () -> returns.recompute(periodId, "DEMO"));
    }

    ReconciliationService.Summary reconcile(Person who, UUID periodId) {
        return as(who, () -> recon.run(periodId, "MANUAL"));
    }

    List<ReconciliationService.Exception> open(UUID periodId) {
        return system("demo-data", () -> recon.list(periodId, null, null)).stream()
                .filter(e -> Set.of("OPEN", "ASSIGNED").contains(e.status())).toList();
    }

    void comment(Person who, UUID periodId, String objectType, String objectId, String body) {
        as(who, () -> comments.add(calendar.get(periodId), objectType, objectId, null, body));
    }

    void waive(Person who, UUID exceptionId, String reason) {
        as(who, () -> recon.waive(exceptionId, reason));
    }

    // ---- Workflow ----

    void submit(Person who, UUID periodId, String note) {
        as(who, () -> workflow.submit(periodId, note));
    }

    void review(Person who, UUID periodId, String note) {
        as(who, () -> {
            workflow.refresh(periodId);
            return workflow.review(periodId, note);
        });
    }

    void partner(Person who, UUID periodId, String note) {
        as(who, () -> {
            workflow.refresh(periodId);
            return workflow.partnerSignoff(periodId, note);
        });
    }

    void approve(Person who, UUID periodId, String note, String signedName) {
        as(who, () -> {
            workflow.refresh(periodId);
            return workflow.approve(periodId, note, signedName == null ? null : new WorkflowService.SignatureInput(signedName, true),
                    "127.0.0.1", "demo-data-loader");
        });
    }

    WorkflowService.Status status(UUID periodId) {
        return system("demo-data", () -> workflow.status(periodId));
    }

    void pack(Person who, UUID periodId) {
        as(who, () -> packs.generate(periodId));
    }

    void file(Person who, UUID periodId, String reference, LocalDate on, UUID ackEvidence) {
        as(who, () -> filings.recordFiling(periodId, new FilingService.FilingInput(reference, on, ackEvidence)));
    }

    // ---- Lookups ----

    UUID documentId(UUID filer, String direction, String number) {
        return query(() -> jdbc.sql("""
                SELECT id FROM source_document WHERE filer_entity_id = ? AND direction = ? AND doc_number = ? AND voided_at IS NULL
                ORDER BY doc_date DESC LIMIT 1
                """).params(filer, direction, number).query(UUID.class).single());
    }

    List<UUID> currentLines(UUID periodId, String where, Object... params) {
        return query(() -> {
            var spec = jdbc.sql("SELECT id FROM tax_line WHERE period_id = ? AND superseded_at IS NULL AND line_kind = 'NORMAL' AND " + where)
                    .param(periodId);
            for (Object p : params) {
                spec = spec.param(p);
            }
            return spec.query(UUID.class).list();
        });
    }

    BigDecimal sum(String sql, Object... params) {
        return query(() -> {
            var spec = jdbc.sql(sql);
            for (Object p : params) {
                spec = spec.param(p);
            }
            return spec.query(BigDecimal.class).optional().orElse(BigDecimal.ZERO);
        });
    }
}
