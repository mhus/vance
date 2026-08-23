package de.mhus.vance.brain.ursascheduler;

import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

/**
 * Owns the "has this one-shot already fired?" state — what keeps an
 * {@code at:} scheduler from running twice when the brain crashed between
 * the fire and the trash step.
 *
 * <p>Scope is {@code (tenant, project, scheduler)} plus the consumed
 * {@code at:} value. The previous implementation asked the
 * {@code event_log} collection for a {@code STARTED} row of
 * {@code ursascheduler:<name>}, which was scoped to the tenant only — two
 * projects of one tenant with same-named one-shots shadowed each other,
 * and a re-created one-shot was trashed on sight forever.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UrsaOneShotFireService {

    private final MongoTemplate mongoTemplate;

    /**
     * Whether the one-shot scheduled for {@code scheduledFor} has already
     * been consumed. A marker left over from a <em>different</em>
     * {@code at:} value does not count — that document was re-armed.
     */
    public boolean hasFired(
            String tenantId, String projectId, String scheduler, Instant scheduledFor) {
        return find(tenantId, projectId, scheduler)
                .map(OneShotFireDocument::getScheduledFor)
                .map(scheduledFor::equals)
                .orElse(false);
    }

    /**
     * Record the one-shot as consumed. Upsert by id — the newest fire wins,
     * so a re-armed scheduler overwrites the stale marker instead of
     * accumulating rows.
     *
     * <p>Must run <b>before</b> the scheduler document is moved to trash:
     * a crash in between then self-heals on the next bootstrap (marker
     * present → trash now, don't register) instead of re-firing.
     */
    public void markFired(
            String tenantId,
            String projectId,
            String scheduler,
            Instant scheduledFor,
            @Nullable String correlationId) {
        String id = markerId(tenantId, projectId, scheduler);
        mongoTemplate.save(OneShotFireDocument.builder()
                .id(id)
                .scheduledFor(scheduledFor)
                .firedAt(Instant.now())
                .correlationId(correlationId)
                .build());
        log.debug("One-shot '{}' marked as fired (at={} run='{}')", id, scheduledFor, correlationId);
    }

    private Optional<OneShotFireDocument> find(
            String tenantId, String projectId, String scheduler) {
        return Optional.ofNullable(mongoTemplate.findById(
                markerId(tenantId, projectId, scheduler), OneShotFireDocument.class));
    }

    static String markerId(String tenantId, String projectId, String scheduler) {
        return tenantId + '/' + projectId + '/' + scheduler;
    }
}
