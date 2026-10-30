package de.mhus.vance.shared.user;

import de.mhus.vance.shared.user.maintenance.MappedUserDataHandler;
import de.mhus.vance.shared.user.maintenance.UserReference;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Open e-mail codes of this account.
 *
 * <p>Short-lived and evicted by a TTL index, so rarely more than a row — but a
 * code is a credential, and the next account of the same name must not inherit
 * one that was mailed to the previous owner.
 */
@Component
public class EmailLoginCodeUserDataHandler extends MappedUserDataHandler {

    public EmailLoginCodeUserDataHandler(MongoTemplate mongoTemplate) {
        super(mongoTemplate);
    }

    @Override
    public String id() {
        return "email-login-codes";
    }

    @Override
    public int order() {
        return 800;
    }

    @Override
    protected List<Class<?>> entityTypes() {
        return List.of(EmailLoginCodeDocument.class);
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
