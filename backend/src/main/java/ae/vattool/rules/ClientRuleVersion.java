package ae.vattool.rules;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** One published set of client rules. A change is always a new version, so what classified a line can be shown later. */
public record ClientRuleVersion(UUID id, UUID entityId, int versionNo, LocalDate effectiveFrom, ClientRules content, String contentHash,
                                String note, String status, String createdBy, String createdByName, OffsetDateTime createdAt,
                                String signedOffBy, String signedOffByName, OffsetDateTime signedOffAt, String signOffNote) {

    public boolean signedOff() {
        return "SIGNED_OFF".equals(status);
    }
}
