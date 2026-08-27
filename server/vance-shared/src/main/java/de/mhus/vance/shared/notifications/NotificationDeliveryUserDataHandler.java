package de.mhus.vance.shared.notifications;

import de.mhus.vance.shared.user.maintenance.MappedUserDataHandler;
import de.mhus.vance.shared.user.maintenance.UserReference;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Notifications delivered to this account.
 *
 * <p>Not merely untidy to leave behind: they are addressed to a login, and a
 * login comes back. The next account under that name would find its
 * predecessor's notifications waiting.
 */
@Component
public class NotificationDeliveryUserDataHandler extends MappedUserDataHandler {

    public NotificationDeliveryUserDataHandler(MongoTemplate mongoTemplate) {
        super(mongoTemplate);
    }

    @Override
    public String id() {
        return "notification-deliveries";
    }

    @Override
    public int order() {
        return 900;
    }

    @Override
    protected List<Class<?>> entityTypes() {
        return List.of(NotificationDeliveryDocument.class);
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
