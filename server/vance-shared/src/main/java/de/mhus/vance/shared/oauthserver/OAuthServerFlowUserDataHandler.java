package de.mhus.vance.shared.oauthserver;

import de.mhus.vance.shared.user.maintenance.MappedUserDataHandler;
import de.mhus.vance.shared.user.maintenance.UserReference;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * In-flight authorization requests and issued codes of a user — minutes-lived,
 * TTL-evicted anyway, but an unredeemed code is a credential and goes with the
 * account like the OAuth client's states do.
 */
@Component
public class OAuthServerFlowUserDataHandler extends MappedUserDataHandler {

    public OAuthServerFlowUserDataHandler(MongoTemplate mongoTemplate) {
        super(mongoTemplate);
    }

    @Override
    public String id() {
        return "oauth-server-flows";
    }

    @Override
    public int order() {
        return 800;
    }

    @Override
    protected List<Class<?>> entityTypes() {
        return List.of(OAuthServerAuthRequestDocument.class, OAuthServerCodeDocument.class);
    }

    @Override
    protected String userField() {
        return "userId";
    }

    @Override
    protected UserReference reference() {
        return UserReference.OWNED;
    }
}
