package ae.vattool.corrections;

import ae.vattool.adjustments.CorrectionService;
import ae.vattool.masterdata.PeriodCalendar;
import ae.vattool.platform.security.AccessControl;
import ae.vattool.platform.security.Permission;
import ae.vattool.platform.web.DomainException;
import java.time.LocalDate;
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
public class CorrectionsController {

    private final PenaltyService penalties;
    private final DisclosureService disclosures;
    private final RefundService refunds;
    private final CorrectionService corrections;
    private final PeriodCalendar calendar;
    private final AccessControl access;

    public CorrectionsController(PenaltyService penalties, DisclosureService disclosures, RefundService refunds, CorrectionService corrections,
                                 PeriodCalendar calendar, AccessControl access) {
        this.penalties = penalties;
        this.disclosures = disclosures;
        this.refunds = refunds;
        this.corrections = corrections;
        this.calendar = calendar;
        this.access = access;
    }

    // Penalty exposure, disclosures and refund claims are the firm's own working, so an external
    // auditor's locked-period access does not reach them. Hence VIEW here, not the locked-period check.
    @GetMapping("/periods/{id}/penalties")
    public PenaltyCalculator.Estimate periodPenalties(@PathVariable UUID id, @RequestParam(required = false) LocalDate asOf) {
        access.require(calendar.get(id).entityId(), Permission.VIEW);
        return penalties.forPeriod(id, asOf);
    }

    @GetMapping("/corrections/{id}/penalties")
    public PenaltyCalculator.Estimate correctionPenalties(@PathVariable UUID id, @RequestParam(required = false) LocalDate asOf,
                                                          @RequestParam(required = false) LocalDate disclosedOn) {
        CorrectionService.CorrectionCase c = corrections.get(id);
        access.require(c.entityId(), Permission.VIEW);
        return penalties.forCorrection(c, asOf, disclosedOn);
    }

    @PostMapping("/corrections/{id}/disclosure")
    public DisclosureService.View draftDisclosure(@PathVariable UUID id, @RequestBody DisclosureService.CreateInput in) {
        access.require(corrections.get(id).entityId(), Permission.EDIT_ADJUSTMENTS);
        return disclosures.create(id, in);
    }

    @GetMapping("/corrections/{id}/disclosure")
    public DisclosureService.View disclosureFor(@PathVariable UUID id) {
        CorrectionService.CorrectionCase c = corrections.get(id);
        access.require(c.entityId(), Permission.VIEW);
        return disclosures.forCorrection(id).map(d -> disclosures.view(d.id())).orElseThrow(() -> DomainException.notFound("Voluntary disclosure"));
    }

    @PostMapping("/disclosures/{id}/approve")
    public DisclosureService.View approve(@PathVariable UUID id) {
        UUID entity = disclosures.get(id).entityId();
        boolean partner = access.can(entity, Permission.PARTNER_SIGNOFF);
        if (!partner && !access.can(entity, Permission.REVIEW_SIGNOFF) && !access.can(entity, Permission.APPROVE_AND_LOCK)) {
            throw DomainException.forbidden("Only a reviewer, partner or approver can approve a disclosure");
        }
        return disclosures.approve(id, partner);
    }

    @PostMapping("/disclosures/{id}/pack")
    public DisclosureService.View disclosurePack(@PathVariable UUID id) {
        access.require(disclosures.get(id).entityId(), Permission.EDIT_ADJUSTMENTS);
        return disclosures.generatePack(id);
    }

    @PostMapping("/disclosures/{id}/submission")
    public DisclosureService.View submitDisclosure(@PathVariable UUID id, @RequestBody DisclosureService.SubmissionInput in) {
        access.require(disclosures.get(id).entityId(), Permission.RECORD_FILING);
        return disclosures.recordSubmission(id, in);
    }

    @PostMapping("/disclosures/{id}/settlement")
    public DisclosureService.View settle(@PathVariable UUID id, @RequestBody DisclosureService.SettlementInput in) {
        access.require(disclosures.get(id).entityId(), Permission.RECORD_FILING);
        return disclosures.recordSettlement(id, in);
    }

    @GetMapping("/periods/{id}/refund")
    public RefundService.Position refund(@PathVariable UUID id) {
        access.require(calendar.get(id).entityId(), Permission.VIEW);
        return refunds.position(id);
    }

    @PostMapping("/periods/{id}/refund")
    public RefundService.Position draftRefund(@PathVariable UUID id, @RequestBody(required = false) RefundService.CreateInput in) {
        access.require(calendar.get(id).entityId(), Permission.RECORD_FILING);
        return refunds.create(id, in);
    }

    @PostMapping("/refunds/{id}/pack")
    public RefundService.Position refundPack(@PathVariable UUID id) {
        access.require(refunds.get(id).entityId(), Permission.RECORD_FILING);
        return refunds.generatePack(id);
    }

    @PostMapping("/refunds/{id}/submission")
    public RefundService.Position submitRefund(@PathVariable UUID id, @RequestBody RefundService.SubmissionInput in) {
        access.require(refunds.get(id).entityId(), Permission.RECORD_FILING);
        return refunds.recordSubmission(id, in);
    }

    @PostMapping("/refunds/{id}/decision")
    public RefundService.Position refundDecision(@PathVariable UUID id, @RequestBody RefundService.DecisionInput in) {
        access.require(refunds.get(id).entityId(), Permission.RECORD_FILING);
        return refunds.recordDecision(id, in);
    }

    @PostMapping("/refunds/{id}/receipt")
    public RefundService.Position refundReceipt(@PathVariable UUID id, @RequestBody RefundService.ReceiptInput in) {
        access.require(refunds.get(id).entityId(), Permission.RECORD_FILING);
        return refunds.recordReceipt(id, in);
    }
}
