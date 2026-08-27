package de.mhus.vance.shared.settings;

import de.mhus.vance.shared.project.maintenance.MappedProjectDataHandler;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Settings owned by the project — the {@code project} layer of the cascade.
 *
 * <p>The project is not in a {@code projectId} field here but in the
 * {@code (referenceType, referenceId)} pair that carries every scope, so the
 * predicate is overridden rather than inherited. Consequence worth stating: a
 * rename rewrites {@code referenceId}, which is what keeps encrypted values
 * readable — they are stored against the scope, and a scope nobody resolves is
 * a credential nobody can decrypt.
 */
@Component
public class SettingProjectDataHandler extends MappedProjectDataHandler {

    public SettingProjectDataHandler(MongoTemplate mongoTemplate) {
        super(mongoTemplate);
    }

    @Override
    public String id() {
        return "settings";
    }

    @Override
    public int order() {
        return 2100;
    }

    @Override
    protected List<Class<?>> entityTypes() {
        return List.of(SettingDocument.class);
    }

    @Override
    protected String projectField() {
        return "referenceId";
    }

    @Override
    protected Query scope(String tenantId, String projectId) {
        return new Query(Criteria.where("tenantId").is(tenantId)
                .and("referenceType").is(SettingService.SCOPE_PROJECT)
                .and("referenceId").is(projectId));
    }
}
