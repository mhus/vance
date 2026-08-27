package de.mhus.vance.shared.memory;

import de.mhus.vance.shared.project.maintenance.MappedProjectDataHandler;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Compacted conversation memory. Scoped to the project it was compacted in —
 * the memory cascade never reaches sideways, so nothing outside the project
 * reads these rows.
 */
@Component
public class MemoryProjectDataHandler extends MappedProjectDataHandler {

    public MemoryProjectDataHandler(MongoTemplate mongoTemplate) {
        super(mongoTemplate);
    }

    @Override
    public String id() {
        return "memories";
    }

    @Override
    public int order() {
        return 1100;
    }

    @Override
    protected List<Class<?>> entityTypes() {
        return List.of(MemoryDocument.class);
    }
}
