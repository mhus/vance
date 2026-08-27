package de.mhus.vance.shared.oauth;

import de.mhus.vance.shared.user.maintenance.MappedUserDataHandler;
import de.mhus.vance.shared.user.maintenance.UserReference;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * In-flight OAuth handshakes of this account.
 *
 * <p>Short-lived and self-expiring, so this is rarely more than zero rows — but
 * a state row carries a code verifier, and leaving credentials-adjacent
 * material behind for an account that no longer exists is not something to
 * leave to a TTL.
 */
@Component
public class OAuthStateUserDataHandler extends MappedUserDataHandler {

    public OAuthStateUserDataHandler(MongoTemplate mongoTemplate) {
        super(mongoTemplate);
    }

    @Override
    public String id() {
        return "oauth-states";
    }

    @Override
    public int order() {
        return 800;
    }

    @Override
    protected List<Class<?>> entityTypes() {
        return List.of(OAuthStateDocument.class);
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
