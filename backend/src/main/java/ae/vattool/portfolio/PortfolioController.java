package ae.vattool.portfolio;

import ae.vattool.platform.tenancy.RequestContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The firm's view across all client entities (PRC-01) and the deadline dashboard (WF-03). Only
 * entities the caller holds a live grant on appear, and row-level security separates tenants.
 */
@RestController
@RequestMapping("/api")
public class PortfolioController {

    private static final String VISIBLE = """
            EXISTS (SELECT 1 FROM entity_grant g WHERE g.user_id = :user AND (g.entity_id IS NULL OR g.entity_id = e.id)
                    AND g.valid_from <= now() AND (g.valid_to IS NULL OR g.valid_to > now()))
            """;

    private final JdbcClient jdbc;

    public PortfolioController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record PortfolioRow(UUID entityId, String legalName, String trn, String entityKind, String filingFrequency, UUID periodId,
                               LocalDate periodStart, LocalDate periodEnd, LocalDate dueDate, String state, Integer daysLeft, Long blockingOpen,
                               Long warningsOpen, BigDecimal netVat, Integer versionNo) {}

    public record DeadlineRow(UUID entityId, String legalName, UUID periodId, LocalDate periodEnd, LocalDate dueDate, String state, int daysLeft,
                              long blockingOpen, BigDecimal netVat, String urgency) {}

    /** One row per filer, showing its earliest period not yet finished. */
    @GetMapping("/portfolio")
    @Transactional(readOnly = true)
    public List<PortfolioRow> portfolio() {
        return jdbc.sql("""
                SELECT e.id AS entity_id, e.legal_name, e.trn, e.entity_kind, e.filing_frequency, p.id AS period_id, p.period_start, p.period_end,
                       p.due_date, p.state, (p.due_date - CURRENT_DATE) AS days_left,
                       (SELECT COUNT(*) FROM recon_exception x WHERE x.period_id = p.id AND x.severity = 'BLOCKING' AND x.status IN ('OPEN','ASSIGNED')) AS blocking_open,
                       (SELECT COUNT(*) FROM recon_exception x WHERE x.period_id = p.id AND x.severity = 'WARNING' AND x.status IN ('OPEN','ASSIGNED')) AS warnings_open,
                       (SELECT (v.box_values->'14'->>'vat')::numeric FROM return_version v WHERE v.period_id = p.id ORDER BY v.version_no DESC LIMIT 1) AS net_vat,
                       (SELECT v.version_no FROM return_version v WHERE v.period_id = p.id ORDER BY v.version_no DESC LIMIT 1) AS version_no
                FROM legal_entity e
                LEFT JOIN LATERAL (SELECT * FROM tax_period tp WHERE tp.entity_id = e.id AND tp.state IN ('OPEN','IN_REVIEW','APPROVED','FILED')
                                   ORDER BY tp.period_start LIMIT 1) p ON true
                WHERE e.entity_kind <> 'GROUP_MEMBER' AND %s
                ORDER BY p.due_date NULLS LAST, e.legal_name
                """.formatted(VISIBLE)).param("user", RequestContext.requireUserId()).query(PortfolioRow.class).list();
    }

    @GetMapping("/deadlines")
    @Transactional(readOnly = true)
    public List<DeadlineRow> deadlines(@RequestParam(defaultValue = "60") int days) {
        return jdbc.sql("""
                SELECT e.id AS entity_id, e.legal_name, p.id AS period_id, p.period_end, p.due_date, p.state,
                       (p.due_date - CURRENT_DATE) AS days_left,
                       (SELECT COUNT(*) FROM recon_exception x WHERE x.period_id = p.id AND x.severity = 'BLOCKING' AND x.status IN ('OPEN','ASSIGNED')) AS blocking_open,
                       (SELECT (v.box_values->'14'->>'vat')::numeric FROM return_version v WHERE v.period_id = p.id ORDER BY v.version_no DESC LIMIT 1) AS net_vat,
                       CASE WHEN p.due_date < CURRENT_DATE THEN 'OVERDUE'
                            WHEN p.due_date - CURRENT_DATE <= 2 THEN 'T-2'
                            WHEN p.due_date - CURRENT_DATE <= 5 THEN 'T-5'
                            WHEN p.due_date - CURRENT_DATE <= 10 THEN 'T-10'
                            ELSE 'LATER' END AS urgency
                FROM tax_period p JOIN legal_entity e ON e.id = p.entity_id
                WHERE p.state IN ('OPEN','IN_REVIEW','APPROVED','FILED') AND p.due_date <= CURRENT_DATE + :days AND %s
                ORDER BY p.due_date, e.legal_name
                """.formatted(VISIBLE)).param("days", days).param("user", RequestContext.requireUserId()).query(DeadlineRow.class).list();
    }
}
