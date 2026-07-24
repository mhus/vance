package de.mhus.vance.brain.trillian.nature;

import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;

/**
 * Behaviour layer for a single Trillian generation ("Nature"). The
 * engine framework ({@code TrillianControlEngine},
 * {@code TrillianUserEngine}) carries the mechanics; the Nature
 * carries the per-generation policy decisions.
 *
 * <p>Nature-0 is the baseline — empty / default hooks. Future
 * Natures (A, B, …) overlay personality traits, reflexion phases,
 * mode-switching, token budgets, and so on by overriding the
 * appropriate hook.
 *
 * <p>Lookup is by {@link #id()} from {@link TrillianNatureRegistry};
 * recipes pin the Nature via {@code params.nature: '<id>'}.
 *
 * <p>See {@code specification/trillian-engine.md} §2 + §4.
 */
public interface TrillianNature {

    /**
     * Stable identifier — must match the value pinned in
     * {@code recipe.params.nature}. Test natures use digit ids
     * ({@code "0"}-{@code "9"}); production natures use letter ids
     * ({@code "A"}-{@code "Z"}).
     */
    String id();

    /** Short display title for logs and UI. */
    String title();

    // ─── Prompt overlays ──────────────────────────────────────────

    /**
     * Nature-specific addendum appended to the Trillian-Control
     * system prompt. Returns empty for Nature-0 (no overlay).
     * Future Natures inject personality / reflexion priming here.
     *
     * @param process the calling Control process (engineParams may
     *                carry Nature-specific config the addendum reads)
     */
    default String controlPromptAddendum(ThinkProcessDocument process) {
        return "";
    }

    /**
     * Nature-specific addendum appended to the Trillian-User
     * (orchestrator-loop) system prompt.
     *
     * <p>Nature-0 reads the free-form {@code attributes} map off
     * {@code process.engineParams} and renders it as a key/value
     * block — that's how the Control LLM's
     * {@code user_attr_set(name, value)} surfaces in the worker
     * loop's prompt. Later Natures may consume the same map
     * differently (mode hints, persona traits, token budgets).
     *
     * @param process the calling Trillian-User-Loop process
     */
    default String userPromptAddendum(ThinkProcessDocument process) {
        return "";
    }

    // ─── Turn lifecycle hooks ─────────────────────────────────────

    /**
     * Called by the engine framework at the start of every Trillian-
     * Control turn (after {@code drainPending}, before the LLM call).
     * Nature-0 no-op. Use this in future Natures to trigger
     * reflexion checks, mode transitions, budget enforcement.
     */
    default void beforeControlTurn(
            ThinkProcessDocument process, ThinkEngineContext ctx) {
        // no-op for Nature-0
    }

    /**
     * Called after each Trillian-Control turn (after natural-stop or
     * tool-loop exhaustion). Nature-0 no-op.
     */
    default void afterControlTurn(
            ThinkProcessDocument process, ThinkEngineContext ctx) {
        // no-op for Nature-0
    }

    /**
     * Called at the start of every Trillian-User-loop turn. Nature-0
     * no-op. Future Natures may persist reflexion state, refresh a
     * trait snapshot, or rebalance budget here.
     */
    default void beforeUserTurn(
            ThinkProcessDocument process, ThinkEngineContext ctx) {
        // no-op for Nature-0
    }

    /**
     * Called after each Trillian-User-loop turn. Nature-0 no-op.
     */
    default void afterUserTurn(
            ThinkProcessDocument process, ThinkEngineContext ctx) {
        // no-op for Nature-0
    }
}
