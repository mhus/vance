package de.mhus.vance.shared.toolusage;

import de.mhus.vance.shared.project.maintenance.MappedProjectDataHandler;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Measured tool demand, keyed on {@code (tenant, project, recipe, tool)} — the
 * input to the tool-surface budget. Stale rows would rank a new project's tool
 * manifest by a predecessor's habits.
 */
@Component
public class ToolUsageProjectDataHandler extends MappedProjectDataHandler {

    public ToolUsageProjectDataHandler(MongoTemplate mongoTemplate) {
        super(mongoTemplate);
    }

    @Override
    public String id() {
        return "tool-usage-stats";
    }

    @Override
    public int order() {
        return 1700;
    }

    @Override
    protected List<Class<?>> entityTypes() {
        return List.of(ToolUsageDocument.class);
    }
}
