package ae.vattool.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventFactoryTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");

    private static List<EventFactory.InvoiceSpec> invoices(int n) {
        List<EventFactory.InvoiceSpec> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(new EventFactory.InvoiceSpec("ISSUED", "INVOICE", "INV-" + i, "2027-01-15", "100000000000001",
                    "100000000000002", new BigDecimal("100.00"), new BigDecimal("5.00"), null, UUID.nameUUIDFromBytes(("x" + i).getBytes())));
        }
        return list;
    }

    private static List<Map<String, Object>> run(int n, EventFactory.Faults faults) {
        return new EventFactory(TENANT, "100000000000001", 7).events(invoices(n), faults);
    }

    private static long count(List<Map<String, Object>> events, String type) {
        return events.stream().filter(e -> type.equals(e.get("eventType"))).count();
    }

    @Test
    void withNoFaultsEveryInvoiceGetsAnInvoiceAndAStatusEvent() {
        List<Map<String, Object>> events = run(5, EventFactory.Faults.NONE);

        assertThat(count(events, "einvoice.issued")).isEqualTo(5);
        assertThat(count(events, "einvoice.corner5_status")).isEqualTo(5);
    }

    @Test
    void aMissingInvoiceProducesNoEventsAtAll() {
        List<Map<String, Object>> events = run(5, new EventFactory.Faults(2, 0, 0, 0, 0, 0, 0));

        assertThat(count(events, "einvoice.issued")).isEqualTo(3);
        assertThat(count(events, "einvoice.corner5_status")).isEqualTo(3);
    }

    @Test
    void aRejectedInvoiceIsNeverReported() {
        List<Map<String, Object>> events = run(5, new EventFactory.Faults(0, 0, 0, 1, 0, 0, 0));

        assertThat(count(events, "einvoice.rejected")).isEqualTo(1);
        assertThat(count(events, "einvoice.corner5_status")).isEqualTo(4);
    }

    @Test
    void aDuplicateRepeatsTheSameEventIdSoItIsARedeliveryNotANewInvoice() {
        List<Map<String, Object>> events = run(3, new EventFactory.Faults(0, 0, 1, 0, 0, 0, 0));

        List<Object> ids = events.stream().filter(e -> "einvoice.issued".equals(e.get("eventType"))).map(e -> e.get("eventId")).toList();
        assertThat(ids).hasSize(4);
        assertThat(ids.stream().distinct()).hasSize(3);
    }

    @Test
    void outOfOrderDeliversTheStatusBeforeItsInvoiceButKeepsTrueTimes() {
        List<Map<String, Object>> events = run(1, new EventFactory.Faults(0, 0, 0, 0, 0, 0, 1));

        assertThat(events.get(0).get("eventType")).isEqualTo("einvoice.corner5_status");
        assertThat(events.get(1).get("eventType")).isEqualTo("einvoice.issued");
        assertThat((String) events.get(0).get("occurredAt")).isGreaterThan((String) events.get(1).get("occurredAt"));
    }

    @Test
    void theSameSeedGivesTheSameFaultsEachRun() {
        EventFactory.Faults f = new EventFactory.Faults(1, 1, 0, 1, 0, 0, 0);

        assertThat(run(20, f)).isEqualTo(run(20, f));
    }

    @Test
    void csvAddsUpLinesPerDocumentAndSkipsB2cAndCreditNoteSignIsNegative() throws Exception {
        String csv = """
                doc_type,doc_number,doc_date,net_amount,vat_amount,counterparty_trn,customer_type,line_no
                TAX_INVOICE,A1,2027-01-05,100.00,5.00,100000000000002,B2B,1
                TAX_INVOICE,A1,2027-01-05,200.00,10.00,100000000000002,B2B,2
                B2C_SALE,R1,2027-01-05,50.00,2.50,,B2C,1
                CREDIT_NOTE,C1,2027-01-06,40.00,2.00,100000000000002,B2B,1
                """;

        List<EventFactory.InvoiceSpec> specs = InvoiceCsv.read(new StringReader(csv), "ISSUED", "100000000000001");

        assertThat(specs).hasSize(2);
        assertThat(specs.get(0).netAed()).isEqualByComparingTo("300.00");
        assertThat(specs.get(0).vatAed()).isEqualByComparingTo("15.00");
        assertThat(specs.get(1).docType()).isEqualTo("CREDIT_NOTE");
        assertThat(specs.get(1).netAed()).isEqualByComparingTo("-40.00");
    }
}
