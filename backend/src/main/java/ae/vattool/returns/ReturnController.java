package ae.vattool.returns;

import ae.vattool.masterdata.PeriodCalendar;
import ae.vattool.masterdata.TaxPeriod;
import ae.vattool.platform.security.AccessControl;
import ae.vattool.platform.security.Permission;
import ae.vattool.rules.RulePackDefinition;
import ae.vattool.rules.RulePackStore;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ReturnController {

    private final ReturnService returns;
    private final DrillDownService drillDown;
    private final AccessControl access;
    private final JdbcClient jdbc;
    private final PeriodCalendar calendar;

    public ReturnController(ReturnService returns, DrillDownService drillDown, AccessControl access, JdbcClient jdbc,
                            PeriodCalendar calendar) {
        this.returns = returns;
        this.drillDown = drillDown;
        this.access = access;
        this.jdbc = jdbc;
        this.calendar = calendar;
    }

    public record ReturnView(TaxPeriod period, ReturnVersionView version, List<BoxDefinition> form, boolean ruleDraft) {}

    public record BoxDefinition(String code, String section, Map<String, String> label, List<String> columns,
                                boolean computed, Map<String, String> entryGuide) {}

    public record GroupMember(UUID entityId, String legalName) {}

    @GetMapping("/periods/{id}")
    public TaxPeriod period(@PathVariable UUID id) {
        TaxPeriod p = load(id);
        access.requireReadPeriod(p.entityId(), p.isLocked());
        return p;
    }

    @GetMapping("/periods/{id}/return")
    public ReturnView current(@PathVariable UUID id) {
        TaxPeriod p = load(id);
        access.requireReadPeriod(p.entityId(), p.isLocked());
        return view(p);
    }

    @PostMapping("/periods/{id}/recompute")
    public ReturnView recompute(@PathVariable UUID id) {
        TaxPeriod p = load(id);
        access.require(p.entityId(), Permission.EDIT_DATA);
        returns.recompute(id, "MANUAL");
        return view(load(id));
    }

    @GetMapping("/periods/{id}/versions")
    public List<ReturnVersionView> versions(@PathVariable UUID id) {
        TaxPeriod p = load(id);
        access.requireReadPeriod(p.entityId(), p.isLocked());
        return returns.versions(id);
    }

    @GetMapping("/periods/{id}/boxes/{code}/lines")
    public DrillDownService.Page lines(@PathVariable UUID id, @PathVariable String code,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "50") int size) {
        TaxPeriod p = load(id);
        access.requireReadPeriod(p.entityId(), p.isLocked());
        return drillDown.lines(p, code, page, size);
    }

    @GetMapping("/lines/{lineId}/lineage")
    public DrillDownService.Lineage lineage(@PathVariable UUID lineId) {
        DrillDownService.Lineage lineage = drillDown.lineage(lineId);
        TaxPeriod p = load(lineage.line().periodId());
        access.requireReadPeriod(p.entityId(), p.isLocked());
        return lineage;
    }

    @GetMapping("/periods/{id}/group-members")
    @Transactional(readOnly = true)
    public List<GroupMember> members(@PathVariable UUID id) {
        TaxPeriod p = load(id);
        access.requireReadPeriod(p.entityId(), p.isLocked());
        return jdbc.sql("SELECT id AS entity_id, legal_name FROM legal_entity WHERE id = :e OR group_rep_id = :e ORDER BY legal_name")
                .param("e", p.entityId()).query(GroupMember.class).list();
    }

    private ReturnView view(TaxPeriod p) {
        RulePackStore.ActivePack pack = returns.packFor(p);
        ReturnVersionView version = returns.latest(p.id()).orElse(null);
        List<BoxDefinition> form = pack.definition().form().boxes().stream().map(ReturnController::define).toList();
        // A locked return is judged by the pack recorded on it, not whichever pack is current today.
        boolean draft = version != null ? !version.rulePackSignedOff() : !pack.meta().signedOff();
        return new ReturnView(p, version, form, draft);
    }

    private static BoxDefinition define(RulePackDefinition.Box b) {
        return new BoxDefinition(b.code(), b.section(), b.label(), b.columns(), b.isComputed(), b.entryGuide());
    }

    private TaxPeriod load(UUID id) {
        return calendar.get(id);
    }
}
