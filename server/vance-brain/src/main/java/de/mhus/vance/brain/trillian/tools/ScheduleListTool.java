package de.mhus.vance.brain.trillian.tools;

import de.mhus.vance.brain.trillian.TrillianScheduleStore;
import de.mhus.vance.brain.trillian.TrillianUserEngine;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** The loop's own schedule as it stands — names, dues, recurrences. */
@Component
@RequiredArgsConstructor
public class ScheduleListTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of("type", "object", "properties", Map.of());

    private final TrillianScheduleStore scheduleStore;

    @Override
    public String name() {
        return "schedule_list";
    }

    @Override
    public String description() {
        return "List the appointments on your own schedule: name, when next, "
                + "whether repeating, enabled. What is next to come is always "
                + "in the entry itself.";
    }

    @Override
    public boolean primary() {
        return true;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Set<String> labels() {
        return Set.of(ToolLabels.INTERNAL, "read-only");
    }

    @Override
    public Set<String> requiresEngineRoles() {
        return Set.of(TrillianUserEngine.ROLE_TRILLIAN_USER);
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TrillianScheduleStore.Schedule s : scheduleStore.list(ctx.tenantId(), ctx.projectId())) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", s.name());
            if (s.label() != null) {
                row.put("label", s.label());
            }
            row.put("due", s.due().toString());
            row.put("next", s.next() == null ? "" : s.next());
            row.put("enabled", s.enabled());
            if (s.lastRun() != null) {
                row.put("lastRun", s.lastRun().toString());
            }
            rows.add(row);
        }
        return Map.of("schedules", rows);
    }
}
