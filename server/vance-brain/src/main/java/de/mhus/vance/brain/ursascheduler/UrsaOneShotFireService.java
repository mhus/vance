package de.mhus.vance.brain.ursascheduler;

import de.mhus.vance.shared.ursascheduler.OneShotFireDocument;

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
        log.debug("One-shot '{}/{}/{}' marked as fired (at={} run='{}')",
                tenantId, projectId, scheduler, scheduledFor, correlationId);
    }

    /**
     * Looks the marker up under the current id and, failing that, under the
     * one this service used before the separator changed.
     *
     * <p>The fallback is not decoration: this marker's entire job is to stop a
     * one-shot from running twice, so a changed key that silently forgot the
     * existing markers would cause exactly the failure the class exists to
     * prevent. It can go once no installation carries pre-{@code 2026-08-24}
     * markers — they are rewritten under the new id on the next fire, and a
     * one-shot fires once.
     */
    private Optional<OneShotFireDocument> find(
            String tenantId, String projectId, String scheduler) {
        OneShotFireDocument hit = mongoTemplate.findById(
                markerId(tenantId, projectId, scheduler), OneShotFireDocument.class);
        if (hit == null) {
            hit = mongoTemplate.findById(
                    legacyMarkerId(tenantId, projectId, scheduler), OneShotFireDocument.class);
        }
        return Optional.ofNullable(hit);
    }

    /**
     * The {@code /}-joined id used until 2026-08-24. Read-only — see
     * {@link #find}. Grammar owned by {@link OneShotFireDocument}, which is
     * also where the project-maintenance handler reads it from.
     */
    static String legacyMarkerId(String tenantId, String projectId, String scheduler) {
        return OneShotFireDocument.legacyMarkerId(tenantId, projectId, scheduler);
    }

    /** See {@link OneShotFireDocument#markerId}. */
    static String markerId(String tenantId, String projectId, String scheduler) {
        return OneShotFireDocument.markerId(tenantId, projectId, scheduler);
    }
}
