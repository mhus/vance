package de.mhus.vance.shared.toolhealth;

import de.mhus.vance.api.toolhealth.ToolHealthScope;
import de.mhus.vance.shared.project.maintenance.MappedProjectDataHandler;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Tool health and cooldowns. Like settings, the project sits in a
 * {@code (scope, scopeId)} pair rather than in a {@code projectId} field.
 *
 * <p>Carrying it over on rename matters more than the row count suggests: a
 * cooldown that loses its scope stops applying, and the first thing the renamed
 * project does is hammer the endpoint that put it there.
 */
@Component
public class ToolHealthProjectDataHandler extends MappedProjectDataHandler {

    public ToolHealthProjectDataHandler(MongoTemplate mongoTemplate) {
        super(mongoTemplate);
    }

    @Override
    public String id() {
        return "tool-health";
    }

    @Override
    public int order() {
        return 2200;
    }

    @Override
    protected List<Class<?>> entityTypes() {
        return List.of(ToolHealthDocument.class);
    }

    @Override
    protected String projectField() {
        return "scopeId";
    }

    @Override
    protected Query scope(String tenantId, String projectId) {
        return new Query(Criteria.where("tenantId").is(tenantId)
                .and("scope").is(ToolHealthScope.PROJECT)
                .and("scopeId").is(projectId));
    }
}
