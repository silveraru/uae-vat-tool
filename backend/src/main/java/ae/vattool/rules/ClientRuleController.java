package ae.vattool.rules;

import ae.vattool.platform.security.AccessControl;
import ae.vattool.platform.security.Permission;
import ae.vattool.platform.web.DomainException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Client rules, and the firm's pack as it applies to one client. Anyone who can see the client, an external
 * auditor included, can read what rules apply to it. Only someone who manages rules can change or sign them off.
 */
@RestController
@RequestMapping("/api")
public class ClientRuleController {

    private final ClientRuleService service;
    private final RulePackStore packs;
    private final AccessControl access;

    public ClientRuleController(ClientRuleService service, RulePackStore packs, AccessControl access) {
        this.service = service;
        this.packs = packs;
        this.access = access;
    }

    public record PublishInput(LocalDate effectiveFrom, String note, ClientRules content) {}

    public record NoteInput(@NotBlank String note) {}

    @GetMapping("/entities/{id}/client-rules")
    public ClientRuleService.View view(@PathVariable UUID id) {
        requireVisible(id);
        return service.view(id);
    }

    /** The firm's pack in force today, read-only, for people who work on the client but do not manage rules. */
    @GetMapping("/entities/{id}/rule-pack")
    public RulePackStore.PackView firmPack(@PathVariable UUID id) {
        requireVisible(id);
        return packs.view(packs.forDate(LocalDate.now()).meta().id());
    }

    @PostMapping("/entities/{id}/client-rules")
    public ClientRuleVersion publish(@PathVariable UUID id, @RequestBody PublishInput in) {
        access.require(id, Permission.MANAGE_RULES);
        return service.publish(id, in.effectiveFrom(), in.note(), in.content());
    }

    @PostMapping("/entities/{id}/client-rules/{versionId}/sign-off")
    public ClientRuleVersion signOff(@PathVariable UUID id, @PathVariable UUID versionId, @Valid @RequestBody NoteInput in) {
        access.require(id, Permission.MANAGE_RULES);
        return service.signOff(id, versionId, in.note());
    }

    @GetMapping("/client-rules")
    public List<ClientRuleService.Summary> summaries() {
        access.requireTenantWide(Permission.MANAGE_RULES);
        return service.summaries();
    }

    /** As for the client's other settings: an external auditor may read which rules applied. */
    private void requireVisible(UUID entityId) {
        if (!access.can(entityId, Permission.VIEW) && !access.can(entityId, Permission.VIEW_LOCKED_ONLY)) {
            throw DomainException.forbidden("No access to this entity");
        }
    }
}
