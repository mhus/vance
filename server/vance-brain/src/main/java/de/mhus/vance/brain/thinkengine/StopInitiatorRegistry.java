package de.mhus.vance.brain.thinkengine;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Side-channel that lets {@code ProcessStopTool} (and any future
 * "parent stops child"-style caller) tell {@link ParentNotificationListener}
 * which process initiated a particular stop, so the listener can suppress
 * the resulting {@code <process-event type=STOPPED>} when the parent is
 * already aware (it issued the stop itself).
 *
 * <p>Without this suppression, an Arthur calling {@code process_stop}
 * on its worker would get the worker's STOPPED event back into its own
 * pending-inbox, the lane scheduler would wake Arthur for an extra LLM
 * turn, and Arthur — non-deterministically — sometimes interprets that
 * event as "I should re-run the user's request" and spawns a fresh
 * worker. The classic spontaneous-restart symptom.
 *
 * <p>The registry is intentionally tiny:
 * <ul>
 *   <li>Map entry per {@code childProcessId}</li>
 *   <li>Marked just before {@code engine.stop()} runs</li>
 *   <li>Consumed once — by whichever caller reads first
 *       ({@link ParentNotificationListener} for the matching event,
 *       or {@code ProcessStopTool}'s {@code finally} as a safety net
 *       if the listener never fired)</li>
 * </ul>
 *
 * <p>Thread-safety: backed by {@link ConcurrentHashMap}. Calls are
 * synchronous within a single stop; concurrent stops on different
 * children are isolated by their map key.
 */
@Component
public final class StopInitiatorRegistry {

    private final Map<String, String> initiators = new ConcurrentHashMap<>();

    /**
     * Records that {@code initiatorProcessId} caused the stop of
     * {@code childProcessId}. Overwrites any prior entry — last call wins.
     */
    public void mark(String childProcessId, String initiatorProcessId) {
        if (childProcessId == null || initiatorProcessId == null) {
            return;
        }
        initiators.put(childProcessId, initiatorProcessId);
    }

    /**
     * Reads the initiator and removes the entry. Idempotent — calling
     * twice returns the value once and {@link Optional#empty()} on the
     * second call.
     */
    public Optional<String> consume(String childProcessId) {
        if (childProcessId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(initiators.remove(childProcessId));
    }
}
