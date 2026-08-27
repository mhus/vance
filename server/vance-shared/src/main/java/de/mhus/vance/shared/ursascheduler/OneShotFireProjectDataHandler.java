package de.mhus.vance.shared.ursascheduler;

import de.mhus.vance.shared.project.maintenance.ProjectDataHandler;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * "This one-shot scheduler has already fired" markers.
 *
 * <p>Small rows with an outsized consequence. They carry no TTL by design —
 * their whole job is to outlive a crash — so a project delete that skipped them
 * would leave markers that the next project of the same name inherits, and its
 * {@code at:} schedulers would be trashed on sight instead of firing. That is a
 * silent failure: nothing errors, the work just never runs.
 *
 * <p>The project appears only inside the {@code _id}, hence the prefix match.
 * Both id shapes are covered — the pre-{@code 2026-08-24} {@code /}-joined form
 * is still read by the scheduler, so it still has to be cleaned up.
 */
@Component
@RequiredArgsConstructor
public class OneShotFireProjectDataHandler implements ProjectDataHandler {

    private final MongoTemplate mongoTemplate;

    @Override
    public String id() {
        return "ursa-oneshot-markers";
    }

    @Override
    public Set<String> collections() {
        return Set.of(mongoTemplate.getCollectionName(OneShotFireDocument.class));
    }

    @Override
    public int order() {
        return 2000;
    }

    @Override
    public long count(String tenantId, String projectId) {
        return mongoTemplate.count(scope(tenantId, projectId), OneShotFireDocument.class);
    }

    @Override
    public long delete(String tenantId, String projectId) {
        return mongoTemplate.remove(scope(tenantId, projectId), OneShotFireDocument.class)
                .getDeletedCount();
    }

    /**
     * Re-keys each marker under the new project name.
     *
     * <p>A row at a time, and an insert rather than an update: {@code _id} is
     * immutable in Mongo, so "rename" here means writing a new row and dropping
     * the old one. Order matters — the new marker exists before the old one
     * goes, because a gap in the wrong direction is a one-shot firing twice.
     */
    @Override
    public long rename(String tenantId, String projectId, String newProjectId) {
        List<OneShotFireDocument> markers =
                mongoTemplate.find(scope(tenantId, projectId), OneShotFireDocument.class);
        long moved = 0;
        for (OneShotFireDocument marker : markers) {
            String scheduler = schedulerOf(marker.getId(), tenantId, projectId);
            if (scheduler == null) {
                continue;
            }
            OneShotFireDocument moved0 = OneShotFireDocument.builder()
                    .id(OneShotFireDocument.markerId(tenantId, newProjectId, scheduler))
                    .scheduledFor(marker.getScheduledFor())
                    .firedAt(marker.getFiredAt())
                    .correlationId(marker.getCorrelationId())
                    .build();
            mongoTemplate.save(moved0);
            mongoTemplate.remove(marker);
            moved++;
        }
        return moved;
    }

    /** The scheduler name out of a marker id, whichever id shape it uses. */
    private static @org.jspecify.annotations.Nullable String schedulerOf(
            String markerId, String tenantId, String projectId) {
        String prefix = OneShotFireDocument.idPrefix(tenantId, projectId);
        if (markerId.startsWith(prefix)) {
            return markerId.substring(prefix.length());
        }
        String legacy = OneShotFireDocument.legacyIdPrefix(tenantId, projectId);
        if (markerId.startsWith(legacy)) {
            return markerId.substring(legacy.length());
        }
        return null;
    }

    /**
     * Markers of one project, matched on the {@code _id} prefix in both
     * shapes. {@link Pattern#quote} because a project name is not a regular
     * expression — an unescaped {@code .} would match a neighbour.
     */
    private Query scope(String tenantId, String projectId) {
        String current = "^" + Pattern.quote(OneShotFireDocument.idPrefix(tenantId, projectId));
        String legacy =
                "^" + Pattern.quote(OneShotFireDocument.legacyIdPrefix(tenantId, projectId));
        return new Query(new Criteria().orOperator(
                Criteria.where("_id").regex(current),
                Criteria.where("_id").regex(legacy)));
    }
}
