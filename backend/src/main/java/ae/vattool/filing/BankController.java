package ae.vattool.filing;

import ae.vattool.banking.BankStatementService;
import ae.vattool.masterdata.PeriodCalendar;
import ae.vattool.masterdata.TaxPeriod;
import ae.vattool.platform.security.AccessControl;
import ae.vattool.platform.security.Permission;
import ae.vattool.platform.web.DomainException;
import java.io.IOException;
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
public class BankController {

    private final BankStatementService statements;
    private final BankPaymentMatcher matcher;
    private final FilingService filings;
    private final PeriodCalendar calendar;
    private final AccessControl access;

    public BankController(BankStatementService statements, BankPaymentMatcher matcher, FilingService filings, PeriodCalendar calendar, AccessControl access) {
        this.statements = statements;
        this.matcher = matcher;
        this.filings = filings;
        this.calendar = calendar;
        this.access = access;
    }

    public record ImportOutcome(BankStatementService.ImportResult result, int returnsSettled) {}

    public record ConfirmInput(UUID bankTxnId, String gibanReference) {}

    @PostMapping(path = "/entities/{id}/bank-statements", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportOutcome upload(@PathVariable UUID id, @RequestPart("file") MultipartFile file) throws IOException {
        requireLoad(id);
        BankStatementService.ImportResult result = statements.load(id, file.getOriginalFilename(), file.getBytes());
        return new ImportOutcome(result, result.alreadyProcessed() ? 0 : matcher.autoMatch(id));
    }

    @GetMapping("/entities/{id}/bank-statements")
    public List<BankStatementService.Loaded> list(@PathVariable UUID id) {
        access.require(id, Permission.VIEW);
        return statements.list(id);
    }

    @GetMapping("/entities/{id}/bank-statements/{statementId}/transactions")
    public List<BankStatementService.Txn> transactions(@PathVariable UUID id, @PathVariable UUID statementId, @RequestParam(defaultValue = "500") int limit) {
        access.require(id, Permission.VIEW);
        if (statements.list(id).stream().noneMatch(s -> s.id().equals(statementId))) {
            throw DomainException.notFound("Bank statement");
        }
        return statements.transactions(statementId, limit);
    }

    @GetMapping("/periods/{id}/bank-candidates")
    public List<BankPaymentMatcher.Candidate> candidates(@PathVariable UUID id) {
        access.require(calendar.get(id).entityId(), Permission.RECORD_FILING);
        return matcher.candidates(id);
    }

    @PostMapping("/periods/{id}/payment/from-bank")
    public FilingService.Position confirm(@PathVariable UUID id, @RequestBody ConfirmInput in) {
        access.require(calendar.get(id).entityId(), Permission.RECORD_FILING);
        matcher.confirm(id, in.bankTxnId(), in.gibanReference());
        TaxPeriod refreshed = calendar.get(id);
        return filings.position(refreshed.id());
    }

    private void requireLoad(UUID entity) {
        if (!access.can(entity, Permission.EDIT_DATA) && !access.can(entity, Permission.RECORD_FILING)) {
            throw DomainException.forbidden("Not permitted to load bank statements");
        }
    }
}
