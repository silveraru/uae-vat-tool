package ae.vattool.platform.security;

import ae.vattool.platform.tenancy.RequestContext;
import ae.vattool.platform.web.DomainException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Role checks per legal entity. Row-level security separates tenants; this separates people and
 * entities inside a tenant. A grant with no entity applies across the whole tenant.
 *
 * <p>The class-level transaction matters: grants are read under row-level security, and calls
 * between these methods skip the proxy, so every public entry point must open the transaction.
 */
@Service
@Transactional(readOnly = true)
public class AccessControl {

    private final JdbcClient jdbc;

    public AccessControl(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Set<Role> rolesOn(UUID entityId) {
        RequestContext.Actor actor = RequestContext.requireActor();
        if (actor.isSystem()) {
            return EnumSet.noneOf(Role.class);
        }
        List<String> names = jdbc.sql("""
                SELECT role FROM entity_grant
                WHERE user_id = :user
                  AND (entity_id = :entity OR entity_id IS NULL)
                  AND valid_from <= now()
                  AND (valid_to IS NULL OR valid_to > now())
                """)
                .param("user", actor.userId())
                .param("entity", entityId)
                .query(String.class)
                .list();
        Set<Role> roles = EnumSet.noneOf(Role.class);
        names.forEach(n -> roles.add(Role.valueOf(n)));
        return roles;
    }

    public Set<Role> tenantWideRoles() {
        RequestContext.Actor actor = RequestContext.requireActor();
        if (actor.isSystem()) {
            return EnumSet.noneOf(Role.class);
        }
        List<String> names = jdbc.sql("""
                SELECT role FROM entity_grant
                WHERE user_id = ? AND entity_id IS NULL
                  AND valid_from <= now() AND (valid_to IS NULL OR valid_to > now())
                """)
                .param(actor.userId())
                .query(String.class)
                .list();
        Set<Role> roles = EnumSet.noneOf(Role.class);
        names.forEach(n -> roles.add(Role.valueOf(n)));
        return roles;
    }

    public boolean can(UUID entityId, Permission permission) {
        if (RequestContext.requireActor().isSystem()) {
            return true;
        }
        return rolesOn(entityId).stream().anyMatch(r -> r.grants(permission));
    }

    public void require(UUID entityId, Permission permission) {
        if (!can(entityId, permission)) {
            throw DomainException.forbidden("Missing permission " + permission + " on this entity");
        }
    }

    /** Tenant-level actions such as onboarding a new client, where no entity exists yet. */
    public void requireTenantWide(Permission permission) {
        if (RequestContext.requireActor().isSystem()) {
            return;
        }
        if (tenantWideRoles().stream().noneMatch(r -> r.grants(permission))) {
            throw DomainException.forbidden("Missing tenant-wide permission " + permission);
        }
    }

    /**
     * Reading a period's data. External auditors hold only VIEW_LOCKED_ONLY and may read a period
     * only once it has been locked (spec section 2: read locked periods, time-boxed).
     */
    public void requireReadPeriod(UUID entityId, boolean periodLocked) {
        if (can(entityId, Permission.VIEW)) {
            return;
        }
        if (periodLocked && can(entityId, Permission.VIEW_LOCKED_ONLY)) {
            return;
        }
        throw DomainException.forbidden("Not permitted to read this period");
    }
}
