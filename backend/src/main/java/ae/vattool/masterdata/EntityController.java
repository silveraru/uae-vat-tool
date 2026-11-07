package ae.vattool.masterdata;

import ae.vattool.platform.security.AccessControl;
import ae.vattool.platform.security.Permission;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class EntityController {

    private final EntityService entities;
    private final MappingService mappings;
    private final PartyService parties;
    private final PeriodCalendar calendar;
    private final AccessControl access;

    public EntityController(EntityService entities, MappingService mappings, PartyService parties,
                            PeriodCalendar calendar, AccessControl access) {
        this.entities = entities;
        this.mappings = mappings;
        this.parties = parties;
        this.calendar = calendar;
        this.access = access;
    }

    public record SettingsInput(BigDecimal defaultRecoveryPct, BigDecimal partnerThresholdAed, Boolean requireReviewer,
                                LocalDate einvoiceGoLive, String ftaGiban, Boolean requireEsignature, Integer taxYearStartMonth) {}

    public record MappingInput(LocalDate effectiveFrom, String note, List<MappingService.EntryInput> entries) {}

    @PostMapping("/entities")
    @ResponseStatus(HttpStatus.CREATED)
    public LegalEntity create(@RequestBody EntityService.EntityInput in) {
        access.requireTenantWide(Permission.ADMINISTER);
        return entities.create(in);
    }

    @GetMapping("/entities")
    public List<LegalEntity> list() {
        return entities.listVisible();
    }

    @GetMapping("/entities/{id}")
    public LegalEntity get(@PathVariable UUID id) {
        requireVisible(id);
        return entities.get(id);
    }

    @PutMapping("/entities/{id}/settings")
    public LegalEntity settings(@PathVariable UUID id, @RequestBody SettingsInput in) {
        access.require(id, Permission.ADMINISTER);
        return entities.updateSettings(id, in.defaultRecoveryPct(), in.partnerThresholdAed(), in.requireReviewer(), in.einvoiceGoLive(),
                in.ftaGiban(), in.requireEsignature(), in.taxYearStartMonth());
    }

    @GetMapping("/entities/{id}/members")
    public List<LegalEntity> members(@PathVariable UUID id) {
        requireVisible(id);
        return entities.members(id);
    }

    @PostMapping("/entities/{id}/establishments")
    @ResponseStatus(HttpStatus.CREATED)
    public EntityService.Establishment addEstablishment(@PathVariable UUID id, @RequestBody EntityService.EstablishmentInput in) {
        access.require(id, Permission.ADMINISTER);
        return entities.addEstablishment(id, in);
    }

    @GetMapping("/entities/{id}/establishments")
    public List<EntityService.Establishment> establishments(@PathVariable UUID id) {
        requireVisible(id);
        return entities.establishments(id);
    }

    @GetMapping("/entities/{id}/periods")
    public List<TaxPeriod> periods(@PathVariable UUID id) {
        requireVisible(id);
        return calendar.list(id);
    }

    @PostMapping("/entities/{id}/periods/ensure")
    public List<TaxPeriod> ensurePeriods(@PathVariable UUID id, @RequestBody LocalDate through) {
        access.require(id, Permission.ADMINISTER);
        return calendar.ensureThrough(id, through);
    }

    @PostMapping("/entities/{id}/mappings")
    @ResponseStatus(HttpStatus.CREATED)
    public MappingService.VersionWithEntries publishMapping(@PathVariable UUID id, @RequestBody MappingInput in) {
        access.require(id, Permission.ADMINISTER);
        return mappings.publish(id, in.effectiveFrom(), in.note(), in.entries());
    }

    @GetMapping("/entities/{id}/mappings")
    public List<MappingService.Version> mappingHistory(@PathVariable UUID id) {
        requireVisible(id);
        return mappings.history(id);
    }

    @GetMapping("/entities/{id}/mappings/{versionId}")
    public MappingService.VersionWithEntries mapping(@PathVariable UUID id, @PathVariable UUID versionId) {
        requireVisible(id);
        // Row-level security only separates tenants, so the link to this entity is checked here.
        if (!mappings.belongsTo(versionId, id)) {
            throw ae.vattool.platform.web.DomainException.notFound("Mapping version");
        }
        return mappings.load(versionId);
    }

    @PostMapping("/entities/{id}/agents")
    @ResponseStatus(HttpStatus.CREATED)
    public PartyService.AgentLink linkAgent(@PathVariable UUID id, @RequestBody PartyService.AgentLink in) {
        access.require(id, Permission.ADMINISTER);
        return parties.linkAgent(id, in);
    }

    @GetMapping("/entities/{id}/agents")
    public List<PartyService.AgentLink> agents(@PathVariable UUID id) {
        requireVisible(id);
        return parties.agents(id);
    }

    @PostMapping("/entities/{id}/signatories")
    @ResponseStatus(HttpStatus.CREATED)
    public PartyService.Signatory addSignatory(@PathVariable UUID id, @RequestBody PartyService.Signatory in) {
        access.require(id, Permission.ADMINISTER);
        return parties.addSignatory(id, in);
    }

    @GetMapping("/entities/{id}/signatories")
    public List<PartyService.Signatory> signatories(@PathVariable UUID id) {
        requireVisible(id);
        return parties.signatories(id);
    }

    @PostMapping("/entities/{id}/grants")
    @ResponseStatus(HttpStatus.CREATED)
    public PartyService.Grant grant(@PathVariable UUID id, @RequestBody PartyService.GrantInput in) {
        access.require(id, Permission.ADMINISTER);
        return parties.grant(id, in);
    }

    @GetMapping("/entities/{id}/grants")
    public List<PartyService.Grant> grants(@PathVariable UUID id) {
        access.require(id, Permission.ADMINISTER);
        return parties.grants(id);
    }

    @DeleteMapping("/entities/{id}/grants/{grantId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID id, @PathVariable UUID grantId) {
        access.require(id, Permission.ADMINISTER);
        parties.revokeGrant(id, grantId);
    }

    @PostMapping("/counterparties")
    public PartyService.Counterparty saveCounterparty(@RequestBody PartyService.CounterpartyInput in) {
        access.requireTenantWide(Permission.ADMINISTER);
        return parties.upsertCounterparty(in);
    }

    @GetMapping("/counterparties")
    public List<PartyService.Counterparty> counterparties() {
        access.requireTenantWide(Permission.ADMINISTER);
        return parties.counterparties();
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public PartyService.User createUser(@RequestBody PartyService.UserInput in) {
        access.requireTenantWide(Permission.ADMINISTER);
        return parties.createUser(in);
    }

    @GetMapping("/users")
    public List<PartyService.User> users() {
        access.requireTenantWide(Permission.ADMINISTER);
        return parties.users();
    }

    private void requireVisible(UUID entityId) {
        if (!access.can(entityId, Permission.VIEW) && !access.can(entityId, Permission.VIEW_LOCKED_ONLY)) {
            throw ae.vattool.platform.web.DomainException.forbidden("No access to this entity");
        }
    }
}
