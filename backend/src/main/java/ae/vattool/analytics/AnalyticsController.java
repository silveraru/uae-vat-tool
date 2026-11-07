package ae.vattool.analytics;

import ae.vattool.masterdata.PeriodCalendar;
import ae.vattool.masterdata.TaxPeriod;
import ae.vattool.platform.security.AccessControl;
import ae.vattool.platform.security.Permission;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AnalyticsController {

    private final AnalyticsService analytics;
    private final RegistrationWatchService watches;
    private final PeriodCalendar calendar;
    private final AccessControl access;

    public AnalyticsController(AnalyticsService analytics, RegistrationWatchService watches, PeriodCalendar calendar, AccessControl access) {
        this.analytics = analytics;
        this.watches = watches;
        this.calendar = calendar;
        this.access = access;
    }

    @GetMapping("/entities/{id}/analytics/trends")
    public List<AnalyticsService.Point> trends(@PathVariable UUID id) {
        access.require(id, Permission.VIEW);
        return analytics.trends(id);
    }

    @GetMapping("/periods/{id}/analytics/anomalies")
    public List<AnalyticsService.Anomaly> anomalies(@PathVariable UUID id) {
        TaxPeriod p = calendar.get(id);
        access.require(p.entityId(), Permission.VIEW);
        return analytics.anomalies(p);
    }

    @GetMapping("/registration-watch")
    public List<RegistrationWatchService.Position> watchList() {
        access.requireTenantWide(Permission.VIEW_PORTFOLIO);
        return watches.list();
    }

    @PostMapping("/registration-watch")
    public RegistrationWatchService.Position addWatch(@RequestBody RegistrationWatchService.WatchInput in) {
        access.requireTenantWide(Permission.VIEW_PORTFOLIO);
        return watches.add(in);
    }

    @PostMapping("/registration-watch/{id}/months")
    public RegistrationWatchService.Position recordMonth(@PathVariable UUID id, @RequestBody RegistrationWatchService.MonthInput in) {
        access.requireTenantWide(Permission.VIEW_PORTFOLIO);
        return watches.recordMonth(id, in);
    }
}
