package ae.vattool.rules;

import ae.vattool.platform.security.AccessControl;
import ae.vattool.platform.security.Permission;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
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
}
