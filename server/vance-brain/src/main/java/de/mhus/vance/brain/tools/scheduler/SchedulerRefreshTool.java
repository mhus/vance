package de.mhus.vance.brain.tools.scheduler;

import de.mhus.vance.brain.scheduler.SchedulerService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SchedulerRefreshTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<>(),
            "required", List.of());

    private final SchedulerService schedulerService;

    @Override public String name() { return "scheduler_refresh"; }

    @Override public String description() {
        return "Re-read every scheduler in the current project from the "
                + "document layer and re-register them with the cron registry. "
                + "Use after bulk edits made outside the scheduler tools.";
    }

    @Override public boolean primary() { return false; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public Set<String> labels() { return Set.of("admin", "scheduler"); }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.projectId() == null) {
            throw new ToolException("scheduler_refresh requires a project scope");
        }
        int registered = schedulerService.refresh(ctx.tenantId(), ctx.projectId());
        return Map.of("registered", registered);
    }
}
