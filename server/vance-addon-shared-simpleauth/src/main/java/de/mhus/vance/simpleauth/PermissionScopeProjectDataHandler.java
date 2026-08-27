package de.mhus.vance.simpleauth;

import de.mhus.vance.shared.project.maintenance.MappedProjectDataHandler;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Project-scoped permission grants and the requests for them.
 *
 * <p>The handler that most obviously has to exist, and the one an addon
 * contributes rather than the core: which authorization provider a deployment
 * runs is a choice, and only the provider knows where it keeps its grants. An
 * enterprise governor contributes its own — or none, because its grants live
 * outside Vance entirely.
 *
 * <p>Both directions are security-relevant, which is why this is not merely
 * tidiness. A grant left behind on delete is inherited by the next project
 * created under that name, handing someone access they were never given. A
 * grant not carried over on rename silently locks out everyone but the tenant
 * admin — the mirror-image failure, and the reason rename is a rewrite here
 * rather than a cleanup.
 */
@Component
public class PermissionScopeProjectDataHandler extends MappedProjectDataHandler {

    public PermissionScopeProjectDataHandler(MongoTemplate mongoTemplate) {
        super(mongoTemplate);
    }

    @Override
    public String id() {
        return "permission-grants";
    }

    @Override
    public int order() {
        return 2400;
    }

    @Override
    protected List<Class<?>> entityTypes() {
        return List.of(PermissionGrantDocument.class, PermissionRequestDocument.class);
    }

    @Override
    protected String projectField() {
        return "scopeId";
    }

    @Override
    protected Query scope(String tenantId, String projectId) {
        return new Query(Criteria.where("tenantId").is(tenantId)
                .and("scopeType").is(GrantScopeType.PROJECT)
                .and("scopeId").is(projectId));
    }
}
