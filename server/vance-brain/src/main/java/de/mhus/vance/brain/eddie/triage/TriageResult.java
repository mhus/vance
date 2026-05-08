package de.mhus.vance.brain.eddie.triage;

import de.mhus.vance.api.inbox.Criticality;
import org.jspecify.annotations.Nullable;

/**
 * Outcome of {@link OutputTriageService#classify}. Carries everything
 * the Channel-Adapter needs to route a worker frame plus the
 * Working-Memory delta the {@code WorkerLinkSnapshot} gets updated
 * with.
 *
 * <p>Reuses {@link Criticality} from the inbox subsystem rather than
 * defining a parallel triage enum — same three levels apply to "how
 * loud should this surface to the user", and the inbox-item Eddie
 * may end up posting via {@code RELAY_INBOX} carries the same value
 * forward.
 *
 * @param decision           how to route the frame
 * @param criticality        loudness; {@code CRITICAL} disables
 *                           {@link TriageDecision#REFORMULATE} —
 *                           the channel-adapter must pick VERBATIM
 *                           or INBOX to avoid hallucination on
 *                           plan-approvals, delete-confirmations etc.
 * @param spokenAnnouncement short voice-friendly sentence Eddie says
 *                           about the frame (used as the chat-message
 *                           when {@code decision == INBOX}; also
 *                           handed to the engine as a hint when the
 *                           channel-adapter rebuilds the relay text)
 * @param memorySummary      one-liner Eddie's lane writes to
 *                           {@code WorkerLinkSnapshot.triageSummary}
 *                           so subsequent turns can see "what was
 *                           that worker doing the last time it
 *                           spoke up"
 */
public record TriageResult(
        TriageDecision decision,
        Criticality criticality,
        @Nullable String spokenAnnouncement,
        @Nullable String memorySummary) {
}
