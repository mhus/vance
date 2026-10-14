package de.mhus.vance.shared.oauthserver;

import de.mhus.vance.shared.project.maintenance.MappedProjectDataHandler;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Pending authorization requests for a project door. Minutes-lived; deleted
 * with the project and carried along on rename by the inherited defaults —
 * the grant that a carried request might still produce is pinned to the new
 * name, which is the project the user is looking at.
 */
@Component
public class OAuthServerRequestProjectDataHandler extends MappedProjectDataHandler {

    public OAuthServerRequestProjectDataHandler(MongoTemplate mongoTemplate) {
        super(mongoTemplate);
    }

    @Override
    public String id() {
        return "oauth-server-requests";
    }

    @Override
    public int order() {
        return 800;
    }

    @Override
    protected List<Class<?>> entityTypes() {
        return List.of(OAuthServerAuthRequestDocument.class);
    }
}
