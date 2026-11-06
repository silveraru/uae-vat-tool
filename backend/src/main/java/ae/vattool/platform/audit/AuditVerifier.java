package ae.vattool.platform.audit;

import ae.vattool.platform.tenancy.RequestContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Re-walks a tenant's chain and reports the first place it no longer adds up. */
@Service
public class AuditVerifier {

    private final JdbcClient jdbc;

    public AuditVerifier(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Result(boolean intact, long eventsChecked, Long firstBrokenSeq, String problem, String headHash) {}

    @Transactional(readOnly = true)
    public Result verify() {
        UUID tenant = RequestContext.requireTenant();
        List<Result> failure = new ArrayList<>(1);
        long[] checked = {0};
        String[] prev = {AuditHash.GENESIS};
        long[] expected = {1};

        RowCallbackHandler walk = (ResultSet rs) -> {
            if (!failure.isEmpty()) {
                return;
            }
            long seq = rs.getLong("seq");
            checked[0]++;
            if (seq != expected[0]) {
                failure.add(new Result(false, checked[0], expected[0], "Event " + expected[0] + " is missing", null));
                return;
            }
            String prevHash = rs.getString("prev_hash");
            if (!prev[0].equals(prevHash)) {
                failure.add(new Result(false, checked[0], seq, "Link to the previous event is broken", null));
                return;
            }
            String stored = rs.getString("hash");
            if (!stored.equals(AuditHash.hash(fields(rs, tenant, seq, prevHash)))) {
                failure.add(new Result(false, checked[0], seq, "Event contents do not match its hash", null));
                return;
            }
            prev[0] = stored;
            expected[0] = seq + 1;
        };
        jdbc.sql("SELECT * FROM audit_event ORDER BY seq").query(walk);
        if (!failure.isEmpty()) {
            return failure.getFirst();
        }

        AuditLog.Head head = jdbc.sql("SELECT last_seq, last_hash FROM audit_chain_head WHERE tenant_id = ?")
                .param(tenant)
                .query(AuditLog.Head.class)
                .optional()
                .orElse(new AuditLog.Head(0, AuditHash.GENESIS));
        if (head.lastSeq() != expected[0] - 1 || !head.lastHash().equals(prev[0])) {
            // Rows removed from the end leave a valid chain, but it no longer reaches the head.
            return new Result(false, checked[0], expected[0],
                    "Chain does not reach the recorded head: events may have been removed", head.lastHash());
        }
        return new Result(true, checked[0], null, null, head.lastHash());
    }

    private static AuditHash.Fields fields(ResultSet rs, UUID tenant, long seq, String prevHash) throws SQLException {
        String entity = rs.getString("entity_id");
        return new AuditHash.Fields(
                tenant, seq,
                rs.getTimestamp("occurred_at").toInstant().truncatedTo(ChronoUnit.MICROS),
                rs.getString("actor_type"), rs.getString("actor_id"), rs.getString("action"),
                rs.getString("object_type"), rs.getString("object_id"),
                entity == null ? null : UUID.fromString(entity),
                rs.getString("before_json"), rs.getString("after_json"), rs.getString("reason"), prevHash);
    }
}
