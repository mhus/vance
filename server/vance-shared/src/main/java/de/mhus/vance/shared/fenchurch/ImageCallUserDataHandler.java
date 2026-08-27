package de.mhus.vance.shared.fenchurch;

import de.mhus.vance.shared.user.maintenance.MappedUserDataHandler;
import de.mhus.vance.shared.user.maintenance.UserReference;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Who asked for an image.
 *
 * <p>RECORD rather than OWNED, and the difference is money: these rows are the
 * per-account quota ledger. Deleting them would erase spend from the tenant's
 * history; tombstoning keeps the history and still stops a new account under
 * the same login from inheriting the consumption.
 */
@Component
public class ImageCallUserDataHandler extends MappedUserDataHandler {

    public ImageCallUserDataHandler(MongoTemplate mongoTemplate) {
        super(mongoTemplate);
    }

    @Override
    public String id() {
        return "image-calls";
    }

    @Override
    public int order() {
        return 1700;
    }

    @Override
    protected List<Class<?>> entityTypes() {
        return List.of(ImageCallRecord.class);
    }

    @Override
    protected String userField() {
        return "accountId";
    }

    @Override
    protected UserReference reference() {
        return UserReference.RECORD;
    }
}
