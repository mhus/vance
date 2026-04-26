package de.mhus.vance.brain.thinkengine;

import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.Set;

/**
 * A think-engine algorithm. Implementations are Spring beans; the registry
 * in {@link ThinkEngineService} discovers them automatically via
 * {@code List<ThinkEngine>} autowire.
 *
 * <p>Engines are <b>stateless with respect to a concrete instance</b> — all
 * runtime data lives in the {@link ThinkProcessDocument} that's handed in
 * on every lifecycle call. Do not cache per-process state in instance
 * fields.
 *
 * <p>Lifecycle semantics and the full status set are documented in
 * {@code specification/arthur-engine.md §2} and
 * {@code specification/think-engines.md §3}.
 */
public interface ThinkEngine {

    // ─── Metadata ────────────────────────────────────────────────────────

    /** Unique, lowercase-kebab, persisted in {@code ThinkProcessDocument.thinkEngine}. */
    String name();

    /** Display name for UI / CLI. */
    String title();

    String description();

    /** SemVer used for resume-compat checks. */
    String version();

    /**
     * Restrict the engine's view of the global tool registry to a
     * specific subset (by tool {@code name()}).
     *
     * <p>Returning an <em>empty</em> set means "no restriction" — the
     * engine sees every tool the {@link ToolDispatcher} resolves in
     * its scope (the Zaphod default). Returning a non-empty set
     * filters every {@link ContextToolsApi} read and rejects
     * out-of-set {@link ContextToolsApi#invoke invoke} calls with a
     * {@link ToolException}.
     *
     * <p>The set is consulted once when the per-call
     * {@link ThinkEngineContext} is built, so an engine can return
     * the same constant on every call.
     */
    default Set<String> allowedTools() {
        return Set.of();
    }

    // ─── Lifecycle ──────────────────────────────────────────────────────

    /** First entry — engine initialises its state, writes greeting / plans task-tree. */
    void start(ThinkProcessDocument process, ThinkEngineContext context);

    /** Resume from {@code SUSPENDED} or {@code PAUSED}. */
    void resume(ThinkProcessDocument process, ThinkEngineContext context);

    /** Session-driven suspend — halt at the next safe boundary, no finalisation. */
    void suspend(ThinkProcessDocument process, ThinkEngineContext context);

    /** Deliver a user / sibling-process / tool-result message to the engine. */
    void steer(ThinkProcessDocument process, ThinkEngineContext context, SteerMessage message);

    /**
     * Run a single lane-turn. The runtime calls this whenever the
     * process's pending queue is woken — by a fresh user-input
     * append, a parent-routed {@code ProcessEvent}, or an explicit
     * {@code ProcessEventEmitter#scheduleTurn}. The engine's
     * responsibility is to drain {@link ThinkEngineContext#drainPending()}
     * and process every queued message in this turn.
     *
     * <p>The default implementation is the per-message-{@code steer}
     * fallback used by simple engines like Zaphod: drain, hand each
     * message to {@link #steer}, repeat until the queue stays empty
     * across a full pass (Auto-Wakeup). Engines that benefit from a
     * single LLM round-trip per turn (Arthur and other orchestrators)
     * override this to fold the whole inbox into one call.
     */
    default void runTurn(ThinkProcessDocument process, ThinkEngineContext context) {
        while (true) {
            var drained = context.drainPending();
            if (drained.isEmpty()) return;
            for (SteerMessage msg : drained) {
                steer(process, context, msg);
            }
        }
    }

    /** Final stop. Process becomes {@code STOPPED}. */
    void stop(ThinkProcessDocument process, ThinkEngineContext context);
}
