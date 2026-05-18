package de.mhus.vance.brain.tools.scheduler;

import de.mhus.vance.brain.scheduler.SchedulerService;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@de.mhus.vance.toolpack.SpawnTool
public class SchedulerDeleteTool implements Tool {

    private static final Map<String, Object> SCHEMA;
    static {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("name", Map.of(
                "type", "string",
                "description", "Scheduler name to remove."));
        SCHEMA = Map.of(
                "type", "object",
                "properties", props,
                "required", List.of("name"));
    }

    private final SchedulerToolSupport support;
    private final DocumentService documentService;
    private final SchedulerService schedulerService;

    @Override public String name() { return "scheduler_delete"; }

    @Override public String description() {
        return "Remove a scheduler from the current project. Cancels its cron "
                + "registration; previously spawned processes continue to run "
                + "in their system session and terminate normally. The "
                + "system session itself is left for audit and event-history.";
    }

    @Override public boolean primary() { return false; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public Set<String> labels() { return Set.of("write", "scheduler"); }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.projectId() == null) {
            throw new ToolException("scheduler_delete requires a project scope");
        }
        String name = SchedulerToolSupport.normalizeName(stringOrThrow(params, "name"));

        support.guardMutation(ctx.tenantId(), ctx.projectId(), name);

        boolean existed = documentService.findByPath(
                ctx.tenantId(), ctx.projectId(),
                SchedulerToolSupport.pathFor(name)).isPresent();
        support.deleteByPath(ctx.tenantId(), ctx.projectId(), name);
        schedulerService.refreshOne(ctx.tenantId(), ctx.projectId(), name);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("deleted", existed);
        return out;
    }

    private static String stringOrThrow(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("'" + key + "' is required and must be a non-empty string");
        }
        return s;
    }
}
