package de.mhus.vance.shared.integration;

import de.mhus.vance.shared.user.maintenance.MappedUserDataHandler;
import de.mhus.vance.shared.user.maintenance.UserReference;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * An integration token exists because of one account and authenticates as that
 * account — so it is {@link UserReference#OWNED} and goes with the user.
 *
 * <p>This is the same reasoning that puts {@code store.token.*} and the vault
 * settings in this class rather than leaving them as tombstoned records: an
 * orphaned credential is not a stale row, it is a living secret. Here it is
 * sharper still, because the row <em>is</em> the revocation channel — leave it
 * and the token in the wild keeps authenticating as a user who no longer
 * exists.
 *
 * <p>Runs at 400, ahead of the whole ordinary block. Nothing depends on that
 * ordering the way a cascade does; it is a safety margin. A user deletion that
 * fails halfway must not be the run that left a working credential behind, so
 * the credential is the first thing to go.
 */
@Component
public class IntegrationTokenUserDataHandler extends MappedUserDataHandler {

    public IntegrationTokenUserDataHandler(MongoTemplate mongoTemplate) {
        super(mongoTemplate);
    }

    @Override
    public String id() {
        return "integration-tokens";
    }

    @Override
    public int order() {
        return 400;
    }

    @Override
    protected List<Class<?>> entityTypes() {
        return List.of(IntegrationTokenDocument.class);
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
