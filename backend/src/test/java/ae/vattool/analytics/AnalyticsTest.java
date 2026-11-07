package ae.vattool.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ae.vattool.support.IntegrationTest;
import ae.vattool.support.Scenario;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** ANL-01 to ANL-03: trends, anomaly flags and the registration threshold. */
class AnalyticsTest extends IntegrationTest {

    @Autowired JdbcClient jdbc;

    private static final String AP = "doc_type,doc_number,doc_date,net_amount,vat_amount,tax_code,establishment_code,counterparty_trn,has_tax_invoice\n";

    Scenario s;
    UUID entity;

    @BeforeEach
    void setUp() throws Exception {
        s = new Scenario(mvc, world);
        entity = s.cleanEntity();
    }

    private UUID compute(String start) throws Exception {
        UUID p = s.client.periodId(entity, start);
        s.client.post(s.preparer, "/api/periods/" + p + "/recompute", null);
        return p;
    }

    private void sale(String number, String date, String net, String vat) throws Exception {
        s.client.uploadCsv(entity, "AR", Scenario.AR_HEAD + "TAX_INVOICE," + number + "," + date + "," + net + "," + vat + ",S5,DXB-1," + Scenario.CUSTOMER + ",B2B\n");
    }

    @Test
    void trendsShowTaxRecoveryAndEffectiveRatePerPeriod() throws Exception {
        sale("S-1", "2027-01-10", "10000.00", "500.00");
        s.client.uploadCsv(entity, "AP", AP + "TAX_INVOICE,B-1,2027-01-11,2000.00,100.00,S5,DXB-1," + Scenario.SUPPLIER + ",true\n");
        compute("2027-01-01");

        JsonNode point = s.client.get(s.reviewer, "/api/entities/" + entity + "/analytics/trends").get(0);

        assertThat(new BigDecimal(point.get("outputTax").asString())).isEqualByComparingTo("500.00");
        assertThat(new BigDecimal(point.get("recoverableTax").asString())).isEqualByComparingTo("100.00");
        assertThat(new BigDecimal(point.get("netVat").asString())).isEqualByComparingTo("400.00");
        assertThat(new BigDecimal(point.get("recoveryRatePct").asString())).isEqualByComparingTo("100.00");
        assertThat(new BigDecimal(point.get("effectiveRatePct").asString())).isEqualByComparingTo("5.00");
    }

    @Test
    void aPeriodWithNoReturnYetIsLeftOutRatherThanShownAsZero() throws Exception {
        s.client.periodId(entity, "2027-03-01");

        assertThat(s.client.get(s.reviewer, "/api/entities/" + entity + "/analytics/trends")).isEmpty();
    }

    @Test
    void aLargeSwingInABoxIsFlagged() throws Exception {
        sale("S-1", "2027-01-10", "10000.00", "500.00");
        compute("2027-01-01");
        sale("S-2", "2027-02-10", "20000.00", "1000.00");
        UUID feb = compute("2027-02-01");

        JsonNode anomalies = s.client.get(s.reviewer, "/api/periods/" + feb + "/analytics/anomalies");

        assertThat(anomalies.toString()).contains("BOX_SWING", "\"box\":\"1b\"");
    }

    @Test
    void aSmallChangeIsNotFlagged() throws Exception {
        sale("S-1", "2027-01-10", "10000.00", "500.00");
        compute("2027-01-01");
        sale("S-2", "2027-02-10", "11000.00", "550.00");
        UUID feb = compute("2027-02-01");

        assertThat(s.client.get(s.reviewer, "/api/periods/" + feb + "/analytics/anomalies").toString()).doesNotContain("BOX_SWING");
    }

    @Test
    void aNewHighValueSupplierIsFlaggedOnceAndNotASmallOne() throws Exception {
        s.client.uploadCsv(entity, "AP", AP + "TAX_INVOICE,B-1,2027-01-11,150000.00,7500.00,S5,DXB-1," + Scenario.SUPPLIER + ",true\nTAX_INVOICE,B-2,2027-01-12,100.00,5.00,S5,DXB-1,100000000000802,true\n");
        UUID jan = compute("2027-01-01");
        s.client.uploadCsv(entity, "AP", AP + "TAX_INVOICE,B-3,2027-02-11,150000.00,7500.00,S5,DXB-1," + Scenario.SUPPLIER + ",true\n");
        UUID feb = compute("2027-02-01");

        String first = s.client.get(s.reviewer, "/api/periods/" + jan + "/analytics/anomalies").toString();
        assertThat(first).contains("NEW_HIGH_VALUE_SUPPLIER", Scenario.SUPPLIER).doesNotContain("100000000000802");
        assertThat(s.client.get(s.reviewer, "/api/periods/" + feb + "/analytics/anomalies").toString()).doesNotContain("NEW_HIGH_VALUE_SUPPLIER");
    }

    @Test
    void suddenZeroRatedGrowthIsFlagged() throws Exception {
        String head = "doc_type,doc_number,doc_date,net_amount,vat_amount,tax_code,establishment_code,counterparty_country,goods_or_services\n";
        s.client.uploadCsv(entity, "AR", head + "TAX_INVOICE,Z-1,2027-01-10,10000.00,0.00,Z0,DXB-1,SA,GOODS\n");
        compute("2027-01-01");
        s.client.uploadCsv(entity, "AR", head + "TAX_INVOICE,Z-2,2027-02-10,90000.00,0.00,Z0,DXB-1,SA,GOODS\n");
        UUID feb = compute("2027-02-01");

        assertThat(s.client.get(s.reviewer, "/api/periods/" + feb + "/analytics/anomalies").toString()).contains("ZERO_RATED_GROWTH");
    }

    // ---- Registration threshold ----

    private String watch() throws Exception {
        return s.client.post(s.partner, "/api/registration-watch", Map.of("name", "New client LLC")).get("watch").get("id").asString();
    }

    private JsonNode month(String id, String monthStart, String supplies) throws Exception {
        return s.client.post(s.partner, "/api/registration-watch/" + id + "/months", Map.of("monthStart", monthStart, "taxableSuppliesAed", supplies));
    }

    @Test
    void theRollingTotalMovesAClientThroughEachStatus() throws Exception {
        String id = watch();

        assertThat(month(id, "2027-01-01", "100000.00").get("status").asString()).isEqualTo("BELOW");
        assertThat(month(id, "2027-02-01", "100000.00").get("status").asString()).isEqualTo("MAY_REGISTER");
        assertThat(month(id, "2027-03-01", "100000.00").get("status").asString()).isEqualTo("APPROACHING");
        JsonNode over = month(id, "2027-04-01", "100000.00");
        assertThat(over.get("status").asString()).isEqualTo("MUST_REGISTER");
        assertThat(new BigDecimal(over.get("rollingAed").asString())).isEqualByComparingTo("400000.00");
    }

    @Test
    void monthsOlderThanTwelveFallOutOfTheTotal() throws Exception {
        String id = watch();
        month(id, "2026-01-01", "300000.00");

        JsonNode later = month(id, "2027-02-01", "10000.00");

        assertThat(new BigDecimal(later.get("rollingAed").asString())).isEqualByComparingTo("10000.00");
    }

    @Test
    void expectedSalesInTheNextThirtyDaysAloneCanRequireRegistration() throws Exception {
        String id = watch();

        JsonNode p = s.client.post(s.partner, "/api/registration-watch/" + id + "/months", Map.of("monthStart", "2027-01-01", "taxableSuppliesAed", "1000.00", "expectedNext30DaysAed", "400000.00"));

        assertThat(p.get("status").asString()).isEqualTo("MUST_REGISTER");
    }

    @Test
    void theFirmIsToldOnceWhenAClientMustRegister() throws Exception {
        String id = watch();

        month(id, "2027-01-01", "400000.00");
        month(id, "2027-02-01", "1000.00");

        long notices = world.queryInTenant(s.tenant, () -> jdbc.sql("SELECT count(DISTINCT title) FROM notification WHERE kind = 'REGISTRATION_THRESHOLD'").query(Long.class).single());
        long perPerson = world.queryInTenant(s.tenant, () -> jdbc.sql("SELECT count(*) FROM notification WHERE kind = 'REGISTRATION_THRESHOLD' AND user_id = ?").param(s.partner.id()).query(Long.class).single());
        assertThat(notices).isEqualTo(1);
        assertThat(perPerson).isEqualTo(1);
    }

    @Test
    void aPreparerCannotSeeTheFirmsWatchList() throws Exception {
        s.client.getRaw(s.preparer, "/api/registration-watch").andExpect(status().isForbidden());
    }
}
