package de.mhus.vance.brain.thinkengine;

import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.Optional;
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
     * {@code true} for engines that don't produce a synchronous
     * reply per {@code steer} call. {@code process_steer}-style
     * orchestration tools should not block on these: they queue the
     * input and return immediately (the orchestrator will receive
     * progress / completion via {@code ProcessEvent}s).
     *
     * <p>Default {@code false} for chat-style engines (Arthur,
     * Ford) — those produce a reply per turn and the synchronous
     * {@code .get()} on their lane is the right behavior.
     * Tree-runner engines like Marvin override to {@code true}.
     */
    default boolean asyncSteer() {
        return false;
    }

    /**
     * Restrict the engine's view of the global tool registry to a
     * specific subset (by tool {@code name()}).
     *
     * <p>Returning an <em>empty</em> set means "no restriction" — the
     * engine sees every tool the {@link ToolDispatcher} resolves in
     * its scope (the Ford default). Returning a non-empty set
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

    /**
     * Returns engine-owned default configuration that bypasses the
     * recipe system. When present, spawners (notably the
     * {@code SessionChatBootstrapper}) skip recipe resolution and
     * create the {@link ThinkProcessDocument} directly from the
     * bundled fields. Used by hub engines like Vance whose persona
     * and engine-level mechanics aren't config-bar via recipes.
     *
     * <p>Default {@link Optional#empty()} preserves the existing
     * recipe-driven path for Arthur, Ford, Marvin, Vogon, Zaphod.
     *
     * <p>See {@code specification/vance-engine.md} §1.2.
     */
    default Optional<EngineBundledConfig> bundledConfig() {
        return Optional.empty();
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
     * fallback used by simple engines like Ford: drain, hand each
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

    /**
     * Build the report this engine sends to its parent process when
     * a life-cycle transition fires (DONE / FAILED / STOPPED /
     * BLOCKED). The runtime ({@link ParentNotificationListener})
     * calls this whenever a parent exists and renders the result
     * into the {@code ProcessEvent} that lands in the parent's
     * pending queue.
     *
     * <p>Default produces a minimal generic line — useful for
     * reactive engines like Arthur and Ford whose final reply
     * already lives in the chat log and gets read separately.
     * Engines that synthesize a substantive result of their own
     * (Marvin's tree-AGGREGATE, Vogon's phase-synthesis) override
     * this to ship the actual content up to the parent — saves the
     * parent from having to dig into the child's storage.
     *
     * <p>The {@code parentProcessId} of the calling process can be
     * {@code null} (top-level run); engines should not branch on
     * that here — the listener will just skip emitting if there's
     * no parent.
     */
    default ParentReport summarizeForParent(
            ThinkProcessDocument process, ProcessEventType eventType) {
        return ParentReport.of(
                "Child process " + process.getId()
                        + " status=" + eventType.name().toLowerCase());
    }
}
