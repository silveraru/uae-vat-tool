package ae.vattool.masterdata;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record LegalEntity(UUID id, String legalName, String legalNameAr, String trn, String entityKind,
                          UUID groupRepId, String filingFrequency, String stagger, LocalDate firstPeriodStart,
                          LocalDate einvoiceGoLive, BigDecimal defaultRecoveryPct, BigDecimal partnerThresholdAed,
                          boolean requireReviewer, String ftaGiban, boolean requireEsignature, Integer taxYearStartMonth) {

    public static final String SELECT = """
            SELECT id, legal_name, legal_name_ar, trn, entity_kind, group_rep_id, filing_frequency, stagger,
                   first_period_start, einvoice_go_live, default_recovery_pct, partner_threshold_aed, require_reviewer,
                   fta_giban, require_esignature, tax_year_start_month
            FROM legal_entity
            """;

    public boolean isFiler() {
        return !"GROUP_MEMBER".equals(entityKind);
    }

    /**
     * The first day of the tax year holding a date. The setting wins, and without it the year runs from
     * the month the entity's first period starts, which only a person can say is right.
     */
    public LocalDate taxYearStart(LocalDate day) {
        int month = taxYearStartMonth != null ? taxYearStartMonth : firstPeriodStart.getMonthValue();
        LocalDate start = LocalDate.of(day.getYear(), month, 1);
        return start.isAfter(day) ? start.minusYears(1) : start;
    }

    /** The entity that files the return this entity's lines belong to. */
    public UUID filerId() {
        return isFiler() ? id : groupRepId;
    }
}
