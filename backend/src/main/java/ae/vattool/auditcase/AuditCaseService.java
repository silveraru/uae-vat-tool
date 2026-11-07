package ae.vattool.auditcase;

import ae.vattool.evidence.EvidenceService;
import ae.vattool.platform.audit.AuditLog;
import ae.vattool.platform.documents.GeneratedDocuments;
import ae.vattool.platform.storage.VaultStorage;
import ae.vattool.platform.tenancy.RequestContext;
import ae.vattool.platform.web.DomainException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * An FTA audit from notice to close (AUD-03, spec journey 4). Opening a case puts its periods and
 * their evidence on legal hold, so nothing relevant can age out while the audit runs. A bundle is
 * what goes to the auditor: the FAF, the return packs and the evidence, with a manifest of fingerprints.
 */
@Service
public class AuditCaseService {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public record AuditCase(UUID id, UUID entityId, String ftaReference, LocalDate notifiedOn, LocalDate periodFrom, LocalDate periodTo, String description,
                            String status, String outcome, String openedBy, OffsetDateTime openedAt, String closedBy, OffsetDateTime closedAt) {
        static final String SELECT = """
                SELECT id, entity_id, fta_reference, notified_on, period_from, period_to, description, status, outcome, opened_by, opened_at, closed_by, closed_at
                FROM audit_case
                """;
    }

    public record Request(UUID id, UUID caseId, String description, LocalDate requestedOn, LocalDate dueOn, String status, String responseNote,
                          String respondedBy, LocalDate respondedOn, String createdBy, OffsetDateTime createdAt) {
        static final String SELECT = """
                SELECT id, case_id, description, requested_on, due_on, status, response_note, responded_by, responded_on, created_by, created_at
                FROM audit_request
                """;
    }

    public record CaseInput(String ftaReference, LocalDate notifiedOn, LocalDate periodFrom, LocalDate periodTo, String description) {}

    public record RequestInput(String description, LocalDate requestedOn, LocalDate dueOn) {}

    public record ResponseInput(String note, LocalDate respondedOn, List<UUID> evidenceIds) {}

    public record CloseInput(String outcome, boolean releaseHolds) {}

    public record View(AuditCase auditCase, List<Request> requests, List<EvidenceService.Evidence> requestEvidence, List<GeneratedDocuments.Doc> documents, int heldPeriods) {}

    private record Attachment(String fileName, String storageKey, String sha256) {}

    private final JdbcClient jdbc;
    private final FafService faf;
    private final EvidenceService evidence;
    private final GeneratedDocuments documents;
    private final VaultStorage vault;
    private final AuditLog audit;

    public AuditCaseService(JdbcClient jdbc, FafService faf, EvidenceService evidence, GeneratedDocuments documents, VaultStorage vault, AuditLog audit) {
        this.jdbc = jdbc;
        this.faf = faf;
        this.evidence = evidence;
        this.documents = documents;
        this.vault = vault;
        this.audit = audit;
    }

    @Transactional
    public View open(UUID entityId, CaseInput in) {
        if (!jdbc.sql("SELECT EXISTS (SELECT 1 FROM legal_entity WHERE id = ?)").param(entityId).query(Boolean.class).single()) {
            throw DomainException.notFound("Entity");
        }
        if (in.ftaReference() == null || in.ftaReference().isBlank()) {
            throw DomainException.invalid("FTA_REFERENCE_REQUIRED", "The FTA's reference for this audit is required");
        }
        if (in.notifiedOn() == null || in.periodFrom() == null || in.periodTo() == null || in.periodTo().isBefore(in.periodFrom())) {
            throw DomainException.invalid("RANGE_INVALID", "Give the notice date and a period range");
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO audit_case (id, tenant_id, entity_id, fta_reference, notified_on, period_from, period_to, description, status, opened_by)
                VALUES (:id, :tenant, :entity, :ref, :notified, :from, :to, :description, 'OPEN', :by)
                """)
                .param("id", id).param("tenant", RequestContext.requireTenant()).param("entity", entityId).param("ref", in.ftaReference())
                .param("notified", in.notifiedOn()).param("from", in.periodFrom()).param("to", in.periodTo()).param("description", in.description())
                .param("by", RequestContext.requireActor().actorId())
                .update();
        int held = hold(id, entityId, in);
        audit.record("AUDIT_CASE_OPENED", "AUDIT_CASE", id, entityId, null,
                Map.of("ftaReference", in.ftaReference(), "from", in.periodFrom().toString(), "to", in.periodTo().toString(), "periodsHeld", held), in.description());
        return view(id);
    }

    /** Evidence in storage goes on hold too, because the database row alone would not stop it being removed. */
    private int hold(UUID caseId, UUID entityId, CaseInput in) {
        record Period(UUID id) {}
        List<UUID> periods = jdbc.sql("""
                SELECT id FROM tax_period WHERE entity_id = :entity AND period_start <= :to AND period_end >= :from
                """).param("entity", entityId).param("to", in.periodTo()).param("from", in.periodFrom()).query(UUID.class).list();
        String reason = "FTA audit " + in.ftaReference();
        for (UUID period : periods) {
            jdbc.sql("""
                    INSERT INTO period_legal_hold (id, tenant_id, entity_id, period_id, reason, case_id, set_by)
                    VALUES (:id, :tenant, :entity, :period, :reason, :case, :by)
                    """).param("id", UUID.randomUUID()).param("tenant", RequestContext.requireTenant()).param("entity", entityId).param("period", period)
                    .param("reason", reason).param("case", caseId).param("by", RequestContext.requireActor().actorId()).update();
            for (EvidenceService.Evidence e : evidence.forPeriod(period)) {
                if (!e.legalHold()) {
                    evidence.setLegalHold(e.id(), true, reason);
                }
            }
        }
        return periods.size();
    }

    @Transactional
    public View addRequest(UUID caseId, RequestInput in) {
        AuditCase c = requireOpen(caseId);
        if (in.description() == null || in.description().isBlank() || in.dueOn() == null) {
            throw DomainException.invalid("REQUEST_INVALID", "A request needs a description and a due date");
        }
        UUID id = UUID.randomUUID();
        LocalDate requestedOn = in.requestedOn() != null ? in.requestedOn() : LocalDate.now();
        jdbc.sql("""
                INSERT INTO audit_request (id, tenant_id, case_id, description, requested_on, due_on, status, created_by)
                VALUES (:id, :tenant, :case, :description, :requested, :due, 'OPEN', :by)
                """)
                .param("id", id).param("tenant", RequestContext.requireTenant()).param("case", caseId).param("description", in.description())
                .param("requested", requestedOn).param("due", in.dueOn()).param("by", RequestContext.requireActor().actorId())
                .update();
        audit.record("AUDIT_REQUEST_ADDED", "AUDIT_REQUEST", id, c.entityId(), null, Map.of("due", in.dueOn().toString()), in.description());
        return view(caseId);
    }

    @Transactional
    public View respond(UUID requestId, ResponseInput in) {
        Request r = getRequest(requestId);
        AuditCase c = requireOpen(r.caseId());
        if (!"OPEN".equals(r.status())) {
            throw DomainException.conflict("ALREADY_DECIDED", "This request has already been answered");
        }
        if (in.note() == null || in.note().isBlank()) {
            throw DomainException.invalid("REASON_REQUIRED", "Describe what was given to the FTA");
        }
        LocalDate on = in.respondedOn() != null ? in.respondedOn() : LocalDate.now();
        jdbc.sql("UPDATE audit_request SET status = 'RESPONDED', response_note = :note, responded_by = :by, responded_on = :on WHERE id = :id")
                .param("note", in.note()).param("by", RequestContext.requireActor().actorId()).param("on", on).param("id", requestId).update();
        if (in.evidenceIds() != null) {
            for (UUID e : in.evidenceIds()) {
                evidence.requireForEntity(e, c.entityId());
                evidence.link(e, "AUDIT_REQUEST", requestId.toString());
            }
        }
        audit.record("AUDIT_REQUEST_ANSWERED", "AUDIT_REQUEST", requestId, c.entityId(), Map.of("status", "OPEN"), Map.of("status", "RESPONDED", "late", on.isAfter(r.dueOn())), in.note());
        return view(r.caseId());
    }

    @Transactional
    public FafService.Result generateFaf(UUID caseId) {
        AuditCase c = get(caseId);
        return faf.generate(c.entityId(), c.periodFrom(), c.periodTo(), caseId);
    }

    /** Assembles the bundle in one file, with a manifest that lets the recipient check every part. */
    @Transactional
    public GeneratedDocuments.Doc bundle(UUID caseId) {
        AuditCase c = get(caseId);
        List<GeneratedDocuments.Doc> fafs = documents.forObject(caseId.toString()).stream().filter(d -> "FAF".equals(d.kind())).toList();
        GeneratedDocuments.Doc fafFile = fafs.isEmpty() ? faf.generate(c.entityId(), c.periodFrom(), c.periodTo(), caseId).document() : fafs.getFirst();

        List<Attachment> packs = jdbc.sql("""
                SELECT 'return-packs/' || p.period_end || '/return-pack.pdf' AS file_name, rp.pdf_key AS storage_key, rp.pack_sha256 AS sha256
                FROM return_pack rp JOIN tax_period p ON p.id = rp.period_id
                WHERE p.entity_id = :entity AND p.period_start <= :to AND p.period_end >= :from
                  AND rp.created_at = (SELECT MAX(x.created_at) FROM return_pack x WHERE x.period_id = rp.period_id)
                ORDER BY p.period_end
                """).param("entity", c.entityId()).param("to", c.periodTo()).param("from", c.periodFrom()).query(Attachment.class).list();
        List<Attachment> attached = jdbc.sql("""
                SELECT 'evidence/' || e.id || '-' || regexp_replace(e.file_name, '[^A-Za-z0-9._-]', '_', 'g') AS file_name, e.storage_key, e.sha256
                FROM evidence e LEFT JOIN tax_period p ON p.id = e.period_id
                WHERE e.entity_id = :entity AND (
                    (p.period_start <= :to AND p.period_end >= :from)
                    OR e.id IN (SELECT l.evidence_id FROM evidence_link l JOIN audit_request r ON r.id::text = l.object_id
                                WHERE l.object_type = 'AUDIT_REQUEST' AND r.case_id = :case))
                ORDER BY e.uploaded_at
                """).param("entity", c.entityId()).param("to", c.periodTo()).param("from", c.periodFrom()).param("case", caseId).query(Attachment.class).list();

        List<Map<String, String>> manifest = new ArrayList<>();
        byte[] zip;
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(); ZipOutputStream z = new ZipOutputStream(out)) {
            Set<String> names = new HashSet<>();
            GeneratedDocuments.Content fafContent = documents.content(fafFile.id());
            add(z, names, manifest, "faf/" + fafContent.fileName(), fafContent.bytes());
            for (Attachment a : packs) {
                add(z, names, manifest, a.fileName(), vault.get(a.storageKey()));
            }
            for (Attachment a : attached) {
                byte[] content = vault.get(a.storageKey());
                if (!VaultStorage.sha256(content).equals(a.sha256())) {
                    throw new IllegalStateException("Evidence " + a.fileName() + " no longer matches its recorded fingerprint");
                }
                add(z, names, manifest, a.fileName(), content);
            }
            String head = jdbc.sql("SELECT last_hash FROM audit_chain_head WHERE tenant_id = ?").param(RequestContext.requireTenant()).query(String.class).optional().orElse("GENESIS");
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("ftaReference", c.ftaReference());
            summary.put("periodFrom", c.periodFrom().toString());
            summary.put("periodTo", c.periodTo().toString());
            summary.put("auditChainHead", head);
            summary.put("files", manifest);
            z.putNextEntry(new ZipEntry("manifest.json"));
            z.write(JSON.writeValueAsString(summary).getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.finish();
            zip = out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not build the audit bundle", e);
        }
        GeneratedDocuments.Doc doc = documents.store(c.entityId(), null, "AUDIT_BUNDLE", caseId, "audit-bundle-" + c.ftaReference().replaceAll("[^A-Za-z0-9._-]", "_") + ".zip", "application/zip", zip, fafFile.draftRules());
        audit.record("AUDIT_BUNDLE_MADE", "AUDIT_CASE", caseId, c.entityId(), null, Map.of("files", manifest.size(), "sha256", doc.sha256()), null);
        return doc;
    }

    private static void add(ZipOutputStream z, Set<String> names, List<Map<String, String>> manifest, String name, byte[] content) throws IOException {
        if (!names.add(name)) {
            return;
        }
        z.putNextEntry(new ZipEntry(name));
        z.write(content);
        z.closeEntry();
        manifest.add(Map.of("file", name, "sha256", VaultStorage.sha256(content), "bytes", String.valueOf(content.length)));
    }

    @Transactional
    public View close(UUID caseId, CloseInput in) {
        AuditCase c = requireOpen(caseId);
        if (in.outcome() == null || in.outcome().isBlank()) {
            throw DomainException.invalid("REASON_REQUIRED", "Record the outcome of the audit");
        }
        long open = jdbc.sql("SELECT count(*) FROM audit_request WHERE case_id = ? AND status = 'OPEN'").param(caseId).query(Long.class).single();
        if (open > 0) {
            throw DomainException.conflict("AUDIT_REQUESTS_OPEN", open + " request(s) from the FTA are still unanswered");
        }
        String me = RequestContext.requireActor().actorId();
        jdbc.sql("UPDATE audit_case SET status = 'CLOSED', outcome = :outcome, closed_by = :by, closed_at = now() WHERE id = :id")
                .param("outcome", in.outcome()).param("by", me).param("id", caseId).update();
        if (in.releaseHolds()) {
            jdbc.sql("UPDATE period_legal_hold SET released_by = ?, released_at = now() WHERE case_id = ? AND released_at IS NULL").params(me, caseId).update();
            for (EvidenceService.Evidence e : jdbc.sql("""
                    SELECT id FROM tax_period WHERE entity_id = :entity AND period_start <= :to AND period_end >= :from
                    """).param("entity", c.entityId()).param("to", c.periodTo()).param("from", c.periodFrom()).query(UUID.class).list().stream()
                    .flatMap(p -> evidence.forPeriod(p).stream()).toList()) {
                boolean heldElsewhere = jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM period_legal_hold WHERE period_id = ? AND released_at IS NULL)
                        """).param(e.periodId()).query(Boolean.class).single();
                if (e.legalHold() && !heldElsewhere) {
                    evidence.setLegalHold(e.id(), false, "Audit " + c.ftaReference() + " closed");
                }
            }
        }
        audit.record("AUDIT_CASE_CLOSED", "AUDIT_CASE", caseId, c.entityId(), Map.of("status", "OPEN"), Map.of("status", "CLOSED", "holdsReleased", in.releaseHolds()), in.outcome());
        return view(caseId);
    }

    @Transactional(readOnly = true)
    public AuditCase get(UUID id) {
        return jdbc.sql(AuditCase.SELECT + " WHERE id = ?").param(id).query(AuditCase.class).optional().orElseThrow(() -> DomainException.notFound("Audit case"));
    }

    @Transactional(readOnly = true)
    public Request getRequest(UUID id) {
        return jdbc.sql(Request.SELECT + " WHERE id = ?").param(id).query(Request.class).optional().orElseThrow(() -> DomainException.notFound("Audit request"));
    }

    @Transactional(readOnly = true)
    public List<AuditCase> list(UUID entityId) {
        return jdbc.sql(AuditCase.SELECT + " WHERE entity_id = ? ORDER BY opened_at DESC").param(entityId).query(AuditCase.class).list();
    }

    @Transactional(readOnly = true)
    public View view(UUID id) {
        AuditCase c = get(id);
        List<Request> requests = jdbc.sql(Request.SELECT + " WHERE case_id = ? ORDER BY due_on, created_at").param(id).query(Request.class).list();
        List<EvidenceService.Evidence> attached = new ArrayList<>();
        for (Request r : requests) {
            attached.addAll(evidence.forObject("AUDIT_REQUEST", r.id().toString()));
        }
        int held = jdbc.sql("SELECT count(*) FROM period_legal_hold WHERE case_id = ? AND released_at IS NULL").param(id).query(Integer.class).single();
        return new View(c, requests, attached, documents.forObject(id.toString()), held);
    }

    private AuditCase requireOpen(UUID id) {
        AuditCase c = get(id);
        if (!"OPEN".equals(c.status())) {
            throw DomainException.conflict("AUDIT_CASE_CLOSED", "This audit case is closed");
        }
        return c;
    }
}
