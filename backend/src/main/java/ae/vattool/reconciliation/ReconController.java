package ae.vattool.reconciliation;

import ae.vattool.masterdata.PeriodCalendar;
import ae.vattool.masterdata.TaxPeriod;
import ae.vattool.platform.security.AccessControl;
import ae.vattool.platform.security.Permission;
import ae.vattool.platform.web.DomainException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ReconController {

    private final ReconciliationService recon;
    private final CertificateService certificates;
    private final DocumentMatcher matcher;
    private final AccessControl access;
    private final PeriodCalendar calendar;

    public ReconController(ReconciliationService recon, CertificateService certificates, DocumentMatcher matcher,
                           AccessControl access, PeriodCalendar calendar) {
        this.recon = recon;
        this.certificates = certificates;
        this.matcher = matcher;
        this.access = access;
        this.calendar = calendar;
    }

    public record AssignInput(UUID ownerId, LocalDate dueDate) {}

    public record ResolveInput(String resolutionCode, String note) {}

    public record WaiveInput(String reason) {}

    public record TimingInput(String accountType, BigDecimal amountAed, String description) {}

    public record MatchDecision(boolean accept) {}

    @GetMapping("/recon/rules")
    public List<RuleCatalog.Rule> rules() {
        return RuleCatalog.RULES;
    }

    /** REC-01: on demand. */
    @PostMapping("/periods/{id}/reconcile")
    public ReconciliationService.Summary run(@PathVariable UUID id) {
        TaxPeriod p = load(id);
        access.require(p.entityId(), Permission.VIEW);
        return recon.run(id, "ON_DEMAND");
    }

    @GetMapping("/periods/{id}/exceptions")
    public List<ReconciliationService.Exception> exceptions(@PathVariable UUID id, @RequestParam(required = false) String status,
                                                            @RequestParam(required = false) String severity) {
        TaxPeriod p = load(id);
        access.requireReadPeriod(p.entityId(), p.isLocked());
        return recon.list(id, status, severity);
    }

    @GetMapping("/periods/{id}/certificate")
    public CertificateService.Certificate certificate(@PathVariable UUID id) {
        TaxPeriod p = load(id);
        access.requireReadPeriod(p.entityId(), p.isLocked());
        return certificates.build(p);
    }

    @PostMapping("/exceptions/{id}/assign")
    public ReconciliationService.Exception assign(@PathVariable UUID id, @RequestBody AssignInput in) {
        access.require(recon.get(id).entityId(), Permission.COMMENT);
        return recon.assign(id, in.ownerId(), in.dueDate());
    }

    @PostMapping("/exceptions/{id}/resolve")
    public ReconciliationService.Exception resolve(@PathVariable UUID id, @RequestBody ResolveInput in) {
        access.require(recon.get(id).entityId(), Permission.EDIT_DATA);
        return recon.resolve(id, in.resolutionCode(), in.note());
    }

    /** Waiving takes review or approval authority, which a preparer does not have. */
    @PostMapping("/exceptions/{id}/waive")
    public ReconciliationService.Exception waive(@PathVariable UUID id, @RequestBody WaiveInput in) {
        UUID entity = recon.get(id).entityId();
        if (!access.can(entity, Permission.REVIEW_SIGNOFF) && !access.can(entity, Permission.APPROVE_AND_LOCK)
                && !access.can(entity, Permission.PARTNER_SIGNOFF)) {
            throw DomainException.forbidden("Only a reviewer, partner or approver can waive an exception");
        }
        return recon.waive(id, in.reason());
    }

    @PostMapping("/periods/{id}/timing-items")
    public Map<String, UUID> addTiming(@PathVariable UUID id, @RequestBody TimingInput in) {
        TaxPeriod p = load(id);
        access.require(p.entityId(), Permission.EDIT_DATA);
        requireOpen(p);
        return Map.of("id", recon.addTimingItem(p, in.accountType(), in.amountAed(), in.description()));
    }

    @GetMapping("/periods/{id}/timing-items")
    public List<Map<String, Object>> timing(@PathVariable UUID id) {
        TaxPeriod p = load(id);
        access.requireReadPeriod(p.entityId(), p.isLocked());
        return recon.timingItems(id);
    }

    @GetMapping("/periods/{id}/match-proposals")
    public List<DocumentMatcher.Proposal> proposals(@PathVariable UUID id) {
        TaxPeriod p = load(id);
        access.requireReadPeriod(p.entityId(), p.isLocked());
        return matcher.proposals(p);
    }

    @PostMapping("/periods/{id}/match-proposals/{matchId}")
    public ReconciliationService.Summary decide(@PathVariable UUID id, @PathVariable UUID matchId, @RequestBody MatchDecision in) {
        TaxPeriod p = load(id);
        access.require(p.entityId(), Permission.EDIT_DATA);
        requireOpen(p);
        matcher.decide(matchId, p.entityId(), in.accept());
        return recon.run(id, "MATCH_DECISION");
    }

    private static void requireOpen(TaxPeriod p) {
        if (p.isLocked()) {
            throw DomainException.conflict("PERIOD_LOCKED", "This period is locked");
        }
    }

    private TaxPeriod load(UUID id) {
        return calendar.get(id);
    }
}
