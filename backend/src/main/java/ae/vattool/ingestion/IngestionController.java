package ae.vattool.ingestion;

import ae.vattool.ingestion.einvoice.EinvoiceEvent;
import ae.vattool.ingestion.einvoice.EinvoiceStore;
import ae.vattool.platform.security.AccessControl;
import ae.vattool.platform.security.Permission;
import ae.vattool.platform.tenancy.RequestContext;
import ae.vattool.platform.web.DomainException;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class IngestionController {

    private final IngestionService ingestion;
    private final EinvoiceStore einvoices;
    private final AccessControl access;
    private final String backfillUrl;
    private final UblImportService ubl;
    private final FxFeedService fxFeed;

    public IngestionController(IngestionService ingestion, EinvoiceStore einvoices, AccessControl access, UblImportService ubl, FxFeedService fxFeed,
                               @Value("${app.asp.backfill-url}") String backfillUrl) {
        this.ubl = ubl;
        this.fxFeed = fxFeed;
        this.ingestion = ingestion;
        this.einvoices = einvoices;
        this.access = access;
        this.backfillUrl = backfillUrl;
    }

    public record BackfillResult(int received, int applied, int duplicates, int unknownEntity) {}

    @PostMapping(path = "/entities/{id}/ingest/{kind}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public IngestionService.LoadResult upload(@PathVariable UUID id, @PathVariable String kind,
                                              @RequestPart("file") MultipartFile file,
                                              @RequestParam(defaultValue = Templates.CURRENT_VERSION) String templateVersion) throws IOException {
        access.require(id, Permission.EDIT_DATA);
        return ingestion.loadFile(id, Templates.parseKind(kind), templateVersion, "UPLOAD", file.getOriginalFilename(), file.getBytes());
    }

    @PostMapping(path = "/entities/{id}/ingest/{kind}/rows", consumes = MediaType.APPLICATION_JSON_VALUE)
    public IngestionService.LoadResult rows(@PathVariable UUID id, @PathVariable String kind,
                                            @RequestBody List<Map<String, String>> rows,
                                            @RequestParam(defaultValue = Templates.CURRENT_VERSION) String templateVersion) {
        access.require(id, Permission.EDIT_DATA);
        return ingestion.loadRows(id, Templates.parseKind(kind), templateVersion, rows);
    }

    @PostMapping(path = "/entities/{id}/einvoices/ubl", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public IngestionService.LoadResult uploadUbl(@PathVariable UUID id, @RequestPart("file") MultipartFile file) throws IOException {
        access.require(id, Permission.EDIT_DATA);
        return ubl.load(id, "UPLOAD", file.getOriginalFilename(), file.getBytes());
    }

    @PostMapping(path = "/entities/{id}/einvoices/ubl", consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE})
    public IngestionService.LoadResult postUbl(@PathVariable UUID id, @RequestBody byte[] xml) {
        access.require(id, Permission.EDIT_DATA);
        return ubl.load(id, "API", "api-" + ae.vattool.platform.storage.VaultStorage.sha256(xml).substring(0, 12) + ".xml", xml);
    }

    @PostMapping("/entities/{id}/einvoices/corner5-status")
    public UblImportService.StatusResult corner5(@PathVariable UUID id, @RequestBody List<UblImportService.StatusInput> statuses) {
        access.require(id, Permission.EDIT_DATA);
        return ubl.applyStatuses(id, statuses);
    }

    @PostMapping("/fx/feed/pull")
    public FxFeedService.Pulled pullFx() {
        access.requireTenantWide(Permission.ADMINISTER);
        return fxFeed.pullConfigured();
    }

    @GetMapping("/entities/{id}/ingest/batches")
    public List<IngestionService.Batch> batches(@PathVariable UUID id) {
        access.require(id, Permission.VIEW);
        return ingestion.list(id);
    }

    @GetMapping("/entities/{id}/ingest/batches/{batchId}/rows")
    public List<IngestionService.RowView> rows(@PathVariable UUID id, @PathVariable UUID batchId,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(defaultValue = "500") int limit) {
        access.require(id, Permission.VIEW);
        if (!ingestion.get(batchId).entityId().equals(id)) {
            throw DomainException.notFound("Data load");
        }
        return ingestion.rows(batchId, status, limit);
    }

    @GetMapping("/ingest/templates/{kind}")
    public ResponseEntity<String> template(@PathVariable String kind,
                                           @RequestParam(defaultValue = Templates.CURRENT_VERSION) String version) {
        Templates.Kind k = Templates.parseKind(kind);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + k.name().toLowerCase() + "-" + version + ".csv\"")
                .contentType(new MediaType("text", "csv"))
                .body(Templates.headerLine(k, version) + "\n");
    }

    @GetMapping("/entities/{id}/einvoices")
    public List<EinvoiceStore.Einvoice> einvoices(@PathVariable UUID id, @RequestParam LocalDate from, @RequestParam LocalDate to) {
        access.require(id, Permission.VIEW);
        return einvoices.list(id, from, to);
    }

    /** Fetches events the platform already sent, for a new client or after an outage. */
    @PostMapping("/entities/{id}/einvoices/backfill")
    public BackfillResult backfill(@PathVariable UUID id, @RequestParam String trn,
                                   @RequestParam LocalDate from, @RequestParam LocalDate to) {
        access.require(id, Permission.EDIT_DATA);
        List<EinvoiceEvent> events = RestClient.create(backfillUrl).get()
                .uri(u -> u.path("/backfill")
                        .queryParam("tenantId", RequestContext.requireTenant())
                        .queryParam("entityTrn", trn).queryParam("from", from).queryParam("to", to).build())
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<>() {});
        int applied = 0;
        int duplicates = 0;
        int unknown = 0;
        for (EinvoiceEvent e : events == null ? List.<EinvoiceEvent>of() : events) {
            // Only this tenant's events are ever applied, whatever the upstream response contains.
            if (!RequestContext.requireTenant().equals(e.tenantId())) {
                continue;
            }
            switch (einvoices.apply(e)) {
                case APPLIED -> applied++;
                case DUPLICATE -> duplicates++;
                case UNKNOWN_ENTITY -> unknown++;
            }
        }
        return new BackfillResult(events == null ? 0 : events.size(), applied, duplicates, unknown);
    }
}
