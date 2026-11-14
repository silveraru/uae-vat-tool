package ae.vattool.rules;

import ae.vattool.platform.security.AccessControl;
import ae.vattool.platform.security.Permission;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rule-packs")
public class RulePackController {

    private final RulePackStore store;
    private final AccessControl access;

    public RulePackController(RulePackStore store, AccessControl access) {
        this.store = store;
        this.access = access;
    }

    public record SignOff(@NotBlank String note) {}

    @GetMapping
    public List<RulePackStore.StoredPack> list() {
        access.requireTenantWide(Permission.MANAGE_RULES);
        return store.list();
    }

    @PostMapping(consumes = {"text/plain", "application/yaml", "application/x-yaml"})
    public RulePackStore.StoredPack upload(@RequestBody String yaml) {
        access.requireTenantWide(Permission.MANAGE_RULES);
        return store.upload(yaml);
    }

    @GetMapping("/bundled")
    public RulePackStore.Bundled bundled() {
        access.requireTenantWide(Permission.MANAGE_RULES);
        return store.bundled();
    }

    @PostMapping("/install-bundled")
    public RulePackStore.StoredPack installBundled() {
        access.requireTenantWide(Permission.MANAGE_RULES);
        return store.installBundled();
    }

    @PostMapping("/{id}/sign-off")
    public RulePackStore.StoredPack signOff(@PathVariable UUID id,
                                            @jakarta.validation.Valid @RequestBody SignOff body) {
        access.requireTenantWide(Permission.MANAGE_RULES);
        return store.signOff(id, body.note());
    }

    public record CheckInput(@NotBlank String yaml, UUID basedOn) {}

    public record EditInput(@NotBlank String yaml, @NotNull UUID basedOn, String note) {}

    @GetMapping("/{id}")
    public RulePackStore.PackView view(@PathVariable UUID id) {
        access.requireTenantWide(Permission.MANAGE_RULES);
        return store.view(id);
    }

    /** The pack's source with a new version filled in, to edit as a copy. */
    @GetMapping("/{id}/copy")
    public RulePackStore.Copy copy(@PathVariable UUID id) {
        access.requireTenantWide(Permission.MANAGE_RULES);
        return store.copy(id);
    }

    @PostMapping(path = "/check", consumes = MediaType.APPLICATION_JSON_VALUE)
    public RulePackStore.CheckResult check(@jakarta.validation.Valid @RequestBody CheckInput in) {
        access.requireTenantWide(Permission.MANAGE_RULES);
        return store.check(in.yaml(), in.basedOn());
    }

    @PostMapping(path = "/edits", consumes = MediaType.APPLICATION_JSON_VALUE)
    public RulePackStore.StoredPack edit(@jakarta.validation.Valid @RequestBody EditInput in) {
        access.requireTenantWide(Permission.MANAGE_RULES);
        return store.saveEdit(in.yaml(), in.basedOn(), in.note());
    }

    @PostMapping("/{id}/retire")
    public RulePackStore.StoredPack retire(@PathVariable UUID id, @jakarta.validation.Valid @RequestBody SignOff body) {
        access.requireTenantWide(Permission.MANAGE_RULES);
        return store.retire(id, body.note());
    }
}
