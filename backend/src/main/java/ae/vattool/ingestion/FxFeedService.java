package ae.vattool.ingestion;

import ae.vattool.platform.audit.AuditLog;
import ae.vattool.platform.tenancy.RequestContext;
import ae.vattool.platform.web.DomainException;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pulls exchange rates from a configured source (ING-08) and releases rows waiting on them. No public
 * Central Bank API was found, so the source is a URL serving the FX template's columns: a licensed
 * feed, or a job someone runs. As with uploads, a rate already loaded is never changed, because lines
 * may have used it. Which source counts as the Central Bank rate is for the tax team to say.
 */
@Service
public class FxFeedService {
    private static final Logger log = LoggerFactory.getLogger(FxFeedService.class);

    public record Pulled(int rows, int added) {}

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final RowProcessor rows;
    private final AuditLog audit;
    private final String url;
    private final boolean workerEnabled;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public FxFeedService(JdbcClient jdbc, TransactionTemplate tx, RowProcessor rows, AuditLog audit,
                         @Value("${app.fx.feed.url:}") String url, @Value("${app.worker.enabled}") boolean workerEnabled) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.rows = rows;
        this.audit = audit;
        this.url = url;
        this.workerEnabled = workerEnabled;
    }

    @Scheduled(cron = "${app.worker.fx-cron:0 15 16 * * *}", zone = "Asia/Dubai")
    public void daily() {
        if (!workerEnabled || url.isBlank()) {
            return;
        }
        for (UUID tenant : jdbc.sql("SELECT list_tenant_ids()").query(UUID.class).list()) {
            RequestContext.asSystem(tenant, "fx-feed", () -> {
                try {
                    pull(url);
                } catch (RuntimeException e) {
                    log.warn("The FX feed could not be read: {}", e.getMessage());
                }
            });
        }
    }

    public Pulled pullConfigured() {
        if (url.isBlank()) {
            throw DomainException.conflict("FX_FEED_NOT_CONFIGURED", "No FX feed is configured. Rates can still be uploaded.");
        }
        return pull(url);
    }

    /** Reads the source and adds what is new, for the tenant in context. */
    public Pulled pull(String source) {
        byte[] body;
        try {
            HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(URI.create(source)).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw DomainException.conflict("FX_FEED_FAILED", "The FX feed answered HTTP " + response.statusCode());
            }
            body = response.body();
        } catch (IOException e) {
            throw DomainException.conflict("FX_FEED_FAILED", "The FX feed could not be reached: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw DomainException.conflict("FX_FEED_FAILED", "Interrupted while reading the FX feed");
        }
        TabularFile.Parsed parsed = TabularFile.read("feed.csv", body);
        Templates.checkHeaders(Templates.Kind.FX, Templates.CURRENT_VERSION, parsed.headers());
        int added = tx.execute(s -> {
            int count = 0;
            for (Map<String, String> r : parsed.rows()) {
                count += jdbc.sql("""
                        INSERT INTO fx_rate (tenant_id, currency, rate_date, aed_per_unit, source, loaded_by)
                        VALUES (:tenant, :currency, :date, :rate, :source, :by) ON CONFLICT (tenant_id, currency, rate_date) DO NOTHING
                        """)
                        .param("tenant", RequestContext.requireTenant()).param("currency", r.get("currency").trim().toUpperCase()).param("date", LocalDate.parse(r.get("rate_date").trim()))
                        .param("rate", new BigDecimal(r.get("aed_per_unit").trim())).param("source", r.get("source") == null || r.get("source").isBlank() ? "FEED" : r.get("source"))
                        .param("by", RequestContext.requireActor().actorId())
                        .update();
            }
            int total = parsed.rows().size();
            audit.record("FX_FEED_LOADED", "FX_FEED", "fx", null, null, Map.of("rows", total, "added", count), null);
            return count;
        });
        if (added > 0) {
            rows.reprocessHeld();
        }
        return new Pulled(parsed.rows().size(), added);
    }
}
