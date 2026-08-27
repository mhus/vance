package de.mhus.vance.shared.session;

import de.mhus.vance.shared.user.maintenance.MappedUserDataHandler;
import de.mhus.vance.shared.user.maintenance.UserReference;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * The account's sessions, across every project.
 *
 * <p>OWNED without qualification: a session is one person's conversation, and
 * there is no reading of it that survives its owner. Everything hanging off it
 * — chat messages, think processes, and what hangs off those — is reached
 * through these rows, which is why those handlers carry a lower sort index.
 */
@Component
public class SessionUserDataHandler extends MappedUserDataHandler {

    public SessionUserDataHandler(MongoTemplate mongoTemplate) {
        super(mongoTemplate);
    }

    @Override
    public String id() {
        return "sessions";
    }

    @Override
    public int order() {
        return 500;
    }

    @Override
    protected List<Class<?>> entityTypes() {
        return List.of(SessionDocument.class);
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
