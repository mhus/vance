package de.mhus.vance.brain.script;

import org.jspecify.annotations.Nullable;

/**
 * Host-side hooks a Shooty guard script drives through the
 * {@code vance.guard.*} surface. Kept in the {@code script}
 * package (rather than {@code guard}) so {@link VanceScriptApi} stays
 * free of a dependency on the guard subsystem; the
 * {@code ShootyGuardService} supplies the implementation when it
 * builds the {@code vance.guard} surface for a guard run.
 *
 * <p>Each operation belongs to a specific guard point (see
 * {@code planning/shooty.md} §3): the service builds a host that
 * implements the point-applicable operation and throws a
 * {@link VanceScriptApi.ScriptHostException} for the others — calling
 * {@code continueWith} from a {@code start} guard is a script bug, not a
 * silent no-op.
 *
 * <ul>
 *   <li>{@link #continueWith} — STOP/TERMINATE (the classic completion
 *       follow-up). Deliberately <em>cap-aware</em>: the implementation
 *       increments the process's persistent {@code guardRounds} counter
 *       and refuses (returns {@code false}) once {@code maxRounds} is
 *       reached. That keeps the hard loop backstop in the service — a
 *       buggy guard script cannot nudge the engine forever.</li>
 *   <li>{@link #deny} — COMMAND. Veto against the dispatched engine
 *       command; fail-closed by contract (the caller turns the returned
 *       reason into a hard command error).</li>
 *   <li>{@link #activateSkill} — any point. Activates a skill on the
 *       guarded process, auto-trigger-style (marked active, no separate
 *       action turn), so a {@code start} guard firing before prompt
 *       assembly puts the skill into the very turn it opens.</li>
 *   <li>{@link #setTurnPrompt} — START. <b>Replaces</b> this turn's
 *       system prompt with {@code text} (not additive — the recipe's
 *       prompt, skill blocks and date context are gone for the turn).
 *       One text per turn, last call wins; the store is cleared at the
 *       next genuine user turn, and by default nothing is replaced.</li>
 * </ul>
 */
public interface GuardScriptHost {

    /**
     * Inject {@code prompt} into the process's own pending queue and
     * schedule another engine turn, so the engine keeps working instead
     * of yielding.
     *
     * @param prompt the follow-up prompt (non-blank)
     * @return {@code true} if the prompt was injected; {@code false}
     *         when the round cap is already reached (no injection)
     */
    boolean continueWith(String prompt);

    /**
     * Veto the engine command being gated. Only meaningful at the
     * COMMAND point; the caller fails the command hard with this reason.
     *
     * @param reason short human-readable denial ground (non-blank)
     * @return always {@code true} — reserved for future applicability
     */
    boolean deny(String reason);

    /**
     * Activate {@code skillName} on the guarded process (sticky, no
     * action turn — the enclosing/upcoming turn covers the work).
     * Commands fired by the activation run with the Shooty re-entrancy
     * marker set and therefore bypass the COMMAND gate.
     *
     * @param skillName cascade-resolved skill name (non-blank)
     * @param args      optional raw trailing text, bound like
     *                  {@code /skill <name> <args>}
     * @return {@code true} when the skill was (freshly) activated,
     *         {@code false} when it was already active
     */
    boolean activateSkill(String skillName, @Nullable String args);

    /**
     * <b>Replace</b> this turn's system prompt with {@code text}. START
     * point only — the host throws a
     * {@link VanceScriptApi.ScriptHostException} elsewhere. Not additive:
     * recipe prompt, skill blocks and date context are gone for the
     * turn. One text per turn (last call wins); cleared at the next
     * genuine user turn; nothing set means no manipulation.
     *
     * @param text the turn's system prompt (non-blank)
     */
    void setTurnPrompt(String text);
}
