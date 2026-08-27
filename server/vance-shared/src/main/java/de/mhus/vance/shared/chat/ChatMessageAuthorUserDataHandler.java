package de.mhus.vance.shared.chat;

import de.mhus.vance.shared.user.maintenance.MappedUserDataHandler;
import de.mhus.vance.shared.user.maintenance.UserReference;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Who said it, in sessions that are <em>not</em> this account's.
 *
 * <p>The account's own sessions and their messages are gone by the time this
 * runs. What is left is multi-user sessions: messages this person contributed
 * to somebody else's conversation, which stay because the conversation stays.
 * Tombstoned, so a future holder of the login does not appear to have said
 * them.
 */
@Component
public class ChatMessageAuthorUserDataHandler extends MappedUserDataHandler {

    public ChatMessageAuthorUserDataHandler(MongoTemplate mongoTemplate) {
        super(mongoTemplate);
    }

    @Override
    public String id() {
        return "chat-message-authors";
    }

    @Override
    public int order() {
        return 1500;
    }

    @Override
    protected List<Class<?>> entityTypes() {
        return List.of(ChatMessageDocument.class);
    }

    @Override
    protected String userField() {
        return "senderUserId";
    }

    @Override
    protected UserReference reference() {
        return UserReference.RECORD;
    }
}
