package ae.vattool.ingestion.einvoice;

import ae.vattool.platform.tenancy.RequestContext;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes e-invoice events from the ASP platform (ING-01). A message that cannot be applied goes
 * to the dead letter topic after retries, so one bad message cannot stop the feed. The tenant in
 * the event decides which tenant's data is touched, and row-level security enforces it from there.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class EinvoiceBridge {
    private static final Logger log = LoggerFactory.getLogger(EinvoiceBridge.class);
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final EinvoiceStore store;
    private final MeterRegistry metrics;

    public EinvoiceBridge(EinvoiceStore store, MeterRegistry metrics) {
        this.store = store;
        this.metrics = metrics;
    }

    @KafkaListener(topics = "${app.kafka.topic}")
    public void onMessage(ConsumerRecord<String, String> record) {
        EinvoiceEvent event;
        try {
            event = JSON.readValue(record.value(), EinvoiceEvent.class);
        } catch (JacksonException e) {
            metrics.counter("vat.einvoice.events", "result", "unreadable").increment();
            throw new IllegalArgumentException("Unreadable e-invoice event at offset " + record.offset(), e);
        }
        if (event.tenantId() == null || event.eventId() == null || event.invoiceUuid() == null
                || event.eventType() == null || event.occurredAt() == null) {
            metrics.counter("vat.einvoice.events", "result", "invalid").increment();
            throw new IllegalArgumentException("E-invoice event at offset " + record.offset() + " is missing required fields");
        }
        EinvoiceStore.Applied result = RequestContext.asSystem(event.tenantId(), "einvoice-bridge", () -> store.apply(event));
        metrics.counter("vat.einvoice.events", "result", result.name().toLowerCase()).increment();
        // A sender's clock running ahead must not turn the lag negative, which a timer rejects.
        java.time.Duration lag = java.time.Duration.between(event.occurredAt().toInstant(), java.time.Instant.now());
        metrics.timer("vat.einvoice.lag").record(lag.isNegative() ? java.time.Duration.ZERO : lag);
        if (result == EinvoiceStore.Applied.UNKNOWN_ENTITY) {
            log.warn("E-invoice event {} refers to a TRN not onboarded in tenant {}", event.eventId(), event.tenantId());
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
    static class ErrorHandling {
        @Bean
        CommonErrorHandler einvoiceErrorHandler(KafkaTemplate<Object, Object> template) {
            return new DefaultErrorHandler(new DeadLetterPublishingRecoverer(template), new FixedBackOff(500L, 2L));
        }
    }
}
