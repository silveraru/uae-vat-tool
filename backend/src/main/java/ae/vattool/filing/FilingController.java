package ae.vattool.filing;

import ae.vattool.masterdata.PeriodCalendar;
import ae.vattool.masterdata.TaxPeriod;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/periods/{id}")
public class FilingController {

    private final ReturnPackService packs;
    private final FilingService filings;
    private final PeriodCalendar calendar;
    private final AccessControl access;

    public FilingController(ReturnPackService packs, FilingService filings, PeriodCalendar calendar, AccessControl access) {
        this.packs = packs;
        this.filings = filings;
        this.calendar = calendar;
        this.access = access;
    }

    @PostMapping("/pack")
    public ReturnPackService.Pack generate(@PathVariable UUID id) {
        access.require(calendar.get(id).entityId(), Permission.RECORD_FILING);
        return packs.generate(id);
    }

    @GetMapping("/pack")
    public ReturnPackService.Pack latest(@PathVariable UUID id) {
        TaxPeriod p = calendar.get(id);
        access.requireReadPeriod(p.entityId(), p.isLocked());
        return packs.latest(id).orElseThrow(() -> DomainException.notFound("Return pack"));
    }

    @GetMapping("/pack/{format}")
    public ResponseEntity<byte[]> download(@PathVariable UUID id, @PathVariable String format) {
        TaxPeriod p = calendar.get(id);
        access.requireReadPeriod(p.entityId(), p.isLocked());
        ReturnPackService.Download d = packs.download(id, format);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(d.fileName()).build().toString())
                .contentType(MediaType.parseMediaType(d.contentType()))
                .body(d.content());
    }

    @GetMapping("/filing")
    public FilingService.Position position(@PathVariable UUID id) {
        TaxPeriod p = calendar.get(id);
        access.requireReadPeriod(p.entityId(), p.isLocked());
        return filings.position(id);
    }

    @PostMapping("/filing")
    public FilingService.Position file(@PathVariable UUID id, @RequestBody FilingService.FilingInput in) {
        access.require(calendar.get(id).entityId(), Permission.RECORD_FILING);
        return filings.recordFiling(id, in);
    }

    @PostMapping("/payment")
    public FilingService.Position pay(@PathVariable UUID id, @RequestBody FilingService.PaymentInput in) {
        access.require(calendar.get(id).entityId(), Permission.RECORD_FILING);
        return filings.recordPayment(id, in);
    }
}
