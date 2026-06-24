package de.mhus.vance.brain.usage;

import de.mhus.vance.api.insights.UsageReportDto;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for the LLM usage / cost reports. All endpoints are
 * tenant-scoped and require {@link Action#ADMIN} on the tenant.
 *
 * <p>Time window is inclusive of {@code from}, exclusive of {@code to}.
 * Both default to the trailing 30 days when omitted, so the UI can
 * hit the endpoints without arguments and get a sensible default
 * view.
 */
@RestController
@RequestMapping("/brain/{tenant}/usage")
@RequiredArgsConstructor
@Slf4j
public class LlmUsageReportController {

    private final LlmUsageReportService reportService;
    private final RequestAuthority authority;

    @GetMapping("/summary")
    public UsageReportDto summary(
            @PathVariable("tenant") String tenant,
            @RequestParam(value = "from", required = false) @Nullable Instant from,
            @RequestParam(value = "to", required = false) @Nullable Instant to,
            @RequestParam(value = "groupBy", required = false, defaultValue = "day") String groupBy,
            @RequestParam(value = "projectId", required = false) @Nullable String projectId,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Tenant(tenant), Action.ADMIN);
        Window w = window(from, to);
        return reportService.summary(tenant, w.from, w.to, groupBy, projectId);
    }

    @GetMapping("/by-project")
    public UsageReportDto byProject(
            @PathVariable("tenant") String tenant,
            @RequestParam(value = "from", required = false) @Nullable Instant from,
            @RequestParam(value = "to", required = false) @Nullable Instant to,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Tenant(tenant), Action.ADMIN);
        Window w = window(from, to);
        return reportService.byProject(tenant, w.from, w.to);
    }

    @GetMapping("/by-model")
    public UsageReportDto byModel(
            @PathVariable("tenant") String tenant,
            @RequestParam(value = "from", required = false) @Nullable Instant from,
            @RequestParam(value = "to", required = false) @Nullable Instant to,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Tenant(tenant), Action.ADMIN);
        Window w = window(from, to);
        return reportService.byModel(tenant, w.from, w.to);
    }

    private static Window window(@Nullable Instant from, @Nullable Instant to) {
        Instant effectiveTo = to == null ? Instant.now() : to;
        Instant effectiveFrom = from == null
                ? effectiveTo.minus(30, ChronoUnit.DAYS)
                : from;
        if (!effectiveFrom.isBefore(effectiveTo)) {
            throw new IllegalArgumentException(
                    "from (" + effectiveFrom + ") must be before to (" + effectiveTo + ")");
        }
        return new Window(effectiveFrom, effectiveTo);
    }

    private record Window(Instant from, Instant to) {}
}
