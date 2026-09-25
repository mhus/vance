package de.mhus.vance.brain.hactar;

import de.mhus.vance.api.hactar.HactarState;
import de.mhus.vance.api.hactar.HactarStatus;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.hactar.phases.ExecutingPhase;
import de.mhus.vance.brain.hactar.phases.LoadingPhase;
import de.mhus.vance.brain.hactar.phases.ValidatingPhase;
import de.mhus.vance.brain.thinkengine.ParentReport;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Hactar v2.1 — the script-execution engine with a dual identity
 * (planning/hactar-agent-identity.md).
 *
 * <p><b>Headless mode</b> (default, {@code engineParams.sessionMode} unset or
 * false): the pure executor of v2, unchanged and byte-identical in
 * behavior — a single-phase-per-turn state machine
 *
 * <pre>
 *   READY → LOADING → [VALIDATING] → EXECUTING → DONE
 *               │           │            │
 *               └───────────┴────────────┴→ FAILED
 * </pre>
 *
 * <p>driven synchronously by {@code runTurn}. All existing callers (recipes
 * {@code hactar}/{@code hactar-run}/{@code slart-and-run}, scheduler,
 * Slart-self-execute, Cortex) see zero behavior change.
 *
 * <p><b>Session mode</b> ({@code sessionMode: true}): the phase machine moves
 * to the background ({@link HactarRunService}) and the engine becomes a
 * Ford-adapted chat identity ({@link HactarSessionLoop}) — the Wowbagger
 * pattern: the mechanic works, the agent steers and reports. Mid-run steers
 * are answered from the live run state; a chat identity can be told "run
 * this script", "show me the script", "adjust the script so that …" (the
 * last one via a Slart {@code mode=Update} spawn — the agent is the operator,
 * never the author).
 *
 * <p><b>Spawn forms</b> (F1, decided): a session-mode spawn WITH
 * {@code scriptRef} is the worker form — auto-kick, mid-run steerable, but
 * the process ENDS at the run's terminal transition exactly like the
 * headless path. A spawn WITHOUT {@code scriptRef} is the chat form — the
 * identity is the process' purpose; the process survives run terminals
 * (re-arm, Ford-parallel). The form is persisted as
 * {@link HactarState#isChatIdentity()}.
 *
 * <p><b>Lazy identity</b>: session mode without traffic makes zero LLM calls.
 * Scheduler and worker spawns stay free even with {@code sessionMode: true}.
 *
 * <p>State persists on {@code engineParams.deepThoughtState} (legacy key kept
 * for Mongo backwards-compatibility); the codec lives in
 * {@link HactarStateStore}, shared by the engine lane and the run runner.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HactarEngine implements ThinkEngine {

    public static final String NAME = "hactar";
    public static final String VERSION = "2.1.0";

    /** The role the {@code hactar_*} self-steering tools gate on. */
    public static final String ROLE = "hactar";

    /** Set on {@code engineParams[STATE_KEY]} as the persisted
     *  {@link HactarState} for this process. Legacy name kept for
     *  backwards-compatibility with persisted Mongo documents. */
    public static final String STATE_KEY = "deepThoughtState";

    /** {@code engineParams[SCRIPT_REF_KEY]} — project document path
     *  to the script. Required for the headless and worker form; may be
     *  absent in the chat form (the agent sets it via {@code hactar_start}). */
    public static final String SCRIPT_REF_KEY = "scriptRef";

    /** {@code engineParams[LANGUAGE_KEY]} — script language. v1
     *  only accepts {@code "js"} (default). Reserved for a future
     *  Python expansion. */
    public static final String LANGUAGE_KEY = "language";

    /** {@code engineParams[VALIDATE_BEFORE_RUN_KEY]} — boolean,
     *  default false. When true, the {@link ValidatingPhase} runs
     *  {@link HactarService#deepValidate} before EXECUTING. */
    public static final String VALIDATE_BEFORE_RUN_KEY = "validateBeforeRun";

    /** {@code engineParams[SESSION_MODE_KEY]} — boolean, default false.
     *  Switches the engine to the reactive identity (see class javadoc).
     *  Never changes the phase machine's behavior — only who drives it. */
    public static final String SESSION_MODE_KEY = "sessionMode";

    /** Re-export of {@link LoadingPhase#SCRIPT_ALLOWED_TOOLS_KEY}
     *  so external callers (recipes, Cortex controller) discover
     *  the engine-param surface through the engine class. */
    public static final String SCRIPT_ALLOWED_TOOLS_KEY = LoadingPhase.SCRIPT_ALLOWED_TOOLS_KEY;

    /** Re-export of {@link ExecutingPhase#SCRIPT_PARAMS_KEY}. */
    public static final String SCRIPT_PARAMS_KEY = ExecutingPhase.SCRIPT_PARAMS_KEY;

    /** Re-export of {@link ExecutingPhase#TIMEOUT_KEY}. */
    public static final String TIMEOUT_KEY = ExecutingPhase.TIMEOUT_KEY;

    private final ThinkProcessService thinkProcessService;
    private final ProcessEventEmitter eventEmitter;
    private final LoadingPhase loadingPhase;
    private final ValidatingPhase validatingPhase;
    private final ExecutingPhase executingPhase;
    private final HactarStateStore stateStore;
    private final HactarRunService runService;
    private final HactarSessionLoop sessionLoop;
    /**
     * Appends one ASSISTANT chat-message at terminal transitions so
     * {@code process_history_text(name=<hactar-process>)} returns the
     * script's return value (or error) as queryable data — without
     * this, Hactar's chat history is always empty and forensic /
     * orchestrator lookups land on a {@code messageCount=0} response.
     */
    private final de.mhus.vance.shared.chat.ChatMessageService chatMessageService;

    private final tools.jackson.databind.ObjectMapper objectMapper;

    // ──────────────────── Metadata ────────────────────

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "Hactar (Script Executor)";
    }

    @Override
    public String description() {
        return "Script-execution engine with a dual identity: headless it loads a "
                + "JavaScript orchestrator from a project document, validates "
                + "(parse + header + tool-allowlist; optional LLM deep-review) "
                + "and runs it in a sandboxed GraalJS context — zero LLM calls. "
                + "In session mode a Ford-style chat agent operates the same phase "
                + "machine: it starts and stops runs, reports mid-run progress, and "
                + "routes script changes to Slartibartfast (never authoring them "
                + "itself).";
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public Set<String> allowedTools() {
        // Engine's own LLM tool surface is unrestricted (Ford default) — the
        // headless phase machine makes no LLM calls, and the session identity
        // carries the full operator pool. The executed script's tool surface
        // comes from engineParams.scriptAllowedTools and is built inside
        // ExecutingPhase.
        return Set.of();
    }

    /** The hactar_* tools gate on this role — invisible to other engines. */
    @Override
    public Set<String> roles() {
        return Set.of(ROLE);
    }

    @Override
    public boolean asyncSteer() {
        return true;
    }

    @Override
    public boolean producesUserFacingOutput() {
        // Hactar's DONE summary is "Hactar executed '<path>' (<ms>ms).
        // Return value: ```json <value>```" — useful as forensic
        // payload for a parent orchestrator, never as the answer text
        // a human wanted. ParentNotificationListener routes terminal
        // events through the engine-output-translator recipe.
        return false;
    }

    // ──────────────────── Lifecycle ────────────────────

    @Override
    public void start(ThinkProcessDocument process, ThinkEngineContext ctx) {
        if (sessionMode(process)) {
            startSessionMode(process);
            return;
        }
        HactarState state = buildInitialState(process);
        stateStore.persist(process, state);
        log.info(
                "Hactar.start tenant='{}' session='{}' id='{}' " + "scriptRef='{}' language={} validateBeforeRun={}",
                process.getTenantId(),
                process.getSessionId(),
                process.getId(),
                state.getScriptRef(),
                state.getLanguage(),
                state.isValidateBeforeRun());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
        eventEmitter.scheduleTurn(process.getId());
    }

    /**
     * Session-mode start. Spawn form (F1): WITH {@code scriptRef} the worker
     * form auto-kicks the run in the background (mid-run steerable, terminal
     * closes the process like the headless path); WITHOUT it the chat form
     * waits — Ford semantics, no greeting turn, the spawn steer or the
     * user's first message drives the first turn.
     */
    private void startSessionMode(ThinkProcessDocument process) {
        HactarState state = buildInitialSessionState(process);
        String scriptRef = process.getEngineParams() == null
                ? null
                : stringParam(process.getEngineParams().get(SCRIPT_REF_KEY));
        boolean workerForm = scriptRef != null;
        state.setScriptRef(scriptRef);
        state.setChatIdentity(!workerForm);
        stateStore.persist(process, state);
        log.info(
                "Hactar.start session tenant='{}' session='{}' id='{}' form={} scriptRef='{}'",
                process.getTenantId(),
                process.getSessionId(),
                process.getId(),
                workerForm ? "worker" : "chat",
                scriptRef);
        if (workerForm) {
            // Auto-kick: the run service owns the phases from here on; the
            // engine lane stays free for mid-run steers.
            runService.start(process, state);
        }
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
    }

    @Override
    public void resume(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.debug("Hactar.resume id='{}'", process.getId());
        if (sessionMode(process)) {
            HactarState state = stateStore.load(process);
            if (!runService.isRunning(process.getId())
                    && (state.getStatus() == HactarStatus.LOADING
                            || state.getStatus() == HactarStatus.VALIDATING
                            || state.getStatus() == HactarStatus.EXECUTING)) {
                // The state claims a mid-run phase but no live handle exists:
                // a Brain restart killed the runner. Terminal per the
                // no-recovery contract — the agent reports and offers a
                // fresh kick.
                runService.failOrphanedRun(process, state);
            }
            thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
            return;
        }
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
        eventEmitter.scheduleTurn(process.getId());
    }

    @Override
    public void suspend(ThinkProcessDocument process, ThinkEngineContext ctx) {
        if (sessionMode(process)) {
            // A background script keeps running while the process is
            // SUSPENDED — the script is server-side work, the suspend only
            // parks the conversational lane (HactarRunService javadoc).
            // Contrast with Wowbagger, which parks its pool: a Hactar run
            // is not chunk-resumable, interrupting it would lose work.
            thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.SUSPENDED);
            return;
        }
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.SUSPENDED);
    }

    @Override
    public void steer(ThinkProcessDocument process, ThinkEngineContext ctx, SteerMessage message) {
        if (sessionMode(process)) {
            sessionLoop.turnFor(process, ctx, List.of(message));
            return;
        }
        eventEmitter.scheduleTurn(process.getId());
    }

    @Override
    public void stop(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.info("Hactar.stop id='{}'", process.getId());
        if (sessionMode(process)) {
            // F1: session/process close is a STOP — a live run is hard-cancelled,
            // not drain-waited.
            runService.stop(process.getId());
        }
        thinkProcessService.closeProcess(process.getId(), CloseReason.STOPPED);
    }

    // ──────────────────── runTurn ────────────────────

    @Override
    public void runTurn(ThinkProcessDocument process, ThinkEngineContext ctx) {
        if (sessionMode(process)) {
            runTurnSessionMode(process, ctx);
            return;
        }
        runTurnHeadless(process, ctx);
    }

    /**
     * Session-mode turn: the terminal handling of the WORKER form happens
     * here (the run service cannot emit the reply or close the process —
     * both need the engine context). The chat form always goes to the agent
     * loop; the run service's wakeup note is in the history and the drained
     * pending copy drives the report turn.
     */
    private void runTurnSessionMode(ThinkProcessDocument process, ThinkEngineContext ctx) {
        HactarState state = stateStore.load(process);
        if (!state.isChatIdentity()
                && (state.getStatus() == HactarStatus.DONE || state.getStatus() == HactarStatus.FAILED)) {
            // Worker form at the run's terminal: the "[run]" history note is
            // already written by the run service — no second one (unlike the
            // headless path). Reply + close, no agent turn.
            for (SteerMessage ignored : ctx.drainPending()) {
                // hygiene — the wakeup copy is only the trigger
            }
            ProcessEventType eventType =
                    state.getStatus() == HactarStatus.DONE ? ProcessEventType.DONE : ProcessEventType.FAILED;
            emitFinalReply(process, ctx, state, eventType);
            thinkProcessService.closeProcess(
                    process.getId(), state.getStatus() == HactarStatus.DONE ? CloseReason.DONE : CloseReason.STALE);
            return;
        }
        while (true) {
            // Cooperative halt-check BEFORE draining (the pause contract,
            // Arthur parity — see SessionLifecycleService: the halt flag
            // goes out before the pause lane task, so a drain-loop still
            // holding the lane must yield HERE). Draining first (the Ford
            // shape this loop was adapted from) eats queued user messages
            // into a turn that immediately parks — "no answer surfaced",
            // and the messages' only trace is the chat log (observed
            // live: pause during a slow in-flight turn swallowed two
            // queued user messages).
            if (thinkProcessService.isHaltRequested(process.getId())) {
                log.info("Hactar id='{}' runTurn — halt requested, yielding (inbox left queued)", process.getId());
                return;
            }
            List<SteerMessage> drained = ctx.drainPending();
            if (drained.isEmpty()) {
                return;
            }
            sessionLoop.turnFor(process, ctx, drained);
        }
    }

    /**
     * Headless turn — the v2 single-phase-per-turn machine, unchanged.
     */
    private void runTurnHeadless(ThinkProcessDocument process, ThinkEngineContext ctx) {
        HactarState state = stateStore.load(process);

        // Terminal-status short-circuit — queued runTurns must not
        // re-fire DONE/FAILED transitions.
        if (state.getStatus() == HactarStatus.DONE) {
            thinkProcessService.closeProcess(process.getId(), CloseReason.DONE);
            return;
        }
        if (state.getStatus() == HactarStatus.FAILED) {
            thinkProcessService.closeProcess(process.getId(), CloseReason.STALE);
            return;
        }

        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.RUNNING);
        try {
            for (SteerMessage ignored : ctx.drainPending()) {
                // v2: no inbox-driven mode (executor doesn't ask
                // questions). Drained for hygiene only.
            }

            HactarStatus next = dispatch(process, state);
            state.setStatus(next);
            stateStore.persist(process, state);

            if (next == HactarStatus.DONE) {
                persistTerminalOutcomeToChatHistory(process, state, next);
                emitFinalReply(process, ctx, state, ProcessEventType.DONE);
                thinkProcessService.closeProcess(process.getId(), CloseReason.DONE);
            } else if (next == HactarStatus.FAILED) {
                persistTerminalOutcomeToChatHistory(process, state, next);
                emitFinalReply(process, ctx, state, ProcessEventType.FAILED);
                thinkProcessService.closeProcess(process.getId(), CloseReason.STALE);
            } else {
                eventEmitter.scheduleTurn(process.getId());
                thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
            }
        } catch (RuntimeException e) {
            log.warn("Hactar runTurn failed id='{}': {}", process.getId(), e.toString(), e);
            state.setStatus(HactarStatus.FAILED);
            state.setFailureReason("runTurn threw: " + e.getMessage());
            stateStore.persist(process, state);
            thinkProcessService.closeProcess(process.getId(), CloseReason.STALE);
            throw e;
        }
    }

    /**
     * Single-step state machine — picks the next phase based on
     * the current status. Phase methods mutate {@code state} and
     * return the next status.
     */
    private HactarStatus dispatch(ThinkProcessDocument process, HactarState state) {
        return switch (state.getStatus()) {
            case READY -> HactarStatus.LOADING;
            case LOADING -> loadingPhase.execute(state, process);
            case VALIDATING -> validatingPhase.execute(state, process);
            case EXECUTING -> executingPhase.execute(state, process);
            case DONE -> HactarStatus.DONE;
            case FAILED -> HactarStatus.FAILED;
        };
    }

    /**
     * Pushes the script-execution outcome as a REPLY to the parent
     * before the lifecycle CLOSED transition. Uses
     * {@link #summarizeForParent} for the body — the receiving
     * parent (typically Slart, sometimes Arthur via the
     * {@code hactar-run} recipe) dedups against the legacy
     * lifecycle DONE event of the same source.
     *
     * <p>Idempotent via {@link HactarState#isReplyEmitted()} — queued
     * runTurn re-entries after closeProcess must not duplicate the
     * push. See {@code planning/process-engine-reply-channel.md} §4.8.
     */
    private void emitFinalReply(
            ThinkProcessDocument process, ThinkEngineContext ctx, HactarState state, ProcessEventType eventType) {
        if (process.getParentProcessId() == null || process.getParentProcessId().isBlank()) {
            return;
        }
        if (state.isReplyEmitted()) {
            return;
        }
        try {
            ParentReport report = summarizeForParent(process, eventType);
            String body = report == null ? null : report.humanSummary();
            if (body == null || body.isBlank()) {
                return;
            }
            ctx.emitReply(body, /*inResponseToAt*/ null, report.payload());
            state.setReplyEmitted(true);
            stateStore.persist(process, state);
        } catch (RuntimeException e) {
            log.warn("Hactar id='{}' emitFinalReply failed: {}", process.getId(), e.toString());
        }
    }

    // ──────────────────── summarizeForParent ────────────────────

    @Override
    public ParentReport summarizeForParent(ThinkProcessDocument process, ProcessEventType eventType) {
        HactarState state;
        try {
            state = stateStore.load(process);
        } catch (RuntimeException e) {
            return ParentReport.of("Hactar process " + process.getId() + " status="
                    + eventType.name().toLowerCase());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", eventType.name());
        payload.put(
                "status", state.getStatus() == null ? null : state.getStatus().name());
        payload.put("scriptRef", state.getScriptRef());
        payload.put("validationIssues", state.getValidationIssues().size());
        payload.put("executionDurationMs", state.getExecutionDurationMs());
        if (state.getExecutionErrorClass() != null) {
            payload.put("executionErrorClass", state.getExecutionErrorClass());
        }

        if (state.getStatus() == HactarStatus.DONE) {
            payload.put("executionResult", state.getExecutionResult());
            return new ParentReport(
                    "Hactar executed '" + state.getScriptRef() + "' ("
                            + state.getExecutionDurationMs() + "ms). Return value:\n\n"
                            + renderExecutionValue(state.getExecutionResult()),
                    payload);
        }
        if (state.getStatus() == HactarStatus.FAILED) {
            return new ParentReport(
                    "Hactar failed: "
                            + (state.getFailureReason() == null ? "unknown reason" : state.getFailureReason()),
                    payload);
        }
        return new ParentReport(
                "Hactar in progress — phase="
                        + (state.getStatus() == null ? "?" : state.getStatus().name()),
                payload);
    }

    /**
     * Persists a terminal-transition summary as an ASSISTANT chat
     * message on the Hactar process (headless path — the session-mode
     * run service writes its own "[run]" note). Mirrors the body the
     * {@code summarizeForParent} report would carry so a lookup via
     * {@code process_history_text(name=<hactar-process>)} returns the
     * same information the parent (Slart, Arthur, …) sees through
     * the ProcessEvent — without the parent's event-rendering chrome.
     *
     * <p>Best-effort: chat-history is a debugging surface, never
     * part of the engine's correctness contract. Any persistence
     * failure is logged and swallowed so the runTurn close path
     * still completes.
     */
    private void persistTerminalOutcomeToChatHistory(
            ThinkProcessDocument process, HactarState state, HactarStatus terminal) {
        if (chatMessageService == null) {
            return; // unit-test wiring may stub this out
        }
        try {
            String body;
            if (terminal == HactarStatus.DONE) {
                body = "Hactar executed '" + state.getScriptRef() + "' ("
                        + state.getExecutionDurationMs() + "ms). Return value:\n\n"
                        + renderExecutionValue(state.getExecutionResult());
            } else {
                body = "Hactar failed: "
                        + (state.getFailureReason() == null ? "unknown reason" : state.getFailureReason());
                if (state.getExecutionErrorClass() != null) {
                    body += "\n\n(errorClass=" + state.getExecutionErrorClass() + ")";
                }
            }
            chatMessageService.append(de.mhus.vance.shared.chat.ChatMessageDocument.builder()
                    .tenantId(process.getTenantId())
                    .sessionId(process.getSessionId())
                    .thinkProcessId(process.getId())
                    .role(de.mhus.vance.api.chat.ChatRole.ASSISTANT)
                    .content(body)
                    .build());
        } catch (RuntimeException e) {
            log.warn(
                    "Hactar id='{}' failed to persist terminal outcome to chat history: {}",
                    process.getId(),
                    e.toString());
        }
    }

    private String renderExecutionValue(@Nullable Object value) {
        if (value == null) return "(no return value)";
        if (value instanceof String s) return s;
        try {
            String json = objectMapper.writeValueAsString(value);
            return "```json\n" + json + "\n```";
        } catch (RuntimeException e) {
            return String.valueOf(value);
        }
    }

    // ──────────────────── State construction ────────────────────

    HactarState buildInitialState(ThinkProcessDocument process) {
        Map<String, Object> p = process.getEngineParams() == null ? new LinkedHashMap<>() : process.getEngineParams();

        String scriptRef = stringParam(p.get(SCRIPT_REF_KEY));
        if (scriptRef == null || scriptRef.isBlank()) {
            throw new IllegalStateException("Hactar.start requires engineParams['scriptRef'] — "
                    + "no script reference is set (id='"
                    + process.getId() + "')");
        }

        String language = stringParam(p.get(LANGUAGE_KEY));
        if (language == null || language.isBlank()) language = "js";
        if (!"js".equals(language)) {
            throw new IllegalStateException(
                    "Hactar v2 supports only language='js' — got '" + language + "' (id='" + process.getId() + "')");
        }

        boolean validateBeforeRun = parseBoolean(p.get(VALIDATE_BEFORE_RUN_KEY), false);

        return HactarState.builder()
                .scriptRef(scriptRef)
                .language(language)
                .validateBeforeRun(validateBeforeRun)
                .status(HactarStatus.READY)
                .build();
    }

    /**
     * Session-mode variant: {@code scriptRef} may be absent (chat form — the
     * agent sets it via {@code hactar_start}); the language gate stays
     * fail-closed.
     */
    HactarState buildInitialSessionState(ThinkProcessDocument process) {
        Map<String, Object> p = process.getEngineParams() == null ? new LinkedHashMap<>() : process.getEngineParams();

        String language = stringParam(p.get(LANGUAGE_KEY));
        if (language == null || language.isBlank()) language = "js";
        if (!"js".equals(language)) {
            throw new IllegalStateException(
                    "Hactar v2 supports only language='js' — got '" + language + "' (id='" + process.getId() + "')");
        }

        boolean validateBeforeRun = parseBoolean(p.get(VALIDATE_BEFORE_RUN_KEY), false);

        return HactarState.builder()
                .language(language)
                .validateBeforeRun(validateBeforeRun)
                .status(HactarStatus.READY)
                .build();
    }

    static boolean sessionMode(ThinkProcessDocument process) {
        Map<String, Object> p = process.getEngineParams();
        Object raw = p == null ? null : p.get(SESSION_MODE_KEY);
        if (raw instanceof Boolean b) return b;
        return raw instanceof String s && Boolean.parseBoolean(s.trim());
    }

    // ──────────────────── Param-read helpers ────────────────────

    private static @Nullable String stringParam(@Nullable Object raw) {
        return raw instanceof String s && !s.isBlank() ? s : null;
    }

    private static boolean parseBoolean(@Nullable Object raw, boolean fallback) {
        if (raw instanceof Boolean b) return b;
        if (raw instanceof String s) return Boolean.parseBoolean(s.trim());
        return fallback;
    }
}
