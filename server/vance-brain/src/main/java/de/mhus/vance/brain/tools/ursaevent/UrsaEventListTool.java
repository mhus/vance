package de.mhus.vance.brain.tools.ursaevent;

import de.mhus.vance.shared.ursaevents.ResolvedUrsaEvent;
import de.mhus.vance.shared.ursaevents.UrsaEventLoader;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Lists all events visible to the current project — both project-local
 * entries and cascade-resolved ones from {@code _tenant}.
 */
@Component
@RequiredArgsConstructor
public class UrsaEventListTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(),
            "required", List.of());

    private final UrsaEventLoader loader;
    private final UrsaEventToolSupport support;

    @Override public String name() { return "event_list"; }

    @Override public String description() {
        return "List all events visible to the current project (project-local "
                + "plus cascade-resolved from _tenant), sorted by name.";
    }

    @Override public boolean primary() { return false; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public Set<String> labels() { return Set.of("read-only", "events"); }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.projectId() == null) {
            throw new ToolException("event_list requires a project scope");
        }
        List<ResolvedUrsaEvent> entries = loader.listAll(ctx.tenantId(), ctx.projectId());
        List<Map<String, Object>> shaped = new ArrayList<>(entries.size());
        for (ResolvedUrsaEvent r : entries) {
            shaped.add(support.shape(r));
        }
        shaped.sort(Comparator.comparing(m -> String.valueOf(m.get("name"))));
        return Map.of("events", shaped, "count", shaped.size());
    }
}
