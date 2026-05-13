package de.mhus.vance.brain.tools.scheduler;

import de.mhus.vance.brain.scheduler.SchedulerService;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.scheduler.ResolvedScheduler;
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
public class SchedulerUpdateTool implements Tool {

    private static final Map<String, Object> SCHEMA;
    static {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("name", Map.of(
                "type", "string",
                "description", "Scheduler name (must already exist)."));
        props.put("yaml", Map.of(
                "type", "string",
                "description", "Replacement YAML body. Validated server-side; "
                        + "rejected if required fields are missing."));
        SCHEMA = Map.of(
                "type", "object",
                "properties", props,
                "required", List.of("name", "yaml"));
    }

    private final SchedulerToolSupport support;
    private final DocumentService documentService;
    private final SchedulerService schedulerService;

    @Override public String name() { return "scheduler_update"; }

    @Override public String description() {
        return "Replace the YAML of an existing scheduler in the current project "
                + "and re-register it with the cron registry. Fails if the "
                + "scheduler does not exist — use scheduler_create instead.";
    }

    @Override public boolean primary() { return false; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public Set<String> labels() { return Set.of("write", "scheduler"); }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.projectId() == null) {
            throw new ToolException("scheduler_update requires a project scope");
        }
        String name = SchedulerToolSupport.normalizeName(stringOrThrow(params, "name"));
        String yaml = stringOrThrow(params, "yaml");

        support.guardMutation(ctx.tenantId(), ctx.projectId(), name);

        if (documentService.findByPath(ctx.tenantId(), ctx.projectId(),
                SchedulerToolSupport.pathFor(name)).isEmpty()) {
            throw new ToolException("scheduler '" + name
                    + "' does not exist in this project — use scheduler_create");
        }

        ResolvedScheduler validated = support.parseOrThrow(name, yaml);
        support.upsert(ctx.tenantId(), ctx.projectId(), name, yaml, ctx.userId());
        boolean registered = schedulerService.refreshOne(ctx.tenantId(), ctx.projectId(), name);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("enabled", validated.enabled());
        out.put("registered", registered);
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
