package ae.vattool.classification;

import ae.vattool.masterdata.PeriodCalendar;
import ae.vattool.masterdata.TaxPeriod;
import ae.vattool.platform.security.AccessControl;
import ae.vattool.platform.security.Permission;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ClassificationController {

    private final SuggestionService suggestions;
    private final ExportEvidenceService exports;
    private final PeriodCalendar calendar;
    private final AccessControl access;

    public ClassificationController(SuggestionService suggestions, ExportEvidenceService exports, PeriodCalendar calendar, AccessControl access) {
        this.suggestions = suggestions;
        this.exports = exports;
        this.calendar = calendar;
        this.access = access;
    }

    public record AcceptInput(List<UUID> suggestionIds) {}

    @PostMapping("/periods/{id}/suggestions")
    public SuggestionService.Generated generate(@PathVariable UUID id) {
        access.require(calendar.get(id).entityId(), Permission.EDIT_DATA);
        return suggestions.generate(id);
    }

    @GetMapping("/periods/{id}/suggestions")
    public List<SuggestionService.Suggestion> list(@PathVariable UUID id, @RequestParam(required = false) String status) {
        access.require(calendar.get(id).entityId(), Permission.VIEW);
        return suggestions.list(id, status);
    }

    @PostMapping("/suggestions/accept")
    public SuggestionService.DecisionResult accept(@RequestBody AcceptInput in) {
        // Every suggestion is checked, because the list is the caller's and may span entities.
        for (UUID s : in.suggestionIds()) {
            access.require(suggestions.entityOf(s), Permission.EDIT_DATA);
        }
        return suggestions.accept(in.suggestionIds());
    }

    @PostMapping("/suggestions/{id}/reject")
    public void reject(@PathVariable UUID id) {
        access.require(suggestions.entityOf(id), Permission.EDIT_DATA);
        suggestions.reject(id);
    }

    @GetMapping("/periods/{id}/exports")
    public List<ExportEvidenceService.Export> exports(@PathVariable UUID id) {
        TaxPeriod p = calendar.get(id);
        access.requireReadPeriod(p.entityId(), p.isLocked());
        return exports.forPeriod(id);
    }

    @PostMapping("/documents/{id}/export-evidence")
    public void exportEvidence(@PathVariable UUID id, @RequestBody ExportEvidenceService.Input in) {
        access.require(exports.entityOf(id), Permission.EDIT_DATA);
        exports.record(id, in);
    }
}
