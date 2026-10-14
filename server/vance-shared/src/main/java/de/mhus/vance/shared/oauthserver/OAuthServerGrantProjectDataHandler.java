package de.mhus.vance.shared.oauthserver;

import de.mhus.vance.shared.project.maintenance.MappedProjectDataHandler;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
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
 *
 * <p>Both paths revoke through {@link OAuthServerGrantService} rather than
 * stamping rows here: the revocation is audited and announced
 * ({@link OAuthGrantRevokedEvent}), so open connections of the grant end now
 * and not when the row is gone.
 */
@Component
public class OAuthServerGrantProjectDataHandler extends MappedProjectDataHandler {

    private final OAuthServerGrantService grantService;

    public OAuthServerGrantProjectDataHandler(MongoTemplate mongoTemplate, OAuthServerGrantService grantService) {
        super(mongoTemplate);
        this.grantService = grantService;
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
    public long delete(String tenantId, String projectId) {
        grantService.revokeAllForProject(tenantId, projectId, "project-deleted");
        return super.delete(tenantId, projectId);
    }

    @Override
    public long rename(String tenantId, String projectId, String newProjectId) {
        grantService.revokeAllForProject(tenantId, projectId, "project-renamed");
        return super.rename(tenantId, projectId, newProjectId);
    }
}
