package de.mhus.vance.shared.cluster;

import de.mhus.vance.shared.project.maintenance.ProjectDataHandler;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * The project's name inside the pod registry's {@code activeProjects} list.
 *
 * <p>Denormalised state, not project data — and it sits with the other
 * references that outlive the project. Each
 * pod republishes the list on its next heartbeat, so a leftover name corrects
 * itself within a beat on any pod that is alive.
 *
 * <p>It is scrubbed anyway, for the pods that are <em>not</em> alive: a row
 * whose owner is gone is never rewritten, and it is exactly the row an operator
 * reads when asking where a project went. A rename is the same story from the
 * other side — the entry would name a project that no longer exists while the
 * renamed one appears unhosted.
 */
@Component
@RequiredArgsConstructor
public class BrainPodProjectDataHandler implements ProjectDataHandler {

    private static final String F_ACTIVE = "activeProjects";

    private final MongoTemplate mongoTemplate;

    @Override
    public String id() {
        return "cluster-pod-rows";
    }

    @Override
    public Set<String> collections() {
        return Set.of(mongoTemplate.getCollectionName(BrainPodDocument.class));
    }

    /** Free-standing: nothing is reached through a pod row. */
    @Override
    public int order() {
        return 2600;
    }

    @Override
    public long count(String tenantId, String projectId) {
        return mongoTemplate.count(scope(projectId), BrainPodDocument.class);
    }

    @Override
    public long delete(String tenantId, String projectId) {
        return mongoTemplate.updateMulti(
                        scope(projectId),
                        new Update().pull(F_ACTIVE, projectId),
                        BrainPodDocument.class)
                .getModifiedCount();
    }

    @Override
    public long rename(String tenantId, String projectId, String newProjectId) {
        // $set on the positional operator would need the matched index; pulling
        // and adding is idempotent, which a positional update is not.
        //
        // Two round trips, and that is not a choice: Mongo rejects a single
        // update that touches one path twice ("Updating the path
        // 'activeProjects' would create a conflict"), so the previous one-call
        // version threw for every rename. The comment above already said "two
        // writes" — the code did not. Found by running project rename against a
        // live pair of brains.
        //
        // Add first, then remove: the window in between has the project under
        // both names, which is a cosmetic duplicate in a display list. The other
        // order has a window with neither, and a heartbeat landing there would
        // publish a pod that looks idle.
        long changed = mongoTemplate.updateMulti(
                        scope(projectId),
                        new Update().addToSet(F_ACTIVE, newProjectId),
                        BrainPodDocument.class)
                .getModifiedCount();
        mongoTemplate.updateMulti(
                scope(projectId),
                new Update().pull(F_ACTIVE, projectId),
                BrainPodDocument.class);
        return changed;
    }

    /**
     * Pod rows listing the project.
     *
     * <p>No tenant in the predicate — and that is a property of the data, not
     * an oversight: {@code activeProjects} is a flat list of project names with
     * no tenant beside them. Two tenants with a same-named project therefore
     * scrub each other's entry. Harmless in the direction it fails: the next
     * heartbeat of a live pod puts the name back, and a dead pod's row is
     * advisory.
     */
    private Query scope(String projectId) {
        return new Query(Criteria.where(F_ACTIVE).is(projectId));
    }
}
