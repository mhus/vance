package de.mhus.vance.brain.tools.scheduler;

import de.mhus.vance.shared.scheduler.ResolvedScheduler;
import de.mhus.vance.shared.scheduler.SchedulerLoader;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SchedulerListTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", new LinkedHashMap<>(),
            "required", List.of());

    private final SchedulerLoader loader;
    private final SchedulerToolSupport support;

    @Override public String name() { return "scheduler_list"; }

    @Override public String description() {
        return "List all schedulers visible to the current project — "
                + "from both the project itself and the tenant-wide "
                + "_vance/scheduler/ folder. Returns name, description, "
                + "cron, recipe, enabled flag, last run, next run.";
    }

    @Override public boolean primary() { return false; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public Set<String> labels() { return Set.of("read-only", "scheduler"); }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.projectId() == null) {
            throw new ToolException("scheduler_list requires a project scope");
        }
        List<ResolvedScheduler> entries = loader.listAll(ctx.tenantId(), ctx.projectId());
        List<Map<String, Object>> out = new ArrayList<>(entries.size());
        for (ResolvedScheduler r : entries) {
            // Hidden entries are invisible to LLM list — protected
            // entries stay listed so the agent can reason about them
            // ("there is already a nightly-cleanup running, no need to
            // spawn one"). See specification/scheduler.md §10b.
            if (r.isLlmHidden()) continue;
            out.add(support.shape(ctx.tenantId(), ctx.projectId(), r));
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("schedulers", out);
        return resp;
    }
}
