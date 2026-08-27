package de.mhus.vance.shared.marvin;

import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.user.maintenance.UserDataHandler;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Marvin task nodes belonging to processes of the account's own sessions.
 *
 * <p><b>Cascade.</b> Runs at {@value #ORDER}, before {@code sessions} ({@code 500}):
 * these rows are found <em>through</em> the account's sessions, and once those
 * are gone the rows are unreachable with nothing reporting a problem.
 *
 * <p>Rename does nothing — the link is a session id, which a rename of the
 * account does not touch.
 */
@Component
@RequiredArgsConstructor
public class MarvinNodeSessionUserDataHandler implements UserDataHandler {

    public static final int ORDER = 300;

    private final MongoTemplate mongoTemplate;
    private final SessionService sessionService;
    private final ThinkProcessService thinkProcessService;

    @Override
    public String id() {
        return "marvin-nodes-of-sessions";
    }

    @Override
    public Set<String> collections() {
        return Set.of(mongoTemplate.getCollectionName(MarvinNodeDocument.class));
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public long count(String tenantId, String userName) {
        Query query = scope(tenantId, userName);
        return query == null ? 0 : mongoTemplate.count(query, MarvinNodeDocument.class);
    }

    @Override
    public long delete(String tenantId, String userName) {
        Query query = scope(tenantId, userName);
        return query == null
                ? 0
                : mongoTemplate.remove(query, MarvinNodeDocument.class).getDeletedCount();
    }

    /**
     * The rows reached through the account's sessions, or {@code null} when it
     * owns none.
     *
     * <p>The null case is stated rather than left to {@code $in}'s behaviour on
     * an empty list: it happens to match nothing, which is right, but only by
     * accident of that operator — and the accident the other way would be
     * "every row in the tenant".
     */
    private @Nullable Query scope(String tenantId, String userName) {
        List<String> processIds = thinkProcessService.findIdsBySessions(
                tenantId, sessionService.findSessionIdsForUser(tenantId, userName));
        if (processIds.isEmpty()) {
            return null;
        }
        return new Query(Criteria.where("tenantId").is(tenantId)
                .and("processId").in(processIds));
    }
}
