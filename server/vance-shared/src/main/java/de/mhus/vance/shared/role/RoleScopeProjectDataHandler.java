package de.mhus.vance.shared.role;

import de.mhus.vance.api.roles.RoleScopeType;
import de.mhus.vance.shared.project.maintenance.MappedProjectDataHandler;
import de.mhus.vance.shared.role.request.RoleRequestDocument;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Project memberships and the requests for them.
 *
 * <p>Both directions are security-relevant, which is why this is not merely
 * tidiness. A membership left behind on delete is inherited by the next project
 * created under that name, handing someone access they were never given. A
 * membership not carried over on rename silently locks out everyone but the
 * tenant admins — the mirror-image failure, and the reason rename is a rewrite
 * here rather than a cleanup.
 */
@Component
public class RoleScopeProjectDataHandler extends MappedProjectDataHandler {

    public RoleScopeProjectDataHandler(MongoTemplate mongoTemplate) {
        super(mongoTemplate);
    }

    @Override
    public String id() {
        return "role-assignments";
    }

    @Override
    public int order() {
        return 2400;
    }

    @Override
    protected List<Class<?>> entityTypes() {
        return List.of(RoleAssignmentDocument.class, RoleRequestDocument.class);
    }

    @Override
    protected String projectField() {
        return "scopeId";
    }

    @Override
    protected Query scope(String tenantId, String projectId) {
        return new Query(Criteria.where("tenantId")
                .is(tenantId)
                .and("scopeType")
                .is(RoleScopeType.PROJECT)
                .and("scopeId")
                .is(projectId));
    }
}
