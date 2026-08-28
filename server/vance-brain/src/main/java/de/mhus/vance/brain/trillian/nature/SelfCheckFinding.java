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
 * <p><b>The subject is not always a process.</b> It started as one and the
 * fields were named accordingly; an unread inbox thread is the second sort
 * of thing a Trillian can be woken for, and it is addressed by id rather
 * than by name. The {@link Kind} says which — nothing may read
 * {@link #subjectId()} without having looked at it first.
 *
 * @param kind        what sort of situation this is
 * @param subjectName the thing it concerns, as the loop would address it:
 *                    the process name for the worker kinds, the thread id
 *                    for {@link Kind#INBOX_UNREAD}
 * @param subjectId   the same thing, for lookups and logging — a process
 *                    id, or a thread id
 * @param detail      one line for the prompt: what is the case, and what
 *                    the loop is expected to weigh
 */
public record SelfCheckFinding(
        Kind kind,
        String subjectName,
        String subjectId,
        String detail) {

    public enum Kind {
        /** Asked a question and is parked. Nothing will arrive on its own. */
        WORKER_WAITING,
        /** Hit a safety net (wallclock, idle-stuck). Context intact, resumable. */
        WORKER_BLOCKED,
        /** Still RUNNING, but has said nothing for a long time. */
        WORKER_SILENT,
        /**
         * An inbox thread the Trillian has not seen. The one finding whose
         * subject is not a process — and the one whose delivery
         * <em>must</em> have an effect, because an unread thread that stays
         * unread would produce the same finding on every round forever.
         */
        INBOX_UNREAD,

        /**
         * Something outside Vancetope is waiting to be worked — a report in
         * a collector, a run in a foreign pipeline.
         *
         * <p>Deliberately unspecific, unlike the kinds above: those carry a
         * rule the model must not re-derive ("continued three times means
         * looping"). This one carries no rule at all, because what the item
         * means is the Nature's business and the detail line says it. A kind
         * per outside system would put every Nature's vocabulary into the
         * engine's enum.
         *
         * <p>Same delivery obligation as {@link #INBOX_UNREAD}: whatever
         * makes the item stop being pending has to happen, or the finding
         * repeats for ever. It happens in the turn — never in
         * {@code selfCheckFindings}, which runs on ticks that end in no
         * wakeup at all.
         */
        EXTERNAL_PENDING
    }

    /**
     * One line about this finding, without the prompt's list marker — for
     * anywhere that is not a bullet list, such as the Megadodo feed row of
     * the wakeup this finding caused.
     */
    public String summary() {
        return "[" + kind.name().toLowerCase(java.util.Locale.ROOT) + "] "
                + subjectName + ": " + detail;
    }

    /** Rendered into the self-check frame. */
    public String render() {
        return "- " + summary();
    }
}
