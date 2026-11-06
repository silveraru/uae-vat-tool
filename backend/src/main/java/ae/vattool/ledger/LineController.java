package ae.vattool.ledger;

import ae.vattool.masterdata.PeriodCalendar;
import ae.vattool.masterdata.TaxPeriod;
import ae.vattool.platform.audit.AuditLog;
import ae.vattool.platform.security.AccessControl;
import ae.vattool.platform.security.Permission;
import ae.vattool.platform.web.DomainException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class LineController {

    private static final Set<String> TREATMENTS = Set.of("STANDARD", "ZERO_RATED", "EXEMPT", "OUT_OF_SCOPE", "REVERSE_CHARGE",
            "IMPORT", "IMPORT_ADJUSTMENT", "DRC", "TOURIST_REFUND");
    private static final Set<String> REASON_CODES = Set.of("WRONG_TAX_CODE", "PLACE_OF_SUPPLY", "DRC_CONFIRMED", "BLOCKED_CONFIRMED",
            "BLOCKED_REMOVED", "RECOVERY_RATE", "CONFIRMED_AS_IS", "OTHER");
    private static final int MAX_LINES_PER_RECLASS = 5000;

    private final JdbcClient jdbc;
    private final Ledger ledger;
    private final AccessControl access;
    private final AuditLog audit;
    private final PeriodCalendar calendar;

    public LineController(JdbcClient jdbc, Ledger ledger, AccessControl access, AuditLog audit, PeriodCalendar calendar) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.access = access;
        this.audit = audit;
        this.calendar = calendar;
    }

    public record ReclassifyInput(List<UUID> lineIds, String treatment, String blockedCategory, BigDecimal recoveryPct,
                                  String reasonCode, String reason) {}

    public record ReclassifyResult(int changed, List<UUID> newLineIds) {}

    public record LinesPage(List<TaxLine> rows, long total, int page, int size) {}

    /** Line list for review, filtered by treatment, flag or free text. Values reach SQL as bind parameters. */
    @GetMapping("/periods/{id}/lines")
    @Transactional(readOnly = true)
    public LinesPage lines(@PathVariable UUID id, @RequestParam(required = false) String treatment,
                           @RequestParam(required = false) String flag, @RequestParam(required = false) String direction,
                           @RequestParam(required = false) String search, @RequestParam(defaultValue = "0") int page,
                           @RequestParam(defaultValue = "50") int size) {
        TaxPeriod p = period(id);
        access.requireReadPeriod(p.entityId(), p.isLocked());
        List<String> where = new ArrayList<>(List.of("period_id = :period", "superseded_at IS NULL"));
        Map<String, Object> params = new HashMap<>();
        params.put("period", id);
        if (treatment != null && !treatment.isBlank()) {
            where.add("treatment = :treatment");
            params.put("treatment", treatment);
        }
        if (flag != null && !flag.isBlank()) {
            where.add(":flag = ANY(review_flags)");
            params.put("flag", flag);
        }
        if (direction != null && !direction.isBlank()) {
            where.add("direction = :direction");
            params.put("direction", direction);
        }
        if (search != null && !search.isBlank()) {
            where.add("(description ILIKE :search OR tax_code ILIKE :search OR gl_account ILIKE :search OR counterparty_trn ILIKE :search)");
            params.put("search", "%" + search.replace("%", "\\%").replace("_", "\\_") + "%");
        }
        String w = String.join(" AND ", where);
        long total = jdbc.sql("SELECT COUNT(*) FROM tax_line WHERE " + w).params(params).query(Long.class).single();
        int safe = Math.min(Math.max(size, 1), 500);
        List<TaxLine> rows = jdbc.sql("SELECT " + TaxLine.COLUMNS + " FROM tax_line WHERE " + w + " ORDER BY tax_point, id LIMIT :limit OFFSET :offset")
                .params(params).param("limit", safe).param("offset", Math.max(page, 0) * safe)
                .query(TaxLineRowMapper.INSTANCE).list();
        return new LinesPage(rows, total, Math.max(page, 0), safe);
    }

    @GetMapping("/lines/{logicalId}/history")
    public List<TaxLine> history(@PathVariable UUID logicalId) {
        List<TaxLine> history = ledger.history(logicalId);
        if (history.isEmpty()) {
            throw DomainException.notFound("Tax line");
        }
        TaxPeriod p = period(history.getFirst().periodId());
        access.requireReadPeriod(p.entityId(), p.isLocked());
        return history;
    }

    /**
     * Bulk reclassification (TXC-07). The reason code and text are required, and one audit event
     * records every line changed. Locked periods are refused here: they change only through the
     * correction flow (RET-04).
     */
    @PostMapping("/periods/{id}/reclassify")
    @Transactional
    public ReclassifyResult reclassify(@PathVariable UUID id, @RequestBody ReclassifyInput in) {
        TaxPeriod p = jdbc.sql(TaxPeriod.SELECT + " WHERE id = ? FOR SHARE").param(id).query(TaxPeriod.class).optional()
                .orElseThrow(() -> DomainException.notFound("Tax period"));
        access.require(p.entityId(), Permission.EDIT_DATA);
        if (p.isLocked()) {
            throw DomainException.conflict("PERIOD_LOCKED", "This period is locked. Use a correction instead.");
        }
        if (in.lineIds() == null || in.lineIds().isEmpty()) {
            throw DomainException.invalid("NO_LINES", "Choose at least one line");
        }
        if (in.lineIds().size() > MAX_LINES_PER_RECLASS) {
            throw DomainException.invalid("TOO_MANY_LINES", "Reclassify at most " + MAX_LINES_PER_RECLASS + " lines at a time");
        }
        if (in.treatment() == null || !TREATMENTS.contains(in.treatment())) {
            throw DomainException.invalid("TREATMENT_INVALID", "Treatment must be one of " + TREATMENTS);
        }
        if (in.reasonCode() == null || !REASON_CODES.contains(in.reasonCode())) {
            throw DomainException.invalid("REASON_CODE_INVALID", "Reason code must be one of " + REASON_CODES);
        }
        if (in.reason() == null || in.reason().isBlank()) {
            throw DomainException.invalid("REASON_REQUIRED", "A reason is required");
        }
        if (in.recoveryPct() != null && (in.recoveryPct().signum() < 0 || in.recoveryPct().compareTo(BigDecimal.valueOf(100)) > 0)) {
            throw DomainException.invalid("RECOVERY_PCT_INVALID", "Recovery must be between 0 and 100");
        }
        List<UUID> created = new ArrayList<>();
        List<Map<String, Object>> changes = new ArrayList<>();
        for (UUID lineId : in.lineIds()) {
            TaxLine line = jdbc.sql("SELECT " + TaxLine.COLUMNS + " FROM tax_line WHERE id = ? AND superseded_at IS NULL")
                    .param(lineId).query(TaxLineRowMapper.INSTANCE).optional()
                    .orElseThrow(() -> DomainException.notFound("Current tax line " + lineId));
            if (!line.periodId().equals(id)) {
                throw DomainException.invalid("LINE_NOT_IN_PERIOD", "Line " + lineId + " is not in this period");
            }
            if (!"NORMAL".equals(line.lineKind())) {
                throw DomainException.invalid("LINE_IS_ADJUSTMENT", "Adjustment lines cannot be reclassified");
            }
            UUID newId = ledger.reclassify(line, in.treatment(), in.blockedCategory(), in.recoveryPct(), in.reasonCode(), in.reason());
            created.add(newId);
            Map<String, Object> change = new HashMap<>();
            change.put("from", lineId.toString());
            change.put("to", newId.toString());
            change.put("oldTreatment", line.treatment());
            changes.add(change);
        }
        audit.record("LINES_RECLASSIFIED", "TAX_PERIOD", id, p.entityId(), null,
                Map.of("treatment", in.treatment(), "reasonCode", in.reasonCode(), "lines", changes), in.reason());
        return new ReclassifyResult(created.size(), created);
    }

    private TaxPeriod period(UUID id) {
        return calendar.get(id);
    }
}
