package de.mhus.vance.shared.thinkprocess;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.action.TriggerKind;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * What started this process — embedded on
 * {@code ThinkProcessDocument.triggerOrigin}, written once at spawn time
 * and never mutated afterwards.
 *
 * <p>Two very different consumers read it:
 *
 * <ul>
 *   <li><b>Cycle guards.</b> A process spawned <em>by</em> a hook must not
 *       re-fire process-lifecycle hooks on its own termination, or a
 *       {@code process.completed} hook with a recipe action spawns forever.
 *       The guards ask for {@link #kind} only.</li>
 *   <li><b>Run attribution.</b> The scheduler's termination listener needs
 *       to know which run a terminating process belonged to, so it can
 *       close the matching run log. That is what {@link #source} and
 *       {@link #runId} carry.</li>
 * </ul>
 *
 * <p>Both used to be answered by querying the {@code event_log} collection
 * for the {@code STARTED} row of this process. Keeping the answer on the
 * process itself removes a Mongo lookup from every process termination in
 * the system and stops run identity from depending on a log with a
 * retention window — see {@code planning/megadodo.md}.
 *
 * <p>{@link #runAs} is a <b>snapshot</b> of the identity the spawn ran
 * under. Deliberately not re-resolved later: the trigger document may have
 * been edited since, and the run belongs to whoever started it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TriggerOrigin {

    /** Which trigger surface spawned the process. */
    private @Nullable TriggerKind kind;

    /**
     * Trigger-specific source tag, e.g. {@code "ursascheduler:morning-briefing"}
     * or {@code "hook:process.completed:notify-slack"}. Same string the
     * trigger surface puts on its log rows.
     */
    private @Nullable String source;

    /**
     * Id of the one logical run this process belongs to — the
     * {@code correlationId} of the trigger. Shared with the run's log
     * document and, once Megadodo lands, with its feed rows.
     */
    private @Nullable String runId;

    /** Identity the spawn ran under, as resolved at spawn time. */
    private @Nullable String runAs;
}
