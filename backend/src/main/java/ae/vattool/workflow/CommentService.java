package ae.vattool.workflow;

import ae.vattool.masterdata.TaxPeriod;
import ae.vattool.platform.tenancy.RequestContext;
import ae.vattool.platform.web.DomainException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Comment threads on boxes, lines, exceptions and the period (WF-02). Comments are never edited. */
@Service
public class CommentService {

    public record Comment(UUID id, String objectType, String objectId, UUID parentId, UUID authorId, String authorName, String body,
                          OffsetDateTime createdAt) {}

    private static final Set<String> TYPES = Set.of("PERIOD", "BOX", "LINE", "EXCEPTION");

    private final JdbcClient jdbc;

    public CommentService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Comment add(TaxPeriod period, String objectType, String objectId, UUID parentId, String body) {
        if (objectType == null || !TYPES.contains(objectType)) {
            throw DomainException.invalid("COMMENT_TYPE_INVALID", "Comments can be on " + TYPES);
        }
        if (body == null || body.isBlank()) {
            throw DomainException.invalid("COMMENT_EMPTY", "A comment cannot be empty");
        }
        if (objectId == null || objectId.isBlank()) {
            throw DomainException.invalid("COMMENT_TARGET_REQUIRED", "A comment needs something to attach to");
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO comment (id, tenant_id, entity_id, period_id, object_type, object_id, parent_id, author_id, body)
                VALUES (:id, :tenant, :entity, :period, :type, :object, :parent, :author, :body)
                """)
                .param("id", id).param("tenant", RequestContext.requireTenant()).param("entity", period.entityId())
                .param("period", period.id()).param("type", objectType).param("object", objectId).param("parent", parentId)
                .param("author", RequestContext.requireUserId()).param("body", body.trim())
                .update();
        return list(period.id(), objectType, objectId).stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<Comment> list(UUID periodId, String objectType, String objectId) {
        return jdbc.sql("""
                SELECT c.id, c.object_type, c.object_id, c.parent_id, c.author_id, u.display_name AS author_name, c.body, c.created_at
                FROM comment c JOIN app_user u ON u.id = c.author_id
                WHERE c.period_id = :period
                  AND (CAST(:type AS text) IS NULL OR c.object_type = :type)
                  AND (CAST(:object AS text) IS NULL OR c.object_id = :object)
                ORDER BY c.created_at
                """).param("period", periodId).param("type", objectType).param("object", objectId).query(Comment.class).list();
    }
}
