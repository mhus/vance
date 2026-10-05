package de.mhus.vance.shared.hotblack;

import de.mhus.vance.shared.user.maintenance.MappedUserDataHandler;
import de.mhus.vance.shared.user.maintenance.UserReference;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Who asked for speech / a transcript / music.
 *
 * <p>RECORD rather than OWNED, and the difference is money: these rows
 * are the per-account quota ledger. Deleting them would erase spend
 * from the tenant's history; tombstoning keeps the history and still
 * stops a new account under the same login from inheriting the
 * consumption. Same semantics as {@code image-calls}.
 */
@Component
public class AudioCallUserDataHandler extends MappedUserDataHandler {

    public AudioCallUserDataHandler(MongoTemplate mongoTemplate) {
        super(mongoTemplate);
    }

    @Override
    public String id() {
        return "audio-calls";
    }

    @Override
    public int order() {
        return 1710;
    }

    @Override
    protected List<Class<?>> entityTypes() {
        return List.of(AudioCallRecord.class);
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
