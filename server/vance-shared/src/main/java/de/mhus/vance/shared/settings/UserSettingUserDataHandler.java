package de.mhus.vance.shared.settings;

import de.mhus.vance.shared.user.maintenance.MappedUserDataHandler;
import de.mhus.vance.shared.user.maintenance.UserReference;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Settings owned by the account — the {@code user} layer of the cascade.
 *
 * <p>OWNED, and the most clearly so of any handler here: this layer holds
 * {@code store.token.<source>} (a PASSWORD setting) and the account's vault
 * bindings. A credential that outlives its owner is not a stale row, it is a
 * live secret with nobody responsible for it — and the next account under that
 * login would resolve it as its own.
 *
 * <p>The user sits in the {@code (referenceType, referenceId)} pair that
 * carries every scope, so the predicate is overridden rather than inherited.
 */
@Component
public class UserSettingUserDataHandler extends MappedUserDataHandler {

    public UserSettingUserDataHandler(MongoTemplate mongoTemplate) {
        super(mongoTemplate);
    }

    @Override
    public String id() {
        return "settings-user-scope";
    }

    @Override
    public int order() {
        return 700;
    }

    @Override
    protected List<Class<?>> entityTypes() {
        return List.of(SettingDocument.class);
    }

    @Override
    protected String userField() {
        return "referenceId";
    }

    @Override
    protected UserReference reference() {
        return UserReference.OWNED;
    }

    @Override
    protected Query scope(String tenantId, String userName) {
        return new Query(Criteria.where("tenantId").is(tenantId)
                .and("referenceType").is(SettingService.SCOPE_USER)
                .and("referenceId").is(userName));
    }
}
