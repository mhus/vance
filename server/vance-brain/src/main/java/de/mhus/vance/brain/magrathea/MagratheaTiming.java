package de.mhus.vance.brain.magrathea;

import de.mhus.vance.shared.magrathea.MagratheaTaskDocument;
import java.time.Duration;
import java.time.Instant;

/**
 * How long a task has been at work, for the tasks that finish outside the
 * executor — an agent, a timer, a gate, a sub-run. Their completion is
 * published by a listener that never saw the start, so the duration cannot be
 * measured on the spot; it is read off the task row instead.
 *
 * <p>The claim is the start: it is the moment the task was actually taken up,
 * so a debug halt in front of the state is not counted. A gate's duration is
 * therefore the time it waited for an answer, a timer's the time it waited.
 */
final class MagratheaTiming {

    private MagratheaTiming() {}

    /** Milliseconds from the task's claim to now; {@code 0} when it was never claimed. */
    static long sinceClaimMs(MagratheaTaskDocument task) {
        return between(task.getClaimedAt(), Instant.now());
    }

    /** Milliseconds from {@code since} to {@code now}, never negative; {@code 0} without a start. */
    static long between(Instant since, Instant now) {
        if (since == null) return 0L;
        return Math.max(0L, Duration.between(since, now).toMillis());
    }
}
