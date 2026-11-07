package ae.vattool.platform.documents;

import ae.vattool.platform.security.AccessControl;
import ae.vattool.platform.security.Permission;
import ae.vattool.platform.web.DomainException;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DocumentController {

    private final GeneratedDocuments documents;
    private final AccessControl access;

    public DocumentController(GeneratedDocuments documents, AccessControl access) {
        this.documents = documents;
        this.access = access;
    }

    @GetMapping("/api/documents/{id}")
    public GeneratedDocuments.Doc get(@PathVariable UUID id) {
        return readable(id);
    }

    @GetMapping("/api/documents/{id}/content")
    public ResponseEntity<byte[]> content(@PathVariable UUID id) {
        readable(id);
        GeneratedDocuments.Content c = documents.content(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(c.fileName()).build().toString())
                .contentType(MediaType.parseMediaType(c.contentType()))
                .body(c.bytes());
    }

    private GeneratedDocuments.Doc readable(UUID id) {
        GeneratedDocuments.Doc d = documents.get(id);
        if (!access.can(d.entityId(), Permission.VIEW)
                && !(access.can(d.entityId(), Permission.VIEW_LOCKED_ONLY) && documents.sharedWithAuditors(d))) {
            throw DomainException.forbidden("No access to this document");
        }
        return d;
    }
}
