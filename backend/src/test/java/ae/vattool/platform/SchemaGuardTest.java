package ae.vattool.platform;

import static org.assertj.core.api.Assertions.assertThat;

import ae.vattool.support.IntegrationTest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guards future migrations. Any new table holding tenant data that forgets row-level security, or
 * any partition exposed directly to the application, fails here instead of leaking in production.
 */
class SchemaGuardTest extends IntegrationTest {

    // login_directory has a tenant column but no row-level security on purpose: it is what finds the
    // tenant in the first place, so the app role has no access to it at all (tested below).
    private static final List<String> GLOBAL_REFERENCE_TABLES = List.of("flyway_schema_history", "tenant");

    private List<String> query(String sql) throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection c = superuserConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    @Test
    void everyTableWithATenantColumnHasForcedRowLevelSecurity() throws Exception {
        List<String> unprotected = query("""
                SELECT c.relname FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'tenant_id' AND NOT a.attisdropped
                WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p') AND NOT c.relispartition
                  AND NOT (c.relrowsecurity AND c.relforcerowsecurity)
                  AND c.relname <> 'login_directory'
                """);

        assertThat(unprotected).isEmpty();
    }

    @Test
    void everyTableIsEitherTenantScopedOrAKnownGlobalTable() throws Exception {
        List<String> stray = query("""
                SELECT c.relname FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p') AND NOT c.relispartition
                  AND NOT EXISTS (SELECT 1 FROM pg_attribute a
                                  WHERE a.attrelid = c.oid AND a.attname = 'tenant_id' AND NOT a.attisdropped)
                """);

        assertThat(stray).allMatch(GLOBAL_REFERENCE_TABLES::contains);
    }

    @Test
    void theApplicationRoleHasNoDirectAccessToPartitions() throws Exception {
        // Row-level security on a partitioned parent does not protect direct reads of a partition.
        List<String> exposed = query("""
                SELECT c.relname FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relispartition
                  AND has_table_privilege('vat_app', c.oid, 'SELECT')
                """);

        assertThat(exposed).isEmpty();
    }

    @Test
    void theApplicationRoleIsUnprivileged() throws Exception {
        List<String> flags = query("SELECT rolsuper || ',' || rolbypassrls || ',' || rolcreaterole || ',' || rolcreatedb FROM pg_roles WHERE rolname = 'vat_app'");

        assertThat(flags).containsExactly("false,false,false,false");
    }

    @Test
    void theApplicationRoleCannotReadTheLoginDirectoryDirectly() throws Exception {
        List<String> priv = query("SELECT has_table_privilege('vat_app', 'login_directory', 'SELECT')::text");

        assertThat(priv).containsExactly("false");
    }
}
