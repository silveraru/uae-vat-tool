package ae.vattool.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import ae.vattool.support.TestWorld.TestUser;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/** One person per role, and shortcuts to a clean period that is ready to be approved. */
public class Scenario {

    public static final String AR_HEAD = "doc_type,doc_number,doc_date,net_amount,vat_amount,tax_code,establishment_code,counterparty_trn,customer_type\n";
    public static final String AP_HEAD = "doc_type,doc_number,doc_date,net_amount,vat_amount,tax_code,establishment_code,counterparty_trn,has_tax_invoice,agreed_payment_date,paid_date,paid_amount,goods_or_services\n";
    public static final String CUSTOMER = "100000000000901";
    public static final String SUPPLIER = "100000000000801";

    public final UUID tenant;
    public final TestUser admin;
    public final TestUser preparer;
    public final TestUser reviewer;
    public final TestUser partner;
    public final TestUser approver;
    public final TestClient client;
    private final MockMvc mvc;

    public Scenario(MockMvc mvc, TestWorld world) {
        this.mvc = mvc;
        tenant = world.newTenant("Scenario firm " + UUID.randomUUID());
        admin = world.newUser(tenant, "FIRM", "FIRM_ADMIN");
        preparer = world.newUser(tenant, "CLIENT", "PREPARER");
        reviewer = world.newUser(tenant, "FIRM", "TAX_REVIEWER");
        partner = world.newUser(tenant, "FIRM", "ENGAGEMENT_PARTNER");
        approver = world.newUser(tenant, "CLIENT", "APPROVER");
        client = new TestClient(mvc, admin, preparer);
    }

    /** An entity with e-invoicing not yet live and control balances matching, so nothing blocks approval. */
    public UUID cleanEntity() throws Exception {
        UUID entity = client.simpleEntity(null);
        client.post(admin, "/api/counterparties", java.util.Map.of("trn", SUPPLIER, "name", "Good Supplier", "trnStatus", "ACTIVE"));
        return entity;
    }

    /** A sale of the given size in January with a matching GL balance. */
    public UUID cleanJanuary(UUID entity, String net, String vat) throws Exception {
        client.uploadCsv(entity, "AR", AR_HEAD + "TAX_INVOICE,S-" + UUID.randomUUID().toString().substring(0, 6) + ",2027-01-10," + net + "," + vat + ",S5,DXB-1," + CUSTOMER + ",B2B\n");
        client.uploadCsv(entity, "GL", "account_code,account_type,period_end,balance_aed\n2201,OUTPUT,2027-01-31," + vat + "\n");
        return client.periodId(entity, "2027-01-01");
    }

    public JsonNode submit(UUID period) throws Exception {
        return client.post(preparer, "/api/periods/" + period + "/submit", null);
    }

    public JsonNode review(UUID period) throws Exception {
        return client.post(reviewer, "/api/periods/" + period + "/review", null);
    }

    public JsonNode approve(UUID period) throws Exception {
        return client.post(approver, "/api/periods/" + period + "/approve", null);
    }

    public void toApproved(UUID period) throws Exception {
        submit(period);
        review(period);
        approve(period);
    }

    public String evidence(UUID entity, UUID period, String name) throws Exception {
        var file = new MockMultipartFile("file", name, "application/pdf", ("evidence " + name + " " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
        var req = multipart("/api/entities/" + entity + "/evidence").file(file).with(reviewer.auth());
        if (period != null) {
            req = req.param("periodId", period.toString());
        }
        return TestClient.read(mvc.perform(req)).get("id").asString();
    }

    public void toFiled(UUID entity, UUID period) throws Exception {
        toApproved(period);
        String ack = evidence(entity, period, "ack.pdf");
        client.post(reviewer, "/api/periods/" + period + "/filing", java.util.Map.of("submissionRef", "EMT-1", "submittedOn", "2027-02-20", "ackEvidenceId", ack));
    }

    public static List<String> rows(String... lines) {
        return List.of(lines);
    }
}
