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
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Hactar v2 — pure script-execution engine. Loads a script body
 * from a project document, runs minimal validation (parse + header
 * + tool-allowlist), optionally runs an LLM deep-validate gate,
 * and executes the script via {@code ScriptExecutor}. There is
 * no LLM-authoring — script generation moved to
 * {@code SlartibartfastEngine} with {@code OutputSchemaType.SCRIPT_JS}.
 *
 * <p>State persists on {@code engineParams.deepThoughtState}; each
 * {@code runTurn} performs <em>one</em> phase, then yields and
 * schedules the next turn. The state machine:
 *
 * <pre>
 *   READY → LOADING → [VALIDATING] → EXECUTING → DONE
 *               │           │            │
 *               └───────────┴────────────┴→ FAILED
 *             (any failure is terminal — no recovery loop)
 * </pre>
 *
 * <p>VALIDATING is opt-in via {@code engineParams.validateBeforeRun}.
 * See {@code planning/script-architect-executor-split.md} §5.2.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HactarEngine implements ThinkEngine {

    public static final String NAME = "hactar";
    public static final String VERSION = "2.0.0";

    /** Set on {@code engineParams[STATE_KEY]} as the persisted
     *  {@link HactarState} for this process. Legacy name kept for
     *  backwards-compatibility with persisted Mongo documents. */
    public static final String STATE_KEY = "deepThoughtState";

    /** {@code engineParams[SCRIPT_REF_KEY]} — project document path
     *  to the script. <b>Required.</b> */
    public static final String SCRIPT_REF_KEY = "scriptRef";

    /** {@code engineParams[LANGUAGE_KEY]} — script language. v1
     *  only accepts {@code "js"} (default). Reserved for a future
     *  Python expansion. */
    public static final String LANGUAGE_KEY = "language";

    /** {@code engineParams[VALIDATE_BEFORE_RUN_KEY]} — boolean,
     *  default false. When true, the {@link ValidatingPhase} runs
     *  {@link HactarService#deepValidate} before EXECUTING. */
    public static final String VALIDATE_BEFORE_RUN_KEY = "validateBeforeRun";

    /** Re-export of {@link LoadingPhase#SCRIPT_ALLOWED_TOOLS_KEY}
     *  so external callers (recipes, Cortex controller) discover
     *  the engine-param surface through the engine class. */
    public static final String SCRIPT_ALLOWED_TOOLS_KEY =
            LoadingPhase.SCRIPT_ALLOWED_TOOLS_KEY;

    /** Re-export of {@link ExecutingPhase#SCRIPT_PARAMS_KEY}. */
    public static final String SCRIPT_PARAMS_KEY = ExecutingPhase.SCRIPT_PARAMS_KEY;

    /** Re-export of {@link ExecutingPhase#TIMEOUT_KEY}. */
    public static final String TIMEOUT_KEY = ExecutingPhase.TIMEOUT_KEY;

    private final ThinkProcessService thinkProcessService;
    private final ProcessEventEmitter eventEmitter;
    private final ObjectMapper objectMapper;
    private final LoadingPhase loadingPhase;
    private final ValidatingPhase validatingPhase;
    private final ExecutingPhase executingPhase;
    /**
     * Appends one ASSISTANT chat-message at terminal transitions so
     * {@code process_history_text(name=<hactar-process>)} returns the
     * script's return value (or error) as queryable data — without
     * this, Hactar's chat history is always empty and forensic /
     * orchestrator lookups land on a {@code messageCount=0} response.
     */
    private final de.mhus.vance.shared.chat.ChatMessageService chatMessageService;

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
        return "Pure script-execution engine. Loads a JavaScript "
                + "orchestrator from a project document, validates "
                + "(parse + header + tool-allowlist; optional LLM "
                + "deep-review), and runs it in a sandboxed GraalJS "
                + "context. Authoring moved to Slartibartfast.";
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public Set<String> allowedTools() {
        // Engine's own LLM tool surface is empty — Hactar makes no
        // LLM calls. The executed script's tool surface comes from
        // engineParams.scriptAllowedTools and is built inside
        // ExecutingPhase.
        return Set.of();
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
        HactarState state = buildInitialState(process);
        persistState(process, state);
        log.info("Hactar.start tenant='{}' session='{}' id='{}' "
                        + "scriptRef='{}' language={} validateBeforeRun={}",
                process.getTenantId(), process.getSessionId(), process.getId(),
                state.getScriptRef(), state.getLanguage(),
                state.isValidateBeforeRun());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
        eventEmitter.scheduleTurn(process.getId());
    }

    @Override
    public void resume(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.debug("Hactar.resume id='{}'", process.getId());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
        eventEmitter.scheduleTurn(process.getId());
    }

    @Override
    public void suspend(ThinkProcessDocument process, ThinkEngineContext ctx) {
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.SUSPENDED);
    }

    @Override
    public void steer(ThinkProcessDocument process, ThinkEngineContext ctx,
            SteerMessage message) {
        eventEmitter.scheduleTurn(process.getId());
    }

    @Override
    public void stop(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.info("Hactar.stop id='{}'", process.getId());
        thinkProcessService.closeProcess(process.getId(), CloseReason.STOPPED);
    }

    // ──────────────────── runTurn ────────────────────

    @Override
    public void runTurn(ThinkProcessDocument process, ThinkEngineContext ctx) {
        HactarState state = loadState(process);

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

            HactarStatus next = dispatch(process, ctx, state);
            state.setStatus(next);
            persistState(process, state);

            if (next == HactarStatus.DONE) {
                persistTerminalOutcomeToChatHistory(process, state, next);
                thinkProcessService.closeProcess(process.getId(), CloseReason.DONE);
            } else if (next == HactarStatus.FAILED) {
                persistTerminalOutcomeToChatHistory(process, state, next);
                thinkProcessService.closeProcess(process.getId(), CloseReason.STALE);
            } else {
                eventEmitter.scheduleTurn(process.getId());
                thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
            }
        } catch (RuntimeException e) {
            log.warn("Hactar runTurn failed id='{}': {}",
                    process.getId(), e.toString(), e);
            state.setStatus(HactarStatus.FAILED);
            state.setFailureReason("runTurn threw: " + e.getMessage());
            persistState(process, state);
            thinkProcessService.closeProcess(process.getId(), CloseReason.STALE);
            throw e;
        }
    }

    /**
     * Single-step state machine — picks the next phase based on
     * the current status. Phase methods mutate {@code state} and
     * return the next status.
     */
    private HactarStatus dispatch(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            HactarState state) {
        return switch (state.getStatus()) {
            case READY -> HactarStatus.LOADING;
            case LOADING -> loadingPhase.execute(state, process, ctx);
            case VALIDATING -> validatingPhase.execute(state, process, ctx);
            case EXECUTING -> executingPhase.execute(state, process, ctx);
            case DONE -> HactarStatus.DONE;
            case FAILED -> HactarStatus.FAILED;
        };
    }

    // ──────────────────── summarizeForParent ────────────────────

    @Override
    public ParentReport summarizeForParent(
            ThinkProcessDocument process, ProcessEventType eventType) {
        HactarState state;
        try {
            state = loadState(process);
        } catch (RuntimeException e) {
            return ParentReport.of("Hactar process " + process.getId()
                    + " status=" + eventType.name().toLowerCase());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", eventType.name());
        payload.put("status", state.getStatus() == null
                ? null : state.getStatus().name());
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
                            + (state.getFailureReason() == null
                                    ? "unknown reason" : state.getFailureReason()),
                    payload);
        }
        return new ParentReport(
                "Hactar in progress — phase="
                        + (state.getStatus() == null ? "?" : state.getStatus().name()),
                payload);
    }

    /**
     * Persists a terminal-transition summary as an ASSISTANT chat
     * message on the Hactar process. Mirrors the body the
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
            ThinkProcessDocument process,
            HactarState state,
            HactarStatus terminal) {
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
                        + (state.getFailureReason() == null
                                ? "unknown reason" : state.getFailureReason());
                if (state.getExecutionErrorClass() != null) {
                    body += "\n\n(errorClass=" + state.getExecutionErrorClass() + ")";
                }
            }
            chatMessageService.append(
                    de.mhus.vance.shared.chat.ChatMessageDocument.builder()
                            .tenantId(process.getTenantId())
                            .sessionId(process.getSessionId())
                            .thinkProcessId(process.getId())
                            .role(de.mhus.vance.api.chat.ChatRole.ASSISTANT)
                            .content(body)
                            .build());
        } catch (RuntimeException e) {
            log.warn(
                    "Hactar id='{}' failed to persist terminal outcome to chat history: {}",
                    process.getId(), e.toString());
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

    // ──────────────────── State construction + persistence ────────────────────

    HactarState buildInitialState(ThinkProcessDocument process) {
        Map<String, Object> p = process.getEngineParams() == null
                ? new LinkedHashMap<>() : process.getEngineParams();

        String scriptRef = stringParam(p.get(SCRIPT_REF_KEY));
        if (scriptRef == null || scriptRef.isBlank()) {
            throw new IllegalStateException(
                    "Hactar.start requires engineParams['scriptRef'] — "
                            + "no script reference is set (id='"
                            + process.getId() + "')");
        }

        String language = stringParam(p.get(LANGUAGE_KEY));
        if (language == null || language.isBlank()) language = "js";
        if (!"js".equals(language)) {
            throw new IllegalStateException(
                    "Hactar v2 supports only language='js' — got '"
                            + language + "' (id='" + process.getId() + "')");
        }

        boolean validateBeforeRun = parseBoolean(p.get(VALIDATE_BEFORE_RUN_KEY), false);

        return HactarState.builder()
                .scriptRef(scriptRef)
                .language(language)
                .validateBeforeRun(validateBeforeRun)
                .status(HactarStatus.READY)
                .build();
    }

    HactarState loadState(ThinkProcessDocument process) {
        Map<String, Object> p = process.getEngineParams();
        if (p == null) return HactarState.builder().build();
        Object raw = p.get(STATE_KEY);
        if (raw == null) return HactarState.builder().build();
        return objectMapper.convertValue(raw, HactarState.class);
    }

    @SuppressWarnings("unchecked")
    void persistState(ThinkProcessDocument process, HactarState state) {
        Map<String, Object> p = process.getEngineParams() == null
                ? new LinkedHashMap<>() : process.getEngineParams();
        Map<String, Object> serialized = objectMapper.convertValue(state, Map.class);
        p.put(STATE_KEY, serialized);
        process.setEngineParams(p);
        thinkProcessService.replaceEngineParams(process.getId(), p);
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
