package ae.vattool.portfolio;

import ae.vattool.platform.audit.AuditLog;
import ae.vattool.platform.security.AccessControl;
import ae.vattool.platform.security.Permission;
import ae.vattool.platform.web.DomainException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
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

/**
 * Retention of database records (NFR-06): 5 years from period end, 15 for real estate records, and a
 * legal hold stops the clock. The database role cannot delete tax records at all, so "enforced" means
 * they cannot go early. There is deliberately no purge: deleting tax data needs a policy decision, so
 * this reports what has aged out, and a person acts on it.
 */
@RestController
@RequestMapping("/api")
public class RetentionController {

    private static final int STANDARD_YEARS = 5;
    private static final int REAL_ESTATE_YEARS = 15;

    public record Row(UUID periodId, UUID entityId, String legalName, LocalDate periodEnd, String state, boolean realEstateRecords, LocalDate retainUntil, boolean expired, long activeHolds) {}

    public record RealEstateInput(boolean realEstate, String reason) {}

    private final JdbcClient jdbc;
    private final AccessControl access;
    private final AuditLog audit;

    public RetentionController(JdbcClient jdbc, AccessControl access, AuditLog audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
    }

    /** Oldest first. A held period can be past its date and still is not expired. */
    @GetMapping("/retention")
    @Transactional(readOnly = true)
    public List<Row> report(@RequestParam(required = false) LocalDate asOf, @RequestParam(required = false) String client, @RequestParam(defaultValue = "500") int limit) {
        access.requireTenantWide(Permission.ADMINISTER);
        LocalDate on = asOf != null ? asOf : LocalDate.now(ZoneId.of("Asia/Dubai"));
        return jdbc.sql("""
                SELECT p.id AS period_id, p.entity_id, e.legal_name, p.period_end, p.state, p.real_estate_records,
                       (p.period_end + make_interval(years => CASE WHEN p.real_estate_records THEN :longYears ELSE :years END))::date AS retain_until,
                       (p.period_end + make_interval(years => CASE WHEN p.real_estate_records THEN :longYears ELSE :years END))::date < :on
                         AND NOT EXISTS (SELECT 1 FROM period_legal_hold h WHERE h.period_id = p.id AND h.released_at IS NULL) AS expired,
                       (SELECT COUNT(*) FROM period_legal_hold h WHERE h.period_id = p.id AND h.released_at IS NULL) AS active_holds
                FROM tax_period p JOIN legal_entity e ON e.id = p.entity_id
                WHERE CAST(:client AS text) IS NULL OR position(lower(CAST(:client AS text)) IN lower(e.legal_name)) > 0
                ORDER BY p.period_end, e.legal_name LIMIT :limit
                """).param("longYears", REAL_ESTATE_YEARS).param("years", STANDARD_YEARS).param("on", on).param("client", client == null || client.isBlank() ? null : client.trim())
                .param("limit", Math.min(limit, 5000)).query(Row.class).list();
    }

    @PostMapping("/periods/{id}/real-estate-records")
    @Transactional
    public void markRealEstate(@PathVariable UUID id, @RequestBody RealEstateInput in) {
        UUID entity = jdbc.sql("SELECT entity_id FROM tax_period WHERE id = ?").param(id).query(UUID.class).optional().orElseThrow(() -> DomainException.notFound("Tax period"));
        access.require(entity, Permission.ADMINISTER);
        if (in.reason() == null || in.reason().isBlank()) {
            throw DomainException.invalid("REASON_REQUIRED", "A reason is required");
        }
        jdbc.sql("UPDATE tax_period SET real_estate_records = ? WHERE id = ?").params(in.realEstate(), id).update();
        audit.record("RETENTION_CLASS_CHANGED", "TAX_PERIOD", id, entity, null, Map.of("realEstateRecords", in.realEstate()), in.reason());
    }
}
