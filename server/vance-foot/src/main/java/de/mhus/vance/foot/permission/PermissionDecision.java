package de.mhus.vance.foot.permission;

/**
 * Verdict of the sandbox gate for a single brain-issued tool call.
 *
 * <ul>
 *   <li>{@link #ALLOW} — a {@code allow} rule matched (or the call is
 *       out of sandbox scope / sandbox is off): run the tool.</li>
 *   <li>{@link #DENY} — a {@code deny} rule matched: reject hard, no
 *       user prompt.</li>
 *   <li>{@link #ASK} — no rule matched: the REPL must ask the user
 *       (allow/deny, once/always).</li>
 * </ul>
 */
public enum PermissionDecision {
    ALLOW,
    DENY,
    ASK
}
