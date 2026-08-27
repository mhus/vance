package de.mhus.vance.shared.prak.audit;

import de.mhus.vance.shared.project.maintenance.MappedProjectDataHandler;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/** Prak run records — the audit trail of script executions inside the project. */
@Component
public class PrakRunProjectDataHandler extends MappedProjectDataHandler {

    public PrakRunProjectDataHandler(MongoTemplate mongoTemplate) {
        super(mongoTemplate);
    }

    @Override
    public String id() {
        return "prak-runs";
    }

    @Override
    public int order() {
        return 1800;
    }

    @Override
    protected List<Class<?>> entityTypes() {
        return List.of(PrakRunRecord.class);
    }
}
