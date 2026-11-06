package ae.vattool.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** A ledger row as the API and reconciliation see it. */
public record TaxLine(UUID id, UUID logicalLineId, int revision, UUID filerEntityId, UUID memberEntityId, String emirate,
                      UUID periodId, UUID documentId, UUID ingestRowId, int lineNo, String lineKind, String adjBox,
                      String sourceSystem, String direction, String goodsOrServices, String glAccount, String taxCode,
                      String description, String counterpartyTrn, String counterpartyCountry, LocalDate taxPoint,
                      String currency, BigDecimal fxRate, BigDecimal netDoc, BigDecimal vatDoc, BigDecimal netAed,
                      BigDecimal vatAed, BigDecimal recoverableVatAed, BigDecimal recoveryPct, String treatment,
                      String blockedCategory, String drcCategory, String placeOfSupply, boolean memo,
                      String[] reviewFlags, String classifiedBy, BigDecimal confidence, String[] ruleIds,
                      String rulePackVersion, String changeReasonCode, String changeReason, String changedBy,
                      OffsetDateTime createdAt, OffsetDateTime supersededAt) {

    public static final String COLUMNS = """
            id, logical_line_id, revision, filer_entity_id, member_entity_id, emirate, period_id, document_id,
            ingest_row_id, line_no, line_kind, adj_box, source_system, direction, goods_or_services, gl_account,
            tax_code, description, counterparty_trn, counterparty_country, tax_point, currency, fx_rate, net_doc,
            vat_doc, net_aed, vat_aed, recoverable_vat_aed, recovery_pct, treatment, blocked_category, drc_category,
            place_of_supply, memo, review_flags, classified_by, confidence, rule_ids, rule_pack_version,
            change_reason_code, change_reason, changed_by, created_at, superseded_at
            """;
}
