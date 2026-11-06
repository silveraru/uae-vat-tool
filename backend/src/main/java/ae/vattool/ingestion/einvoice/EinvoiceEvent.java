package ae.vattool.ingestion.einvoice;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** One event from the ASP platform, matching contracts/einvoice-events/einvoice-event.schema.json. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EinvoiceEvent(String specVersion, UUID eventId, String eventType, OffsetDateTime occurredAt, UUID tenantId,
                            String entityTrn, UUID invoiceUuid, Invoice invoice, Rejection rejection, Corner5 corner5) {

    public static final String ISSUED = "einvoice.issued";
    public static final String RECEIVED = "einvoice.received";
    public static final String REJECTED = "einvoice.rejected";
    public static final String CORNER5 = "einvoice.corner5_status";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Invoice(String docType, String number, LocalDate issueDate, String supplierTrn, String buyerTrn,
                          String currency, BigDecimal netAed, BigDecimal vatAed, String billingReference) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Rejection(String reason) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Corner5(String status) {}
}
