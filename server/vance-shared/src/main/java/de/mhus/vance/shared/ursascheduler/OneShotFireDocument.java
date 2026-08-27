package de.mhus.vance.shared.ursascheduler;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Durable "this one-shot has fired" marker, one row per
 * {@code tenant/project/scheduler}.
 *
 * <p>Deliberately <b>without</b> a TTL, unlike its neighbour
 * {@link FireClaimDocument}: a fire-claim only has to outlive the pod
 * handover window, this marker has to outlive the scheduler. It keeps an
 * {@code at:} scheduler from firing a second time when the brain crashed
 * between the fire and the trash step — see
 * {@code specification/scheduler.md} §10a.
 *
 * <p>{@code scheduledFor} carries the {@code at:} value that was consumed.
 * The marker only counts against a registration with the <em>same</em>
 * {@code at:} — a document re-created under the same name with a new
 * timestamp arms normally instead of being trashed on sight.
 *
 * <p>The marker replaces the former lookup against the {@code event_log}
 * collection. A log with a retention window is the wrong home for a piece
 * of state whose disappearance re-runs work; see
 * {@code planning/megadodo.md}.
 */
@Document(collection = "ursa_oneshot_fires")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OneShotFireDocument {

    /** See {@link #markerId}. */
    @Id
    private String id;

    /** The {@code at:} value that was consumed. */
    private Instant scheduledFor;

    private Instant firedAt;

    /** Run that consumed the one-shot — diagnostics only. */
    private @Nullable String correlationId;

    // ─── Id grammar ────────────────────────────────────────────────────────
    //
    // Here rather than in the service, because it is a property of the row:
    // there is no field naming the project, so the id *is* the only place the
    // project appears — and the project-maintenance handler has to be able to
    // find these rows without the brain's scheduler on its classpath.

    /**
     * The marker's {@code _id}: the three names with a {@code U+0000}
     * separator.
     *
     * <p>NUL rather than {@code /}, because a separator that can occur inside
     * a part is not one: {@code ("a", "b/c", "d")} and {@code ("a", "b",
     * "c/d")} would collide, and a collision here means a one-shot that
     * silently never fires. The same reasoning and the same separator as
     * {@code LlmUsageDailyDocument.bucketId}. Project and scheduler names are
     * constrained today; the id outlives the constraint.
     */
    public static String markerId(String tenantId, String projectId, String scheduler) {
        return tenantId + SEPARATOR + projectId + SEPARATOR + scheduler;
    }

    /** The {@code /}-joined id used until 2026-08-24. Read-only. */
    public static String legacyMarkerId(String tenantId, String projectId, String scheduler) {
        return tenantId + LEGACY_SEPARATOR + projectId + LEGACY_SEPARATOR + scheduler;
    }

    /** Everything belonging to one project, in both id shapes. */
    public static String idPrefix(String tenantId, String projectId) {
        return tenantId + SEPARATOR + projectId + SEPARATOR;
    }

    /** Legacy counterpart of {@link #idPrefix}. */
    public static String legacyIdPrefix(String tenantId, String projectId) {
        return tenantId + LEGACY_SEPARATOR + projectId + LEGACY_SEPARATOR;
    }

    private static final char SEPARATOR = '\0';
    private static final char LEGACY_SEPARATOR = '/';
}
