package de.mhus.vance.brain.ursascheduler;

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

    /** {@code tenant/project/scheduler}. */
    @Id
    private String id;

    /** The {@code at:} value that was consumed. */
    private Instant scheduledFor;

    private Instant firedAt;

    /** Run that consumed the one-shot — diagnostics only. */
    private @Nullable String correlationId;
}
