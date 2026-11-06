package ae.vattool.platform.security;

import ae.vattool.platform.tenancy.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Turns a verified token into a tenant and user. The tenant comes from our own directory, keyed
 * by the token subject, so a token cannot choose its tenant. A valid token for someone we do not
 * know, or for a deactivated user, gets 403.
 */
@Component
public class CurrentUserFilter extends OncePerRequestFilter {

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public CurrentUserFilter(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    private record Login(UUID tenantId, UUID userId) {}

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth != null && auth.getPrincipal() instanceof Jwt jwt)) {
            chain.doFilter(request, response);
            return;
        }
        List<Login> logins = jdbc.sql("SELECT tenant_id, user_id FROM resolve_login(?)")
                .param(jwt.getSubject())
                .query(Login.class)
                .list();
        if (logins.isEmpty() || !isActive(logins.getFirst())) {
            response.sendError(HttpStatus.FORBIDDEN.value(), "User is not registered or is inactive");
            return;
        }
        Login login = logins.getFirst();
        RequestContext.set(new RequestContext.Actor(login.tenantId(), login.userId(), null));
        try {
            chain.doFilter(request, response);
        } finally {
            RequestContext.clear();
        }
    }

    private boolean isActive(Login login) {
        return RequestContext.runAs(new RequestContext.Actor(login.tenantId(), login.userId(), null),
                () -> Boolean.TRUE.equals(tx.execute(s -> jdbc.sql("SELECT active FROM app_user WHERE id = ?")
                        .param(login.userId())
                        .query(Boolean.class)
                        .optional()
                        .orElse(false))));
    }
}
