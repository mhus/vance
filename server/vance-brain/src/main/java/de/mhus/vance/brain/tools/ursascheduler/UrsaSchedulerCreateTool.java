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
@de.mhus.vance.toolpack.SpawnTool
public class SchedulerCreateTool implements Tool {

    private static final Map<String, Object> SCHEMA;
    static {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("name", Map.of(
                "type", "string",
                "description", "Scheduler name — lowercase, alphanumeric + '_-', max 64 chars."));
        props.put("yaml", Map.of(
                "type", "string",
                "description", "Full YAML body. Must include 'description', 'cron', 'recipe'. "
                        + "Optional fields: timezone, enabled, params, initialMessage, "
                        + "runAs, overlap, tags."));
        SCHEMA = Map.of(
                "type", "object",
                "properties", props,
                "required", List.of("name", "yaml"));
    }

    private final SchedulerToolSupport support;
    private final DocumentService documentService;
    private final SchedulerService schedulerService;

    @Override public String name() { return "scheduler_create"; }

    @Override public String description() {
        return "Create a new scheduler in the current project. Fails if a "
                + "scheduler with this name already exists in the project. "
                + "Use scheduler_update to modify an existing one.";
    }

    @Override public boolean primary() { return false; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public Set<String> labels() { return Set.of("write", "scheduler"); }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.projectId() == null) {
            throw new ToolException("scheduler_create requires a project scope");
        }
        String name = SchedulerToolSupport.normalizeName(stringOrThrow(params, "name"));
        String yaml = stringOrThrow(params, "yaml");

        // Refuse if a cascade-resolved entry with this name is locked —
        // creating a project-local override would otherwise shadow a
        // protected tenant entry.
        support.guardMutation(ctx.tenantId(), ctx.projectId(), name);

        if (documentService.findByPath(ctx.tenantId(), ctx.projectId(),
                SchedulerToolSupport.pathFor(name)).isPresent()) {
            throw new ToolException("scheduler '" + name + "' already exists in this project");
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
