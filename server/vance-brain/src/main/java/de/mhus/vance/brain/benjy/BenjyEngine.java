package de.mhus.vance.brain.benjy;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.api.thinkprocess.TodoItem;
import de.mhus.vance.api.thinkprocess.TodoStatus;
import de.mhus.vance.brain.ai.EngineChatFactory;
import de.mhus.vance.brain.ai.light.LightLlmJsonAnswer;
import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.brain.ai.light.SchemaValidationException;
import de.mhus.vance.brain.arthur.PlanModeEventEmitter;
import de.mhus.vance.brain.thinkengine.ParentReport;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.thinkprocess.TodoPatch;
import de.mhus.vance.shared.workspace.WorkspaceService;
import de.mhus.vance.shared.worktarget.WorkTarget;
import de.mhus.vance.shared.worktarget.WorkTargetKind;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Benjy — the iterative orchestration worker for small models
 * (planning/benjy-engine.md). Frankie carries the loop control in the
 * model; Benjy carries it in the engine: state, verification, retries
 * and escalation are deterministic code, and the model is only called
 * at narrow, schema-bound edges — deciding (route), doing (a spawned
 * Ford worker per item), judging (evaluate / reflect).
 *
 * <p><b>The queue is the state.</b> Every LLM call, every worker spawn,
 * every check is one task in a persisted FIFO queue inside
 * {@code engineParams.benjyState}. The loop pops a task, executes it,
 * and the result handler enqueues the successors. Happy-path successors
 * are mechanics (the chain template per task type); the route call only
 * fires at branches (check failed, eval exhausted its retry budget, a
 * new message arrived, all items terminal).
 *
 * <p><b>Async boundaries.</b> A do-task spawns a Ford worker parented to
 * this process and ends the turn (status IDLE). The worker's reply
 * arrives as a {@code SteerMessage.Reply} in the pending queue, which
 * auto-wakes this lane; the reply handler enqueues the item's remaining
 * chain and the loop continues. ask_parent parks the process on BLOCKED
 * with the question — the parent's steer (or a user message) re-enters
 * the loop through the drain.
 *
 * <p><b>Safety nets are mechanics, not prompt pleas</b> (§6): stagnation
 * detection, a per-phase wallclock, a controller token budget, a reflect
 * convergence cap and route-stuck detection. None of them measures volume —
 * volume is not danger, standing still is: the stagnation streak only grows
 * on tasks that produced no observable forward progress, so productive work
 * of any length never trips it. Exhaustion is a decision point, not a
 * verdict: cost nets park on a checkpoint question whose answer re-grants
 * the budget and feeds the reply to route; BLOCKED remains the last exit
 * (route-stuck, schema exhaustion, route deciding to block, engine errors).
 * Small controller models love "one more test would be nice" — numbers and
 * state transitions bend that, not a prompt.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BenjyEngine implements ThinkEngine {

    public static final String NAME = "benjy";
    public static final String VERSION = "0.1.0";
    public static final String STATE_KEY = "benjyState";

    // ── Params (recipe → engineParams) with engine defaults ──

    private static final String PARAM_MAX_STAGNATION = "maxStagnation";
    private static final String PARAM_MAX_REFLECT_NO = "maxReflectNo";
    private static final String PARAM_MAX_ITEM_ATTEMPTS = "maxItemAttempts";
    private static final String PARAM_MAX_WALLCLOCK_MINUTES = "maxWallclockMinutes";
    private static final String PARAM_MAX_TOKENS = "maxTokens";
    private static final String PARAM_MAX_TOOL_CALLS = "maxToolCalls";
    private static final String PARAM_MAX_INITIAL_ITEMS = "maxInitialItems";

    /**
     * Tasks without observable forward progress before the stagnation net
     * reacts (§6). Generous on purpose: a legitimately failing coding item
     * burns up to ~10 no-progress tasks before its attempt cap routes the
     * branch (3 attempts × do/check/evaluate + the route call); the net must
     * not trip inside that stretch.
     */
    private static final int DEFAULT_MAX_STAGNATION = 15;

    /** Reflect verdicts of partially/no before the convergence cap parks (§6). */
    private static final int DEFAULT_MAX_REFLECT_NO = 3;

    private static final int DEFAULT_MAX_ITEM_ATTEMPTS = 3;
    private static final int DEFAULT_MAX_WALLCLOCK_MINUTES = 30;
    private static final long DEFAULT_MAX_TOKENS = 2_000_000L;
    /** Default per-item tool budget handed to the doer as maxIterations. */
    private static final int DEFAULT_MAX_TOOL_CALLS = 15;
    /**
     * Structural cap on items committed before facts arrive (decision #23):
     * interpret's initial items, route's split items and route's revise items
     * are each truncated to this bound. Guards the anti-Marvin invariant —
     * decomposition of the unknown stays incremental — while leaving room for
     * tasks whose structure is evident from the task text (reading, not
     * inventing). Wider than the plan's original "1–3" on purpose: reflect is
     * the continuation organ, and a premature reflect-DONE is the more
     * expensive failure; the rest arrives via reflect-gaps → route-split,
     * with the previous items' facts in the digest.
     */
    private static final int DEFAULT_MAX_INITIAL_ITEMS = 5;

    /** Same-identical route decision this many times in a row ⇒ stuck ⇒ BLOCKED. */
    private static final int STUCK_ROUTE_LIMIT = 3;

    /** Checkpoint types that can park the pending question (§6) — the granted budget differs, see applyCheckpointAnswer. */
    private static final String CHECKPOINT_STAGNATION = "stagnation";

    private static final String CHECKPOINT_WALLCLOCK = "wallclock";
    private static final String CHECKPOINT_TOKENS = "tokens";
    private static final String CHECKPOINT_REFLECT = "reflect";

    /** Wall-clock budget for one mechanical check (exec_run waitMs). */
    private static final long CHECK_WAIT_MS = 120_000L;

    private static final String METRIC_CYCLES = "vance.benjy.cycles";
    private static final String METRIC_LLM_CALLS = "vance.benjy.llm.calls";
    private static final String METRIC_OUTCOMES = "vance.benjy.outcomes";

    private static final String OUTCOME_SUCCESS = "success";
    private static final String OUTCOME_BLOCKED = "blocked";
    private static final String OUTCOME_ERROR = "error";

    private final ThinkProcessService thinkProcessService;
    private final ObjectMapper objectMapper;
    private final LightLlmService lightLlmService;
    private final BenjyWorkerSpawner workerSpawner;
    private final MetricService metricService;
    private final de.mhus.vance.brain.tools.worktarget.WorkTargetService workTargetService;
    private final WorkspaceService workspaceService;
    private final PlanModeEventEmitter planModeEventEmitter;

    // ──────────────────── Metadata ────────────────────

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "Benjy (Iterative Orchestration Worker)";
    }

    @Override
    public String description() {
        return "Engine-agency orchestration loop for iterative tasks on small/local "
                + "models: interprets the goal into acceptance criteria and items, "
                + "delegates each item to a focused Ford worker, verifies mechanically, "
                + "evaluates against criteria, reflects on the goal, and escalates at "
                + "branches. Companion to Frankie (LM-agency) for setups where the model "
                + "cannot carry loop discipline.";
    }

    @Override
    public String version() {
        return VERSION;
    }

    /**
     * Benjy runs long multi-worker turns — an orchestrator steering it
     * must not block on the lane {@code .get()}. Progress and completion
     * arrive via {@code ProcessEvent}s, like Marvin.
     */
    @Override
    public boolean asyncSteer() {
        return true;
    }

    /**
     * Benjy's engine-side tool surface: the work-target wrapper layer
     * ({@code exec_run} for the mechanical check) plus read-side doc
     * tools ({@code doc_read} for criteria source injection). Benjy
     * itself exposes no tool manifest to any LLM — its controller calls
     * are LightLm single-shots without tools (§11).
     */
    private static final java.util.Set<String> ENGINE_DEFAULT_TOOLS;

    static {
        java.util.Set<String> tools = new java.util.LinkedHashSet<>();
        tools.addAll(de.mhus.vance.brain.tools.worktarget.BaseEngineTools.WORK_TARGET);
        tools.add("doc_read");
        ENGINE_DEFAULT_TOOLS = java.util.Set.copyOf(tools);
    }

    @Override
    public java.util.Set<String> allowedTools() {
        return ENGINE_DEFAULT_TOOLS;
    }

    // ──────────────────── Lifecycle ────────────────────

    @Override
    public void start(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.info(
                "Benjy.start tenant='{}' session='{}' id='{}'",
                process.getTenantId(),
                process.getSessionId(),
                process.getId());
        BenjyState state = loadState(process);
        boolean fresh = state.getQueue().isEmpty()
                && state.getItems().isEmpty()
                && state.getCriteria().isEmpty()
                && state.getInFlight() == null;
        if (fresh) {
            state.setGoal(process.getGoal());
            if (state.getGoal() != null && !state.getGoal().isBlank()) {
                enqueueTask(state, BenjyTaskTypes.INTERPRET, null);
            }
            persistState(process, state);
        }
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
    }

    @Override
    public void resume(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.debug("Benjy.resume id='{}'", process.getId());
        // The queue may hold ready tasks from before the suspend — resume
        // means "keep popping" (§4a Stop/Restart contract), not "wait for
        // the next message". Pending messages (if any) drain through the
        // normal runTurn wake after this.
        runLoop(process, ctx);
    }

    @Override
    public void suspend(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.debug("Benjy.suspend id='{}'", process.getId());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.SUSPENDED);
    }

    @Override
    public void steer(ThinkProcessDocument process, ThinkEngineContext ctx, SteerMessage message) {
        BenjyState state = loadState(process);
        boolean stateChanged = ingest(process, ctx, state, message);
        if (stateChanged) {
            persistState(process, state);
        }
        runLoop(process, ctx);
    }

    /**
     * Folds the whole drained inbox into queue operations, then runs the
     * loop once per pass (§4a "Immer ansprechbar"). Messages that arrive
     * mid-loop land in the freshly-emptied queue and the next pass picks
     * them up — the standard Auto-Wakeup contract.
     */
    @Override
    public void runTurn(ThinkProcessDocument process, ThinkEngineContext ctx) {
        while (true) {
            List<SteerMessage> drained = ctx.drainPending();
            if (drained.isEmpty()) {
                break;
            }
            BenjyState state = loadState(process);
            boolean changed = false;
            for (SteerMessage msg : drained) {
                changed |= ingest(process, ctx, state, msg);
            }
            if (changed) {
                persistState(process, state);
            }
            runLoop(process, ctx);
        }
    }

    @Override
    public void stop(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.info("Benjy.stop id='{}'", process.getId());
        thinkProcessService.closeProcess(process.getId(), CloseReason.STOPPED);
    }

    @Override
    public ParentReport summarizeForParent(ThinkProcessDocument process, ProcessEventType eventType) {
        BenjyState state = loadState(process);
        if (state.getFinalReport() != null && !state.getFinalReport().isBlank()) {
            return ParentReport.of(state.getFinalReport());
        }
        if (state.getPendingQuestion() != null && !state.getPendingQuestion().isBlank()) {
            return ParentReport.of(state.getPendingQuestion());
        }
        return ParentReport.of("Child process " + process.getId() + " status="
                + eventType.name().toLowerCase());
    }

    // ──────────────────── Ingest: messages → queue operations ────────────────────

    /**
     * Translates one inbound message into queue operations. Returns
     * {@code true} when the state changed and needs persisting. Two
     * layers, one translation point (§4a): the queue is the operational
     * effect, the dialogue turn is the memory (§22).
     */
    private boolean ingest(ThinkProcessDocument process, ThinkEngineContext ctx, BenjyState state, SteerMessage msg) {
        switch (msg) {
            case SteerMessage.UserChatInput uci -> {
                appendDialogue(process, ctx, ChatRole.USER, uci.content());
                if (state.getPendingQuestion() != null) {
                    applyCheckpointAnswer(state);
                    state.setPendingQuestion(null);
                    enqueueRoute(state, "The user/parent answered the open question. Message:\n" + uci.content());
                } else if (state.getInterpretedGoal() == null) {
                    if (state.getGoal() == null || state.getGoal().isBlank()) {
                        state.setGoal(uci.content());
                    }
                    if (state.getQueue().stream().noneMatch(t -> BenjyTaskTypes.INTERPRET.equals(t.getType()))) {
                        enqueueTask(state, BenjyTaskTypes.INTERPRET, null);
                    }
                } else {
                    enqueueRoute(state, "New user/parent message while running:\n" + uci.content());
                }
                return true;
            }
            case SteerMessage.Reply reply -> {
                BenjyState.InFlight inFlight = state.getInFlight();
                if (inFlight == null || !reply.sourceProcessId().equals(inFlight.getWorkerProcessId())) {
                    log.debug(
                            "Benjy id='{}' ignoring reply from non-in-flight source '{}'",
                            process.getId(),
                            reply.sourceProcessId());
                    return false;
                }
                completeDoTask(process, ctx, state, inFlight, reply.content());
                return true;
            }
            case SteerMessage.ProcessEvent event -> {
                BenjyState.InFlight inFlight = state.getInFlight();
                if (inFlight != null && event.sourceProcessId().equals(inFlight.getWorkerProcessId())) {
                    // Terminal event for the in-flight worker without a prior
                    // reply: the worker died before answering — a branch.
                    completeDoTask(
                            process,
                            ctx,
                            state,
                            inFlight,
                            "(worker terminated with " + event.type() + " before replying"
                                    + (event.humanSummary() != null ? ": " + event.humanSummary() : "")
                                    + ")");
                }
                return inFlight != null;
            }
            case SteerMessage.ToolResult tr -> {
                log.debug("Benjy id='{}' ignoring ToolResult tool='{}'", process.getId(), tr.toolName());
                return false;
            }
            case SteerMessage.ExternalCommand ec -> {
                log.info(
                        "Benjy id='{}' external command '{}' — routed by the framework", process.getId(), ec.command());
                return false;
            }
            case SteerMessage.InboxAnswer ia -> {
                log.debug("Benjy id='{}' ignoring InboxAnswer item='{}'", process.getId(), ia.inboxItemId());
                return false;
            }
            case SteerMessage.PeerEvent pe -> {
                log.debug("Benjy id='{}' ignoring PeerEvent type='{}'", process.getId(), pe.type());
                return false;
            }
        }
    }

    /**
     * A do-task finished: record the worker's reply, enqueue the item's
     * remaining chain, release the in-flight slot and close the worker
     * process (it is IDLE after its reply — no reason to keep it).
     */
    private void completeDoTask(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            BenjyState state,
            BenjyState.InFlight inFlight,
            @Nullable String workerReply) {
        findItem(state, inFlight.getItemId()).ifPresent(item -> {
            item.setLastResult(workerReply);
            item.addFact("worker attempt " + item.getAttempts() + " finished");
        });
        state.setInFlight(null);
        enqueueChain(state, inFlight.getItemId(), inFlight.getRemainingChain());
        journal(
                ctx,
                process,
                state,
                "do #" + inFlight.getItemId() + ": worker replied (" + (workerReply == null ? 0 : workerReply.length())
                        + " chars)");
        try {
            thinkProcessService.closeProcess(inFlight.getWorkerProcessId(), CloseReason.STOPPED);
        } catch (RuntimeException ex) {
            log.debug(
                    "Benjy id='{}' could not close doer '{}' (already closed?): {}",
                    process.getId(),
                    inFlight.getWorkerProcessId(),
                    ex.toString());
        }
    }

    // ──────────────────── The loop ────────────────────

    private void runLoop(ThinkProcessDocument process, ThinkEngineContext ctx) {
        BenjyState state = loadState(process);
        // One continuous runLoop invocation = one work phase (§6:
        // "pro laufender Arbeit", Frankie semantics). The wallclock
        // budget must not measure time the process spent IDLE, BLOCKED
        // on a question or suspended — a run resumed after a long wait
        // would otherwise hit the budget before doing anything. Every
        // entry point (steer, reply-wake, resume) starts the phase anew;
        // within one invocation the loop runs without yielding, so the
        // budget bounds exactly that continuous stretch.
        state.setPhaseStartedAt(Instant.now());
        BenjyFeatureConfig features =
                BenjyFeatureConfig.fromParams(EngineChatFactory.effectiveParams(process), process.getId());
        Map<String, Object> rawParams = EngineChatFactory.effectiveParams(process);
        int maxStagnation = intParam(rawParams, PARAM_MAX_STAGNATION, DEFAULT_MAX_STAGNATION);
        int maxReflectNo = intParam(rawParams, PARAM_MAX_REFLECT_NO, DEFAULT_MAX_REFLECT_NO);
        int maxItemAttempts = intParam(rawParams, PARAM_MAX_ITEM_ATTEMPTS, DEFAULT_MAX_ITEM_ATTEMPTS);
        int maxWallclockMinutes = intParam(rawParams, PARAM_MAX_WALLCLOCK_MINUTES, DEFAULT_MAX_WALLCLOCK_MINUTES);
        long maxTokens = longParam(rawParams, PARAM_MAX_TOKENS, DEFAULT_MAX_TOKENS);

        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.RUNNING);
        try {
            while (true) {
                // ── Interrupt: framework status flips and the halt flag ──
                ThinkProcessStatus live = liveStatus(process);
                if (live == ThinkProcessStatus.PAUSED
                        || live == ThinkProcessStatus.SUSPENDED
                        || live == ThinkProcessStatus.CLOSED) {
                    persistState(process, state);
                    return;
                }
                if (thinkProcessService.isHaltRequested(process.getId())) {
                    thinkProcessService.clearHalt(process.getId());
                    persistState(process, state);
                    thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.PAUSED);
                    return;
                }

                // ── Async boundaries: waiting for a worker / a parent answer ──
                if (state.getInFlight() != null) {
                    persistState(process, state);
                    thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
                    log.debug(
                            "Benjy id='{}' waiting for doer '{}' — IDLE",
                            process.getId(),
                            state.getInFlight().getWorkerProcessId());
                    return;
                }
                if (state.getPendingQuestion() != null) {
                    persistState(process, state);
                    thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.BLOCKED);
                    log.info(
                            "Benjy id='{}' BLOCKED on question ({} chars)",
                            process.getId(),
                            state.getPendingQuestion().length());
                    return;
                }

                // ── Queue empty: reflect gate or done ──
                if (state.getQueue().isEmpty()) {
                    if (state.getInterpretedGoal() == null
                            && state.getItems().isEmpty()
                            && state.getCriteria().isEmpty()) {
                        // Nothing interpreted yet — no goal, no criteria,
                        // no items (a resume or a stray steer on a fresh
                        // process). There is nothing to reflect on and
                        // nothing to close: "closing" here would emit a
                        // DONE report around a null goal. Wait for the first
                        // task text instead.
                        persistState(process, state);
                        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
                        return;
                    }
                    if (!state.isReflected() && allItemsTerminal(state)) {
                        if (features.getReflectRecipe() != null) {
                            enqueueTask(state, BenjyTaskTypes.REFLECT, null);
                        } else {
                            enqueueTask(state, BenjyTaskTypes.DONE, null);
                        }
                    } else if (allItemsTerminal(state)) {
                        // Reflect already ran (route decided done after gaps
                        // were worked, or an empty run) — close.
                        enqueueTask(state, BenjyTaskTypes.DONE, null);
                    } else {
                        // Open items with an empty queue is a branch, not a
                        // done: a chain was dropped somewhere (unknown
                        // itemRef, reset mid-chain). Route decides.
                        enqueueRoute(
                                state, "The queue is empty but items are still open — " + "decide how to continue.");
                    }
                }

                // ── Safety nets (§6) — mechanics, not prompts ──
                // Stagnation: volume is not danger, standing still is.
                // The streak counts executed tasks that produced no
                // observable forward progress — productive work of any
                // length never trips it. The terminal gates (reflect/done)
                // always get to run first: their convergence is guarded
                // by the reflect-no cap, not by stagnation.
                BenjyState.QueuedTask head =
                        state.getQueue().isEmpty() ? null : state.getQueue().getFirst();
                boolean headIsTerminalGate = head != null
                        && (BenjyTaskTypes.REFLECT.equals(head.getType())
                                || BenjyTaskTypes.DONE.equals(head.getType()));
                if (!headIsTerminalGate && state.getCounters().getNoProgressStreak() >= maxStagnation) {
                    if (!escalateAfterStagnation(process, ctx, state, features)) {
                        parkCheckpoint(
                                process,
                                ctx,
                                state,
                                CHECKPOINT_STAGNATION,
                                "No observable progress for "
                                        + state.getCounters().getNoProgressStreak()
                                        + " executed tasks (param maxStagnation="
                                        + maxStagnation
                                        + "). Open items: "
                                        + openItemsDescription(state));
                        persistState(process, state);
                        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.BLOCKED);
                        return;
                    }
                }
                if (Duration.between(state.getPhaseStartedAt(), Instant.now())
                                .compareTo(Duration.ofMinutes(maxWallclockMinutes))
                        > 0) {
                    parkCheckpoint(
                            process,
                            ctx,
                            state,
                            CHECKPOINT_WALLCLOCK,
                            "Wallclock budget: more than "
                                    + maxWallclockMinutes
                                    + " minutes of continuous work in this phase (param "
                                    + PARAM_MAX_WALLCLOCK_MINUTES
                                    + "), "
                                    + state.getCounters().getRounds()
                                    + " tasks executed so far.");
                    persistState(process, state);
                    thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.BLOCKED);
                    return;
                }
                long tokensConsumed = state.getCounters().getTokens() - state.getTokenBudgetOffset();
                if (maxTokens > 0 && tokensConsumed > maxTokens) {
                    parkCheckpoint(
                            process,
                            ctx,
                            state,
                            CHECKPOINT_TOKENS,
                            "Controller token budget: "
                                    + tokensConsumed
                                    + " tokens across "
                                    + state.getCounters().getLlmCalls()
                                    + " LightLm calls (param "
                                    + PARAM_MAX_TOKENS
                                    + "="
                                    + maxTokens
                                    + ").");
                    persistState(process, state);
                    thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.BLOCKED);
                    return;
                }

                // ── Pop (queue transition persisted BEFORE the side effects) ──
                BenjyState.QueuedTask task = state.getQueue().removeFirst();
                state.getCounters().setRounds(state.getCounters().getRounds() + 1);
                persistState(process, state);
                metricService.counter(METRIC_CYCLES).increment();

                try {
                    boolean progress = executeTask(process, ctx, state, features, task, maxItemAttempts, maxReflectNo);
                    // Stagnation accounting (§6): only observable forward
                    // progress resets the streak — an item reaching a
                    // terminal state, a criterion transition, new items or
                    // criteria, or a terminal gate running. Churn (retries,
                    // spawns, checks, verdicts without state effect) grows it.
                    if (progress) {
                        state.getCounters().setNoProgressStreak(0);
                        state.setStagnationEscalated(false);
                    } else {
                        state.getCounters()
                                .setNoProgressStreak(state.getCounters().getNoProgressStreak() + 1);
                    }
                } catch (SchemaValidationException e) {
                    // The LightLm schema-retry budget is exhausted — the small
                    // model could not produce a valid decision. That is a
                    // branch Benjy cannot mechanise away: park BLOCKED with
                    // the diagnosis instead of looping (schema exhaustion is
                    // an escalation trigger, §2.1).
                    blockWith(
                            process,
                            ctx,
                            state,
                            "Controller call '" + task.getType() + "' exhausted its schema-retry budget: "
                                    + e.getMessage());
                    return;
                } catch (RuntimeException e) {
                    log.warn("Benjy id='{}' task '{}' failed: {}", process.getId(), task.getType(), e.toString());
                    metricService
                            .counter(METRIC_OUTCOMES, "outcome", OUTCOME_ERROR)
                            .increment();
                    blockWith(
                            process,
                            ctx,
                            state,
                            "Task '" + task.getType() + "' failed with an engine error: " + e.getMessage());
                    return;
                }

                persistState(process, state);
                if (liveStatus(process) == ThinkProcessStatus.CLOSED) {
                    return;
                }
            }
        } finally {
            // The loop returns with the right status already set at every
            // exit point; nothing to normalise here. The finally exists so
            // a crashed loop never silently leaves RUNNING behind.
            if (liveStatus(process) == ThinkProcessStatus.RUNNING) {
                thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.BLOCKED);
            }
        }
    }

    /**
     * Executes one popped task and reports whether it produced observable
     * forward progress — the stagnation metric (§6): an item reaching a
     * terminal state, a criterion status transition, new items or criteria
     * being created, or a terminal gate (reflect/done) running. Churn —
     * retries, spawns, checks, verdicts without state effect — reports
     * {@code false} and grows the stagnation streak.
     */
    private boolean executeTask(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            BenjyState state,
            BenjyFeatureConfig features,
            BenjyState.QueuedTask task,
            int maxItemAttempts,
            int maxReflectNo) {
        switch (task.getType()) {
            case BenjyTaskTypes.INTERPRET -> {
                return handleInterpret(process, ctx, state, features);
            }
            case BenjyTaskTypes.ROUTE -> {
                return handleRoute(process, ctx, state, features, task, maxItemAttempts);
            }
            case BenjyTaskTypes.DO -> {
                handleDo(process, ctx, state, features, task);
                return false; // a spawn alone moves nothing — the chain's close/eval will
            }
            case BenjyTaskTypes.CHECK -> {
                handleCheck(process, ctx, state, features, task);
                return false; // facts are not progress — the chain's verdict steps are
            }
            case BenjyTaskTypes.EVALUATE -> {
                return handleEvaluate(process, ctx, state, features, task, maxItemAttempts);
            }
            case BenjyTaskTypes.REFLECT -> {
                // The terminal gate always runs (§6) — its convergence is
                // guarded by the reflect-no cap, never by stagnation.
                return handleReflect(process, ctx, state, features, maxReflectNo);
            }
            case BenjyTaskTypes.CLOSE_ITEM -> {
                handleCloseItem(process, ctx, state, task);
                return true; // terminal item state
            }
            case BenjyTaskTypes.DONE -> {
                handleDone(process, ctx, state);
                return true;
            }
            default -> {
                log.warn("Benjy id='{}' unknown task type '{}' — dropping", process.getId(), task.getType());
                journal(ctx, process, state, "dropped unknown task type " + task.getType());
                return false;
            }
        }
    }

    // ──────────────────── Handlers ────────────────────

    /** Interpret call #0 — goal → taskType, criteria, first 1–3 items (minimal rule §4). */
    private boolean handleInterpret(
            ThinkProcessDocument process, ThinkEngineContext ctx, BenjyState state, BenjyFeatureConfig features) {
        String goal = state.getGoal() == null ? "" : state.getGoal();
        if (goal.isBlank()) {
            blockWith(process, ctx, state, "Interpret ran without a goal — no task text ever arrived.");
            return false;
        }
        StringBuilder prompt = new StringBuilder("## Original task\n").append(goal);
        for (String source : features.getCriteriaSources()) {
            String content = readCriteriaSource(process, ctx, source);
            if (content != null) {
                prompt.append("\n\n## Criteria source: ")
                        .append(source)
                        .append("\n")
                        .append(content, 0, Math.min(content.length(), 3000));
            }
        }
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties",
                        Map.of(
                                "interpretedGoal", Map.of("type", "string"),
                                "taskType", Map.of("type", "string", "enum", List.copyOf(features.getTaskTypes())),
                                "acceptanceCriteria",
                                        Map.of(
                                                "type",
                                                "array",
                                                "items",
                                                Map.of(
                                                        "type", "object",
                                                        "properties",
                                                                Map.of(
                                                                        "text", Map.of("type", "string"),
                                                                        "sourceRef", Map.of("type", "string")),
                                                        "required", List.of("text"))),
                                "openQuestions", Map.of("type", "array", "items", Map.of("type", "string")),
                                "initialItems",
                                        Map.of(
                                                "type",
                                                "array",
                                                "items",
                                                Map.of("type", "string"),
                                                "description",
                                                "The first 1-3 concrete work items. Never a full plan — "
                                                        + "decomposition continues incrementally at branches.")),
                "required", List.of("interpretedGoal", "taskType", "acceptanceCriteria", "initialItems"));
        Map<String, Object> answer = callLlm(process, state, features.getInterpretRecipe(), prompt.toString(), schema);

        state.setInterpretedGoal(stringValue(answer.get("interpretedGoal"), goal));
        String taskType = stringValue(answer.get("taskType"), BenjyFeatureConfig.TASK_TYPE_INFO);
        if (!features.isTaskTypeAllowed(taskType)) {
            journal(ctx, process, state, "interpret: taskType '" + taskType + "' not active in recipe — using info");
            taskType = BenjyFeatureConfig.TASK_TYPE_INFO;
        }
        state.setTaskType(taskType);

        List<BenjyState.Criterion> criteria = new ArrayList<>();
        int c = 1;
        for (Object raw : listValue(answer.get("acceptanceCriteria"))) {
            String text = null;
            String sourceRef = null;
            if (raw instanceof Map<?, ?> m) {
                text = stringValue(m.get("text"), null);
                sourceRef = stringValue(m.get("sourceRef"), null);
            } else if (raw instanceof String s) {
                text = s;
            }
            if (text != null && !text.isBlank()) {
                criteria.add(BenjyState.Criterion.of("c" + c++, text.trim(), sourceRef));
            }
        }
        state.setCriteria(criteria);

        // Weak-criteria guard (§4 amendment): criteria quality is the single
        // point of failure of the "well solved is a checkable list" premise.
        // Empty criteria after a successful interpret ⇒ ask, don't guess.
        if (criteria.isEmpty()) {
            parkOnQuestion(
                    process,
                    ctx,
                    state,
                    "I interpreted the goal but could not derive any acceptance criteria:\n\n"
                            + state.getInterpretedGoal()
                            + "\n\nWhat does 'well solved' mean here — which concrete, "
                            + "checkable criteria must the result satisfy?");
            return false;
        }
        List<String> openQuestions = new ArrayList<>();
        for (Object q : listValue(answer.get("openQuestions"))) {
            if (q instanceof String s && !s.isBlank()) {
                openQuestions.add(s.trim());
            }
        }
        if (!openQuestions.isEmpty()) {
            parkOnQuestion(
                    process,
                    ctx,
                    state,
                    "Before I start, I need clarification:\n\n- " + String.join("\n- ", openQuestions));
            return false;
        }

        List<String> itemTexts = new ArrayList<>();
        for (Object raw : listValue(answer.get("initialItems"))) {
            if (raw instanceof String s && !s.isBlank()) {
                itemTexts.add(s.trim());
            }
        }
        int initialCap = intParam(
                EngineChatFactory.effectiveParams(process), PARAM_MAX_INITIAL_ITEMS, DEFAULT_MAX_INITIAL_ITEMS);
        List<String> capped = capItems(itemTexts, initialCap);
        if (capped.size() < itemTexts.size()) {
            journal(ctx, process, state, capNotice("interpret", itemTexts.size(), capped.size()));
        }
        createItems(process, state, capped);
        for (BenjyState.Item item : state.getItems()) {
            if ("pending".equals(item.getStatus())) {
                enqueueDoChain(state, features, item, null);
            }
        }
        journal(
                ctx,
                process,
                state,
                "interpret: taskType=" + taskType + ", " + criteria.size() + " criteria, " + itemTexts.size()
                        + " items");
        // Criteria and items were created — observable forward progress (§6).
        return true;
    }
    /** Route call — fires only at branches; emits queue operations. */
    private boolean handleRoute(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            BenjyState state,
            BenjyFeatureConfig features,
            BenjyState.QueuedTask task,
            int maxItemAttempts) {
        String trigger = stringValue(task.getPayload().get("trigger"), "(no trigger note)");
        if (features.getRouteRecipe() == null) {
            // Billig-Modus (§4d): route off → mechanical fallback policy.
            return mechanicalFallback(process, ctx, state, features, trigger, maxItemAttempts);
        }
        String digest = BenjyDigest.render(state, trigger);
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties",
                        Map.of(
                                "action",
                                        Map.of(
                                                "type",
                                                "string",
                                                "enum",
                                                List.of(
                                                        "retry",
                                                        "split",
                                                        "revise",
                                                        "escalate",
                                                        "ask_parent",
                                                        "done",
                                                        "reset",
                                                        "blocked")),
                                "itemRef",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "The item id (without '#') an action applies to, when applicable."),
                                "items", Map.of("type", "array", "items", Map.of("type", "string")),
                                "criteria",
                                        Map.of(
                                                "type",
                                                "array",
                                                "items",
                                                Map.of(
                                                        "type",
                                                        "object",
                                                        "properties",
                                                        Map.of(
                                                                "text", Map.of("type", "string"),
                                                                "sourceRef", Map.of("type", "string")),
                                                        "required",
                                                        List.of("text"))),
                                "question", Map.of("type", "string"),
                                "reason", Map.of("type", "string")),
                "required", List.of("action", "reason"));
        Map<String, Object> answer = callLlm(process, state, features.getRouteRecipe(), digest, schema);

        String action = stringValue(answer.get("action"), "blocked");
        String reason = stringValue(answer.get("reason"), "(no reason given)");
        String itemRef = stringValue(answer.get("itemRef"), null);

        // Stuck detection (§6): the same decision on an unchanged state.
        String routeKey = action + ":" + (itemRef == null ? "" : itemRef) + ":" + reason.hashCode();
        if (routeKey.equals(state.getLastRouteKey())) {
            state.setSameRouteCount(state.getSameRouteCount() + 1);
        } else {
            state.setLastRouteKey(routeKey);
            state.setSameRouteCount(1);
        }
        if (state.getSameRouteCount() >= STUCK_ROUTE_LIMIT) {
            blockWith(
                    process,
                    ctx,
                    state,
                    "Route stuck: the same decision ('" + action + "': " + reason
                            + ") repeated " + state.getSameRouteCount()
                            + " times on an unchanged state.");
            return false;
        }

        journal(ctx, process, state, "route: " + action + " — " + reason);
        switch (action) {
            case "retry" -> {
                BenjyState.Item item = requireItem(process, ctx, state, itemRef).orElse(null);
                if (item == null) {
                    return false;
                }
                if (item.getAttempts() >= maxItemAttempts) {
                    // The route wants a retry the item no longer has budget
                    // for. The cap is the cap (§6): demote to failed — a
                    // terminal state with the attempt facts — and let the
                    // reflect gate decide the exit when the queue drains.
                    // Deliberately no todos projection update: TodoStatus
                    // has no FAILED; the final report lists the item.
                    item.setStatus("failed");
                    item.addFact("route retry refused — attempt budget (" + maxItemAttempts + ") exhausted");
                    journal(
                            ctx,
                            process,
                            state,
                            "route retry on #" + item.getId() + " refused: attempt budget exhausted — item failed");
                    return true;
                }
                enqueueDoChain(state, features, item, "Retry after: " + reason);
                return false;
            }
            case "split" -> {
                List<String> texts = new ArrayList<>();
                for (Object raw : listValue(answer.get("items"))) {
                    if (raw instanceof String s && !s.isBlank()) {
                        texts.add(s.trim());
                    }
                }
                int splitCap = intParam(
                        EngineChatFactory.effectiveParams(process), PARAM_MAX_INITIAL_ITEMS, DEFAULT_MAX_INITIAL_ITEMS);
                List<String> capped = capItems(texts, splitCap);
                if (capped.size() < texts.size()) {
                    journal(ctx, process, state, capNotice("split", texts.size(), capped.size()));
                }
                createItems(process, state, capped);
                for (BenjyState.Item item : state.getItems()) {
                    if ("pending".equals(item.getStatus())) {
                        enqueueDoChain(state, features, item, null);
                    }
                }
                // New items were created — observable forward progress (§6).
                return !capped.isEmpty();
            }
            case "revise" -> {
                List<BenjyState.Criterion> revised = new ArrayList<>();
                int c = 1;
                for (Object raw : listValue(answer.get("criteria"))) {
                    String text = null;
                    String sourceRef = null;
                    if (raw instanceof Map<?, ?> m) {
                        text = stringValue(m.get("text"), null);
                        sourceRef = stringValue(m.get("sourceRef"), null);
                    } else if (raw instanceof String s) {
                        text = s;
                    }
                    if (text != null && !text.isBlank()) {
                        revised.add(BenjyState.Criterion.of("c" + c++, text.trim(), sourceRef));
                    }
                }
                if (!revised.isEmpty()) {
                    state.setCriteria(revised);
                    journal(ctx, process, state, "revise: " + revised.size() + " criteria");
                }
                List<String> texts = new ArrayList<>();
                for (Object raw : listValue(answer.get("items"))) {
                    if (raw instanceof String s && !s.isBlank()) {
                        texts.add(s.trim());
                    }
                }
                if (!texts.isEmpty()) {
                    int reviseCap = intParam(
                            EngineChatFactory.effectiveParams(process),
                            PARAM_MAX_INITIAL_ITEMS,
                            DEFAULT_MAX_INITIAL_ITEMS);
                    List<String> capped = capItems(texts, reviseCap);
                    if (capped.size() < texts.size()) {
                        journal(ctx, process, state, capNotice("revise", texts.size(), capped.size()));
                    }
                    createItems(process, state, capped);
                    for (BenjyState.Item item : state.getItems()) {
                        if ("pending".equals(item.getStatus())) {
                            enqueueDoChain(state, features, item, null);
                        }
                    }
                }
                // Revised criteria and new items are observable forward progress (§6).
                return !revised.isEmpty() || !texts.isEmpty();
            }
            case "escalate" -> {
                if (features.getEscalationRecipe() == null) {
                    blockWith(
                            process,
                            ctx,
                            state,
                            "Route decided to escalate, but no escalation feature is configured "
                                    + "(params.features.escalation.recipe).");
                    return false;
                }
                BenjyState.Item item = requireItem(process, ctx, state, itemRef).orElse(null);
                if (item != null) {
                    item.addFact("escalated: " + reason);
                    enqueueDoChain(state, features, item, null, features.getEscalationRecipe());
                }
                // The escalation shows in the item's chain verdict — not progress yet (§6).
                return false;
            }
            case "ask_parent" -> {
                parkOnQuestion(
                        process, ctx, state, stringValue(answer.get("question"), "Route needs a decision: " + reason));
                return false;
            }
            case "done" -> {
                // Reflect has precedence over route's done — but only once:
                // after a reflect run, route-done closes (§4c).
                enqueueTask(
                        state,
                        features.getReflectRecipe() != null && !state.isReflected()
                                ? BenjyTaskTypes.REFLECT
                                : BenjyTaskTypes.DONE,
                        null);
                return false;
            }
            case "reset" -> {
                resetState(process, state, state.getGoal());
                enqueueTask(state, BenjyTaskTypes.INTERPRET, null);
                return false;
            }
            default -> {
                blockWith(process, ctx, state, "Route decided to block: " + reason);
                return false;
            }
        }
    }

    /**
     * Mechanical fallback when the route feature is off (Billig-Modus):
     * retry the open item until its budget, escalate if configured,
     * otherwise BLOCKED.
     */
    private boolean mechanicalFallback(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            BenjyState state,
            BenjyFeatureConfig features,
            String trigger,
            int maxItemAttempts) {
        BenjyState.Item open = state.getItems().stream()
                .filter(i -> !i.isTerminal())
                .findFirst()
                .orElse(null);
        if (open == null) {
            enqueueTask(
                    state, features.getReflectRecipe() != null ? BenjyTaskTypes.REFLECT : BenjyTaskTypes.DONE, null);
            return false;
        }
        if (open.getAttempts() < maxItemAttempts) {
            journal(ctx, process, state, "route(off): retrying #" + open.getId() + " after " + trigger);
            enqueueDoChain(state, features, open, "Retry after: " + trigger);
            return false;
        }
        if (features.getEscalationRecipe() != null) {
            journal(ctx, process, state, "route(off): escalating #" + open.getId());
            enqueueDoChain(state, features, open, null, features.getEscalationRecipe());
            return false;
        }
        blockWith(
                process,
                ctx,
                state,
                "Route feature is off and item #" + open.getId() + " exhausted its attempt budget (" + maxItemAttempts
                        + ").");
        return false;
    }

    /** Spawns a focused Ford worker for one item; iteration belongs to Benjy, not the doer (#5, #16). */
    private void handleDo(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            BenjyState state,
            BenjyFeatureConfig features,
            BenjyState.QueuedTask task) {
        String itemId = task.getItemRef();
        BenjyState.Item item = findItem(state, itemId).orElse(null);
        if (item == null) {
            journal(ctx, process, state, "do: unknown item " + itemId + " — skipping");
            return;
        }
        item.setAttempts(item.getAttempts() + 1);
        item.setStatus("in_progress");
        projectItemStatus(process, item, TodoStatus.IN_PROGRESS);

        List<String> chain = stringListValue(task.getPayload().get("chain"));
        String recipe = stringValue(task.getPayload().get("recipe"), features.getDoRecipe());
        String context = stringValue(task.getPayload().get("context"), null);

        Map<String, Object> extraParams = new LinkedHashMap<>();
        int maxToolCalls =
                intParam(EngineChatFactory.effectiveParams(process), PARAM_MAX_TOOL_CALLS, DEFAULT_MAX_TOOL_CALLS);
        extraParams.put("maxIterations", maxToolCalls);
        Object thinking = task.getPayload().get("thinking");
        if (thinking != null) {
            extraParams.put("thinking", thinking);
        }
        // Pin the worker to the SAME workspace RootDir Benjy's own
        // mechanical check runs in. A WORK target with a null targetName
        // resolves per-process at dispatch time — Benjy's check and the
        // doer would land in two different temp RootDirs and the check
        // would verify a directory nobody wrote to. Pinning the resolved
        // name here makes doer and check share one workspace.
        String workTargetName = resolveWorkTargetName(process);
        if (workTargetName != null) {
            extraParams.put("workTarget", Map.of("kind", WorkTargetKind.WORK.name(), "targetName", workTargetName));
        }

        String prompt = buildDoPrompt(state, item, context);
        String workerId = workerSpawner.spawn(process, recipe, extraParams, prompt);
        BenjyState.InFlight inFlight = new BenjyState.InFlight();
        inFlight.setTaskId(task.getId());
        inFlight.setWorkerProcessId(workerId);
        inFlight.setItemId(item.getId());
        inFlight.setRemainingChain(chain);
        state.setInFlight(inFlight);
        journal(
                ctx,
                process,
                state,
                "do #" + item.getId() + " (attempt " + item.getAttempts() + "): spawned worker, recipe=" + recipe);
    }

    private void handleCheck(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            BenjyState state,
            BenjyFeatureConfig features,
            BenjyState.QueuedTask task) {
        BenjyState.Item item = findItem(state, task.getItemRef()).orElse(null);
        if (item == null) {
            return;
        }
        String command = features.getCheckCommand();
        if (command == null) {
            // Feature without command — treat as pass-through (misconfig was
            // already visible at spawn validation; here we degrade loudly).
            item.addFact("check skipped: no check command configured");
            enqueueChain(state, item.getId(), stringListValue(task.getPayload().get("chain")));
            return;
        }
        Map<String, Object> result;
        try {
            result = ctx.tools().invoke("exec_run", Map.of("command", command, "waitMs", CHECK_WAIT_MS));
        } catch (RuntimeException e) {
            item.addFact("check CRASHED: " + truncate(e.toString(), 200));
            enqueueRoute(state, "The mechanical check command crashed: " + e.getMessage());
            return;
        }
        Integer exitCode = result.get("exitCode") instanceof Number n ? n.intValue() : null;
        String stdout = String.valueOf(result.getOrDefault("stdout", ""));
        String stderr = String.valueOf(result.getOrDefault("stderr", ""));
        if (exitCode != null && exitCode == 0) {
            item.addFact("check pass: " + command + " (exit 0)");
            enqueueChain(state, item.getId(), stringListValue(task.getPayload().get("chain")));
            journal(ctx, process, state, "check #" + item.getId() + ": passed (exit 0)");
        } else {
            item.addFact("check FAILED: " + command + " exit " + exitCode + " — "
                    + truncate(stderr.isBlank() ? stdout : stderr, 300));
            enqueueRoute(
                    state,
                    "The mechanical check for item #" + item.getId() + " failed (exit " + exitCode + "). Error tail:\n"
                            + truncate(stderr.isBlank() ? stdout : stderr, 1500));
            journal(ctx, process, state, "check #" + item.getId() + ": FAILED (exit " + exitCode + ")");
        }
    }

    private boolean handleEvaluate(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            BenjyState state,
            BenjyFeatureConfig features,
            BenjyState.QueuedTask task,
            int maxItemAttempts) {
        BenjyState.Item item = findItem(state, task.getItemRef()).orElse(null);
        if (item == null) {
            return false;
        }
        if (features.getEvaluateRecipe() == null) {
            // Feature off in the meantime (reset) — treat as pass-through.
            enqueueChain(state, item.getId(), stringListValue(task.getPayload().get("chain")));
            return false;
        }
        String view = BenjyDigest.renderItem(state, item, item.getLastResult());
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties",
                        Map.of(
                                "verdict", Map.of("type", "string", "enum", List.of("pass", "fail", "needs_change")),
                                "reasons", Map.of("type", "array", "items", Map.of("type", "string")),
                                "missing",
                                        Map.of(
                                                "type", "array",
                                                "items", Map.of("type", "string"),
                                                "description",
                                                        "Ids of acceptance criteria this result does not satisfy.")),
                "required", List.of("verdict"));
        Map<String, Object> answer = callLlm(process, state, features.getEvaluateRecipe(), view, schema);

        String verdict = stringValue(answer.get("verdict"), "fail");
        List<String> reasons = new ArrayList<>();
        for (Object r : listValue(answer.get("reasons"))) {
            if (r instanceof String s && !s.isBlank()) {
                reasons.add(s.trim());
            }
        }
        boolean criteriaChanged = false;
        for (Object m : listValue(answer.get("missing"))) {
            String id = stringValue(m, null);
            if (id != null) {
                for (BenjyState.Criterion c : state.getCriteria()) {
                    if (c.getId().equals(id) && !"fail".equals(c.getStatus())) {
                        c.setStatus("fail");
                        c.setEvidence(String.join("; ", reasons));
                        criteriaChanged = true;
                    }
                }
            }
        }
        journal(
                ctx,
                process,
                state,
                "eval #" + item.getId() + ": " + verdict + (reasons.isEmpty() ? "" : " — " + reasons.getFirst()));
        if ("pass".equals(verdict)) {
            enqueueChain(state, item.getId(), stringListValue(task.getPayload().get("chain")));
            return criteriaChanged;
        }
        if (item.getAttempts() < maxItemAttempts) {
            // Mechanical retry (#11): eval fail → do again with error context,
            // no route call — the budget number bends this, not the LLM.
            item.addFact("eval " + verdict + ": " + String.join("; ", reasons));
            enqueueDoChain(
                    state, features, item, "The previous attempt failed verification: " + String.join("; ", reasons));
        } else {
            item.addFact("eval " + verdict + " after " + item.getAttempts() + " attempts — budget exhausted");
            enqueueRoute(
                    state,
                    "Item #" + item.getId() + " exhausted its attempt budget ("
                            + maxItemAttempts + "). Last verdict: " + verdict
                            + ". Reasons: " + String.join("; ", reasons));
        }
        // A verdict alone is not progress — a criterion transition is (§6);
        // the item's close step or the route branch supply the rest.
        return criteriaChanged;
    }

    /** Terminal gate — goal level: did we achieve what the asker meant? (§4c) */
    private boolean handleReflect(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            BenjyState state,
            BenjyFeatureConfig features,
            int maxReflectNo) {
        state.setReflected(true);
        if (features.getReflectRecipe() == null) {
            enqueueTask(state, BenjyTaskTypes.DONE, null);
            return true; // the terminal gate ran — stagnation never guards reflect (§6)
        }
        String digest = BenjyDigest.render(state, null);
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties",
                        Map.of(
                                "achieved", Map.of("type", "string", "enum", List.of("yes", "partially", "no")),
                                "gaps",
                                        Map.of(
                                                "type", "array",
                                                "items", Map.of("type", "string"),
                                                "description",
                                                        "Concrete uncovered aspects — new items can be built from these."),
                                "recommendation", Map.of("type", "string")),
                "required", List.of("achieved"));
        Map<String, Object> answer = callLlm(process, state, features.getReflectRecipe(), digest, schema);
        String achieved = stringValue(answer.get("achieved"), "no");
        List<String> gaps = new ArrayList<>();
        for (Object g : listValue(answer.get("gaps"))) {
            if (g instanceof String s && !s.isBlank()) {
                gaps.add(s.trim());
            }
        }
        journal(
                ctx,
                process,
                state,
                "reflect: " + achieved + (gaps.isEmpty() ? "" : " — gaps: " + String.join("; ", gaps)));
        if ("yes".equals(achieved)) {
            for (BenjyState.Criterion c : state.getCriteria()) {
                if ("pending".equals(c.getStatus())) {
                    c.setStatus("pass");
                    c.setEvidence("reflect: achieved");
                }
            }
            enqueueTask(state, BenjyTaskTypes.DONE, null);
            return true;
        }
        // partially / no → no DONE — gaps go to route as a branch. The
        // convergence cap (§6) parks the loop when the controller's
        // verdict stops converging instead of letting the gap loop churn:
        // every 'not achieved' without DONE counts toward maxReflectNo.
        state.setReflectNoCount(state.getReflectNoCount() + 1);
        if (features.getRouteRecipe() == null) {
            blockWith(
                    process,
                    ctx,
                    state,
                    "Reflect verdict is '" + achieved + "' with gaps, but the route feature is off — "
                            + "Benjy cannot react to gaps mechanically. Gaps: "
                            + String.join("; ", gaps));
            return true;
        }
        if (state.getReflectNoCount() >= maxReflectNo) {
            parkCheckpoint(
                    process,
                    ctx,
                    state,
                    CHECKPOINT_REFLECT,
                    "The reflect gate judged the goal '" + achieved + "' " + state.getReflectNoCount()
                            + " times without reaching DONE (param maxReflectNo=" + maxReflectNo
                            + ") — the controller's judgment is not converging. Uncovered gaps: "
                            + String.join("; ", gaps)
                            + ". Failing criteria: "
                            + failingCriteriaDescription(state));
            // The loop's async-boundary check parks BLOCKED on the question.
            return true;
        }
        enqueueRoute(
                state,
                "The final reflection says the goal is '" + achieved
                        + "' — all items are closed but these gaps remain:\n- "
                        + String.join("\n- ", gaps));
        return true;
    }

    private void handleCloseItem(
            ThinkProcessDocument process, ThinkEngineContext ctx, BenjyState state, BenjyState.QueuedTask task) {
        BenjyState.Item item = findItem(state, task.getItemRef()).orElse(null);
        if (item == null) {
            return;
        }
        item.setStatus("completed");
        projectItemStatus(process, item, TodoStatus.COMPLETED);
        journal(ctx, process, state, "close #" + item.getId() + ": item completed");
    }

    private void handleDone(ThinkProcessDocument process, ThinkEngineContext ctx, BenjyState state) {
        String report = buildFinalReport(state);
        state.setFinalReport(report);
        journal(ctx, process, state, "done: closing with report");
        appendDialogue(process, ctx, ChatRole.ASSISTANT, report);
        ctx.emitReply(report);
        metricService.counter(METRIC_OUTCOMES, "outcome", OUTCOME_SUCCESS).increment();
        clearTodosProjection(process, state);
        thinkProcessService.closeProcess(process.getId(), CloseReason.DONE);
    }

    // ──────────────────── Helpers ────────────────────

    /** Creates items via the todos projection (server-assigned ids) and enqueues their do-chains. */
    private void createItems(ThinkProcessDocument process, BenjyState state, List<String> texts) {
        if (texts.isEmpty()) {
            return;
        }
        List<TodoItem> todos = new ArrayList<>(texts.size());
        for (String text : texts) {
            todos.add(TodoItem.builder().content(text).build());
        }
        List<TodoItem> assigned = thinkProcessService.addTodos(process.getId(), todos);
        if (assigned == null) {
            throw new IllegalStateException("Benjy id='" + process.getId() + "' could not project new items to todos");
        }
        for (TodoItem todo : assigned) {
            state.getItems().add(BenjyState.Item.of(todo.getId(), todo.getContent()));
        }
        // New items must reach the clients immediately — the box IS the
        // progress display while the queue grinds through the chains.
        emitTodosProjection(process);
    }

    /** Enqueues the do-task for an item with its full chain (DO first, tail in the payload). */
    private void enqueueDoChain(
            BenjyState state, BenjyFeatureConfig features, BenjyState.Item item, @Nullable String context) {
        enqueueDoChain(state, features, item, context, null);
    }

    private void enqueueDoChain(
            BenjyState state,
            BenjyFeatureConfig features,
            BenjyState.Item item,
            @Nullable String context,
            @Nullable String recipeOverride) {
        List<String> chain = features.chainFor(state.getTaskType());
        // chain[0] == DO — the tail travels in the payload; each executed
        // stage enqueues the next with the remaining tail.
        BenjyState.QueuedTask doTask = enqueueTask(state, BenjyTaskTypes.DO, item.getId());
        doTask.getPayload().put("chain", new ArrayList<>(chain.subList(1, chain.size())));
        if (context != null) {
            doTask.getPayload().put("context", context);
        }
        if (recipeOverride != null) {
            doTask.getPayload().put("recipe", recipeOverride);
        }
    }

    /** Enqueues the head of a chain with the tail in the payload; no-op on an empty chain. */
    private void enqueueChain(BenjyState state, String itemId, List<String> chain) {
        if (chain == null || chain.isEmpty()) {
            return;
        }
        BenjyState.QueuedTask next = enqueueTask(state, chain.getFirst(), itemId);
        next.getPayload().put("chain", new ArrayList<>(chain.subList(1, chain.size())));
    }

    private BenjyState.QueuedTask enqueueTask(BenjyState state, String type, @Nullable String itemRef) {
        BenjyState.QueuedTask task = BenjyState.QueuedTask.of("t" + state.getNextTaskId(), type, itemRef);
        state.setNextTaskId(state.getNextTaskId() + 1);
        state.getQueue().add(task);
        return task;
    }

    private void enqueueRoute(BenjyState state, String trigger) {
        BenjyState.QueuedTask route = enqueueTask(state, BenjyTaskTypes.ROUTE, null);
        route.getPayload().put("trigger", trigger);
    }

    private java.util.Optional<BenjyState.Item> findItem(BenjyState state, @Nullable String id) {
        if (id == null) {
            return java.util.Optional.empty();
        }
        return state.getItems().stream().filter(i -> i.getId().equals(id)).findFirst();
    }

    private java.util.Optional<BenjyState.Item> requireItem(
            ThinkProcessDocument process, ThinkEngineContext ctx, BenjyState state, @Nullable String id) {
        java.util.Optional<BenjyState.Item> item = findItem(state, id);
        if (item.isEmpty()) {
            journal(ctx, process, state, "route: unknown itemRef '" + id + "' — action skipped");
        }
        return item;
    }

    private static boolean allItemsTerminal(BenjyState state) {
        return state.getItems().stream().allMatch(BenjyState.Item::isTerminal);
    }

    /** Parks on BLOCKED with a question to the parent/user (ask_parent, §12.1 worker mode). */
    private void parkOnQuestion(
            ThinkProcessDocument process, ThinkEngineContext ctx, BenjyState state, String question) {
        state.setPendingQuestion(question);
        journal(ctx, process, state, "ask_parent: " + truncate(question, 200));
        appendDialogue(process, ctx, ChatRole.ASSISTANT, question);
        // Status flip happens at the loop exit (async boundary check) — the
        // ParentNotificationListener turns the BLOCKED transition into the
        // parent's ProcessEvent with the question as the report.
    }

    /**
     * Parks the process on a safety-net checkpoint question (§6): BLOCKED with
     * a decision request instead of a terminal verdict. The answer (user or
     * parent) re-grants the matching budget via {@link #applyCheckpointAnswer}
     * and flows into route — the model is never asked to judge its own limits.
     * The status flip happens at the loop exit, like ask_parent.
     */
    private void parkCheckpoint(
            ThinkProcessDocument process, ThinkEngineContext ctx, BenjyState state, String type, String diagnosis) {
        String question = "⚠️ Benjy safety-net checkpoint:\n\n"
                + diagnosis
                + "\n\nReply to continue — the budget is re-granted and your answer goes to the next "
                + "route decision. Steer guidance instead, or stop the process to end it here.";
        state.setPendingCheckpoint(type);
        state.setPendingQuestion(question);
        journal(ctx, process, state, "checkpoint(" + type + "): " + truncate(diagnosis, 200));
        appendDialogue(process, ctx, ChatRole.ASSISTANT, question);
        log.warn("Benjy id='{}' checkpoint({}): {}", process.getId(), type, diagnosis);
    }

    /**
     * Mechanical escalation on stagnation (§6, error-based): the pending
     * do-tasks of open items are re-recipe'd to the escalation feature — the
     * big sibling takes over the queued work instead of duplicating it
     * (never a second chain for an item whose chain is already queued).
     * One shot: a second trip before any progress goes to the checkpoint
     * question ({@code stagnationEscalated}).
     *
     * @return {@code true} when tasks were escalated and the loop may continue
     */
    private boolean escalateAfterStagnation(
            ThinkProcessDocument process, ThinkEngineContext ctx, BenjyState state, BenjyFeatureConfig features) {
        String escalation = features.getEscalationRecipe();
        if (state.isStagnationEscalated() || escalation == null) {
            return false;
        }
        List<BenjyState.QueuedTask> doTasks = new ArrayList<>();
        for (BenjyState.QueuedTask t : state.getQueue()) {
            if (!BenjyTaskTypes.DO.equals(t.getType())) {
                continue;
            }
            BenjyState.Item item = findItem(state, t.getItemRef()).orElse(null);
            if (item != null && !item.isTerminal()) {
                doTasks.add(t);
            }
        }
        if (doTasks.isEmpty()) {
            return false;
        }
        for (BenjyState.QueuedTask t : doTasks) {
            t.getPayload().put("recipe", escalation);
            t.getPayload()
                    .put(
                            "context",
                            "Escalated by the stagnation guard after repeated unsuccessful attempts — "
                                    + "prior attempts' facts are in the item digest.");
            findItem(state, t.getItemRef()).ifPresent(item -> item.addFact("stagnation: escalated to " + escalation));
        }
        journal(
                ctx,
                process,
                state,
                "stagnation: " + doTasks.size() + " queued do-task(s) escalated to '" + escalation + "'");
        state.setStagnationEscalated(true);
        state.getCounters().setNoProgressStreak(0);
        return true;
    }

    /**
     * Applies the grant semantics of an answered question (§6). Any answer is
     * new input — the stagnation clock and the convergence cap restart. A
     * token checkpoint additionally re-grants a full budget from the current
     * consumption (the offset moves; the counters stay truthful for the final
     * report). Wallclock needs no grant: the phase restarts at the next loop
     * entry. Package-private for the checkpoint test.
     */
    static void applyCheckpointAnswer(BenjyState state) {
        state.getCounters().setNoProgressStreak(0);
        state.setStagnationEscalated(false);
        state.setReflectNoCount(0);
        if (CHECKPOINT_TOKENS.equals(state.getPendingCheckpoint())) {
            state.setTokenBudgetOffset(state.getCounters().getTokens());
        }
        state.setPendingCheckpoint(null);
    }

    /** Bounded description of the open (non-terminal) items for checkpoint diagnoses. */
    private static String openItemsDescription(BenjyState state) {
        List<String> open = state.getItems().stream()
                .filter(i -> !i.isTerminal())
                .map(i -> "#"
                        + i.getId()
                        + " ("
                        + i.getStatus()
                        + ", "
                        + i.getAttempts()
                        + " attempts): "
                        + truncate(i.getContent(), 80))
                .toList();
        return open.isEmpty() ? "none" : String.join("; ", open);
    }

    /** Bounded description of the failing/passing criteria for checkpoint diagnoses. */
    private static String failingCriteriaDescription(BenjyState state) {
        List<String> failing = state.getCriteria().stream()
                .filter(c -> "fail".equals(c.getStatus()))
                .map(c -> c.getId() + ": " + truncate(c.getText(), 80))
                .toList();
        return failing.isEmpty() ? "none" : String.join("; ", failing);
    }

    private void blockWith(ThinkProcessDocument process, ThinkEngineContext ctx, BenjyState state, String diagnosis) {
        state.setPendingQuestion(null);
        state.setPendingCheckpoint(null);
        journal(ctx, process, state, "blocked: " + diagnosis);
        appendDialogue(process, ctx, ChatRole.ASSISTANT, "⚠️ Benjy is BLOCKED:\n\n" + diagnosis);
        persistState(process, state);
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.BLOCKED);
        metricService.counter(METRIC_OUTCOMES, "outcome", OUTCOME_BLOCKED).increment();
        log.warn("Benjy id='{}' BLOCKED: {}", process.getId(), diagnosis);
    }

    private void resetState(ThinkProcessDocument process, BenjyState state, @Nullable String newGoal) {
        List<String> todoIds =
                state.getItems().stream().map(BenjyState.Item::getId).toList();
        if (!todoIds.isEmpty()) {
            thinkProcessService.removeTodos(process.getId(), todoIds);
            // The emptied projection must reach clients too, or foot keeps
            // showing the pre-reset box until the next interpret emits.
            emitTodosProjection(process);
        }
        state.getItems().clear();
        state.getCriteria().clear();
        state.getQueue().clear();
        state.setInFlight(null);
        state.setPendingQuestion(null);
        state.setInterpretedGoal(null);
        state.setReflected(false);
        state.setFinalReport(null);
        state.setLastRouteKey(null);
        state.setSameRouteCount(0);
        // Loop-progress nets restart with the re-interpretation (§6) — the
        // token budget offset deliberately survives: cost is cost.
        state.getCounters().setNoProgressStreak(0);
        state.setStagnationEscalated(false);
        state.setReflectNoCount(0);
        if (newGoal != null && !newGoal.isBlank()) {
            state.setGoal(newGoal);
        }
        journalResetNote(process);
    }

    private void journalResetNote(ThinkProcessDocument process) {
        // Audit survives the reset: the chat history and LlmTraces stay; only
        // queue/items/criteria were cleared (§4a reset semantics).
        log.info("Benjy id='{}' reset — state cleared, chat history preserved for audit", process.getId());
    }

    /**
     * Projects an item status change into the todos layer and pushes it to
     * the session's clients — foot's scrollback box and the Web-UI todo panel
     * update on the same {@code todos-updated} frame Frankie's {@code todo_*}
     * tools emit (§9).
     */
    private void projectItemStatus(ThinkProcessDocument process, BenjyState.Item item, TodoStatus status) {
        TodoPatch patch = new TodoPatch(
                item.getId(),
                status,
                null,
                status == TodoStatus.IN_PROGRESS ? "Working: " + truncate(item.getContent(), 60) : null);
        thinkProcessService.updateTodos(process.getId(), List.of(patch));
        emitTodosProjection(process);
    }

    /**
     * Emits the todos projection as a {@code todos-updated} frame to the
     * session's clients. The projection in {@code ThinkProcessService} is pure
     * persistence — the frame is a derived effect fired after the mutation
     * landed (Persistenz-Ordnung, §4a). Package-private so the wiring test can
     * pin it.
     */
    void emitTodosProjection(ThinkProcessDocument process) {
        thinkProcessService
                .findById(process.getId())
                .ifPresent(refreshed -> planModeEventEmitter.emitTodosUpdated(refreshed, refreshed.getTodos()));
    }

    /**
     * Auto-clear at DONE (§9): replaces the finished projection with an empty
     * list and emits that empty frame so clients drop the progress box.
     * Nothing is lost — the final report carries the full item list and foot
     * keeps the last rendered box in the scrollback. Frankie clears as soon as
     * every item is COMPLETED because it runs endlessly; Benjy clears once at
     * its terminal point. Package-private for the wiring test.
     */
    void clearTodosProjection(ThinkProcessDocument process, BenjyState state) {
        if (state.getItems().isEmpty()) {
            return;
        }
        thinkProcessService.setTodos(process.getId(), List.of());
        planModeEventEmitter.emitTodosUpdated(process, List.of());
    }

    /**
     * The workspace RootDir name this process's WORK tools resolve to, or
     * {@code null} when the target is not WORK (CLIENT inherits through
     * {@code resolveSpawnParams} untouched).
     */
    private @Nullable String resolveWorkTargetName(ThinkProcessDocument process) {
        WorkTarget target = workTargetService.current(process);
        if (target.kind() != WorkTargetKind.WORK) {
            return null;
        }
        if (target.targetName() != null) {
            return target.targetName();
        }
        return workspaceService
                .getWorkingDir(process.getTenantId(), process.getProjectId(), process.getId())
                .orElseGet(() -> workspaceService
                        .getOrCreateTempRootDir(process.getTenantId(), process.getProjectId(), process.getId())
                        .getDirName());
    }

    private String buildDoPrompt(BenjyState state, BenjyState.Item item, @Nullable String errorContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a focused worker executing ONE item of a larger task. "
                + "Complete exactly this item — nothing more, nothing less.\n\n");
        sb.append("## Overall goal (for context)\n")
                .append(state.getInterpretedGoal() == null ? state.getGoal() : state.getInterpretedGoal())
                .append("\n\n## Your item\n")
                .append(item.getContent())
                .append("\n\n");
        if (!state.getCriteria().isEmpty()) {
            sb.append("## Acceptance criteria the overall result must satisfy\n");
            for (BenjyState.Criterion c : state.getCriteria()) {
                sb.append("- ").append(c.getText()).append('\n');
            }
            sb.append('\n');
        }
        if (errorContext != null && !errorContext.isBlank()) {
            sb.append("## Why this is a retry\n").append(errorContext).append("\n\n");
        }
        if (!item.getFacts().isEmpty()) {
            sb.append("## Facts from prior attempts\n");
            for (String fact : item.getFacts()) {
                sb.append("- ").append(fact).append('\n');
            }
            sb.append('\n');
        }
        sb.append("Work at the active work target (file_* / exec_* tools). "
                + "When done, reply with a concise summary: what you did, which files "
                + "you touched, the outcome of any commands you ran, and open issues.");
        return sb.toString();
    }

    private String buildFinalReport(BenjyState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Result\n\n");
        sb.append(state.getInterpretedGoal() == null ? state.getGoal() : state.getInterpretedGoal())
                .append("\n\n");
        if (!state.getCriteria().isEmpty()) {
            sb.append("### Acceptance criteria\n\n");
            for (BenjyState.Criterion c : state.getCriteria()) {
                sb.append("- **")
                        .append(c.getStatus())
                        .append("** — ")
                        .append(c.getText())
                        .append(c.getEvidence() != null ? " _(evidence: " + c.getEvidence() + ")_" : "")
                        .append('\n');
            }
            sb.append('\n');
        }
        if (!state.getItems().isEmpty()) {
            sb.append("### Items\n\n");
            for (BenjyState.Item item : state.getItems()) {
                sb.append("- #")
                        .append(item.getId())
                        .append(" ")
                        .append(item.getStatus())
                        .append(": ")
                        .append(item.getContent())
                        .append('\n');
            }
        }
        sb.append("\n_(Benjy closed with ")
                .append(state.getCounters().getRounds())
                .append(" executed tasks, ")
                .append(state.getCounters().getLlmCalls())
                .append(" controller calls, ~")
                .append(state.getCounters().getTokens())
                .append(" controller tokens.)_");
        return sb.toString();
    }

    private Map<String, Object> callLlm(
            ThinkProcessDocument process,
            BenjyState state,
            String recipe,
            String userPrompt,
            Map<String, Object> schema) {
        state.getCounters().setLlmCalls(state.getCounters().getLlmCalls() + 1);
        LightLlmJsonAnswer answer = lightLlmService.callForJsonWithModel(LightLlmRequest.builder()
                .recipeName(recipe)
                .userPrompt(userPrompt)
                .schema(schema)
                .tenantId(process.getTenantId())
                .projectId(process.getProjectId())
                .processId(process.getId())
                .build());
        if (answer.usage() != null) {
            long in = answer.usage().inputTokens() == null ? 0 : answer.usage().inputTokens();
            long out =
                    answer.usage().outputTokens() == null ? 0 : answer.usage().outputTokens();
            state.getCounters().setTokens(state.getCounters().getTokens() + in + out);
        }
        metricService.counter(METRIC_LLM_CALLS, "recipe", recipe).increment();
        return answer.json();
    }

    /** Reads a criteria source document through the doc_read tool (criteriaSources, §4e). */
    private @Nullable String readCriteriaSource(ThinkProcessDocument process, ThinkEngineContext ctx, String ref) {
        try {
            Map<String, Object> result = ctx.tools().invoke("doc_read", Map.of("path", ref));
            Object content = result.get("content");
            return content instanceof String s ? s : null;
        } catch (RuntimeException e) {
            log.warn("Benjy id='{}' could not read criteria source '{}': {}", process.getId(), ref, e.toString());
            return null;
        }
    }

    private void appendDialogue(ThinkProcessDocument process, ThinkEngineContext ctx, ChatRole role, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        ChatMessageService chatLog = ctx.chatMessageService();
        chatLog.append(ChatMessageDocument.builder()
                .tenantId(process.getTenantId())
                .sessionId(process.getSessionId())
                .thinkProcessId(process.getId())
                .role(role)
                .content(content)
                .build());
    }

    /**
     * Appends a journal entry (§4b): a short engine-written task record,
     * always ending with the open worklist rendered from the queue — the
     * journal's tail is the open rest, older tails are history
     * (Laborbuch-Prinzip).
     */
    private void journal(ThinkEngineContext ctx, ThinkProcessDocument process, BenjyState state, String entry) {
        // The caller's in-memory state, NOT a reload: handlers journal
        // after mutating their state but before the loop persists it —
        // a reload would render the queue as of the last persist and the
        // "open" tail would systematically miss the successors the
        // current handler just enqueued (the tail IS the open rest, §4b).
        List<String> open = new ArrayList<>();
        for (BenjyState.QueuedTask t : state.getQueue()) {
            open.add(t.getType() + (t.getItemRef() != null ? " #" + t.getItemRef() : ""));
        }
        appendDialogue(process, ctx, ChatRole.ASSISTANT, journalRecord(entry, open));
    }

    /**
     * Renders one journal record as markdown: the record head — stage
     * and item ref, everything up to the first {@code ": "} — in bold, the
     * open worklist as inline-code chips behind a bold {@code Open:} label.
     * Both surfaces render markdown (web: MarkdownView; foot:
     * MarkdownAnsiRenderer — bold, inline code and the "·" separator all
     * pass through), and nothing parses the journal back (§4b), so styling
     * is free. Entries without a {@code ": "} head (dropped-unknown notes)
     * stay plain. Package-private for the format test.
     */
    static String journalRecord(String entry, List<String> openTasks) {
        StringBuilder sb = new StringBuilder("[benjy] ");
        int head = entry.indexOf(": ");
        if (head > 0) {
            sb.append("**").append(entry, 0, head).append(":**").append(entry.substring(head + 1));
        } else {
            sb.append(entry);
        }
        if (!openTasks.isEmpty()) {
            sb.append("\n\n**Open:** ");
            List<String> chips = new ArrayList<>();
            for (String task : openTasks) {
                chips.add("`" + task + "`");
            }
            sb.append(String.join(" · ", chips));
        }
        return sb.toString();
    }

    private ThinkProcessStatus liveStatus(ThinkProcessDocument process) {
        return thinkProcessService
                .findById(process.getId())
                .map(ThinkProcessDocument::getStatus)
                .orElse(process.getStatus());
    }

    // ──────────────────── Param + value helpers ────────────────────

    private static int intParam(Map<String, Object> params, String key, int fallback) {
        Object v = params.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return fallback;
    }

    private static long longParam(Map<String, Object> params, String key, long fallback) {
        Object v = params.get(key);
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return fallback;
    }

    private static @Nullable String stringValue(@Nullable Object raw, @Nullable String fallback) {
        if (raw instanceof String s && !s.isBlank()) {
            return s;
        }
        return fallback;
    }

    private static List<Object> listValue(@Nullable Object raw) {
        if (raw instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return List.of();
    }

    private static List<String> stringListValue(@Nullable Object raw) {
        List<String> out = new ArrayList<>();
        for (Object o : listValue(raw)) {
            if (o instanceof String s && !s.isBlank()) {
                out.add(s);
            }
        }
        return out;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /**
     * Structural cap on items committed in one batch (decision #23) — the
     * question's form bounds the answer's form: {@code initialItems} and
     * {@code split}/{@code revise} item arrays are unbounded in the schema,
     * so the model *could* return a full upfront plan; this truncates to the
     * configured bound. Never silent — callers journal {@link #capNotice}.
     * A cap below 1 is treated as "no cap" (misconfig degrades open, the
     * other safety nets still hold).
     */
    static List<String> capItems(List<String> items, int cap) {
        if (cap < 1 || items.size() <= cap) {
            return items;
        }
        return new ArrayList<>(items.subList(0, cap));
    }

    /** Journal note for a truncated item batch — names the source and both counts. */
    static String capNotice(String source, int returned, int kept) {
        return source + " returned " + returned + " items — taking the first " + kept
                + " (maxInitialItems); the rest arrives via reflect-gaps → route";
    }

    // ──────────────────── State persistence (Zaphod form) ────────────────────

    private BenjyState loadState(ThinkProcessDocument process) {
        Map<String, Object> p = process.getEngineParams();
        if (p == null) {
            return new BenjyState();
        }
        Object raw = p.get(STATE_KEY);
        if (raw == null) {
            return new BenjyState();
        }
        return objectMapper.convertValue(raw, BenjyState.class);
    }

    private void persistState(ThinkProcessDocument process, BenjyState state) {
        Map<String, Object> p = process.getEngineParams() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(process.getEngineParams());
        p.put(STATE_KEY, objectMapper.convertValue(state, Map.class));
        process.setEngineParams(p);
        thinkProcessService.replaceEngineParams(process.getId(), p);
    }
}
