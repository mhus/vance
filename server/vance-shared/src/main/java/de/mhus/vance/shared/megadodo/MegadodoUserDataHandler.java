package de.mhus.vance.shared.megadodo;

import de.mhus.vance.shared.user.maintenance.MappedUserDataHandler;
import de.mhus.vance.shared.user.maintenance.UserReference;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Who acted, in the project activity feed.
 *
 * <p>RECORD: the row says a project was created, a kit installed, a home
 * claimed — and by whom. That stays true after the account goes; what must not
 * happen is the next holder of the login inheriting it.
 */
@Component
public class MegadodoUserDataHandler extends MappedUserDataHandler {

    public MegadodoUserDataHandler(MongoTemplate mongoTemplate) {
        super(mongoTemplate);
    }

    @Override
    public String id() {
        return "feed-actor";
    }

    @Override
    public int order() {
        return 1400;
    }

    @Override
    protected List<Class<?>> entityTypes() {
        return List.of(MegadodoEventDocument.class);
    }

    @Override
    protected String userField() {
        return "actor";
    }

    @Override
    protected UserReference reference() {
        return UserReference.RECORD;
    }
}
