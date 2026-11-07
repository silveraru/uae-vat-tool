package ae.vattool.auditcase;

import ae.vattool.platform.documents.GeneratedDocuments;
import ae.vattool.platform.security.AccessControl;
import ae.vattool.platform.security.Permission;
import ae.vattool.platform.web.DomainException;
import java.time.LocalDate;
import java.util.List;
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
public class AuditCaseController {

    private final AuditCaseService cases;
    private final FafService faf;
    private final AccessControl access;

    public AuditCaseController(AuditCaseService cases, FafService faf, AccessControl access) {
        this.cases = cases;
        this.faf = faf;
        this.access = access;
    }

    @PostMapping("/entities/{id}/faf")
    public FafService.Result faf(@PathVariable UUID id, @RequestParam LocalDate from, @RequestParam LocalDate to) {
        access.require(id, Permission.MANAGE_AUDIT);
        return faf.generate(id, from, to, id);
    }

    @PostMapping("/entities/{id}/audit-cases")
    public AuditCaseService.View open(@PathVariable UUID id, @RequestBody AuditCaseService.CaseInput in) {
        access.require(id, Permission.MANAGE_AUDIT);
        return cases.open(id, in);
    }

    @GetMapping("/entities/{id}/audit-cases")
    public List<AuditCaseService.AuditCase> list(@PathVariable UUID id) {
        access.require(id, Permission.VIEW);
        return cases.list(id);
    }

    @GetMapping("/audit-cases/{id}")
    public AuditCaseService.View view(@PathVariable UUID id) {
        access.require(cases.get(id).entityId(), Permission.VIEW);
        return cases.view(id);
    }

    @PostMapping("/audit-cases/{id}/requests")
    public AuditCaseService.View addRequest(@PathVariable UUID id, @RequestBody AuditCaseService.RequestInput in) {
        access.require(cases.get(id).entityId(), Permission.MANAGE_AUDIT);
        return cases.addRequest(id, in);
    }

    @PostMapping("/audit-requests/{id}/response")
    public AuditCaseService.View respond(@PathVariable UUID id, @RequestBody AuditCaseService.ResponseInput in) {
        AuditCaseService.Request r = cases.getRequest(id);
        access.require(cases.get(r.caseId()).entityId(), Permission.MANAGE_AUDIT);
        return cases.respond(id, in);
    }

    @PostMapping("/audit-cases/{id}/faf")
    public FafService.Result caseFaf(@PathVariable UUID id) {
        access.require(cases.get(id).entityId(), Permission.MANAGE_AUDIT);
        return cases.generateFaf(id);
    }

    @PostMapping("/audit-cases/{id}/bundle")
    public GeneratedDocuments.Doc bundle(@PathVariable UUID id) {
        access.require(cases.get(id).entityId(), Permission.MANAGE_AUDIT);
        return cases.bundle(id);
    }

    @PostMapping("/audit-cases/{id}/close")
    public AuditCaseService.View close(@PathVariable UUID id, @RequestBody AuditCaseService.CloseInput in) {
        UUID entity = cases.get(id).entityId();
        if (!access.can(entity, Permission.PARTNER_SIGNOFF) && !access.can(entity, Permission.ADMINISTER)) {
            throw DomainException.forbidden("Only the partner or firm admin can close an audit case");
        }
        return cases.close(id, in);
    }
}
