package de.mhus.vance.shared.thinkprocess;

import de.mhus.vance.shared.project.maintenance.MappedProjectDataHandler;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Think processes carry {@code projectId} directly. Their pending queue is
 * embedded in the document and goes with it; what lives in separate collections
 * — engine messages, Marvin task nodes — is keyed on the process id and is
 * therefore reached before this handler runs.
 */
@Component
public class ThinkProcessProjectDataHandler extends MappedProjectDataHandler {

    public ThinkProcessProjectDataHandler(MongoTemplate mongoTemplate) {
        super(mongoTemplate);
    }

    @Override
    public String id() {
        return "think-processes";
    }

    @Override
    public int order() {
        return 1000;
    }

    @Override
    protected List<Class<?>> entityTypes() {
        return List.of(ThinkProcessDocument.class);
    }
}
