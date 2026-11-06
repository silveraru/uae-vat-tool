package ae.vattool.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base for tests against real PostgreSQL and object storage. Containers start once per JVM and
 * are shared. The application connects as vat_app exactly as in production; only tests that
 * deliberately simulate an attacker use the superuser connection.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestSecurityConfig.class, TestWorld.class})
public abstract class IntegrationTest {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16")
            .withDatabaseName("uaevat")
            .withInitScript("init-roles.sql");

    static final String MINIO_USER = "testminio";
    static final String MINIO_PASSWORD = "testminio-secret";

    static final GenericContainer<?> MINIO = new GenericContainer<>(
            "cgr.dev/chainguard/minio@sha256:cee9a8e35885e93f14b3cb32960e5017aff74e2adfb52ba87c65003b0163886b")
            .withCommand("server", "/tmp/data")
            .withEnv("MINIO_ROOT_USER", MINIO_USER)
            .withEnv("MINIO_ROOT_PASSWORD", MINIO_PASSWORD)
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));

    static {
        POSTGRES.start();
        MINIO.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", () -> "vat_app");
        r.add("spring.datasource.password", () -> "vat_app");
        r.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user", () -> "vat_owner");
        r.add("spring.flyway.password", () -> "vat_owner");
        r.add("app.storage.endpoint", () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        r.add("app.storage.access-key", () -> MINIO_USER);
        r.add("app.storage.secret-key", () -> MINIO_PASSWORD);
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected TestWorld world;

    protected static final JsonMapper JSON = JsonMapper.builder().build();

    protected ResultActions postJson(TestWorld.TestUser user, String path, Object body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(path).with(user.auth())
                .contentType("application/json")
                .content(body instanceof String s ? s : JSON.writeValueAsString(body)));
    }

    protected ResultActions putJson(TestWorld.TestUser user, String path, Object body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.put(path).with(user.auth())
                .contentType("application/json")
                .content(JSON.writeValueAsString(body)));
    }

    protected ResultActions getAs(TestWorld.TestUser user, String path) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(path).with(user.auth()));
    }

    protected static JsonNode body(ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString());
    }

    /** A superuser connection, used only to simulate someone tampering with the database directly. */
    protected static Connection superuserConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
