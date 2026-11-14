package ae.vattool.demo;

import ae.vattool.platform.seed.DevSeed;
import ae.vattool.platform.tenancy.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Builds the demo firm's year on first start in the dev profile: five clients, twelve months each,
 * every return taken through review, approval, filing and payment, and last month's returns left at
 * different stages. It goes through the real services as named people, so it takes a few minutes.
 * Local development only. scripts/reset-demo.sh starts it again from an empty database.
 */
@Component
@ConditionalOnProperty(name = "app.seed.demo-data", havingValue = "true")
@Order(10)
public class DemoData implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoData.class);

    private final Kit kit;
    private final DemoServer server;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    DemoData(Kit kit, DemoServer server, JdbcClient jdbc, TransactionTemplate tx) {
        this.kit = kit;
        this.server = server;
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public void run(ApplicationArguments args) {
        Boolean built = RequestContext.asSystem(DevSeed.DEMO_TENANT, "demo-data", () -> tx.execute(s ->
                jdbc.sql("SELECT EXISTS (SELECT 1 FROM legal_entity WHERE trn = ?)").param(AlNoor.TRN).query(Boolean.class).single()));
        if (Boolean.TRUE.equals(built)) {
            return;
        }
        long started = System.currentTimeMillis();
        log.info("Building the demo year. This takes a few minutes on first start.");
        new Story(kit, Timeline.now(), server).run();
        log.info("Demo year ready in {} s", (System.currentTimeMillis() - started) / 1000);
    }
}
