package de.mhus.vance.shared.enginemessage;

import de.mhus.vance.shared.user.maintenance.MappedUserDataHandler;
import de.mhus.vance.shared.user.maintenance.UserReference;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Who sent an in-flight engine message.
 *
 * <p>{@code fromUserDisplayName} beside it is left alone: it is a snapshot of
 * how the name read at the time, not a reference to an account, and rewriting a
 * display string would make the record less true rather than more.
 */
@Component
public class EngineMessageAuthorUserDataHandler extends MappedUserDataHandler {

    public EngineMessageAuthorUserDataHandler(MongoTemplate mongoTemplate) {
        super(mongoTemplate);
    }

    @Override
    public String id() {
        return "engine-message-authors";
    }

    @Override
    public int order() {
        return 1600;
    }

    @Override
    protected List<Class<?>> entityTypes() {
        return List.of(EngineMessageDocument.class);
    }

    @Override
    protected String userField() {
        return "fromUser";
    }

    @Override
    protected UserReference reference() {
        return UserReference.RECORD;
    }
}
