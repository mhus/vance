package de.mhus.vance.shared.oauthserver;

import de.mhus.vance.shared.project.maintenance.MappedProjectDataHandler;
import java.time.Instant;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * A project-pinned grant ({@code mcp-project}) reaches nothing once its
 * project is gone; deleting the row revokes it, leaving it would hand the next
 * project of the same name a stranger's live credential.
 *
 * <p><b>Rename revokes</b>, exactly like {@code IntegrationTokenProjectDataHandler}:
 * the pin is a signed claim and cannot follow, and the grant service already
 * refuses a pin that no longer matches the row — the stamp makes the owner's
 * list say so. Unpinned grants ({@code projectId == null}) are not matched.
 */
@Component
public class OAuthServerGrantProjectDataHandler extends MappedProjectDataHandler {

    public OAuthServerGrantProjectDataHandler(MongoTemplate mongoTemplate) {
        super(mongoTemplate);
    }

    @Override
    public String id() {
        return "oauth-server-grants";
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    protected List<Class<?>> entityTypes() {
        return List.of(OAuthServerGrantDocument.class);
    }

    @Override
    public long rename(String tenantId, String projectId, String newProjectId) {
        mongoTemplate.updateMulti(
                scope(tenantId, projectId)
                        .addCriteria(Criteria.where("revokedAt").is(null)),
                new Update().set("revokedAt", Instant.now()).set("revokeReason", "project-renamed"),
                OAuthServerGrantDocument.class);
        return super.rename(tenantId, projectId, newProjectId);
    }
}
