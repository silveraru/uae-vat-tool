package ae.vattool.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** A source line ready for the ledger: validated and converted to AED, not yet classified. */
public record LineInput(
        UUID documentId,
        UUID ingestRowId,
        UUID filerEntityId,
        UUID memberEntityId,
        UUID establishmentId,
        String emirate,
        boolean establishmentDesignatedZone,
        String sourceSystem,
        String direction,
        String docType,
        int lineNo,
        String goodsOrServices,
        String glAccount,
        String taxCode,
        String description,
        String counterpartyTrn,
        String counterpartyCountry,
        LocalDate taxPoint,
        String currency,
        BigDecimal fxRate,
        BigDecimal netDoc,
        BigDecimal vatDoc,
        BigDecimal netAed,
        BigDecimal vatAed,
        BigDecimal recoveryPct,
        String forcedTreatment) {}
