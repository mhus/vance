package de.mhus.vance.shared.inbox;

import de.mhus.vance.shared.project.maintenance.ProjectDataHandler;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Inbox threads pointing at a document of the project.
 *
 * <p>A thread has no {@code projectId} and is not project data: it belongs to
 * the people in it and outlives whatever it was about. What it can hold is a
 * {@code documentRef}, and that reference does name a project.
 *
 * <p>Hence the asymmetry, which is the whole reason this handler exists at all:
 *
 * <ul>
 *   <li><b>Delete leaves the reference standing.</b> The ref carries the
 *       document's title and path — after the project is gone that is the only
 *       record of what the thread was about, and erasing it would make an
 *       archived decision unreadable. It dangles, and a dangling pointer to
 *       something deleted is the truth.</li>
 *   <li><b>Rename rewrites it.</b> Here the opposite holds: leaving the old
 *       name would point the thread at a name that may be created again later
 *       by somebody else, and then the link resolves — to the wrong
 *       document.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class MaximegalonProjectDataHandler implements ProjectDataHandler {

    private static final String REF_PROJECT = "documentRef.projectId";

    private final MongoTemplate mongoTemplate;

    @Override
    public String id() {
        return "inbox-thread-refs";
    }

    @Override
    public Set<String> collections() {
        return Set.of(MaximegalonDocument.COLLECTION);
    }

    /** Free-standing: nothing is reached through an inbox thread. */
    @Override
    public int order() {
        return 2500;
    }

    @Override
    public long count(String tenantId, String projectId) {
        return mongoTemplate.count(scope(tenantId, projectId), MaximegalonDocument.class);
    }

    @Override
    public long delete(String tenantId, String projectId) {
        return 0;
    }

    @Override
    public @Nullable String deleteNote(String tenantId, String projectId) {
        long referencing = count(tenantId, projectId);
        if (referencing == 0) {
            return null;
        }
        return referencing + " inbox thread(s) keep their reference to a document of this"
                + " project — kept on purpose, they are the record of what was decided";
    }

    @Override
    public long rename(String tenantId, String projectId, String newProjectId) {
        return mongoTemplate.updateMulti(
                        scope(tenantId, projectId),
                        new Update().set(REF_PROJECT, newProjectId),
                        MaximegalonDocument.class)
                .getModifiedCount();
    }

    private Query scope(String tenantId, String projectId) {
        return new Query(Criteria.where("tenantId").is(tenantId)
                .and(REF_PROJECT).is(projectId));
    }
}
