package de.mhus.vance.shared.activity;

import de.mhus.vance.shared.user.maintenance.MappedUserDataHandler;
import de.mhus.vance.shared.user.maintenance.UserReference;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Eddie's activity feed for this account.
 *
 * <p>OWNED rather than RECORD, although it does record what somebody did: the
 * feed is the person's own recap, keyed on them and read only by them. There is
 * no third party whose view of history breaks when it goes.
 */
@Component
public class EddieActivityUserDataHandler extends MappedUserDataHandler {

    public EddieActivityUserDataHandler(MongoTemplate mongoTemplate) {
        super(mongoTemplate);
    }

    @Override
    public String id() {
        return "activity-entries";
    }

    @Override
    public int order() {
        return 1000;
    }

    @Override
    protected List<Class<?>> entityTypes() {
        return List.of(EddieActivityEntry.class);
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
