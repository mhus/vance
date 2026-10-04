package de.mhus.vance.brain.trillian.tools;

import de.mhus.vance.brain.trillian.TrillianScheduleStore;
import de.mhus.vance.brain.trillian.TrillianUserEngine;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Drops an appointment from the loop's schedule. */
@Component
@RequiredArgsConstructor
public class ScheduleRemoveTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties",
                    Map.of(
                            "name",
                            Map.of(
                                    "type", "string",
                                    "description", "The schedule entry to remove.")),
            "required", List.of("name"));

    private final TrillianScheduleStore scheduleStore;

    @Override
    public String name() {
        return "schedule_remove";
    }

    @Override
    public String description() {
        return "Remove an appointment from your schedule. To park it instead "
                + "of losing it, use schedule_update with enabled=false.";
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
        return Set.of("executive");
    }

    @Override
    public Set<String> requiresEngineRoles() {
        return Set.of(TrillianUserEngine.ROLE_TRILLIAN_USER);
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        Object raw = params == null ? null : params.get("name");
        if (!(raw instanceof String name) || name.isBlank()) {
            throw new ToolException("'name' is required");
        }
        String key = name.trim();
        if (scheduleStore.find(ctx.tenantId(), ctx.projectId(), key).isEmpty()) {
            throw new ToolException("schedule_remove: no entry named '" + key + "'");
        }
        scheduleStore.delete(ctx.tenantId(), ctx.projectId(), key);
        return Map.of("name", key, "status", "removed");
    }
}
