package de.mhus.vance.brain.deepthought;

import de.mhus.vance.api.deepthought.DeepThoughtState;
import de.mhus.vance.api.deepthought.DeepThoughtStatus;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
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
 * Deep Thought — script-architect engine. Reads a goal, drafts a
 * JavaScript body, validates it parse-only, recovers on syntax errors
 * up to {@link DeepThoughtState#getMaxRecoveries()} times, persists
 * the accepted body and (optionally) hands off to a Script Cortex
 * runner.
 *
 * <p>State persists on
 * {@code ThinkProcessDocument.engineParams.deepThoughtState}; each
 * {@code runTurn} performs <em>one</em> phase, then yields and
 * schedules the next turn — same lane-discipline as Zaphod and
 * Vogon. The state machine:
 *
 * <pre>
 *   READY → DRAFTING → VALIDATING → PERSISTING → DONE
 *                  ↑       │
 *                  └───────┘  (recovery loop: syntax error → re-draft
 *                             with error hint, up to maxRecoveries)
 *                                       │
 *                                       └→ EXECUTING (if executeOnDone)
 * </pre>
 *
 * <p>Phase 0 implementation: all phase methods are stubbed so the
 * lifecycle round-trips; real DRAFTING/VALIDATING/PERSISTING logic
 * lands in the follow-up task (see {@code planning/deepthought-engine.md}).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DeepThoughtEngine implements ThinkEngine {

    public static final String NAME = "deepthought";
    public static final String VERSION = "0.1.0";

    /** Set on {@code engineParams[STATE_KEY]} as the persisted
     *  {@link DeepThoughtState} for this process. */
    public static final String STATE_KEY = "deepThoughtState";

    /** {@code engineParams[GOAL_KEY]} — optional override; falls back
     *  to {@code process.getGoal()}. */
    public static final String GOAL_KEY = "goal";

    /** {@code engineParams[TARGET_NAME_KEY]} — desired filename for
     *  the persisted script (without {@code scripts/} prefix). */
    public static final String TARGET_NAME_KEY = "targetName";

    /** {@code engineParams[EXECUTE_ON_DONE_KEY]} — boolean; default false. */
    public static final String EXECUTE_ON_DONE_KEY = "executeOnDone";

    /** {@code engineParams[MAX_RECOVERIES_KEY]} — int; default 5. */
    public static final String MAX_RECOVERIES_KEY = "maxRecoveries";

    private static final String DEFAULT_TARGET_NAME = "generated.js";

    private final ThinkProcessService thinkProcessService;
    private final ProcessEventEmitter eventEmitter;
    private final ObjectMapper objectMapper;

    // ──────────────────── Metadata ────────────────────

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "Deep Thought (Script Architect)";
    }

    @Override
    public String description() {
        return "Generates JavaScript orchestrator scripts from a high-level "
                + "goal. Drafts via LLM, validates parse-only, recovers on "
                + "syntax errors, persists via doc_write_text.";
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public Set<String> allowedTools() {
        // v1: only doc_write_text — drafting uses direct LLM call,
        // validating is in-process. EXECUTING phase will widen this
        // once Script Cortex lands.
        return Set.of("doc_write_text");
    }

    @Override
    public boolean asyncSteer() {
        return true;
    }

    // ──────────────────── Lifecycle ────────────────────

    @Override
    public void start(ThinkProcessDocument process, ThinkEngineContext ctx) {
        DeepThoughtState state = buildInitialState(process);
        persistState(process, state);
        log.info("DeepThought.start tenant='{}' session='{}' id='{}' "
                        + "targetName='{}' maxRecoveries={}",
                process.getTenantId(), process.getSessionId(), process.getId(),
                state.getTargetName(), state.getMaxRecoveries());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
        eventEmitter.scheduleTurn(process.getId());
    }

    @Override
    public void resume(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.debug("DeepThought.resume id='{}'", process.getId());
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
        // v1: no live-edit — steer just nudges the lane to run the
        // next turn. Inbox-answers (e.g. clarifying a goal) would
        // land here once the FRAMING phase ships.
        eventEmitter.scheduleTurn(process.getId());
    }

    @Override
    public void stop(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.info("DeepThought.stop id='{}'", process.getId());
        thinkProcessService.closeProcess(process.getId(), CloseReason.STOPPED);
    }

    // ──────────────────── runTurn ────────────────────

    @Override
    public void runTurn(ThinkProcessDocument process, ThinkEngineContext ctx) {
        DeepThoughtState state = loadState(process);

        // Terminal-status short-circuit (mirrors Zaphod's pattern —
        // queued runTurns must not re-fire DONE/FAILED transitions).
        if (state.getStatus() == DeepThoughtStatus.DONE) {
            thinkProcessService.closeProcess(process.getId(), CloseReason.DONE);
            return;
        }
        if (state.getStatus() == DeepThoughtStatus.FAILED) {
            thinkProcessService.closeProcess(process.getId(), CloseReason.STALE);
            return;
        }

        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.RUNNING);
        try {
            // Drain pending — v1 ignores; future FRAMING phase will
            // consume inbox-answers here.
            for (SteerMessage ignored : ctx.drainPending()) {
                // discard
            }

            DeepThoughtStatus next = dispatch(process, ctx, state);
            state.setStatus(next);
            persistState(process, state);

            if (next == DeepThoughtStatus.DONE) {
                thinkProcessService.closeProcess(process.getId(), CloseReason.DONE);
            } else if (next == DeepThoughtStatus.FAILED) {
                thinkProcessService.closeProcess(process.getId(), CloseReason.STALE);
            } else {
                eventEmitter.scheduleTurn(process.getId());
                thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
            }
        } catch (RuntimeException e) {
            log.warn("DeepThought runTurn failed id='{}': {}",
                    process.getId(), e.toString(), e);
            state.setStatus(DeepThoughtStatus.FAILED);
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
    private DeepThoughtStatus dispatch(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            DeepThoughtState state) {
        return switch (state.getStatus()) {
            case READY -> DeepThoughtStatus.DRAFTING;
            case DRAFTING -> runDrafting(process, ctx, state);
            case VALIDATING -> runValidating(process, ctx, state);
            case PERSISTING -> runPersisting(process, ctx, state);
            case EXECUTING -> runExecuting(process, ctx, state);
            // DONE/FAILED handled before dispatch; defensive fall-through.
            case DONE -> DeepThoughtStatus.DONE;
            case FAILED -> DeepThoughtStatus.FAILED;
        };
    }

    // ──────────────────── Phase stubs ────────────────────
    //
    // Real implementations land in task #45. Until then, each phase
    // is a deterministic no-op that advances to the next status so
    // the lifecycle round-trips and can be exercised by tests.

    DeepThoughtStatus runDrafting(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            DeepThoughtState state) {
        log.debug("DeepThought.runDrafting STUB id='{}' goal='{}'",
                process.getId(), state.getGoal());
        // Phase-stub: pretend we drafted an empty body.
        state.setGeneratedCode("// drafted body (stub)\n");
        return DeepThoughtStatus.VALIDATING;
    }

    DeepThoughtStatus runValidating(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            DeepThoughtState state) {
        log.debug("DeepThought.runValidating STUB id='{}' codeLen={}",
                process.getId(),
                state.getGeneratedCode() == null ? 0 : state.getGeneratedCode().length());
        // Phase-stub: always accept.
        state.getValidationErrors().clear();
        return DeepThoughtStatus.PERSISTING;
    }

    DeepThoughtStatus runPersisting(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            DeepThoughtState state) {
        log.debug("DeepThought.runPersisting STUB id='{}' target='{}'",
                process.getId(), state.getTargetName());
        // Phase-stub: pretend we wrote the file.
        state.setPersistedPath("scripts/" + state.getTargetName());
        return state.isExecuteOnDone()
                ? DeepThoughtStatus.EXECUTING
                : DeepThoughtStatus.DONE;
    }

    DeepThoughtStatus runExecuting(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            DeepThoughtState state) {
        log.debug("DeepThought.runExecuting STUB id='{}' path='{}'",
                process.getId(), state.getPersistedPath());
        // v1 has no Script Cortex runner — go straight to DONE.
        return DeepThoughtStatus.DONE;
    }

    // ──────────────────── summarizeForParent ────────────────────

    @Override
    public ParentReport summarizeForParent(
            ThinkProcessDocument process, ProcessEventType eventType) {
        DeepThoughtState state;
        try {
            state = loadState(process);
        } catch (RuntimeException e) {
            return ParentReport.of("DeepThought process " + process.getId()
                    + " status=" + eventType.name().toLowerCase());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", eventType.name());
        payload.put("status", state.getStatus() == null
                ? null : state.getStatus().name());
        payload.put("persistedPath", state.getPersistedPath());
        payload.put("recoveryCount", state.getRecoveryCount());
        payload.put("validationErrors", state.getValidationErrors().size());

        if (state.getStatus() == DeepThoughtStatus.DONE
                && state.getPersistedPath() != null) {
            return new ParentReport(
                    "Deep Thought wrote script to `" + state.getPersistedPath()
                            + "` after " + state.getRecoveryCount()
                            + " recovery attempt(s).",
                    payload);
        }
        if (state.getStatus() == DeepThoughtStatus.FAILED) {
            return new ParentReport(
                    "Deep Thought failed: "
                            + (state.getFailureReason() == null
                                    ? "unknown reason" : state.getFailureReason()),
                    payload);
        }
        return new ParentReport(
                "Deep Thought in progress — phase="
                        + (state.getStatus() == null ? "?" : state.getStatus().name())
                        + ", recoveries=" + state.getRecoveryCount(),
                payload);
    }

    // ──────────────────── State construction + persistence ────────────────────

    DeepThoughtState buildInitialState(ThinkProcessDocument process) {
        Map<String, Object> p = process.getEngineParams() == null
                ? new LinkedHashMap<>() : process.getEngineParams();

        String goal = optString(p.get(GOAL_KEY));
        if (goal == null) {
            goal = process.getGoal();
        }
        if (goal == null || goal.isBlank()) {
            throw new IllegalStateException(
                    "DeepThought.start requires a goal — neither "
                            + "engineParams.goal nor process.goal is set "
                            + "(id='" + process.getId() + "')");
        }

        String targetName = optString(p.get(TARGET_NAME_KEY));
        if (targetName == null) targetName = DEFAULT_TARGET_NAME;
        if (!targetName.endsWith(".js")) targetName = targetName + ".js";

        boolean executeOnDone = parseBoolean(p.get(EXECUTE_ON_DONE_KEY), false);
        int maxRecoveries = parseInt(p.get(MAX_RECOVERIES_KEY), 5);
        if (maxRecoveries < 0) maxRecoveries = 0;

        return DeepThoughtState.builder()
                .goal(goal)
                .targetName(targetName)
                .executeOnDone(executeOnDone)
                .maxRecoveries(maxRecoveries)
                .status(DeepThoughtStatus.READY)
                .build();
    }

    DeepThoughtState loadState(ThinkProcessDocument process) {
        Map<String, Object> p = process.getEngineParams();
        if (p == null) return DeepThoughtState.builder().build();
        Object raw = p.get(STATE_KEY);
        if (raw == null) return DeepThoughtState.builder().build();
        return objectMapper.convertValue(raw, DeepThoughtState.class);
    }

    @SuppressWarnings("unchecked")
    void persistState(ThinkProcessDocument process, DeepThoughtState state) {
        Map<String, Object> p = process.getEngineParams() == null
                ? new LinkedHashMap<>() : process.getEngineParams();
        Map<String, Object> serialized = objectMapper.convertValue(state, Map.class);
        p.put(STATE_KEY, serialized);
        process.setEngineParams(p);
        thinkProcessService.replaceEngineParams(process.getId(), p);
    }

    // ──────────────────── Helpers ────────────────────

    private static @Nullable String optString(@Nullable Object raw) {
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
