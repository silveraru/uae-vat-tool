package ae.vattool.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ae.vattool.support.IntegrationTest;
import ae.vattool.support.Scenario;
import ae.vattool.support.TestWorld.TestUser;
import ae.vattool.platform.tenancy.RequestContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

class WorkflowTest extends IntegrationTest {

    @Autowired JdbcClient jdbc;
    @Autowired DeadlineAlertJob alerts;

    Scenario s;
    UUID entity;
    UUID period;

    @BeforeEach
    void setUp() throws Exception {
        s = new Scenario(mvc, world);
        entity = s.cleanEntity();
        period = s.cleanJanuary(entity, "10000.00", "500.00");
    }

    private JsonNode wf() throws Exception {
        return s.client.get(s.preparer, "/api/periods/" + period + "/workflow");
    }

    @Test
    void aCleanReturnGoesFromPreparerThroughReviewerToApproverAndLocks() throws Exception {
        assertThat(s.submit(period).get("period").get("state").asString()).isEqualTo("IN_REVIEW");
        s.review(period);
        JsonNode done = s.approve(period);

        assertThat(done.get("period").get("state").asString()).isEqualTo("APPROVED");
        assertThat(done.get("period").get("lockedVersionId").isNull()).isFalse();
        JsonNode version = s.client.get(s.preparer, "/api/periods/" + period + "/return").get("version");
        assertThat(version.get("status").asString()).isEqualTo("LOCKED");
    }

    @Test
    void approvalIsRefusedWhileABlockingExceptionIsOpen() throws Exception {
        // A loaded file with an unmapped tax code leaves a line in no box.
        s.client.uploadCsv(entity, "AR", Scenario.AR_HEAD + "TAX_INVOICE,BAD,2027-01-11,100.00,5.00,NOPE,DXB-1," + Scenario.CUSTOMER + ",B2B\n");
        s.submit(period);
        s.review(period);

        s.client.postRaw(s.approver, "/api/periods/" + period + "/approve", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPROVAL_BLOCKED"))
                .andExpect(jsonPath("$.blockers[0]").value(org.hamcrest.Matchers.containsString("D03")));
    }

    @Test
    void waivingTheLastBlockingExceptionUnblocksApproval() throws Exception {
        s.client.uploadCsv(entity, "AR", Scenario.AR_HEAD + "TAX_INVOICE,BAD,2027-01-11,100.00,5.00,NOPE,DXB-1," + Scenario.CUSTOMER + ",B2B\n");
        s.submit(period);
        s.review(period);
        for (JsonNode e : s.client.get(s.preparer, "/api/periods/" + period + "/exceptions?severity=BLOCKING")) {
            if (List.of("OPEN", "ASSIGNED").contains(e.get("status").asString())) {
                s.client.post(s.partner, "/api/exceptions/" + e.get("id").asString() + "/waive", Map.of("reason", "Immaterial, agreed with the partner"));
            }
        }

        JsonNode done = s.approve(period);

        assertThat(done.get("period").get("state").asString()).isEqualTo("APPROVED");
    }

    @Test
    void theSamePersonCannotPrepareAndReview() throws Exception {
        TestUser both = world.newUser(s.tenant, "FIRM", "PREPARER", "TAX_REVIEWER", "APPROVER");
        s.client.post(both, "/api/periods/" + period + "/submit", null);

        s.client.postRaw(both, "/api/periods/" + period + "/review", null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MAKER_CHECKER"));
    }

    @Test
    void theSamePersonCannotPrepareAndApprove() throws Exception {
        TestUser both = world.newUser(s.tenant, "FIRM", "PREPARER", "APPROVER");
        s.client.post(both, "/api/periods/" + period + "/submit", null);
        s.review(period);

        s.client.postRaw(both, "/api/periods/" + period + "/approve", null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MAKER_CHECKER"));
    }

    @Test
    void theReviewerCannotAlsoApprove() throws Exception {
        TestUser both = world.newUser(s.tenant, "FIRM", "TAX_REVIEWER", "APPROVER");
        s.submit(period);
        s.client.post(both, "/api/periods/" + period + "/review", null);

        s.client.postRaw(both, "/api/periods/" + period + "/approve", null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MAKER_CHECKER"));
    }

    @Test
    void aPreparerHasNoApproveOrReviewRights() throws Exception {
        s.submit(period);

        s.client.postRaw(s.preparer, "/api/periods/" + period + "/review", null).andExpect(status().isForbidden());
        s.client.postRaw(s.preparer, "/api/periods/" + period + "/approve", null).andExpect(status().isForbidden());
    }

    @Test
    void approvalWithoutTheReviewSignoffIsRefused() throws Exception {
        s.submit(period);

        s.client.postRaw(s.approver, "/api/periods/" + period + "/approve", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.blockers[0]").value(org.hamcrest.Matchers.containsString("REVIEWED")));
    }

    @Test
    void dataChangingAfterSignOffInvalidatesItAndReturnsTheReturnToOpen() throws Exception {
        s.submit(period);
        s.review(period);

        // New data arrives. A recompute picks it up, as the worker would.
        s.client.uploadCsv(entity, "AR", Scenario.AR_HEAD + "TAX_INVOICE,LATE,2027-01-20,100.00,5.00,S5,DXB-1," + Scenario.CUSTOMER + ",B2B\n");
        s.client.post(s.preparer, "/api/periods/" + period + "/recompute", null);

        JsonNode w = wf();
        assertThat(w.get("period").get("state").asString()).isEqualTo("OPEN");
        assertThat(w.get("signoffs")).allSatisfy(so -> assertThat(so.get("invalidatedAt").isNull()).isFalse());
        assertThat(w.get("signoffs").get(0).get("invalidatedReason").asString()).contains("data changed");
    }

    @Test
    void approvingSeesDataThatArrivedAfterTheReviewEvenBeforeTheWorkerRuns() throws Exception {
        s.submit(period);
        s.review(period);
        s.client.uploadCsv(entity, "AR", Scenario.AR_HEAD + "TAX_INVOICE,LATE,2027-01-20,100.00,5.00,S5,DXB-1," + Scenario.CUSTOMER + ",B2B\n");

        // No recompute has run. Approval must still notice and refuse.
        s.client.postRaw(s.approver, "/api/periods/" + period + "/approve", null).andExpect(status().isConflict());

        JsonNode w = wf();
        assertThat(w.get("period").get("state").asString()).isEqualTo("OPEN");
    }

    @Test
    void aReclassificationThatLeavesFiguresUnchangedStillInvalidatesSignOffs() throws Exception {
        String line = s.client.get(s.preparer, "/api/periods/" + period + "/lines").get("rows").get(0).get("id").asString();
        s.submit(period);
        s.review(period);

        // Same treatment, same figures: the data still changed under the people who signed.
        s.client.post(s.preparer, "/api/periods/" + period + "/reclassify", Map.of("lineIds", List.of(line),
                "treatment", "STANDARD", "reasonCode", "CONFIRMED_AS_IS", "reason", "Checked"));
        s.client.post(s.preparer, "/api/periods/" + period + "/recompute", null);

        assertThat(wf().get("period").get("state").asString()).isEqualTo("OPEN");
    }

    @Test
    void aLockedPeriodCannotBeChangedThroughTheNormalRoute() throws Exception {
        String line = s.client.get(s.preparer, "/api/periods/" + period + "/lines").get("rows").get(0).get("id").asString();
        s.toApproved(period);

        s.client.postRaw(s.preparer, "/api/periods/" + period + "/reclassify", Map.of("lineIds", List.of(line), "treatment", "EXEMPT", "reasonCode", "OTHER", "reason", "x"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PERIOD_LOCKED"));
        s.client.postRaw(s.preparer, "/api/periods/" + period + "/timing-items", Map.of("accountType", "OUTPUT", "amountAed", "1", "description", "x"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PERIOD_LOCKED"));
    }

    @Test
    void lateDataForALockedPeriodNeverChangesItsReturnAndRaisesAFlagOnTheNextOpenPeriod() throws Exception {
        s.toApproved(period);
        String lockedHash = s.client.get(s.preparer, "/api/periods/" + period + "/return").get("version").get("valuesHash").asString();

        s.client.uploadCsv(entity, "AR", Scenario.AR_HEAD + "TAX_INVOICE,LATE,2027-01-20,100.00,5.00,S5,DXB-1," + Scenario.CUSTOMER + ",B2B\n");
        s.client.post(s.preparer, "/api/periods/" + period + "/recompute", null);

        JsonNode locked = s.client.get(s.preparer, "/api/periods/" + period + "/return");
        assertThat(locked.get("version").get("valuesHash").asString()).isEqualTo(lockedHash);
        assertThat(locked.get("version").get("status").asString()).isEqualTo("LOCKED");

        UUID feb = s.client.periodId(entity, "2027-02-01");
        s.client.post(s.preparer, "/api/periods/" + feb + "/reconcile", null);
        JsonNode exceptions = s.client.get(s.preparer, "/api/periods/" + feb + "/exceptions?severity=BLOCKING");
        assertThat(exceptions.toString()).contains("D04", "Open a correction");
    }

    @Test
    void storedReturnFiguresCannotBeRewrittenEvenByTheApplicationRole() throws Exception {
        s.toApproved(period);

        assertThatThrownBy(() -> world.inTenant(s.tenant, () -> jdbc.sql("UPDATE return_version SET box_values = '{}' WHERE period_id = ?").param(period).update()))
                .rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void sendingBackNeedsAReasonAndReopensTheReturn() throws Exception {
        s.submit(period);

        s.client.postRaw(s.reviewer, "/api/periods/" + period + "/send-back", Map.of("reason", " "))
                .andExpect(status().isUnprocessableContent());
        JsonNode back = s.client.post(s.reviewer, "/api/periods/" + period + "/send-back", Map.of("reason", "Box 9 looks low"));

        assertThat(back.get("period").get("state").asString()).isEqualTo("OPEN");
        assertThat(back.get("signoffs").get(0).get("invalidatedReason").asString()).contains("Box 9 looks low");
    }

    @Test
    void anApprovalCanBeWithdrawnBeforeFilingButNotAfter() throws Exception {
        s.toApproved(period);

        JsonNode withdrawn = s.client.post(s.approver, "/api/periods/" + period + "/withdraw-approval", Map.of("reason", "Found an error before filing"));
        assertThat(withdrawn.get("period").get("state").asString()).isEqualTo("OPEN");
        assertThat(withdrawn.get("period").get("lockedAt").isNull()).isTrue();

        s.toFiled(entity, period);
        s.client.postRaw(s.approver, "/api/periods/" + period + "/withdraw-approval", Map.of("reason", "Too late"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WRONG_STATE"));
    }

    @Test
    void aPartnerMustSignOffAboveTheThreshold() throws Exception {
        putSettings(Map.of("partnerThresholdAed", "100.00"));
        s.submit(period);
        s.review(period);
        assertThat(wf().get("partnerRequired").asBoolean()).isTrue();

        s.client.postRaw(s.approver, "/api/periods/" + period + "/approve", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.blockers[0]").value(org.hamcrest.Matchers.containsString("PARTNER")));

        s.client.post(s.partner, "/api/periods/" + period + "/partner-signoff", Map.of("note", "Reviewed exposure"));
        assertThat(s.approve(period).get("period").get("state").asString()).isEqualTo("APPROVED");
    }

    @Test
    void noPartnerIsNeededBelowTheThreshold() throws Exception {
        putSettings(Map.of("partnerThresholdAed", "1000000.00"));
        s.submit(period);

        assertThat(wf().get("partnerRequired").asBoolean()).isFalse();
    }

    @Test
    void waivingABlockingExceptionBringsThePartnerInEvenBelowTheThreshold() throws Exception {
        putSettings(Map.of("partnerThresholdAed", "1000000.00"));
        s.client.uploadCsv(entity, "AR", Scenario.AR_HEAD + "TAX_INVOICE,BAD,2027-01-11,100.00,5.00,NOPE,DXB-1," + Scenario.CUSTOMER + ",B2B\n");
        s.submit(period);
        JsonNode blocking = s.client.get(s.preparer, "/api/periods/" + period + "/exceptions?severity=BLOCKING");
        s.client.post(s.reviewer, "/api/exceptions/" + blocking.get(0).get("id").asString() + "/waive", Map.of("reason", "Known"));

        assertThat(wf().get("partnerRequired").asBoolean()).isTrue();
    }

    private void putSettings(Map<String, Object> in) throws Exception {
        putJson(s.admin, "/api/entities/" + entity + "/settings", in).andExpect(status().isOk());
    }

    @Test
    void aReturnCanBeConfiguredToSkipTheSeparateReviewStep() throws Exception {
        putSettings(Map.of("requireReviewer", false));
        s.submit(period);

        assertThat(s.approve(period).get("period").get("state").asString()).isEqualTo("APPROVED");
    }

    @Test
    void openWarningsCarryForwardWithTheirAgeWhenAPeriodLocks() throws Exception {
        String unknownSupplier = "100000000000877";
        s.client.uploadCsv(entity, "AP", Scenario.AP_HEAD + "TAX_INVOICE,B1,2027-01-05,100.00,5.00,S5,DXB-1," + unknownSupplier + ",true,2027-02-05,,,SERVICES\n");
        s.client.uploadCsv(entity, "GL", "account_code,account_type,period_end,balance_aed\n1201,INPUT,2027-01-31,5.00\n");
        s.submit(period);
        s.review(period);
        JsonNode original = null;
        for (JsonNode e : s.client.get(s.preparer, "/api/periods/" + period + "/exceptions?severity=WARNING")) {
            if ("R08".equals(e.get("ruleId").asString())) {
                original = e;
            }
        }
        assertThat(original).isNotNull();
        s.approve(period);

        UUID feb = s.client.periodId(entity, "2027-02-01");
        s.client.post(s.preparer, "/api/periods/" + feb + "/reconcile", null);

        JsonNode carried = null;
        for (JsonNode e : s.client.get(s.preparer, "/api/periods/" + feb + "/exceptions")) {
            if ("R08".equals(e.get("ruleId").asString())) {
                carried = e;
            }
        }
        assertThat(carried).isNotNull();
        assertThat(carried.get("status").asString()).isEqualTo("OPEN");
        assertThat(carried.get("carriedFromId").asString()).isEqualTo(original.get("id").asString());
        assertThat(carried.get("firstOpenedAt").asString()).isEqualTo(original.get("firstOpenedAt").asString());
    }

    @Test
    void exceptionsAreFrozenWithALockedPeriod() throws Exception {
        s.client.uploadCsv(entity, "AP", Scenario.AP_HEAD + "TAX_INVOICE,B1,2027-01-05,100.00,5.00,S5,DXB-1,100000000000877,true,2027-02-05,,,SERVICES\n");
        s.client.uploadCsv(entity, "GL", "account_code,account_type,period_end,balance_aed\n1201,INPUT,2027-01-31,5.00\n");
        s.toApproved(period);
        String id = s.client.get(s.preparer, "/api/periods/" + period + "/exceptions?severity=WARNING").get(0).get("id").asString();

        s.client.postRaw(s.reviewer, "/api/exceptions/" + id + "/waive", Map.of("reason", "too late"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EXCEPTION_LOCKED"));
    }

    @Test
    void commentsAttachToBoxesLinesAndExceptionsAndKeepTheirAuthor() throws Exception {
        s.client.post(s.reviewer, "/api/periods/" + period + "/comments", Map.of("objectType", "BOX", "objectId", "1b", "body", "Looks high"));
        s.client.post(s.preparer, "/api/periods/" + period + "/comments", Map.of("objectType", "BOX", "objectId", "1b", "body", "One big order"));
        s.client.post(s.reviewer, "/api/periods/" + period + "/comments", Map.of("objectType", "LINE", "objectId", "abc", "body", "Check this"));

        JsonNode thread = s.client.get(s.preparer, "/api/periods/" + period + "/comments?objectType=BOX&objectId=1b");

        assertThat(thread).hasSize(2);
        assertThat(thread.get(0).get("body").asString()).isEqualTo("Looks high");
        assertThat(thread.get(0).get("authorId").asString()).isEqualTo(s.reviewer.id().toString());
        s.client.postRaw(s.preparer, "/api/periods/" + period + "/comments", Map.of("objectType", "BOX", "objectId", "1b", "body", "  "))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void anExternalAuditorReadsLockedPeriodsOnly() throws Exception {
        TestUser auditor = world.newUser(s.tenant, "EXTERNAL");
        s.client.post(s.admin, "/api/entities/" + entity + "/grants", Map.of("userId", auditor.id(), "role", "AUDITOR", "validTo", java.time.OffsetDateTime.now().plusDays(10).toString()));

        s.client.getRaw(auditor, "/api/periods/" + period + "/return").andExpect(status().isForbidden());
        s.toApproved(period);

        s.client.getRaw(auditor, "/api/periods/" + period + "/return").andExpect(status().isOk());
        s.client.postRaw(auditor, "/api/periods/" + period + "/comments", Map.of("objectType", "BOX", "objectId", "1b", "body", "x")).andExpect(status().isForbidden());
    }

    @Test
    void portfolioShowsStatusDueDateExceptionsAndNetVat() throws Exception {
        s.client.post(s.preparer, "/api/periods/" + period + "/reconcile", null);

        JsonNode rows = s.client.get(s.admin, "/api/portfolio");

        JsonNode row = null;
        for (JsonNode r : rows) {
            if (r.get("entityId").asString().equals(entity.toString())) {
                row = r;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.get("state").asString()).isEqualTo("OPEN");
        assertThat(row.get("dueDate").asString()).isEqualTo("2027-02-28");
        assertThat(new BigDecimal(row.get("netVat").asString())).isEqualByComparingTo("500.00");
        assertThat(row.get("blockingOpen").asInt()).isZero();
    }

    @Test
    void thePortfolioListsOnlyEntitiesTheUserIsGranted() throws Exception {
        UUID other = s.cleanEntity();
        TestUser scoped = world.newUser(s.tenant, "CLIENT");
        world.grantOnEntity(scoped, entity, "PREPARER");

        JsonNode rows = s.client.get(scoped, "/api/portfolio");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("entityId").asString()).isEqualTo(entity.toString());
        assertThat(rows.toString()).doesNotContain(other.toString());
    }

    private long notifications(TestUser user, String kind) {
        return world.queryInTenant(s.tenant, () -> jdbc.sql("SELECT COUNT(*) FROM notification WHERE user_id = ? AND kind = ?")
                .params(user.id(), kind).query(Long.class).single());
    }

    private int runAlerts(String day) {
        return RequestContext.asSystem(s.tenant, "test", () -> alerts.run(LocalDate.parse(day)));
    }

    @Test
    void alertsEscalateAtTenFiveAndTwoDaysToMoreSeniorPeople() {
        // Due 28 Feb 2027.
        assertThat(runAlerts("2027-02-17")).isZero();
        assertThat(runAlerts("2027-02-18")).isPositive();
        assertThat(notifications(s.preparer, "DEADLINE_T10")).isEqualTo(1);
        assertThat(notifications(s.reviewer, "DEADLINE_T10")).isZero();

        assertThat(runAlerts("2027-02-23")).isPositive();
        assertThat(notifications(s.reviewer, "DEADLINE_T5")).isEqualTo(1);
        assertThat(notifications(s.partner, "DEADLINE_T5")).isZero();

        assertThat(runAlerts("2027-02-26")).isPositive();
        assertThat(notifications(s.partner, "DEADLINE_T2")).isEqualTo(1);
        assertThat(notifications(s.approver, "DEADLINE_T2")).isEqualTo(1);
    }

    @Test
    void aDeadlineAlertIsSentOnlyOnce() {
        assertThat(runAlerts("2027-02-18")).isPositive();

        assertThat(runAlerts("2027-02-18")).isZero();
        assertThat(runAlerts("2027-02-19")).isZero();
        assertThat(notifications(s.preparer, "DEADLINE_T10")).isEqualTo(1);
    }

    @Test
    void aMissedDaySendsOnlyTheMostUrgentAlert() {
        runAlerts("2027-02-27");

        assertThat(notifications(s.preparer, "DEADLINE_T2")).isEqualTo(1);
        assertThat(notifications(s.preparer, "DEADLINE_T5")).isZero();
        assertThat(notifications(s.preparer, "DEADLINE_T10")).isZero();
    }

    @Test
    void anOverdueReturnAlertsEveryoneOnceAndAPaidOneAlertsNoOne() throws Exception {
        runAlerts("2027-03-01");
        assertThat(notifications(s.admin, "DEADLINE_T0")).isEqualTo(1);

        UUID clean = s.cleanEntity();
        UUID cleanPeriod = s.cleanJanuary(clean, "1000.00", "50.00");
        s.toFiled(clean, cleanPeriod);
        s.client.post(s.reviewer, "/api/periods/" + cleanPeriod + "/payment", Map.of("amountPaidAed", "50.00", "gibanReference", "AE1", "paidOn", "2027-02-25",
                "bankReference", "AE1", "bankAmountAed", "50.00", "confirmationEvidenceId", s.evidence(clean, cleanPeriod, "bank.pdf")));
        long before = world.queryInTenant(s.tenant, () -> jdbc.sql("SELECT COUNT(*) FROM notification WHERE period_id = ?").param(cleanPeriod).query(Long.class).single());
        runAlerts("2027-03-02");
        long after = world.queryInTenant(s.tenant, () -> jdbc.sql("SELECT COUNT(*) FROM notification WHERE period_id = ?").param(cleanPeriod).query(Long.class).single());
        assertThat(after).isEqualTo(before);
    }

    @Test
    void theApprovedSnapshotAndAuditEventsAreRecorded() throws Exception {
        s.toApproved(period);

        List<String> actions = world.queryInTenant(s.tenant, () -> jdbc.sql("SELECT action FROM audit_event WHERE object_id = ? ORDER BY seq")
                .param(period.toString()).query(String.class).list());

        assertThat(actions).contains("RETURN_SUBMITTED", "RETURN_SIGNED_OFF", "RETURN_APPROVED_AND_LOCKED");
    }
}
