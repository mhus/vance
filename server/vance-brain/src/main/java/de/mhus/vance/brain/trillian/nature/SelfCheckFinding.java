package de.mhus.vance.brain.trillian.nature;

/**
 * One thing worth waking a Trillian up for.
 *
 * <p>Gathered by the Nature in plain Java — no model involved. That is
 * what makes a quiet wakeup free: when a Nature finds nothing, the
 * heartbeat re-arms and no turn is ever run, so the cost of looking
 * around every hour is one database query and no tokens.
 *
 * <p>The {@link Kind} exists because some findings carry a rule the
 * model must not be left to invent — a worker that has been continued
 * three times is looping, and deciding that afresh each round is exactly
 * how it would be talked out of it.
 *
 * @param kind        what sort of situation this is
 * @param processName the process it concerns, as the loop would address
 *                    it in {@code process_steer}
 * @param processId   the same process, for logging
 * @param detail      one line for the prompt: what is the case, and what
 *                    the loop is expected to weigh
 */
public record SelfCheckFinding(
        Kind kind,
        String processName,
        String processId,
        String detail) {

    public enum Kind {
        /** Asked a question and is parked. Nothing will arrive on its own. */
        WORKER_WAITING,
        /** Hit a safety net (wallclock, idle-stuck). Context intact, resumable. */
        WORKER_BLOCKED,
        /** Still RUNNING, but has said nothing for a long time. */
        WORKER_SILENT
    }

    /** Rendered into the self-check frame. */
    public String render() {
        return "- [" + kind.name().toLowerCase(java.util.Locale.ROOT) + "] "
                + processName + ": " + detail;
    }
}
