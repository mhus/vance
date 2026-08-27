package de.mhus.vance.shared.sessiongroup;

import de.mhus.vance.shared.user.maintenance.MappedUserDataHandler;
import de.mhus.vance.shared.user.maintenance.UserReference;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Session groups are per-user by construction — the whole point of the entity
 * is that the ordering is one person's, not the project's. Nothing else reads
 * them.
 */
@Component
public class SessionGroupUserDataHandler extends MappedUserDataHandler {

    public SessionGroupUserDataHandler(MongoTemplate mongoTemplate) {
        super(mongoTemplate);
    }

    @Override
    public String id() {
        return "session-groups";
    }

    @Override
    public int order() {
        return 600;
    }

    @Override
    protected List<Class<?>> entityTypes() {
        return List.of(SessionGroupDocument.class);
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
