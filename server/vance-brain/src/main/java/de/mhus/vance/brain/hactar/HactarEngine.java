package de.mhus.vance.brain.hactar;

import de.mhus.vance.api.hactar.HactarState;
import de.mhus.vance.api.hactar.HactarStatus;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.hactar.phases.DraftingPhase;
import de.mhus.vance.brain.hactar.phases.ExecutingPhase;
import de.mhus.vance.brain.hactar.phases.FramingPhase;
import de.mhus.vance.brain.hactar.phases.LoadingPhase;
import de.mhus.vance.brain.hactar.phases.ReviewingPhase;
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
 * Hactar — script-architect engine. Reads a goal, drafts a
 * JavaScript body, validates it parse-only, recovers on syntax errors
 * up to {@link HactarState#getMaxRecoveries()} times, and
 * (optionally) hands off to an in-engine script runner.
 *
 * <p>The accepted script lives in {@code HactarState.generatedCode}
 * — there is no separate persistence to a project document. Parents
 * read the final code through {@code summarizeForParent}.
 *
 * <p>State persists on {@code engineParams.deepThoughtState}; each
 * {@code runTurn} performs <em>one</em> phase, then yields and
 * schedules the next turn — same lane-discipline as Zaphod and
 * Vogon. The state machine:
 *
 * <pre>
 *   READY → (framingEnabled?) → FRAMING → REVIEWING → DRAFTING → VALIDATING → DONE
 *                  ↑      │           ↑         │             ↑        │
 *                  └──────┘           └─────────┘             └────────┘
 *               (reject loop)      (skip when no reviewer)  (syntax-error recovery)
 *                                       │                              │
 *                                       └→ EXECUTING → DONE            └→ FAILED
 *                                            (if executeOnDone)        (max recoveries)
 * </pre>
 *
 * <p>Phase implementations live as separate Spring components under
 * {@code de.mhus.vance.brain.hactar.phases} — engine is a thin
 * dispatcher that loads state, picks the next phase, and persists
 * the result.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HactarEngine implements ThinkEngine {

    public static final String NAME = "hactar";
    public static final String VERSION = "0.1.0";

    /** Set on {@code engineParams[STATE_KEY]} as the persisted
     *  {@link HactarState} for this process. */
    public static final String STATE_KEY = "deepThoughtState";

    /** {@code engineParams[GOAL_KEY]} — optional override; falls back
     *  to {@code process.getGoal()}. */
    public static final String GOAL_KEY = "goal";

    /** {@code engineParams[EXECUTE_ON_DONE_KEY]} — boolean; default false. */
    public static final String EXECUTE_ON_DONE_KEY = "executeOnDone";

    /** {@code engineParams[MAX_RECOVERIES_KEY]} — int; default 5. */
    public static final String MAX_RECOVERIES_KEY = "maxRecoveries";

    /** Plan-mode opt-in. Default false (legacy fast path READY → DRAFTING). */
    public static final String FRAMING_ENABLED_KEY = "framingEnabled";

    /** Soft-cap on FRAMING→REVIEWING retry cycles. Default 3. */
    public static final String MAX_FRAMING_RECOVERIES_KEY = "maxFramingRecoveries";

    /** {@code engineParams[SCRIPT_PATH_KEY]} — project document path
     *  to load instead of generating a script. When set, the engine
     *  takes the LOADING → VALIDATING → (EXECUTING) → DONE pathway,
     *  ignoring goal-based generation entirely. Goal becomes
     *  optional. Validation failures are final (no DRAFTING recovery
     *  loop — we didn't draft the script). */
    public static final String SCRIPT_PATH_KEY = "scriptPath";

    // Phase params shared via the phase classes' own constants —
    // re-exported here so external callers (Eddie, recipe authors)
    // discover them through the engine class:
    public static final String SCRIPT_ALLOWED_TOOLS_KEY =
            de.mhus.vance.brain.hactar.phases.HactarContextRenderer
                    .SCRIPT_ALLOWED_TOOLS_KEY;
    public static final String MANUAL_PATHS_KEY =
            de.mhus.vance.brain.hactar.phases.HactarContextRenderer
                    .MANUAL_PATHS_KEY;
    public static final String SCRIPT_ARGS_KEY = ExecutingPhase.SCRIPT_ARGS_KEY;
    public static final String EXECUTION_TIMEOUT_KEY = ExecutingPhase.EXECUTION_TIMEOUT_KEY;
    public static final String REVIEWER_RECIPE_KEY = ReviewingPhase.REVIEWER_RECIPE_KEY;
    public static final String SCRIPT_ARCHITECT_TAG =
            de.mhus.vance.brain.hactar.phases.HactarContextRenderer
                    .SCRIPT_ARCHITECT_TAG;

    private final ThinkProcessService thinkProcessService;
    private final ProcessEventEmitter eventEmitter;
    private final ObjectMapper objectMapper;
    private final LoadingPhase loadingPhase;
    private final FramingPhase framingPhase;
    private final ReviewingPhase reviewingPhase;
    private final DraftingPhase draftingPhase;
    private final ValidatingPhase validatingPhase;
    private final ExecutingPhase executingPhase;

    // ──────────────────── Metadata ────────────────────

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "Hactar (Script Architect)";
    }

    @Override
    public String description() {
        return "Generates JavaScript orchestrator scripts from a high-level "
                + "goal. Drafts via LLM, validates parse-only, recovers on "
                + "syntax errors, optionally runs the script in-engine.";
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public Set<String> allowedTools() {
        // Engine's own LLM tool surface is empty — DRAFTING/FRAMING
        // use direct LLM calls, no tool_use. The executed script's
        // tool surface comes from engineParams.scriptAllowedTools
        // and is built inside ExecutingPhase.
        return Set.of();
    }

    @Override
    public boolean asyncSteer() {
        return true;
    }

    // ──────────────────── Lifecycle ────────────────────

    @Override
    public void start(ThinkProcessDocument process, ThinkEngineContext ctx) {
        HactarState state = buildInitialState(process);
        persistState(process, state);
        log.info("Hactar.start tenant='{}' session='{}' id='{}' "
                        + "mode={} framingEnabled={} executeOnDone={} maxRecoveries={}",
                process.getTenantId(), process.getSessionId(), process.getId(),
                state.getScriptPath() != null ? "load:" + state.getScriptPath() : "generate",
                state.isFramingEnabled(), state.isExecuteOnDone(),
                state.getMaxRecoveries());
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
                // v1: ignore. Future FRAMING-with-inbox-approval will
                // consume the answers here.
            }

            HactarStatus next = dispatch(process, ctx, state);
            state.setStatus(next);
            persistState(process, state);

            if (next == HactarStatus.DONE) {
                thinkProcessService.closeProcess(process.getId(), CloseReason.DONE);
            } else if (next == HactarStatus.FAILED) {
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
     * Single-step state machine — picks the next phase based on the
     * current status. Phase methods are responsible for mutating
     * {@code state}; this method returns the next status only.
     */
    private HactarStatus dispatch(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            HactarState state) {
        return switch (state.getStatus()) {
            case READY -> resolveInitialStatus(state);
            case LOADING -> loadingPhase.execute(state, process, ctx);
            case FRAMING -> framingPhase.execute(state, process, ctx);
            case REVIEWING -> reviewingPhase.execute(state, process, ctx);
            case DRAFTING -> draftingPhase.execute(state, process, ctx);
            case VALIDATING -> validatingPhase.execute(state, process, ctx);
            case EXECUTING -> executingPhase.execute(state, process, ctx);
            // DONE/FAILED handled before dispatch; defensive fall-through.
            case DONE -> HactarStatus.DONE;
            case FAILED -> HactarStatus.FAILED;
        };
    }

    /**
     * Three modes branch off READY: load-mode (scriptPath set →
     * LOADING), plan-mode (framingEnabled → FRAMING), or direct
     * one-shot (DRAFTING). Load-mode wins when both scriptPath and
     * framingEnabled are set — the explicit script is what the caller
     * wants exercised, not a freshly generated one.
     */
    private static HactarStatus resolveInitialStatus(HactarState state) {
        if (state.getScriptPath() != null && !state.getScriptPath().isBlank()) {
            return HactarStatus.LOADING;
        }
        if (state.isFramingEnabled()) return HactarStatus.FRAMING;
        return HactarStatus.DRAFTING;
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
        payload.put("codeLength", state.getGeneratedCode() == null
                ? 0 : state.getGeneratedCode().length());
        payload.put("recoveryCount", state.getRecoveryCount());
        payload.put("validationErrors", state.getValidationErrors().size());
        if (state.isExecuteOnDone()) {
            payload.put("executionDurationMs", state.getExecutionDurationMs());
            if (state.getExecutionErrorClass() != null) {
                payload.put("executionErrorClass", state.getExecutionErrorClass());
            }
        }

        if (state.getStatus() == HactarStatus.DONE
                && state.getGeneratedCode() != null) {
            if (state.isExecuteOnDone()) {
                payload.put("executionResult", state.getExecutionResult());
                return new ParentReport(
                        "Hactar executed the generated script ("
                                + state.getGeneratedCode().length() + " chars, "
                                + state.getExecutionDurationMs() + "ms). Return value:\n\n"
                                + renderExecutionValue(state.getExecutionResult()),
                        payload);
            }
            return new ParentReport(
                    "Hactar drafted a script (" + state.getGeneratedCode().length()
                            + " chars, " + state.getRecoveryCount()
                            + " recovery attempt(s)):\n\n```javascript\n"
                            + state.getGeneratedCode()
                            + "\n```\n",
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
                        + (state.getStatus() == null ? "?" : state.getStatus().name())
                        + ", recoveries=" + state.getRecoveryCount(),
                payload);
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

        String goal = stringParam(p.get(GOAL_KEY));
        if (goal == null) {
            goal = process.getGoal();
        }
        String scriptPath = stringParam(p.get(SCRIPT_PATH_KEY));
        boolean loadMode = scriptPath != null;
        if (!loadMode && (goal == null || goal.isBlank())) {
            throw new IllegalStateException(
                    "Hactar.start requires either a goal or a "
                            + "scriptPath — neither is set on engineParams "
                            + "and process.goal is empty (id='"
                            + process.getId() + "')");
        }

        boolean executeOnDone = parseBoolean(p.get(EXECUTE_ON_DONE_KEY), false);
        int maxRecoveries = parseInt(p.get(MAX_RECOVERIES_KEY), 5);
        if (maxRecoveries < 0) maxRecoveries = 0;
        // Load-mode: validation failure is final — we can't recover
        // into DRAFTING because we never drafted the script. Force
        // the budget to 0 so the first VALIDATING failure naturally
        // flows into FAILED via the existing exhaustion path.
        if (loadMode) maxRecoveries = 0;
        boolean framingEnabled = parseBoolean(p.get(FRAMING_ENABLED_KEY), false);
        int maxFramingRecoveries = parseInt(p.get(MAX_FRAMING_RECOVERIES_KEY), 3);
        if (maxFramingRecoveries < 0) maxFramingRecoveries = 0;

        return HactarState.builder()
                .goal(goal)
                .scriptPath(scriptPath)
                .executeOnDone(executeOnDone)
                .maxRecoveries(maxRecoveries)
                .framingEnabled(framingEnabled)
                .maxFramingRecoveries(maxFramingRecoveries)
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

    private static int parseInt(@Nullable Object raw, int fallback) {
        if (raw instanceof Number n) return n.intValue();
        if (raw instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }
}
