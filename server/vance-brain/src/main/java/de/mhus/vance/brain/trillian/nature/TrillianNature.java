package de.mhus.vance.brain.trillian.nature;

import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.Map;

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
     * {@code recipe.params.nature}, and it is spliced into the service
     * account name {@code _trillian-<id>-<instance>} and into the three
     * recipe names. {@code TrillianNatureRegistry} therefore validates
     * it at boot: lower-case alphanumerics, no dash, and not
     * {@code user}/{@code worker}.
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

    // ─── Attribute durability ─────────────────────────────────────

    /**
     * Attributes a freshly bootstrapped worker loop should start with,
     * asked once by {@code TrillianSessionBootstrapper} when nothing was
     * carried over from a previous incarnation.
     *
     * <p>This is where a Nature decides whether its Trillian is
     * <em>ephemeral</em> or <em>persistent</em>. Nature-0 returns nothing:
     * its attributes live in {@code engineParams} and die with the
     * process rows. A persistent Nature loads them from wherever it put
     * them.
     *
     * <p>Runtime storage stays {@code engineParams} either way — this is
     * a seed, not a second source of truth to read from every turn.
     *
     * @param account the {@code _trillian-<nature>-<instance>} service
     *                account the pair runs as, stable across archive and
     *                reactivate
     */
    default Map<String, Object> initialAttributes(
            String tenantId, String projectId, String account) {
        return Map.of();
    }

    /**
     * The worker's attribute map changed. Called after every mutation
     * through {@code TrillianInternalApi} — one funnel, so both
     * {@code user_attr_set} and {@code //trillian attr} arrive here.
     *
     * <p>Nature-0 no-op. A persistent Nature mirrors the map to durable
     * storage here. Must not throw: losing durability is worth a
     * warning, not a failed attribute write.
     *
     * @param worker     the Trillian-User-Loop process that owns the map
     * @param attributes the map as it now stands, already written to
     *                   {@code engineParams}
     */
    default void attributesChanged(
            ThinkProcessDocument worker, Map<String, Object> attributes) {
        // no-op for Nature-0
    }
}
