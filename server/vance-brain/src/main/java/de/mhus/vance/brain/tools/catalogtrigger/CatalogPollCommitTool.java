package de.mhus.vance.brain.tools.catalogtrigger;

import de.mhus.vance.brain.servicecatalog.CatalogPollService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.ToolLabels;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Remembers what {@code catalog_poll} found, once the entries have been taken care of. */
@Component
@RequiredArgsConstructor
public class CatalogPollCommitTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties",
                    Map.of(
                            "name", Map.of("type", "string", "description", "Name of the trigger."),
                            "pending", Map.of("type", "object", "description", "The pending value of catalog_poll.")),
            "required", List.of("name", "pending"));

    private final CatalogPollService polls;

    @Override
    public String name() {
        return "catalog_poll_commit";
    }

    @Override
    public String description() {
        return "Remember what catalog_poll found so the entries are not delivered again. "
                + "Used by the workflow generated for a polling trigger, after it took care of the entries.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public boolean deferred() {
        return true;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Set<String> labels() {
        return Set.of(ToolLabels.OPERATOR, "events");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.projectId() == null) throw new ToolException("catalog_poll_commit requires a project scope");
        Object name = params == null ? null : params.get("name");
        if (!(name instanceof String n) || n.isBlank()) {
            throw new ToolException("'name' is required and must be a non-empty string");
        }
        if (params.get("pending") instanceof Map<?, ?> p && p.get("ids") instanceof List<?> ids) {
            List<String> remembered = new ArrayList<>();
            for (Object id : ids) remembered.add(String.valueOf(id));
            Object cursor = p.get("cursor");
            polls.commit(
                    ctx.tenantId(),
                    ctx.projectId(),
                    n,
                    new CatalogPollService.Pending(remembered, cursor == null ? null : String.valueOf(cursor)));
            return Map.of("committed", true, "remembered", remembered.size());
        }
        // A poll that failed has nothing to remember; committing nothing is not an error.
        return Map.of("committed", false);
    }
}
