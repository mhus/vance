package de.mhus.vance.shared.oauthserver;

import de.mhus.vance.shared.user.maintenance.MappedUserDataHandler;
import de.mhus.vance.shared.user.maintenance.UserReference;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * An OAuth grant exists because one account said yes, and its tokens act as
 * that account — {@link UserReference#OWNED}, and early (400, next to the
 * integration tokens) for the same reason: the row is the revocation channel,
 * so a deletion that fails halfway must not leave a working credential
 * behind. Deleting the row is revoking every token of the grant.
 */
@Component
public class OAuthServerGrantUserDataHandler extends MappedUserDataHandler {

    public OAuthServerGrantUserDataHandler(MongoTemplate mongoTemplate) {
        super(mongoTemplate);
    }

    @Override
    public String id() {
        return "oauth-server-grants";
    }

    @Override
    public int order() {
        return 400;
    }

    @Override
    protected List<Class<?>> entityTypes() {
        return List.of(OAuthServerGrantDocument.class);
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
