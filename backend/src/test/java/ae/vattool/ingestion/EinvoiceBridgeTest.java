package ae.vattool.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ae.vattool.simulator.EventFactory;
import ae.vattool.support.IntegrationTest;
import ae.vattool.support.TestClient;
import ae.vattool.support.TestWorld.TestUser;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

/** Runs the bridge against a real Kafka broker with events from the simulator. */
class EinvoiceBridgeTest extends IntegrationTest {

    private static final String TOPIC = "einvoice.events.v1";
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    static {
        KAFKA.start();
    }

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry r) {
        r.add("app.kafka.enabled", () -> "true");
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired JdbcClient jdbc;

    UUID tenant;
    TestUser admin;
    TestClient client;
    UUID entity;
    String trn;

    @BeforeEach
    void setUp() throws Exception {
        tenant = world.newTenant("Bridge firm " + UUID.randomUUID());
        admin = world.newUser(tenant, "FIRM", "FIRM_ADMIN");
        TestUser preparer = world.newUser(tenant, "CLIENT", "PREPARER");
        client = new TestClient(mvc, admin, preparer);
        entity = client.simpleEntity();
        trn = client.get(admin, "/api/entities/" + entity).get("trn").asString();
    }

    private static List<EventFactory.InvoiceSpec> invoices(String trn, int n) {
        List<EventFactory.InvoiceSpec> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new EventFactory.InvoiceSpec("ISSUED", "INVOICE", "BR-" + i, "2027-01-15", trn, "100000000000901",
                    new BigDecimal("100.00"), new BigDecimal("5.00"), null, UUID.randomUUID()));
        }
        return out;
    }

    private static void publish(List<Map<String, Object>> events) {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(p)) {
            for (Map<String, Object> e : events) {
                producer.send(new ProducerRecord<>(TOPIC, String.valueOf(e.get("invoiceUuid")), MAPPER.writeValueAsString(e)));
            }
            producer.flush();
        }
    }

    private long reportedCount() {
        return world.queryInTenant(tenant, () -> jdbc.sql("SELECT COUNT(*) FROM einvoice WHERE filer_entity_id = ? AND corner5_status = 'REPORTED'")
                .param(entity).query(Long.class).single());
    }

    @Test
    void eventsFlowFromKafkaIntoSetAEvenWithDuplicatesAndOutOfOrderDelivery() {
        List<Map<String, Object>> events = new EventFactory(tenant, trn, 1)
                .events(invoices(trn, 10), new EventFactory.Faults(0, 0, 3, 0, 0, 0, 3));

        publish(events);

        await().atMost(Duration.ofSeconds(60)).until(() -> reportedCount() == 10);
        long stored = world.queryInTenant(tenant, () -> jdbc.sql("SELECT COUNT(*) FROM einvoice WHERE filer_entity_id = ? AND has_invoice_data").param(entity).query(Long.class).single());
        assertThat(stored).isEqualTo(10);
    }

    @Test
    void anUnreadableMessageGoesToTheDeadLetterTopicAndDoesNotStopTheFeed() {
        String poison = "this is not json " + UUID.randomUUID();
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(p)) {
            producer.send(new ProducerRecord<>(TOPIC, "poison", poison));
            producer.flush();
        }

        // Good events sent after the bad one still arrive.
        publish(new EventFactory(tenant, trn, 2).events(invoices(trn, 2), EventFactory.Faults.NONE));
        await().atMost(Duration.ofSeconds(60)).until(() -> reportedCount() == 2);

        Properties c = new Properties();
        c.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        c.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-check-" + UUID.randomUUID());
        c.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        c.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        c.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(c)) {
            consumer.subscribe(List.of(TOPIC + "-dlt"));
            List<String> seen = new ArrayList<>();
            await().atMost(Duration.ofSeconds(60)).until(() -> {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    seen.add(r.value());
                }
                return seen.contains(poison);
            });
        }
    }

    @Test
    void anEventForAnotherTenantsTrnTouchesOnlyThatTenant() {
        UUID other = world.newTenant("Other " + UUID.randomUUID());
        // Claims to be from the other tenant but uses this entity's TRN, which is not onboarded there.
        publish(new EventFactory(other, trn, 3).events(invoices(trn, 1), EventFactory.Faults.NONE));
        publish(new EventFactory(tenant, trn, 4).events(invoices(trn, 1), EventFactory.Faults.NONE));

        await().atMost(Duration.ofSeconds(60)).until(() -> reportedCount() == 1);

        long inOther = world.queryInTenant(other, () -> jdbc.sql("SELECT COUNT(*) FROM einvoice").query(Long.class).single());
        assertThat(inOther).isZero();
    }

    @Test
    void everySimulatorEventConformsToThePublishedContract() throws Exception {
        JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(Files.newInputStream(Path.of("../contracts/einvoice-events/einvoice-event.schema.json")));
        com.fasterxml.jackson.databind.ObjectMapper jackson2 = new com.fasterxml.jackson.databind.ObjectMapper();
        List<EventFactory.InvoiceSpec> specs = new ArrayList<>(invoices("100000000000001", 6));
        specs.add(new EventFactory.InvoiceSpec("ISSUED", "CREDIT_NOTE", "CN-1", "2027-01-16", "100000000000001", "100000000000901",
                new BigDecimal("-10.00"), new BigDecimal("-0.50"), "BR-0", UUID.randomUUID()));
        specs.add(new EventFactory.InvoiceSpec("ISSUED", "INVOICE", "EXP-1", "2027-01-16", "100000000000001", null,
                new BigDecimal("10.00"), BigDecimal.ZERO, null, UUID.randomUUID()));

        List<Map<String, Object>> events = new EventFactory(UUID.randomUUID(), "100000000000001", 5)
                .events(specs, new EventFactory.Faults(1, 1, 1, 1, 1, 1, 1));

        assertThat(events).isNotEmpty();
        assertThat(events.stream().map(e -> e.get("eventType")).distinct().toList())
                .contains("einvoice.issued", "einvoice.rejected", "einvoice.corner5_status");
        for (Map<String, Object> e : events) {
            assertThat(schema.validate(jackson2.valueToTree(e))).as("event %s", e).isEmpty();
        }
    }

    @Test
    void theContractRefusesAnInvalidEvent() throws Exception {
        JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(Files.newInputStream(Path.of("../contracts/einvoice-events/einvoice-event.schema.json")));
        com.fasterxml.jackson.databind.ObjectMapper jackson2 = new com.fasterxml.jackson.databind.ObjectMapper();

        // An issued event with no invoice body.
        assertThat(schema.validate(jackson2.valueToTree(Map.of(
                "specVersion", "1.0", "eventId", UUID.randomUUID().toString(), "eventType", "einvoice.issued",
                "occurredAt", "2027-01-31T08:00:00Z", "tenantId", UUID.randomUUID().toString(),
                "entityTrn", "100000000000001", "invoiceUuid", UUID.randomUUID().toString())))).isNotEmpty();
    }
}
