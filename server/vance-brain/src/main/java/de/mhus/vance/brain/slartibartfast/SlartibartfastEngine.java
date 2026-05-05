package de.mhus.vance.brain.slartibartfast;

import de.mhus.vance.api.slartibartfast.ArchitectState;
import de.mhus.vance.api.slartibartfast.ArchitectStatus;
import de.mhus.vance.api.slartibartfast.Claim;
import de.mhus.vance.api.slartibartfast.ClassificationKind;
import de.mhus.vance.api.slartibartfast.Criterion;
import de.mhus.vance.api.slartibartfast.EvidenceSource;
import de.mhus.vance.api.slartibartfast.EvidenceType;
import de.mhus.vance.api.slartibartfast.FramedGoal;
import de.mhus.vance.api.slartibartfast.OutputSchemaType;
import de.mhus.vance.api.slartibartfast.RecipeDraft;
import de.mhus.vance.api.slartibartfast.Subgoal;
import de.mhus.vance.api.slartibartfast.ValidationCheck;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.scheduling.LaneScheduler;
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
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Slartibartfast — the plan-architect engine. Produces ready-to-run
 * recipes (Vogon strategies or Marvin recipes) from a free-text user
 * goal, through an evidence-based phased workflow with hard
 * validation gates.
 *
 * <p>Mental model: the engine is a state machine over
 * {@link ArchitectStatus}. Each {@code runTurn} advances exactly one
 * phase, persists {@link ArchitectState} on
 * {@code engineParams.architectState}, and schedules the next turn.
 * Brain-restart resumes pick up wherever the previous turn left off.
 *
 * <p><b>M1 stand</b>: phases are <em>stubbed</em> with deterministic
 * canned data — the goal is to validate the lifecycle (status
 * transitions, state persistence, terminal handling) before plugging
 * in real LLM calls. M2 onward replaces stubs with FRAMING +
 * GATHERING (real LLM + manual_read), then CLASSIFYING + DECOMPOSING
 * + BINDING (M3), PROPOSING + VALIDATING + PERSISTING (M4),
 * ESCALATED → Inbox (M6).
 *
 * <p>See {@code specification/slartibartfast-engine.md} for the full
 * design.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SlartibartfastEngine implements ThinkEngine {

    public static final String NAME = "slartibartfast";
    public static final String VERSION = "0.1.0";

    /** Set on {@code engineParams[STATE_KEY]} as the persisted
     *  {@link ArchitectState} for this process. */
    public static final String STATE_KEY = "architectState";

    /** {@code engineParams[OUTPUT_SCHEMA_TYPE_KEY]} — required at
     *  spawn time, drives the validator and the recipe-render
     *  shape. Values: {@code "vogon-strategy"} | {@code "marvin-recipe"}. */
    public static final String OUTPUT_SCHEMA_TYPE_KEY = "outputSchemaType";

    /** {@code engineParams[USER_DESCRIPTION_KEY]} — the free-text
     *  request. Falls back to {@code ThinkProcessDocument.goal}. */
    public static final String USER_DESCRIPTION_KEY = "userDescription";

    private final ThinkProcessService thinkProcessService;
    private final ProcessEventEmitter eventEmitter;
    private final LaneScheduler laneScheduler;
    private final ObjectMapper objectMapper;

    // ──────────────────── Metadata ────────────────────

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "Slartibartfast (Plan Architect)";
    }

    @Override
    public String description() {
        return "Plan-architect engine. Turns a free-text goal into a "
                + "ready-to-run Vogon strategy or Marvin recipe via an "
                + "evidence-based phased workflow with hard validation "
                + "gates — every plan element traces back to a source.";
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public Set<String> allowedTools() {
        // M2 will add manual_list, manual_read, recipe_describe.
        // For now: no tool calls — phases are stubbed.
        return Set.of();
    }

    @Override
    public boolean asyncSteer() {
        // Slartibartfast does its own LLM calls in-turn (sync), like
        // Vogon. Sub-process spawns (M5: Marvin-recipe path) will go
        // through the same async-steer pattern Marvin uses.
        return true;
    }

    // ──────────────────── Lifecycle ────────────────────

    @Override
    public void start(ThinkProcessDocument process, ThinkEngineContext ctx) {
        ArchitectState state = buildInitialState(process);
        persistState(process, state);
        log.info("Slartibartfast.start tenant='{}' session='{}' id='{}' "
                        + "runId={} schemaType={}",
                process.getTenantId(), process.getSessionId(), process.getId(),
                state.getRunId(), state.getOutputSchemaType());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
        eventEmitter.scheduleTurn(process.getId());
    }

    @Override
    public void resume(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.debug("Slartibartfast.resume id='{}'", process.getId());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
        eventEmitter.scheduleTurn(process.getId());
    }

    @Override
    public void suspend(ThinkProcessDocument process, ThinkEngineContext ctx) {
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.SUSPENDED);
    }

    @Override
    public void steer(ThinkProcessDocument process, ThinkEngineContext ctx, SteerMessage message) {
        // Async — orchestrators just queue; we wake on next runTurn.
        // Inbox-answers (ESCALATED → user reply path) will be drained
        // in runTurn once M6 lands.
        eventEmitter.scheduleTurn(process.getId());
    }

    @Override
    public void stop(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.info("Slartibartfast.stop id='{}'", process.getId());
        thinkProcessService.closeProcess(process.getId(), CloseReason.STOPPED);
    }

    // ──────────────────── runTurn ────────────────────

    @Override
    public void runTurn(ThinkProcessDocument process, ThinkEngineContext ctx) {
        ArchitectState state = loadState(process);

        // Terminal check FIRST — same pattern as Zaphod, avoids
        // spurious RUNNING→DONE flickers when the lane has multiple
        // queued runTurn tasks pending after the final advance.
        if (state.getStatus() == ArchitectStatus.DONE) {
            thinkProcessService.closeProcess(process.getId(), CloseReason.DONE);
            return;
        }
        if (state.getStatus() == ArchitectStatus.FAILED
                || state.getStatus() == ArchitectStatus.ESCALATED) {
            thinkProcessService.closeProcess(process.getId(), CloseReason.STALE);
            return;
        }

        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.RUNNING);
        try {
            // Drain — M1 has no inbound messages but defensively consume.
            for (SteerMessage ignored : ctx.drainPending()) {
                // discard
            }

            advanceOnePhase(process, ctx, state);
            persistState(process, state);

            if (state.getStatus() == ArchitectStatus.DONE) {
                log.info("Slartibartfast id='{}' DONE — recipe at '{}'",
                        process.getId(), state.getPersistedRecipePath());
                thinkProcessService.closeProcess(process.getId(), CloseReason.DONE);
                return;
            }
            if (state.getStatus() == ArchitectStatus.FAILED) {
                log.warn("Slartibartfast id='{}' FAILED: {}",
                        process.getId(), state.getFailureReason());
                thinkProcessService.closeProcess(process.getId(), CloseReason.STALE);
                return;
            }
            if (state.getStatus() == ArchitectStatus.ESCALATED) {
                log.info("Slartibartfast id='{}' ESCALATED — inbox item '{}'",
                        process.getId(), state.getEscalationInboxItemId());
                thinkProcessService.closeProcess(process.getId(), CloseReason.STALE);
                return;
            }

            thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
            eventEmitter.scheduleTurn(process.getId());
        } catch (RuntimeException e) {
            log.warn("Slartibartfast runTurn failed id='{}': {}",
                    process.getId(), e.toString(), e);
            thinkProcessService.closeProcess(process.getId(), CloseReason.STALE);
            throw e;
        }
    }

    /**
     * Advance the state machine by exactly one phase. Each branch
     * mutates {@code state} in place and sets {@code state.status}
     * to the next phase (or to a terminal state). The runTurn
     * caller persists and either schedules another turn or closes
     * the process.
     *
     * <p><b>M1 stand:</b> every phase is a deterministic stub that
     * fills in canned data so the lifecycle can be tested end-to-end
     * without LLM calls. M2 onward replaces the stubs in
     * {@link SlartibartfastPhases} with real implementations.
     */
    private void advanceOnePhase(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            ArchitectState state) {
        switch (state.getStatus()) {
            case READY -> {
                state.setStatus(ArchitectStatus.FRAMING);
            }
            case FRAMING -> {
                SlartibartfastPhases.stubFraming(state);
                state.setStatus(ArchitectStatus.CONFIRMING);
            }
            case CONFIRMING -> {
                SlartibartfastPhases.stubConfirming(state);
                state.setStatus(ArchitectStatus.GATHERING);
            }
            case GATHERING -> {
                SlartibartfastPhases.stubGathering(state);
                state.setStatus(ArchitectStatus.CLASSIFYING);
            }
            case CLASSIFYING -> {
                SlartibartfastPhases.stubClassifying(state);
                state.setStatus(ArchitectStatus.DECOMPOSING);
            }
            case DECOMPOSING -> {
                SlartibartfastPhases.stubDecomposing(state);
                state.setStatus(ArchitectStatus.BINDING);
            }
            case BINDING -> {
                SlartibartfastPhases.stubBinding(state);
                // Stub: assume validation passes — real impl re-prompts
                // DECOMPOSING on failure up to maxBindingRetries.
                state.setStatus(ArchitectStatus.PROPOSING);
            }
            case PROPOSING -> {
                SlartibartfastPhases.stubProposing(state);
                state.setStatus(ArchitectStatus.VALIDATING);
            }
            case VALIDATING -> {
                SlartibartfastPhases.stubValidating(state);
                state.setStatus(ArchitectStatus.PERSISTING);
            }
            case PERSISTING -> {
                SlartibartfastPhases.stubPersisting(state);
                state.setStatus(ArchitectStatus.DONE);
            }
            // Handled by terminal-check at top of runTurn.
            case DONE, FAILED, ESCALATED -> {
                // no-op
            }
        }
    }

    // ──────────────────── State init + persistence ────────────────────

    private ArchitectState buildInitialState(ThinkProcessDocument process) {
        Map<String, Object> p = process.getEngineParams();
        String userDescription = stringParam(p, USER_DESCRIPTION_KEY);
        if (userDescription.isBlank()) {
            // Fall back to the process goal so the engine is usable
            // without an explicit userDescription param.
            userDescription = process.getGoal() == null ? "" : process.getGoal();
        }
        OutputSchemaType schemaType = parseSchemaType(stringParam(p, OUTPUT_SCHEMA_TYPE_KEY));
        return ArchitectState.builder()
                .runId(generateRunId())
                .userDescription(userDescription)
                .outputSchemaType(schemaType)
                .status(ArchitectStatus.READY)
                .build();
    }

    @SuppressWarnings("unchecked")
    private ArchitectState loadState(ThinkProcessDocument process) {
        Map<String, Object> p = process.getEngineParams();
        if (p == null) return ArchitectState.builder().build();
        Object raw = p.get(STATE_KEY);
        if (raw == null) return ArchitectState.builder().build();
        return objectMapper.convertValue(raw, ArchitectState.class);
    }

    @SuppressWarnings("unchecked")
    private void persistState(ThinkProcessDocument process, ArchitectState state) {
        Map<String, Object> p = process.getEngineParams() == null
                ? new LinkedHashMap<>() : process.getEngineParams();
        Map<String, Object> serialized = objectMapper.convertValue(state, Map.class);
        p.put(STATE_KEY, serialized);
        process.setEngineParams(p);
        thinkProcessService.replaceEngineParams(process.getId(), p);
    }

    private static String generateRunId() {
        // First 8 hex chars of a UUIDv4. ~16M buckets — collision
        // chance is negligible for human-paced spawn rates.
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static OutputSchemaType parseSchemaType(String raw) {
        if (raw.isBlank()) return OutputSchemaType.VOGON_STRATEGY;
        String norm = raw.trim().toUpperCase().replace('-', '_');
        try {
            return OutputSchemaType.valueOf(norm);
        } catch (IllegalArgumentException e) {
            log.warn("Slartibartfast unknown outputSchemaType '{}', defaulting to VOGON_STRATEGY", raw);
            return OutputSchemaType.VOGON_STRATEGY;
        }
    }

    private static String stringParam(Map<String, Object> params, String key) {
        if (params == null) return "";
        Object v = params.get(key);
        return v instanceof String s ? s : "";
    }
}
