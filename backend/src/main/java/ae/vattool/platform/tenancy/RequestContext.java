package ae.vattool.platform.tenancy;

import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Who is acting and for which tenant, for the current thread. Row-level security reads the
 * tenant from here when a transaction starts, so code must set it before opening one.
 */
public final class RequestContext {

    public record Actor(UUID tenantId, UUID userId, String systemName) {
        public boolean isSystem() {
            return userId == null;
        }

        public String actorId() {
            return isSystem() ? systemName : userId.toString();
        }
    }

    private static final ThreadLocal<Actor> CURRENT = new ThreadLocal<>();

    private RequestContext() {}

    public static void set(Actor actor) {
        CURRENT.set(actor);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static Actor current() {
        return CURRENT.get();
    }

    public static UUID tenantOrNull() {
        Actor a = CURRENT.get();
        return a == null ? null : a.tenantId();
    }

    public static UUID requireTenant() {
        UUID t = tenantOrNull();
        if (t == null) {
            throw new IllegalStateException("No tenant in context");
        }
        return t;
    }

    public static Actor requireActor() {
        Actor a = CURRENT.get();
        if (a == null) {
            throw new IllegalStateException("No actor in context");
        }
        return a;
    }

    public static UUID requireUserId() {
        UUID u = requireActor().userId();
        if (u == null) {
            throw new IllegalStateException("A user is required, but the actor is a system process");
        }
        return u;
    }

    /** Runs work as a named system process inside one tenant, for jobs and event consumers. */
    public static <T> T asSystem(UUID tenantId, String systemName, Supplier<T> work) {
        return runAs(new Actor(tenantId, null, systemName), work);
    }

    public static void asSystem(UUID tenantId, String systemName, Runnable work) {
        asSystem(tenantId, systemName, () -> {
            work.run();
            return null;
        });
    }

    public static <T> T runAs(Actor actor, Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // The tenant is bound when a transaction begins, so changing it mid-transaction
            // would leave the database on the old tenant while the code believes otherwise.
            throw new IllegalStateException("Cannot change tenant inside an open transaction");
        }
        Actor previous = CURRENT.get();
        CURRENT.set(actor);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
