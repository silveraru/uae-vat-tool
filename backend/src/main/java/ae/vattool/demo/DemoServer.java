package ae.vattool.demo;

import ae.vattool.ingestion.Templates;
import ae.vattool.ingestion.einvoice.EinvoiceEvent;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stands in, on this machine only, for the outside systems the demo's settings point at: a billing
 * system receiving webhooks, the clients' ERPs, a Central Bank rate feed and the e-invoicing platform's
 * backfill. Without it those sections would only ever show failures in development.
 */
@Component
@ConditionalOnProperty(name = "app.seed.demo-data", havingValue = "true")
class DemoServer {

    private static final Logger log = LoggerFactory.getLogger(DemoServer.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static volatile int port = 18097;

    private final Path backlogFile;
    private final List<EinvoiceEvent> backlog = new CopyOnWriteArrayList<>();
    private HttpServer server;

    DemoServer(@Value("${app.demo.server.port:18097}") int configuredPort, @Value("${app.demo.dir:../.data/demo}") String dir) {
        port = configuredPort;
        this.backlogFile = Path.of(dir).resolve("asp-backlog.json");
        readBacklog();
        if (configuredPort > 0) {
            start();
        }
    }

    static int port() {
        return port;
    }

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    private void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        } catch (IOException e) {
            log.warn("The demo stub could not listen on port {} ({}). Webhooks, ERP pulls and the FX feed will show as failing.", port, e.getMessage());
            return;
        }
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/hooks/", this::hook);
        server.createContext("/erp/", this::erp);
        server.createContext("/fx/rates.csv", this::rates);
        server.createContext("/asp/backfill", this::backfill);
        server.start();
        log.info("Demo stub listening on http://localhost:{}", port);
    }

    @PreDestroy
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** The billing system takes every event. The client portal is down, so its deliveries fail and retry. */
    private void hook(HttpExchange x) throws IOException {
        x.getRequestBody().readAllBytes();
        boolean down = x.getRequestURI().getPath().endsWith("/client-portal");
        reply(x, down ? 503 : 204, down ? "Service unavailable" : "", "text/plain");
    }

    /** An ERP with nothing new since the last pull, reached with the right token. A stale token is refused. */
    private void erp(HttpExchange x) throws IOException {
        String auth = x.getRequestHeaders().getFirst("Authorization");
        if (!"Bearer demo-erp-token".equals(auth)) {
            reply(x, 401, "{\"error\":\"invalid_token\",\"error_description\":\"The access token expired\"}", "application/json");
            return;
        }
        String path = x.getRequestURI().getPath();
        String body = path.contains("/books/v3/") ? "{\"invoices\":[]}"
                : path.contains("/sap/opu/odata/") ? "{\"d\":{\"results\":[]}}"
                : path.contains("/fscmRestApi/") ? "{\"items\":[]}"
                : "{\"value\":[]}";
        reply(x, 200, body, "application/json");
    }

    private void rates(HttpExchange x) throws IOException {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Dubai"));
        byte[] csv = ae.vattool.demo.Files.csv(Templates.Kind.FX, Story.fxRows(Set.of("USD", "EUR", "GBP", "INR", "CNY", "SAR"), today.minusDays(460), today.minusDays(1), false));
        reply(x, 200, new String(csv, StandardCharsets.UTF_8), "text/csv");
    }

    /** What the platform holds for one taxpayer and date range, as its REST backfill would answer. */
    private void backfill(HttpExchange x) throws IOException {
        Map<String, String> q = query(x.getRequestURI().getRawQuery());
        String trn = q.get("entityTrn");
        LocalDate from = LocalDate.parse(q.getOrDefault("from", "2000-01-01"));
        LocalDate to = LocalDate.parse(q.getOrDefault("to", "2999-12-31"));
        Set<java.util.UUID> invoices = new java.util.HashSet<>();
        List<EinvoiceEvent> out = new ArrayList<>();
        for (EinvoiceEvent e : backlog) {
            if (e.invoice() != null && e.entityTrn().equals(trn) && !e.invoice().issueDate().isBefore(from) && !e.invoice().issueDate().isAfter(to)) {
                invoices.add(e.invoiceUuid());
            }
        }
        for (EinvoiceEvent e : backlog) {
            if (invoices.contains(e.invoiceUuid())) {
                out.add(e);
            }
        }
        reply(x, 200, JSON.writeValueAsString(out), "application/json");
    }

    /** Events the platform did not deliver, kept on disk so a restart does not lose them. */
    void backlog(List<EinvoiceEvent> events) {
        backlog.clear();
        backlog.addAll(events);
        try {
            Files.createDirectories(backlogFile.getParent());
            Files.writeString(backlogFile, JSON.writeValueAsString(events));
        } catch (IOException e) {
            log.warn("Could not keep the demo platform backlog in {}: {}", backlogFile, e.getMessage());
        }
    }

    private void readBacklog() {
        if (!Files.exists(backlogFile)) {
            return;
        }
        try {
            backlog.addAll(JSON.readValue(Files.readString(backlogFile), new TypeReference<List<EinvoiceEvent>>() {}));
        } catch (IOException | RuntimeException e) {
            log.warn("Could not read the demo platform backlog in {}: {}", backlogFile, e.getMessage());
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static void reply(HttpExchange x, int status, String body, String type) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", type);
        if (bytes.length == 0) {
            x.sendResponseHeaders(status, -1);
        } else {
            x.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = x.getResponseBody()) {
                out.write(bytes);
            }
        }
        x.close();
    }
}
