package de.mhus.vance.brain.tools.scheduler;

import de.mhus.vance.shared.scheduler.ResolvedScheduler;
import de.mhus.vance.shared.scheduler.SchedulerLoader;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SchedulerGetTool implements Tool {

    private static final Map<String, Object> SCHEMA;
    static {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("name", Map.of(
                "type", "string",
                "description", "Scheduler name (without .yaml suffix)."));
        SCHEMA = Map.of(
                "type", "object",
                "properties", props,
                "required", List.of("name"));
    }

    private final SchedulerLoader loader;
    private final SchedulerToolSupport support;

    @Override public String name() { return "scheduler_get"; }

    @Override public String description() {
        return "Return the full YAML body and parsed fields of one scheduler. "
                + "Pass the name without the .yaml suffix.";
    }

    @Override public boolean primary() { return false; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public Set<String> labels() { return Set.of("read-only", "scheduler"); }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.projectId() == null) {
            throw new ToolException("scheduler_get requires a project scope");
        }
        String name = SchedulerToolSupport.normalizeName(stringOrThrow(params, "name"));
        Optional<ResolvedScheduler> hit = loader.load(ctx.tenantId(), ctx.projectId(), name);
        // Hidden entries respond with the same "not found" the LLM would
        // see for a truly missing scheduler — see §10b.
        if (hit.isEmpty() || hit.get().isLlmHidden()) {
            throw new ToolException("scheduler '" + name + "' not found");
        }
        return support.shapeFull(ctx.tenantId(), ctx.projectId(), hit.get());
    }

    private static String stringOrThrow(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("'" + key + "' is required and must be a non-empty string");
        }
        return s;
    }
}
