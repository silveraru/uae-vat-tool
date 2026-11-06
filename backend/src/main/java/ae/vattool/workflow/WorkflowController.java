package ae.vattool.workflow;

import ae.vattool.masterdata.PeriodCalendar;
import ae.vattool.masterdata.TaxPeriod;
import ae.vattool.platform.security.AccessControl;
import ae.vattool.platform.security.Permission;
import ae.vattool.platform.tenancy.RequestContext;
import ae.vattool.platform.web.DomainException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class WorkflowController {

    private final WorkflowService workflow;
    private final CommentService comments;
    private final PeriodCalendar calendar;
    private final AccessControl access;
    private final JdbcClient jdbc;

    public WorkflowController(WorkflowService workflow, CommentService comments, PeriodCalendar calendar, AccessControl access, JdbcClient jdbc) {
        this.workflow = workflow;
        this.comments = comments;
        this.calendar = calendar;
        this.access = access;
        this.jdbc = jdbc;
    }

    public record Note(String note) {}

    public record Reason(String reason) {}

    public record CommentInput(String objectType, String objectId, UUID parentId, String body) {}

    @GetMapping("/periods/{id}/workflow")
    public WorkflowService.Status status(@PathVariable UUID id) {
        TaxPeriod p = calendar.get(id);
        access.requireReadPeriod(p.entityId(), p.isLocked());
        return workflow.status(id);
    }

    @PostMapping("/periods/{id}/submit")
    public WorkflowService.Status submit(@PathVariable UUID id, @RequestBody(required = false) Note in) {
        access.require(calendar.get(id).entityId(), Permission.SUBMIT_FOR_REVIEW);
        return workflow.submit(id, in == null ? null : in.note());
    }

    @PostMapping("/periods/{id}/review")
    public WorkflowService.Status review(@PathVariable UUID id, @RequestBody(required = false) Note in) {
        access.require(calendar.get(id).entityId(), Permission.REVIEW_SIGNOFF);
        workflow.refresh(id);
        return workflow.review(id, in == null ? null : in.note());
    }

    @PostMapping("/periods/{id}/partner-signoff")
    public WorkflowService.Status partner(@PathVariable UUID id, @RequestBody(required = false) Note in) {
        access.require(calendar.get(id).entityId(), Permission.PARTNER_SIGNOFF);
        workflow.refresh(id);
        return workflow.partnerSignoff(id, in == null ? null : in.note());
    }

    @PostMapping("/periods/{id}/approve")
    public WorkflowService.Status approve(@PathVariable UUID id, @RequestBody(required = false) Note in) {
        access.require(calendar.get(id).entityId(), Permission.APPROVE_AND_LOCK);
        workflow.refresh(id);
        return workflow.approve(id, in == null ? null : in.note());
    }

    @PostMapping("/periods/{id}/send-back")
    public WorkflowService.Status sendBack(@PathVariable UUID id, @RequestBody Reason in) {
        UUID entity = calendar.get(id).entityId();
        if (!access.can(entity, Permission.REVIEW_SIGNOFF) && !access.can(entity, Permission.APPROVE_AND_LOCK)
                && !access.can(entity, Permission.PARTNER_SIGNOFF)) {
            throw DomainException.forbidden("Only a reviewer, partner or approver can send a return back");
        }
        return workflow.reject(id, in.reason());
    }

    @PostMapping("/periods/{id}/withdraw-approval")
    public WorkflowService.Status withdraw(@PathVariable UUID id, @RequestBody Reason in) {
        access.require(calendar.get(id).entityId(), Permission.APPROVE_AND_LOCK);
        return workflow.withdrawApproval(id, in.reason());
    }

    @PostMapping("/periods/{id}/comments")
    public CommentService.Comment comment(@PathVariable UUID id, @RequestBody CommentInput in) {
        TaxPeriod p = calendar.get(id);
        access.require(p.entityId(), Permission.COMMENT);
        return comments.add(p, in.objectType(), in.objectId(), in.parentId(), in.body());
    }

    @GetMapping("/periods/{id}/comments")
    public List<CommentService.Comment> comments(@PathVariable UUID id, @RequestParam(required = false) String objectType,
                                                 @RequestParam(required = false) String objectId) {
        TaxPeriod p = calendar.get(id);
        access.requireReadPeriod(p.entityId(), p.isLocked());
        return comments.list(id, objectType, objectId);
    }

    @GetMapping("/notifications")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> notifications() {
        return jdbc.sql("""
                SELECT id, kind, title, body, entity_id, period_id, read_at, created_at FROM notification
                WHERE user_id = ? ORDER BY created_at DESC LIMIT 100
                """).param(RequestContext.requireUserId()).query().listOfRows();
    }

    @PostMapping("/notifications/{id}/read")
    @Transactional
    public void read(@PathVariable UUID id) {
        jdbc.sql("UPDATE notification SET read_at = now() WHERE id = ? AND user_id = ?").params(id, RequestContext.requireUserId()).update();
    }
}
