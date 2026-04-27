package de.mhus.vance.api.inbox;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * The three possible outcomes of an inbox-item answer. Applies to
 * user-submitted answers and (v2) auto-resolver-worker outputs alike.
 *
 * <ul>
 *   <li>{@link #DECIDED} — substantive answer; the {@code value}
 *       field on the answer carries the type-specific payload.</li>
 *   <li>{@link #INSUFFICIENT_INFO} — responder cannot decide without
 *       more data. {@code reason} explains what's missing. The
 *       originating engine should re-steer with more context (or
 *       escalate).</li>
 *   <li>{@link #UNDECIDABLE} — the question is malformed or
 *       genuinely undecidable. {@code reason} explains why. The
 *       originating engine should drop the gate or escalate.</li>
 * </ul>
 */
@GenerateTypeScript("inbox")
public enum AnswerOutcome {
    DECIDED,
    INSUFFICIENT_INFO,
    UNDECIDABLE
}
