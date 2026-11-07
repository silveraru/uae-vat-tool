package ae.vattool.ingestion.connectors;

import ae.vattool.ingestion.IngestionService;
import ae.vattool.platform.security.AccessControl;
import ae.vattool.platform.security.Permission;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class ConnectorController {

    private final ConnectorService connectors;
    private final AccessControl access;

    public ConnectorController(ConnectorService connectors, AccessControl access) {
        this.connectors = connectors;
        this.access = access;
    }

    public record SyncInput(String kind, LocalDate from, LocalDate to) {}

    @GetMapping("/connector-profiles")
    public List<ConnectorService.ProfileView> profiles() {
        return connectors.profiles();
    }

    @GetMapping("/entities/{id}/connectors")
    public List<ConnectorService.Connector> list(@PathVariable UUID id) {
        access.require(id, Permission.VIEW);
        return connectors.list(id);
    }

    @PostMapping("/entities/{id}/connectors")
    public ConnectorService.Connector create(@PathVariable UUID id, @RequestBody ConnectorService.ConnectorInput in) {
        access.require(id, Permission.ADMINISTER);
        return connectors.create(id, in);
    }

    @GetMapping("/connectors/{id}/runs")
    public List<ConnectorService.Run> runs(@PathVariable UUID id) {
        access.require(connectors.get(id).entityId(), Permission.VIEW);
        return connectors.runs(id);
    }

    @PostMapping("/connectors/{id}/sync")
    public ConnectorService.SyncResult sync(@PathVariable UUID id, @RequestBody SyncInput in) {
        access.require(connectors.get(id).entityId(), Permission.EDIT_DATA);
        return connectors.sync(id, in.kind(), in.from(), in.to());
    }

    @PostMapping(path = "/entities/{id}/ingest-export/{system}/{kind}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public IngestionService.LoadResult export(@PathVariable UUID id, @PathVariable String system, @PathVariable String kind, @RequestPart("file") MultipartFile file) throws IOException {
        access.require(id, Permission.EDIT_DATA);
        return connectors.loadExport(id, system, kind, file.getOriginalFilename(), file.getBytes());
    }
}
