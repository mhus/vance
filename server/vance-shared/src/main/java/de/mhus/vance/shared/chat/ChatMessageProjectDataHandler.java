package de.mhus.vance.shared.chat;

import de.mhus.vance.shared.project.maintenance.ProjectDataHandler;
import de.mhus.vance.shared.session.SessionService;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Chat messages reach their project through the session they were said in —
 * there is no {@code projectId} on the row, and adding one would be a second
 * truth to keep in step with {@code SessionDocument.projectId}.
 *
 * <p>That makes this a cascade: it runs first of all, well before
 * the session handler, because the session rows are how the messages are found
 * at all. Reverse the order and the sessions are gone, the messages are
 * unreachable, and nothing reports a problem.
 *
 * <p>Rename does nothing on purpose. The link is the session id, which a rename
 * does not touch.
 */
@Component
@RequiredArgsConstructor
public class ChatMessageProjectDataHandler implements ProjectDataHandler {

    private final MongoTemplate mongoTemplate;
    private final SessionService sessionService;

    @Override
    public String id() {
        return "chat-messages";
    }

    @Override
    public Set<String> collections() {
        return Set.of(mongoTemplate.getCollectionName(ChatMessageDocument.class));
    }

    /** Cascade — must precede {@code sessions}. */
    @Override
    public int order() {
        return 100;
    }

    @Override
    public long count(String tenantId, String projectId) {
        Query query = scope(tenantId, projectId);
        return query == null ? 0 : mongoTemplate.count(query, ChatMessageDocument.class);
    }

    @Override
    public long delete(String tenantId, String projectId) {
        Query query = scope(tenantId, projectId);
        return query == null
                ? 0
                : mongoTemplate.remove(query, ChatMessageDocument.class).getDeletedCount();
    }

    /**
     * The project's messages, or {@code null} when it has no sessions at all.
     *
     * <p>The null case is not decoration: an {@code $in} against an empty list
     * matches nothing, which is the right answer here — but only by accident of
     * that operator's semantics. Saying so explicitly keeps a future edit from
     * turning "no sessions" into "every message in the tenant".
     */
    private @org.jspecify.annotations.Nullable Query scope(String tenantId, String projectId) {
        List<String> sessionIds = sessionService.findSessionIdsForProject(tenantId, projectId);
        if (sessionIds.isEmpty()) {
            return null;
        }
        return new Query(Criteria.where("tenantId").is(tenantId)
                .and("sessionId").in(sessionIds));
    }
}
