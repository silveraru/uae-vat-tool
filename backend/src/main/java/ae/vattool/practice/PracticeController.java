package ae.vattool.practice;

import ae.vattool.platform.security.AccessControl;
import ae.vattool.platform.security.Permission;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class PracticeController {

    private final PracticeService practice;
    private final WebhookService webhooks;
    private final AccessControl access;

    public PracticeController(PracticeService practice, WebhookService webhooks, AccessControl access) {
        this.practice = practice;
        this.webhooks = webhooks;
        this.access = access;
    }

    public record ActiveInput(boolean active) {}

    @GetMapping("/practice/workload")
    public PracticeService.Overview workload() {
        access.requireTenantWide(Permission.VIEW_PORTFOLIO);
        return practice.overview(LocalDate.now(ZoneId.of("Asia/Dubai")));
    }

    @GetMapping("/entities/{id}/assignments")
    public List<PracticeService.Assignment> assignments(@PathVariable UUID id) {
        access.require(id, Permission.VIEW);
        return practice.assignments(id);
    }

    @PostMapping("/entities/{id}/assignments")
    public List<PracticeService.Assignment> assign(@PathVariable UUID id, @RequestBody PracticeService.AssignmentInput in) {
        access.require(id, Permission.MANAGE_PRACTICE);
        return practice.assign(id, in);
    }

    @DeleteMapping("/entities/{id}/assignments/{assignmentId}")
    public List<PracticeService.Assignment> unassign(@PathVariable UUID id, @PathVariable UUID assignmentId) {
        access.require(id, Permission.MANAGE_PRACTICE);
        return practice.unassign(id, assignmentId);
    }

    @GetMapping("/practice/webhooks")
    public List<WebhookService.Endpoint> webhooks() {
        access.requireTenantWide(Permission.ADMINISTER);
        return webhooks.list();
    }

    @GetMapping("/practice/webhook-events")
    public List<String> webhookEvents() {
        access.requireTenantWide(Permission.ADMINISTER);
        return WebhookService.EVENTS.stream().sorted().toList();
    }

    @PostMapping("/practice/webhooks")
    public WebhookService.Created createWebhook(@RequestBody WebhookService.EndpointInput in) {
        access.requireTenantWide(Permission.ADMINISTER);
        return webhooks.create(in);
    }

    @PostMapping("/practice/webhooks/{id}/active")
    public WebhookService.Endpoint setActive(@PathVariable UUID id, @RequestBody ActiveInput in) {
        access.requireTenantWide(Permission.ADMINISTER);
        return webhooks.setActive(id, in.active());
    }

    @GetMapping("/practice/webhooks/{id}/deliveries")
    public List<WebhookService.Delivery> deliveries(@PathVariable UUID id) {
        access.requireTenantWide(Permission.ADMINISTER);
        return webhooks.deliveries(id);
    }
}
