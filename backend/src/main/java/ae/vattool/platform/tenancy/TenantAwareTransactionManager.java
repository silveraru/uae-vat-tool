package ae.vattool.platform.tenancy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/**
 * Binds the tenant to each database transaction. The setting is transaction-local, so it cannot
 * leak to the next user of a pooled connection. Queries outside a transaction carry no tenant
 * and row-level security returns no rows, which fails closed.
 */
public class TenantAwareTransactionManager extends JdbcTransactionManager {

    public TenantAwareTransactionManager(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    protected void prepareTransactionalConnection(Connection con, TransactionDefinition definition)
            throws SQLException {
        super.prepareTransactionalConnection(con, definition);
        UUID tenant = RequestContext.tenantOrNull();
        if (tenant != null) {
            try (PreparedStatement ps = con.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                ps.setString(1, tenant.toString());
                ps.execute();
            }
        }
    }
}
