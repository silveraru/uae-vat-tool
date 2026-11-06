package ae.vattool.adjustments;

import ae.vattool.masterdata.PeriodCalendar;
import ae.vattool.masterdata.TaxPeriod;
import ae.vattool.platform.security.AccessControl;
import ae.vattool.platform.security.Permission;
import ae.vattool.platform.web.DomainException;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AdjustmentController {

    private final AdjustmentService adjustments;
    private final CorrectionService corrections;
    private final PeriodCalendar calendar;
    private final AccessControl access;

    public AdjustmentController(AdjustmentService adjustments, CorrectionService corrections, PeriodCalendar calendar, AccessControl access) {
        this.adjustments = adjustments;
        this.corrections = corrections;
        this.calendar = calendar;
        this.access = access;
    }

    public record Decision(boolean approve, String note) {}

    @GetMapping("/periods/{id}/adjustments")
    public List<AdjustmentService.Adjustment> list(@PathVariable UUID id) {
        TaxPeriod p = calendar.get(id);
        access.requireReadPeriod(p.entityId(), p.isLocked());
        return adjustments.list(id);
    }

    @PostMapping("/periods/{id}/adjustments")
    public AdjustmentService.Adjustment propose(@PathVariable UUID id, @RequestBody AdjustmentService.ManualInput in) {
        TaxPeriod p = calendar.get(id);
        requireAdjust(p.entityId());
        return adjustments.propose(p, in);
    }

    @PostMapping("/adjustments/{id}/decision")
    public AdjustmentService.Adjustment decide(@PathVariable UUID id, @RequestBody Decision in) {
        UUID entity = adjustments.get(id).entityId();
        if (!access.can(entity, Permission.REVIEW_SIGNOFF) && !access.can(entity, Permission.APPROVE_AND_LOCK)) {
            throw DomainException.forbidden("Only a reviewer or approver can decide an adjustment");
        }
        return adjustments.decide(id, in.approve(), in.note());
    }

    @PostMapping("/periods/{id}/adjustments/supplier-reversal")
    public AdjustmentService.ReversalResult reversal(@PathVariable UUID id) {
        TaxPeriod p = calendar.get(id);
        requireAdjust(p.entityId());
        return adjustments.runSupplierReversal(p);
    }

    @PostMapping("/periods/{id}/corrections/preview")
    public CorrectionService.Preview preview(@PathVariable UUID id, @RequestBody CorrectionService.CaseInput in) {
        TaxPeriod p = calendar.get(id);
        access.require(p.entityId(), Permission.EDIT_ADJUSTMENTS);
        return corrections.preview(id, in.manualChanges());
    }

    @PostMapping("/periods/{id}/corrections")
    public CorrectionService.CorrectionCase open(@PathVariable UUID id, @RequestBody CorrectionService.CaseInput in) {
        TaxPeriod p = calendar.get(id);
        access.require(p.entityId(), Permission.EDIT_ADJUSTMENTS);
        return corrections.open(id, in);
    }

    @GetMapping("/periods/{id}/corrections")
    public List<CorrectionService.CorrectionCase> corrections(@PathVariable UUID id) {
        TaxPeriod p = calendar.get(id);
        access.requireReadPeriod(p.entityId(), p.isLocked());
        return corrections.list(id);
    }

    private void requireAdjust(UUID entity) {
        if (!access.can(entity, Permission.EDIT_ADJUSTMENTS) && !access.can(entity, Permission.EDIT_DATA)) {
            throw DomainException.forbidden("Not permitted to make adjustments");
        }
    }
}
