package ae.vattool.returns;

import ae.vattool.masterdata.PeriodCalendar;
import ae.vattool.masterdata.TaxPeriod;
import ae.vattool.platform.security.AccessControl;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** The supporting schedule for the profit margin scheme (RET-05). The return's box holds the margin. This holds how it arose. */
@RestController
public class MarginScheduleController {

    public record Row(String docNumber, LocalDate docDate, String category, String description, BigDecimal purchasePriceAed, BigDecimal acquisitionCostsAed,
                      BigDecimal sellingPriceAed, BigDecimal marginAed, BigDecimal vatAed) {}

    private final JdbcClient jdbc;
    private final PeriodCalendar calendar;
    private final AccessControl access;

    public MarginScheduleController(JdbcClient jdbc, PeriodCalendar calendar, AccessControl access) {
        this.jdbc = jdbc;
        this.calendar = calendar;
        this.access = access;
    }

    @GetMapping("/api/periods/{id}/margin-schedule")
    @Transactional(readOnly = true)
    public List<Row> schedule(@PathVariable UUID id) {
        TaxPeriod p = calendar.get(id);
        access.requireReadPeriod(p.entityId(), p.isLocked());
        return jdbc.sql("""
                SELECT doc_number, doc_date, category, description, purchase_price_aed, acquisition_costs_aed, selling_price_aed, margin_aed, vat_aed
                FROM margin_sale WHERE period_id = ? ORDER BY doc_date, doc_number
                """).param(id).query(Row.class).list();
    }
}
