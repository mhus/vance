package de.mhus.vance.brain.trillian.nature;

import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Behaviour layer for a single Trillian generation ("Nature"). The
 * engine framework ({@code TrillianControlEngine},
 * {@code TrillianUserEngine}) carries the mechanics; the Nature
 * carries the per-generation policy decisions.
 *
 * <p>Nature void is the baseline — empty / default hooks. Future
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

    // ─── Structure ────────────────────────────────────────────────

    /**
     * Whether this Nature runs a separate head per conversation thread
     * ({@code true}) or carries everything on its one loop ({@code false}).
     *
     * <p>This is an axis of the class hierarchy, not a per-Nature switch:
     * {@link TrillianNatureSingleHead} and {@link TrillianNatureMultiHead}
     * answer it {@code final}, so a concrete Nature picks its side by what
     * it extends and cannot flip it by overriding. Deliberately without a
     * default — a Nature that implements this interface directly has to say
     * which side it is on. The multi-head side (first concrete Nature:
     * {@code colin}) is the conversation-head machinery of
     * {@code planning/trillian-exposure.md} §3.3.
     */
    boolean multiHead();

    // ─── Prompt overlays ──────────────────────────────────────────

    /**
     * Nature-specific addendum appended to the Trillian-Control
     * system prompt. Returns empty for Nature void (no overlay).
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
     * <p>Nature void reads the free-form {@code attributes} map off
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

    // ─── Conversation heads (multi-head side) ────────────────────

    /**
     * The recipe a conversation head of this Trillian runs on
     * ({@code engine: trillian-control}, toolset cut to the thread family
     * plus the task handover — see {@code planning/trillian-exposure.md}
     * §3.2). Derived from the Nature id so nobody has to keep the pair
     * in sync by hand; a Nature that wants a different name overrides.
     *
     * <p>Only asked for multi-head Natures ({@link #multiHead()}), and only
     * when a head is spawned — not a loop or control concern.
     */
    default String headRecipe() {
        return "trillian-head-" + id();
    }

    /**
     * Nature-specific addendum appended to a conversation head's system
     * prompt. The head doctrine (how to answer a thread, when to hand a
     * task to the loop, when to conclude) lives in the head engine's
     * prompt ({@code trillian-head-prompt.md}), not here. This hook is for
     * what a Nature has to say about *itself* in a conversation.
     *
     * <p>Composed into {@link #headSystemAddendum} on the multi-headed
     * side, after the shared persona — never into the human's Control.
     */
    default String headPromptAddendum(ThinkProcessDocument head) {
        return "";
    }

    /**
     * Everything this Nature adds to a conversation head's system prompt —
     * what the head engine appends to its engine prompt. Composed on the
     * multi-headed side ({@code TrillianNatureMultiHead}: the shared persona
     * read off the loop, then {@link #headPromptAddendum}); a single-headed
     * Nature has no heads and adds nothing.
     */
    default String headSystemAddendum(ThinkProcessDocument head) {
        return "";
    }

    /**
     * How many conversation heads this Trillian may hold at the same
     * time. A bound on cost and on thread-flood abuse — every head is a
     * process with its own context. Per-Trillian override comes with the
     * exposure configuration (Phase 2); this is the Nature's default.
     */
    default int headLimit() {
        return 5;
    }

    /** How a conversation of this Trillian ended — for {@link #headConcluded}. */
    enum HeadConclusion {
        /** The head (or a person) archived the thread as settled. */
        COMPLETED,
        /** The message limit forced the close — open a new conversation. */
        LIMIT,
        /** No contribution for the configured inactivity window. */
        INACTIVITY,
        /** The Trillian itself ended; open threads were archived with it. */
        ACCOUNT_ENDED
    }

    /**
     * A conversation of this Trillian ended — whatever ended it: the head
     * (or a person) archiving the thread as settled, the message limit, the
     * inactivity sweep, or the end of the Trillian itself. One funnel, four
     * causes: whatever a Nature wants to remember about a concluded
     * conversation (a journal line, a summary to the owner) keys on this
     * hook and needs no second dispatch.
     *
     * <p>Exactly once per conversation. A conversation with a head reports
     * when the sweep closes that head (status-checked, with the cause the
     * sweep recorded on it); one the Trillian ends without a head (limit,
     * inactivity, account end) reports when it is ended. Not called for a
     * thread that ended without the Trillian taking part (a headless thread
     * archived by hand). Best-effort: a throwing hook is logged, never
     * propagated.
     */
    default void headConcluded(String tenantId, String account, String threadId, HeadConclusion cause) {}

    // ─── Turn lifecycle hooks ─────────────────────────────────────

    /**
     * Called by the engine framework at the start of every Trillian-
     * Control turn (after {@code drainPending}, before the LLM call).
     * Nature void no-op. Use this in future Natures to trigger
     * reflexion checks, mode transitions, budget enforcement.
     */
    default void beforeControlTurn(ThinkProcessDocument process, ThinkEngineContext ctx) {
        // no-op for Nature void
    }

    /**
     * Called after each Trillian-Control turn (after natural-stop or
     * tool-loop exhaustion). Nature void no-op.
     */
    default void afterControlTurn(ThinkProcessDocument process, ThinkEngineContext ctx) {
        // no-op for Nature void
    }

    /**
     * Called at the start of every Trillian-User-loop turn. Nature void
     * no-op. Future Natures may persist reflexion state, refresh a
     * trait snapshot, or rebalance budget here.
     */
    default void beforeUserTurn(ThinkProcessDocument process, ThinkEngineContext ctx) {
        // no-op for Nature void
    }

    /**
     * Called after each Trillian-User-loop turn. Nature void no-op.
     */
    default void afterUserTurn(ThinkProcessDocument process, ThinkEngineContext ctx) {
        // no-op for Nature void
    }

    /**
     * How sociable a session this Trillian opens in a project should be
     * (A6). Asked per {@code session_open}; a derived Nature may fix a
     * constant or vary it by purpose and project. The model never chooses —
     * the noise level is doctrine, not a turn decision.
     *
     * <p>Default {@link CollabMode#JOIN}: the session is the Trillian's way
     * of working somewhere, so what is said there flows back to the loop —
     * answers and human collaboration alike. {@link CollabMode#WATCH} is the
     * doctrine for pure observation: visible and shared, but the chat does
     * not wake the loop.
     */
    default CollabMode sessionCollab(ThinkProcessDocument loop, String projectId, @Nullable String purpose) {
        return CollabMode.JOIN;
    }

    /**
     * What this Trillian is called in conversation.
     *
     * <p>Defaults to {@code "Trillian"} — the engine's own name, correct
     * for any Nature that does not name its instances. A Nature that
     * gives each Trillian a given name returns it here, so the human
     * meets "Ada is ready" rather than a class name.
     *
     * <p>Takes the attribute map because that is where a name lives if it
     * lives anywhere: the human can change it with
     * {@code //trillian attr set name}, and the call name has to follow
     * without anything else being told.
     */
    default String callName(Map<String, Object> attributes) {
        return "Trillian";
    }

    /**
     * What, if anything, is worth waking this Trillian up for right now.
     *
     * <p>Called by the heartbeat before any turn is run. An empty list
     * means the wakeup is dropped and simply re-armed — no model call, no
     * tokens. That is the whole economics of the feature: looking around
     * every hour costs one query, and only a real finding costs a turn.
     *
     * <p>Gather deterministically. Judging whether a blocked worker was
     * making progress is the model's job on the turn that follows; deciding
     * that a blocked worker exists is not.
     *
     * <p><b>And gather without side effects.</b> This runs on every due
     * tick, including the ones that end in no wakeup at all, and a Nature
     * that spends a budget or closes something here spends it whether or
     * not the loop ever hears about it. What has to be written down when a
     * finding is actually delivered belongs in
     * {@link #selfCheckDelivered}.
     *
     * <p>Default empty — a Nature that does not say what it watches for
     * never wakes up, which is the right behaviour for a baseline that is
     * meant to be purely reactive.
     */
    default List<SelfCheckFinding> selfCheckFindings(ThinkProcessDocument loop) {
        return List.of();
    }

    /**
     * The findings reached the loop: it has been handed a self-check turn
     * and will act on them.
     *
     * <p>The counterpart to {@link #selfCheckFindings} being free of side
     * effects. Whatever a Nature has to remember about having reported
     * something — a probe budget spent, a round counted, an episode ended
     * — is written here, once the report has actually been delivered.
     *
     * <p>Never called for a wakeup that was dropped or failed to schedule.
     * Best-effort by construction: a Nature that throws here must not undo
     * the wakeup that already happened.
     */
    default void selfCheckDelivered(ThinkProcessDocument loop, List<SelfCheckFinding> findings) {}

    /**
     * Whether silence itself may wake this Trillian (A5, {@code [bored]}):
     * once the loop has been quiet for an hour and its home holds standing
     * goals ({@code _vance/trillian/goals.yaml}), the self-check carries a
     * {@code [bored]} finding and the loop spends a turn on those goals.
     *
     * <p>Opt-in, default {@code false}. A bored turn spends idle tokens on
     * purpose — the opposite of the empty-discard economics every other
     * finding keeps — so a Nature has to say it wants initiative. A watchdog
     * Nature stays silent until something real happens.
     */
    default boolean acceptsBoredFindings() {
        return false;
    }

    // ─── Attribute durability ─────────────────────────────────────

    /**
     * Attributes a freshly bootstrapped worker loop should start with,
     * asked once by {@code TrillianSessionBootstrapper} when nothing was
     * carried over from a previous incarnation.
     *
     * <p>This is where a Nature decides whether its Trillian is
     * <em>ephemeral</em> or <em>persistent</em>. Nature void returns nothing:
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
    default Map<String, Object> initialAttributes(String tenantId, String projectId, String account) {
        return Map.of();
    }

    /**
     * The worker's attribute map changed. Called after every mutation
     * through {@code TrillianInternalApi} — one funnel, so both
     * {@code user_attr_set} and {@code //trillian attr} arrive here.
     *
     * <p>Nature void no-op. A persistent Nature mirrors the map to durable
     * storage here. Must not throw: losing durability is worth a
     * warning, not a failed attribute write.
     *
     * @param worker     the Trillian-User-Loop process that owns the map
     * @param attributes the map as it now stands, already written to
     *                   {@code engineParams}
     */
    default void attributesChanged(ThinkProcessDocument worker, Map<String, Object> attributes) {
        // no-op for Nature void
    }

    /** How a task ended. Only conclusions reach the Nature. */
    enum TaskOutcome {
        DONE,
        FAILED
    }

    /**
     * A task the worker loop was running has concluded.
     *
     * <p>Fired from the single dispatch funnel for {@code task_done} and
     * {@code task_failed}, after the event reached Control — the human
     * hearing the result must never depend on what a Nature does with it.
     * {@code task_request} and {@code task_needs_input} do not arrive
     * here: they are not conclusions, and reflecting on a question would
     * teach the Trillian nothing except that it asked one.
     *
     * <p>Nature void no-op. A reflecting Nature turns the outcome into
     * something durable here. Both outcomes are delivered on purpose —
     * a Trillian that only reviews its successes learns nothing.
     *
     * <p>Called from inside the worker's reporting tool call, which holds
     * that process's lane. Must not throw, and must not do slow work on
     * the calling thread — an implementation that wants a model call or
     * document I/O detaches (see {@code TrillianNatureAdam}, which marks
     * its implementation {@code @Async}). Nothing downstream waits for
     * the answer: the outcome has already reached Control.
     */
    default void taskConcluded(ThinkProcessDocument worker, String taskId, TaskOutcome outcome, String summary) {
        // no-op for Nature void
    }

    /**
     * The service account is being deleted — whatever this Nature stored
     * under it should go with it.
     *
     * <p>Counterpart to {@link #initialAttributes}: what a Nature keyed by
     * the account name has to release when that name stops existing.
     * Accounts are never renamed and a new Trillian gets a new name, so
     * anything left behind would be unreadable and unreachable at once.
     *
     * <p>Named for the account rather than for attributes: a Nature may
     * file several things under that name — adam keeps a reflexion
     * journal beside its attributes — and they all end at the same
     * moment, for the same reason.
     *
     * <p>Nature void no-op — it stored nothing outside {@code engineParams},
     * which die with the process rows anyway. Must not throw: the account
     * deletion proceeds either way.
     */
    default void accountDiscarded(String tenantId, String projectId, String account) {
        // no-op for Nature void
    }
}
