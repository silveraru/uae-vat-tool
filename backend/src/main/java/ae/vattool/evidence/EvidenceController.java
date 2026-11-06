package ae.vattool.evidence;

import ae.vattool.platform.security.AccessControl;
import ae.vattool.platform.security.Permission;
import ae.vattool.platform.web.DomainException;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
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
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class EvidenceController {

    private final EvidenceService evidence;
    private final AccessControl access;

    public EvidenceController(EvidenceService evidence, AccessControl access) {
        this.evidence = evidence;
        this.access = access;
    }

    public record LinkInput(String objectType, String objectId) {}

    public record HoldInput(boolean on, String reason) {}

    @PostMapping(path = "/entities/{id}/evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public EvidenceService.Evidence upload(@PathVariable UUID id, @RequestPart("file") MultipartFile file,
                                           @RequestParam(required = false) UUID periodId,
                                           @RequestParam(required = false) String description,
                                           @RequestParam(required = false) String retentionClass) throws IOException {
        if (!access.can(id, Permission.UPLOAD_EVIDENCE) && !access.can(id, Permission.RECORD_FILING)) {
            throw DomainException.forbidden("Not permitted to add evidence");
        }
        return evidence.upload(id, periodId, file.getOriginalFilename(), file.getContentType(), file.getBytes(), description, retentionClass);
    }

    @GetMapping("/evidence/{id}")
    public EvidenceService.Evidence get(@PathVariable UUID id) {
        EvidenceService.Evidence e = evidence.get(id);
        requireRead(e.entityId());
        return e;
    }

    @GetMapping("/evidence/{id}/content")
    public ResponseEntity<byte[]> content(@PathVariable UUID id) {
        requireRead(evidence.get(id).entityId());
        EvidenceService.Download d = evidence.download(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(d.fileName()).build().toString())
                .contentType(MediaType.parseMediaType(d.contentType()))
                .body(d.content());
    }

    @PostMapping("/evidence/{id}/links")
    public void link(@PathVariable UUID id, @RequestBody LinkInput in) {
        access.require(evidence.get(id).entityId(), Permission.COMMENT);
        evidence.link(id, in.objectType(), in.objectId());
    }

    @GetMapping("/entities/{id}/evidence")
    public List<EvidenceService.Evidence> list(@PathVariable UUID id, @RequestParam(required = false) UUID periodId,
                                               @RequestParam(required = false) String objectType,
                                               @RequestParam(required = false) String objectId) {
        requireRead(id);
        if (objectType != null && objectId != null) {
            return evidence.forObject(objectType, objectId).stream().filter(e -> e.entityId().equals(id)).toList();
        }
        if (periodId == null) {
            throw DomainException.invalid("FILTER_REQUIRED", "Give a period or an object");
        }
        return evidence.forPeriod(periodId).stream().filter(e -> e.entityId().equals(id)).toList();
    }

    @PostMapping("/evidence/{id}/legal-hold")
    public EvidenceService.Evidence hold(@PathVariable UUID id, @RequestBody HoldInput in) {
        access.require(evidence.get(id).entityId(), Permission.ADMINISTER);
        return evidence.setLegalHold(id, in.on(), in.reason());
    }

    private void requireRead(UUID entityId) {
        if (!access.can(entityId, Permission.VIEW) && !access.can(entityId, Permission.VIEW_LOCKED_ONLY)) {
            throw DomainException.forbidden("No access to this evidence");
        }
    }
}
