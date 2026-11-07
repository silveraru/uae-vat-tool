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
    private final ApportionmentService apportionment;
    private final BadDebtService badDebts;
    private final CapitalAssetService assets;

    public AdjustmentController(AdjustmentService adjustments, CorrectionService corrections, PeriodCalendar calendar, AccessControl access,
                                ApportionmentService apportionment, BadDebtService badDebts, CapitalAssetService assets) {
        this.apportionment = apportionment;
        this.badDebts = badDebts;
        this.assets = assets;
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

    public record ApportionInput(java.math.BigDecimal excludedSuppliesAed, boolean post) {}

    public record UseInput(java.math.BigDecimal taxableUsePct) {}

    public record DisposalInput(java.time.LocalDate disposedOn, boolean taxable) {}

    @PostMapping("/periods/{id}/apportionment")
    public ApportionmentService.Result apportion(@PathVariable UUID id, @RequestBody ApportionInput in) {
        TaxPeriod p = calendar.get(id);
        access.require(p.entityId(), Permission.EDIT_ADJUSTMENTS);
        return apportionment.runPeriod(p, in.excludedSuppliesAed(), in.post());
    }

    @PostMapping("/periods/{id}/apportionment/annual")
    public ApportionmentService.Result apportionAnnual(@PathVariable UUID id, @RequestBody ApportionInput in) {
        TaxPeriod p = calendar.get(id);
        access.require(p.entityId(), Permission.EDIT_ADJUSTMENTS);
        return apportionment.runAnnual(p, in.excludedSuppliesAed(), in.post());
    }

    @GetMapping("/periods/{id}/apportionment")
    public List<ApportionmentService.Run> apportionmentRuns(@PathVariable UUID id) {
        TaxPeriod p = calendar.get(id);
        access.requireReadPeriod(p.entityId(), p.isLocked());
        return apportionment.runs(id);
    }

    @GetMapping("/periods/{id}/bad-debt")
    public List<BadDebtService.Claim> badDebtClaims(@PathVariable UUID id) {
        TaxPeriod p = calendar.get(id);
        access.requireReadPeriod(p.entityId(), p.isLocked());
        return badDebts.list(id);
    }

    @GetMapping("/periods/{id}/bad-debt/candidates")
    public List<BadDebtService.Candidate> badDebtCandidates(@PathVariable UUID id) {
        TaxPeriod p = calendar.get(id);
        access.require(p.entityId(), Permission.EDIT_ADJUSTMENTS);
        return badDebts.candidates(p);
    }

    @PostMapping("/periods/{id}/bad-debt")
    public BadDebtService.Claim claimBadDebt(@PathVariable UUID id, @RequestBody BadDebtService.ReliefInput in) {
        TaxPeriod p = calendar.get(id);
        access.require(p.entityId(), Permission.EDIT_ADJUSTMENTS);
        return badDebts.claimRelief(p, in);
    }

    @GetMapping("/periods/{id}/bad-debt/recoverable")
    public List<BadDebtService.Relieved> badDebtRecoverable(@PathVariable UUID id) {
        TaxPeriod p = calendar.get(id);
        access.require(p.entityId(), Permission.EDIT_ADJUSTMENTS);
        return badDebts.recoverable(p.entityId());
    }

    @PostMapping("/periods/{id}/bad-debt/recovery")
    public BadDebtService.Claim recoverBadDebt(@PathVariable UUID id, @RequestBody BadDebtService.RecoveryInput in) {
        TaxPeriod p = calendar.get(id);
        access.require(p.entityId(), Permission.EDIT_ADJUSTMENTS);
        return badDebts.recordRecovery(p, in);
    }

    @GetMapping("/entities/{id}/capital-assets")
    public List<CapitalAssetService.AssetView> capitalAssets(@PathVariable UUID id) {
        access.require(id, Permission.VIEW);
        return assets.list(id);
    }

    @PostMapping("/entities/{id}/capital-assets")
    public CapitalAssetService.AssetView registerAsset(@PathVariable UUID id, @RequestBody CapitalAssetService.AssetInput in) {
        access.require(id, Permission.EDIT_ADJUSTMENTS);
        return assets.register(id, in);
    }

    @GetMapping("/periods/{id}/capital-assets/due")
    public List<CapitalAssetService.Due> assetsDue(@PathVariable UUID id) {
        TaxPeriod p = calendar.get(id);
        access.requireReadPeriod(p.entityId(), p.isLocked());
        return assets.dueIn(p);
    }

    @PostMapping("/periods/{id}/capital-assets/{assetId}/adjust")
    public CapitalAssetService.AssetView adjustAsset(@PathVariable UUID id, @PathVariable UUID assetId, @RequestBody UseInput in) {
        TaxPeriod p = calendar.get(id);
        access.require(p.entityId(), Permission.EDIT_ADJUSTMENTS);
        return assets.adjust(p, assetId, in.taxableUsePct());
    }

    @PostMapping("/periods/{id}/capital-assets/{assetId}/dispose")
    public CapitalAssetService.AssetView disposeAsset(@PathVariable UUID id, @PathVariable UUID assetId, @RequestBody DisposalInput in) {
        TaxPeriod p = calendar.get(id);
        access.require(p.entityId(), Permission.EDIT_ADJUSTMENTS);
        return assets.dispose(p, assetId, in.disposedOn(), in.taxable());
    }

    private void requireAdjust(UUID entity) {
        if (!access.can(entity, Permission.EDIT_ADJUSTMENTS) && !access.can(entity, Permission.EDIT_DATA)) {
            throw DomainException.forbidden("Not permitted to make adjustments");
        }
    }
}
