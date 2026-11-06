package ae.vattool.simulator;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Turns invoices into the events a real ASP would publish, then applies injected faults. Faults let
 * reconciliation be exercised against the problems it exists to catch. A seed keeps runs repeatable.
 */
public final class EventFactory {

    public record InvoiceSpec(String direction, String docType, String number, String issueDate, String supplierTrn,
                              String buyerTrn, BigDecimal netAed, BigDecimal vatAed, String billingReference, UUID uuid) {}

    /** How many invoices each fault applies to. Faults are applied to distinct invoices. */
    public record Faults(int missing, int mismatch, int duplicate, int rejected, int corner5Failed, int corner5Pending, int outOfOrder) {
        public static final Faults NONE = new Faults(0, 0, 0, 0, 0, 0, 0);
    }

    private final UUID tenantId;
    private final String entityTrn;
    private final Random random;

    public EventFactory(UUID tenantId, String entityTrn, long seed) {
        this.tenantId = tenantId;
        this.entityTrn = entityTrn;
        this.random = new Random(seed);
    }

    public List<Map<String, Object>> events(List<InvoiceSpec> invoices, Faults faults) {
        List<InvoiceSpec> shuffled = new ArrayList<>(invoices);
        Collections.shuffle(shuffled, random);
        int cursor = 0;
        List<InvoiceSpec> missing = take(shuffled, cursor, faults.missing());
        cursor += faults.missing();
        List<InvoiceSpec> mismatch = take(shuffled, cursor, faults.mismatch());
        cursor += faults.mismatch();
        List<InvoiceSpec> duplicate = take(shuffled, cursor, faults.duplicate());
        cursor += faults.duplicate();
        List<InvoiceSpec> rejected = take(shuffled, cursor, faults.rejected());
        cursor += faults.rejected();
        List<InvoiceSpec> failed = take(shuffled, cursor, faults.corner5Failed());
        cursor += faults.corner5Failed();
        List<InvoiceSpec> pending = take(shuffled, cursor, faults.corner5Pending());
        cursor += faults.corner5Pending();
        List<InvoiceSpec> outOfOrder = take(shuffled, cursor, faults.outOfOrder());

        OffsetDateTime clock = OffsetDateTime.parse("2027-01-31T08:00:00Z");
        List<Map<String, Object>> events = new ArrayList<>();
        for (InvoiceSpec inv : invoices) {
            if (missing.contains(inv)) {
                continue;
            }
            clock = clock.plusSeconds(10);
            InvoiceSpec sent = mismatch.contains(inv)
                    ? new InvoiceSpec(inv.direction(), inv.docType(), inv.number(), inv.issueDate(), inv.supplierTrn(), inv.buyerTrn(),
                    inv.netAed().add(new BigDecimal("100.00")), inv.vatAed().add(new BigDecimal("5.00")), inv.billingReference(), inv.uuid())
                    : inv;
            Map<String, Object> invoiceEvent = invoiceEvent(sent, clock);
            Map<String, Object> statusEvent = null;
            if (rejected.contains(inv)) {
                events.add(invoiceEvent);
                events.add(rejectionEvent(inv, clock.plusSeconds(1)));
                continue;
            }
            if (!pending.contains(inv)) {
                statusEvent = corner5Event(inv, failed.contains(inv) ? "FAILED" : "REPORTED", clock.plusSeconds(2));
            }
            if (outOfOrder.contains(inv) && statusEvent != null) {
                // Status overtakes its invoice on the wire, with the true times unchanged.
                events.add(statusEvent);
                events.add(invoiceEvent);
            } else {
                events.add(invoiceEvent);
                if (statusEvent != null) {
                    events.add(statusEvent);
                }
            }
            if (duplicate.contains(inv)) {
                // Same event id delivered again: at-least-once delivery, not a second invoice.
                events.add(invoiceEvent);
            }
        }
        return events;
    }

    private static List<InvoiceSpec> take(List<InvoiceSpec> list, int from, int count) {
        return list.subList(Math.min(from, list.size()), Math.min(from + count, list.size()));
    }

    private Map<String, Object> envelope(String type, UUID invoiceUuid, OffsetDateTime at) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("specVersion", "1.0");
        e.put("eventId", UUID.nameUUIDFromBytes((type + "|" + invoiceUuid).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
        e.put("eventType", type);
        e.put("occurredAt", at.toString());
        e.put("tenantId", tenantId.toString());
        e.put("entityTrn", entityTrn);
        e.put("invoiceUuid", invoiceUuid.toString());
        return e;
    }

    private Map<String, Object> invoiceEvent(InvoiceSpec inv, OffsetDateTime at) {
        Map<String, Object> e = envelope("ISSUED".equals(inv.direction()) ? "einvoice.issued" : "einvoice.received", inv.uuid(), at);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("docType", inv.docType());
        body.put("number", inv.number());
        body.put("issueDate", inv.issueDate());
        body.put("supplierTrn", inv.supplierTrn());
        body.put("buyerTrn", inv.buyerTrn());
        body.put("currency", "AED");
        body.put("netAed", inv.netAed());
        body.put("vatAed", inv.vatAed());
        body.put("billingReference", inv.billingReference());
        e.put("invoice", body);
        return e;
    }

    private Map<String, Object> rejectionEvent(InvoiceSpec inv, OffsetDateTime at) {
        Map<String, Object> e = envelope("einvoice.rejected", inv.uuid(), at);
        e.put("rejection", Map.of("reason", "Simulated rejection by the receiving access point"));
        return e;
    }

    private Map<String, Object> corner5Event(InvoiceSpec inv, String status, OffsetDateTime at) {
        Map<String, Object> e = envelope("einvoice.corner5_status", inv.uuid(), at);
        e.put("corner5", Map.of("status", status));
        return e;
    }
}
