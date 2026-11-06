package ae.vattool.masterdata;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

public record TaxPeriod(UUID id, UUID entityId, LocalDate periodStart, LocalDate periodEnd, LocalDate dueDate,
                        String state, UUID lockedVersionId, OffsetDateTime lockedAt) {

    public static final String SELECT = """
            SELECT id, entity_id, period_start, period_end, due_date, state, locked_version_id, locked_at
            FROM tax_period
            """;

    private static final Set<String> LOCKED_STATES = Set.of("APPROVED", "FILED", "PAID", "AMENDED");

    public boolean isLocked() {
        return LOCKED_STATES.contains(state);
    }

    public boolean contains(LocalDate date) {
        return !date.isBefore(periodStart) && !date.isAfter(periodEnd);
    }
}
