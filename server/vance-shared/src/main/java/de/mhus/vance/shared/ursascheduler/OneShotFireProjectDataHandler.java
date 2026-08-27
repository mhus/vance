package de.mhus.vance.shared.ursascheduler;

import de.mhus.vance.shared.project.maintenance.ProjectDataHandler;
import java.util.List;
import java.util.Set;
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
     * Markers of one project, matched on the {@code _id} prefix in both shapes.
     *
     * <p><b>A range, not a regex.</b> The current id shape separates its parts
     * with {@code \0} ({@code OneShotFireDocument.SEPARATOR}), and a BSON
     * regular expression is transported as a cstring — which cannot contain a
     * NUL. Every call built the pattern successfully in Java and then failed at
     * serialisation with {@code BsonSerializationException}, so {@code count},
     * {@code delete} and {@code rename} all threw for every project. Found by
     * running {@code project inspect} against a live pair of brains.
     *
     * <p>A {@code >= prefix < prefix⁺} range says the same thing, uses the
     * {@code _id} index the same way, and carries the NUL as ordinary string
     * content. It compares bytes rather than characters, which is exact here
     * because tenant and project names are restricted to the {@code name}
     * grammar — no multi-byte code point can reorder against the boundary.
     */
    private Query scope(String tenantId, String projectId) {
        return new Query(new Criteria().orOperator(
                prefixRange(OneShotFireDocument.idPrefix(tenantId, projectId)),
                prefixRange(OneShotFireDocument.legacyIdPrefix(tenantId, projectId))));
    }

    /**
     * {@code _id} beginning with {@code prefix}, as a half-open range.
     *
     * <p>The upper bound raises the prefix's last character by one: every
     * string that starts with the prefix sorts before that, and nothing else
     * does.
     */
    private static Criteria prefixRange(String prefix) {
        String upper = prefix.substring(0, prefix.length() - 1)
                + (char) (prefix.charAt(prefix.length() - 1) + 1);
        return Criteria.where("_id").gte(prefix).lt(upper);
    }
}
