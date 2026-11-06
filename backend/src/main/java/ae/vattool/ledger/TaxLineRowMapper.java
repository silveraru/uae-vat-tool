package ae.vattool.ledger;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;

/** Hand-written because of the text[] columns, which the generic record mapper cannot convert. */
public final class TaxLineRowMapper implements RowMapper<TaxLine> {
    public static final TaxLineRowMapper INSTANCE = new TaxLineRowMapper();

    private static String[] strings(Array a) throws SQLException {
        return a == null ? new String[0] : (String[]) a.getArray();
    }

    @Override
    public TaxLine mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new TaxLine(
                rs.getObject("id", UUID.class), rs.getObject("logical_line_id", UUID.class), rs.getInt("revision"),
                rs.getObject("filer_entity_id", UUID.class), rs.getObject("member_entity_id", UUID.class),
                rs.getString("emirate"), rs.getObject("period_id", UUID.class), rs.getObject("document_id", UUID.class),
                rs.getObject("ingest_row_id", UUID.class), rs.getInt("line_no"), rs.getString("line_kind"),
                rs.getString("adj_box"), rs.getString("source_system"), rs.getString("direction"),
                rs.getString("goods_or_services"), rs.getString("gl_account"), rs.getString("tax_code"),
                rs.getString("description"), rs.getString("counterparty_trn"), rs.getString("counterparty_country"),
                rs.getObject("tax_point", LocalDate.class), rs.getString("currency"), rs.getBigDecimal("fx_rate"),
                rs.getBigDecimal("net_doc"), rs.getBigDecimal("vat_doc"), rs.getBigDecimal("net_aed"),
                rs.getBigDecimal("vat_aed"), rs.getBigDecimal("recoverable_vat_aed"), rs.getBigDecimal("recovery_pct"),
                rs.getString("treatment"), rs.getString("blocked_category"), rs.getString("drc_category"),
                rs.getString("place_of_supply"), rs.getBoolean("memo"), strings(rs.getArray("review_flags")),
                rs.getString("classified_by"), rs.getBigDecimal("confidence"), strings(rs.getArray("rule_ids")),
                rs.getString("rule_pack_version"), rs.getString("change_reason_code"), rs.getString("change_reason"),
                rs.getString("changed_by"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("superseded_at", OffsetDateTime.class));
    }
}
