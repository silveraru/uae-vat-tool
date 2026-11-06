package ae.vattool.returns;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ReturnVersionView(UUID id, UUID entityId, UUID periodId, int versionNo, String status, String formCode,
                                String formVersion, UUID rulePackId, String rulePackVersion, boolean rulePackSignedOff,
                                String boxValues, String memberValues, String valuesHash, String dataFingerprint,
                                String diff, long lineCount, String trigger, String createdBy, OffsetDateTime createdAt) {
    public static final String SELECT = """
            SELECT id, entity_id, period_id, version_no, status, form_code, form_version, rule_pack_id, rule_pack_version,
                   rule_pack_signed_off, box_values::text AS box_values, member_values::text AS member_values, values_hash,
                   data_fingerprint, diff::text AS diff, line_count, trigger, created_by, created_at
            FROM return_version
            """;
}
