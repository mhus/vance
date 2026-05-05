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
import de.mhus.vance.api.inbox.AnswerOutcome;
import de.mhus.vance.api.inbox.AnswerPayload;
import de.mhus.vance.api.inbox.Criticality;
import de.mhus.vance.api.inbox.InboxItemType;
import de.mhus.vance.api.slartibartfast.Criterion;
import de.mhus.vance.api.slartibartfast.CriterionOrigin;
import de.mhus.vance.api.slartibartfast.PendingInboxKind;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.slartibartfast.phases.BindingPhase;
import de.mhus.vance.brain.slartibartfast.phases.ClassifyingPhase;
import de.mhus.vance.brain.slartibartfast.phases.ConfirmingPhase;
import de.mhus.vance.brain.slartibartfast.phases.DecomposingPhase;
import de.mhus.vance.brain.slartibartfast.phases.FramingPhase;
import de.mhus.vance.brain.slartibartfast.phases.GatheringPhase;
import de.mhus.vance.brain.slartibartfast.phases.PersistingPhase;
import de.mhus.vance.brain.slartibartfast.phases.ProposingPhase;
import de.mhus.vance.brain.slartibartfast.phases.ValidatingPhase;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.inbox.InboxItemDocument;
import de.mhus.vance.shared.inbox.InboxItemService;
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

    /** {@code engineParams[CONFIRMATION_MODE_KEY]} — name of a
     *  {@link de.mhus.vance.api.slartibartfast.ConfirmationMode}
     *  value. Default {@code DROP_LOW_CONF}. */
    public static final String CONFIRMATION_MODE_KEY = "confirmationMode";

    /** {@code engineParams[ESCALATION_MODE_KEY]} — name of an
     *  {@link de.mhus.vance.api.slartibartfast.EscalationMode}
     *  value. Default {@code FAIL}. */
    public static final String ESCALATION_MODE_KEY = "escalationMode";

    private final ThinkProcessService thinkProcessService;
    private final ProcessEventEmitter eventEmitter;
    private final LaneScheduler laneScheduler;
    private final ObjectMapper objectMapper;
    private final InboxItemService inboxItemService;
    private final FramingPhase framingPhase;
    private final ConfirmingPhase confirmingPhase;
    private final GatheringPhase gatheringPhase;
    private final ClassifyingPhase classifyingPhase;
    private final DecomposingPhase decomposingPhase;
    private final BindingPhase bindingPhase;
    private final ProposingPhase proposingPhase;
    private final ValidatingPhase validatingPhase;
    private final PersistingPhase persistingPhase;

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
            // Drain pending — InboxAnswer messages on the
            // currently-tracked dialog flip state in place.
            for (SteerMessage msg : ctx.drainPending()) {
                if (msg instanceof SteerMessage.InboxAnswer ia) {
                    handleInboxAnswer(state, ia, process);
                }
            }

            advanceOnePhase(process, ctx, state);
            persistState(process, state);

            // Park check: if the phase posted an inbox dialog and
            // status didn't move to a terminal, BLOCK the process
            // until the inbox answer arrives via drainPending on a
            // future turn.
            if (state.getPendingInboxItemId() != null
                    && state.getPendingInboxKind() != PendingInboxKind.NONE
                    && state.getStatus() != ArchitectStatus.DONE
                    && state.getStatus() != ArchitectStatus.FAILED
                    && state.getStatus() != ArchitectStatus.ESCALATED) {
                log.info("Slartibartfast id='{}' parking on inbox '{}' (kind={})",
                        process.getId(), state.getPendingInboxItemId(),
                        state.getPendingInboxKind());
                thinkProcessService.updateStatus(
                        process.getId(), ThinkProcessStatus.BLOCKED);
                return;
            }

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
     * Apply an incoming {@link SteerMessage.InboxAnswer} to the
     * pending dialog tracked by
     * {@link ArchitectState#getPendingInboxItemId()}. Mismatching
     * ids are warned and ignored. The {@link PendingInboxKind}
     * decides what shape the answer takes.
     */
    private void handleInboxAnswer(
            ArchitectState state,
            SteerMessage.InboxAnswer ia,
            ThinkProcessDocument process) {
        String pendingId = state.getPendingInboxItemId();
        if (pendingId == null || !pendingId.equals(ia.inboxItemId())) {
            log.warn("Slartibartfast id='{}' got InboxAnswer for unexpected "
                            + "item='{}' (pending='{}'). Ignoring.",
                    process.getId(), ia.inboxItemId(), pendingId);
            return;
        }
        AnswerPayload answer = ia.answer();
        boolean approved = answer.getOutcome() == AnswerOutcome.DECIDED
                && readApproved(answer.getValue());
        log.info("Slartibartfast id='{}' inbox answer kind={} approved={} "
                        + "outcome={}",
                process.getId(), state.getPendingInboxKind(), approved,
                answer.getOutcome());

        switch (state.getPendingInboxKind()) {
            case CONFIRMATION -> applyConfirmationAnswer(state, approved);
            case ESCALATION -> applyEscalationAnswer(state, approved);
            case NONE -> log.warn("Slartibartfast id='{}' answer arrived but "
                            + "pendingInboxKind=NONE — ignored",
                    process.getId());
        }
        state.setPendingInboxItemId(null);
        state.setPendingInboxKind(PendingInboxKind.NONE);
    }

    /**
     * Approval flag from an APPROVAL-typed answer payload. The
     * shape (per AnswerPayload doc) is {@code {"approved": <bool>}};
     * {@code null} or non-bool resolves to {@code false}.
     */
    private static boolean readApproved(java.util.@org.jspecify.annotations.Nullable
            Map<String, Object> value) {
        if (value == null) return false;
        Object v = value.get("approved");
        return v instanceof Boolean b && b;
    }

    /**
     * Confirmation answer: when approved, every low-conf assumed
     * criterion has its origin flipped to {@code USER_CONFIRMED}
     * — ConfirmingPhase's next pass treats them as authoritative.
     * On rejection the originals stay (still low-conf, still
     * INFERRED_*) and ConfirmingPhase drops them in the standard
     * threshold partition.
     */
    private void applyConfirmationAnswer(ArchitectState state, boolean approved) {
        if (state.getGoal() == null) return;
        if (!approved) return;
        java.util.List<Criterion> updated = new java.util.ArrayList<>(
                state.getGoal().getAssumedCriteria().size());
        for (Criterion c : state.getGoal().getAssumedCriteria()) {
            if (c.getConfidence() < state.getConfirmationThreshold()
                    && c.getOrigin() != CriterionOrigin.USER_CONFIRMED) {
                updated.add(Criterion.builder()
                        .id(c.getId()).text(c.getText())
                        .origin(CriterionOrigin.USER_CONFIRMED)
                        .confidence(c.getConfidence())
                        .rationaleId(c.getRationaleId())
                        .testHint(c.getTestHint())
                        .build());
            } else {
                updated.add(c);
            }
        }
        state.getGoal().setAssumedCriteria(updated);
    }

    /**
     * Escalation answer: when approved (retry), reset
     * {@link ArchitectState#getRecoveryCount()} so the engine has
     * a fresh budget; the status goes back to the recovery
     * target. On rejection (abort) the engine transitions to
     * ESCALATED.
     */
    private void applyEscalationAnswer(ArchitectState state, boolean approved) {
        if (approved) {
            // Fresh budget. Status was ESCALATING; flip it to the
            // last recovery's target if available, else go back
            // through the normal lifecycle from PROPOSING.
            state.setRecoveryCount(0);
            state.setStatus(ArchitectStatus.PROPOSING);
        } else {
            state.setStatus(ArchitectStatus.ESCALATED);
        }
    }

    /**
     * Build and post the escalation inbox APPROVAL — used by the
     * recovery handler when {@code escalationMode=ASK_USER}.
     */
    private void postEscalationInbox(
            ThinkProcessDocument process,
            ArchitectState state,
            de.mhus.vance.api.slartibartfast.RecoveryRequest lastRecovery) {
        StringBuilder body = new StringBuilder();
        body.append("Validation hat trotz ")
                .append(state.getMaxRecoveries())
                .append(" Korrektur-Versuchen keinen brauchbaren Plan ")
                .append("produziert. Letzter Fehler-Grund: ")
                .append(lastRecovery.getReason()).append("\n\n");
        body.append("Letzter Hinweis an den Planner:\n")
                .append(lastRecovery.getHint()).append("\n\n");
        body.append("Antwort: yes → frischer Recovery-Versuch (Budget "
                + "wird zurückgesetzt); no → Lauf als ESCALATED beenden.");

        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("kind", "slartibartfast.escalation");
        payload.put("runId", state.getRunId());
        payload.put("validationReport", state.getValidationReport());
        payload.put("recipeDraft", state.getProposedRecipe());
        payload.put("recoveryReason", lastRecovery.getReason());

        InboxItemDocument toCreate = InboxItemDocument.builder()
                .tenantId(process.getTenantId())
                .originatorUserId("slartibartfast:" + process.getId())
                .assignedToUserId(null)
                .originProcessId(process.getId())
                .originSessionId(process.getSessionId())
                .type(InboxItemType.APPROVAL)
                .criticality(Criticality.CRITICAL)
                .title("Slartibartfast: Recovery-Budget erschöpft — retry?")
                .body(body.toString())
                .payload(payload)
                .requiresAction(true)
                .build();
        InboxItemDocument saved = inboxItemService.create(toCreate);
        state.setPendingInboxItemId(saved.getId());
        state.setPendingInboxKind(PendingInboxKind.ESCALATION);
        state.setStatus(ArchitectStatus.ESCALATING);

        log.info("Slartibartfast id='{}' ESCALATION inbox posted '{}' — "
                        + "parking on user verdict",
                process.getId(), saved.getId());
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

        // Recovery handling first — a downstream gate may have set
        // pendingRecovery on the previous turn. We honour it (flip
        // status to the requested phase) until the recovery budget
        // is exhausted; past that we ESCALATE so the user can
        // weigh in instead of looping forever.
        de.mhus.vance.api.slartibartfast.RecoveryRequest consumedRecovery =
                state.getPendingRecovery();
        if (consumedRecovery != null) {
            int newCount = state.getRecoveryCount() + 1;
            state.setRecoveryCount(newCount);
            if (newCount > state.getMaxRecoveries()) {
                de.mhus.vance.api.slartibartfast.EscalationMode escMode =
                        state.getEscalationMode() == null
                                ? de.mhus.vance.api.slartibartfast.EscalationMode.FAIL
                                : state.getEscalationMode();
                log.info("Slartibartfast id='{}' exceeded maxRecoveries={} "
                                + "— escalation mode={}, last reason: {}",
                        process.getId(), state.getMaxRecoveries(),
                        escMode, consumedRecovery.getReason());
                switch (escMode) {
                    case FAIL -> state.setStatus(ArchitectStatus.ESCALATED);
                    case ASK_USER -> postEscalationInbox(
                            process, state, consumedRecovery);
                }
                state.setPendingRecovery(null);
                return;
            }
            log.info("Slartibartfast id='{}' recovery {}/{}: {} → {} "
                            + "(reason: {})",
                    process.getId(), newCount, state.getMaxRecoveries(),
                    consumedRecovery.getFromPhase(),
                    consumedRecovery.getToPhase(),
                    consumedRecovery.getReason());
            // Status flip happens here; the actual rollback (re-run
            // of the target phase) happens on this same turn below.
            // Phases that care about a recovery hint read
            // state.getPendingRecovery() at the top of their
            // execute() before the engine's safety-net clear at
            // the end of this method.
            state.setStatus(consumedRecovery.getToPhase());
        }

        switch (state.getStatus()) {
            case READY -> {
                state.setStatus(ArchitectStatus.FRAMING);
            }
            case FRAMING -> {
                framingPhase.execute(state, process, ctx);
                if (state.getFailureReason() != null) {
                    state.setStatus(ArchitectStatus.FAILED);
                } else {
                    state.setStatus(ArchitectStatus.CONFIRMING);
                }
            }
            case CONFIRMING -> {
                confirmingPhase.execute(state, process, ctx);
                if (state.getFailureReason() != null) {
                    state.setStatus(ArchitectStatus.FAILED);
                } else {
                    state.setStatus(ArchitectStatus.GATHERING);
                }
            }
            case GATHERING -> {
                gatheringPhase.execute(state, process, ctx);
                if (state.getFailureReason() != null) {
                    state.setStatus(ArchitectStatus.FAILED);
                } else {
                    state.setStatus(ArchitectStatus.CLASSIFYING);
                }
            }
            case CLASSIFYING -> {
                classifyingPhase.execute(state, process, ctx);
                if (state.getFailureReason() != null) {
                    state.setStatus(ArchitectStatus.FAILED);
                } else {
                    state.setStatus(ArchitectStatus.DECOMPOSING);
                }
            }
            case DECOMPOSING -> {
                decomposingPhase.execute(state, process, ctx);
                if (state.getFailureReason() != null) {
                    state.setStatus(ArchitectStatus.FAILED);
                } else {
                    state.setStatus(ArchitectStatus.BINDING);
                }
            }
            case BINDING -> {
                bindingPhase.execute(state, process, ctx);
                if (state.getFailureReason() != null) {
                    state.setStatus(ArchitectStatus.FAILED);
                } else if (state.getPendingRecovery() != null) {
                    // Stay in BINDING — the next runTurn picks up
                    // the recovery and rolls back to DECOMPOSING.
                    // (advanceOnePhase's recovery-first block does
                    // the actual flip.)
                } else {
                    state.setStatus(ArchitectStatus.PROPOSING);
                }
            }
            case PROPOSING -> {
                proposingPhase.execute(state, process, ctx);
                if (state.getFailureReason() != null) {
                    state.setStatus(ArchitectStatus.FAILED);
                } else {
                    state.setStatus(ArchitectStatus.VALIDATING);
                }
            }
            case VALIDATING -> {
                validatingPhase.execute(state, process, ctx);
                if (state.getFailureReason() != null) {
                    state.setStatus(ArchitectStatus.FAILED);
                } else if (state.getPendingRecovery() != null) {
                    // Stay in VALIDATING — next runTurn picks up the
                    // recovery and rolls back to PROPOSING.
                } else {
                    state.setStatus(ArchitectStatus.PERSISTING);
                }
            }
            case PERSISTING -> {
                persistingPhase.execute(state, process, ctx);
                if (state.getFailureReason() != null) {
                    state.setStatus(ArchitectStatus.FAILED);
                } else {
                    state.setStatus(ArchitectStatus.DONE);
                }
            }
            case ESCALATING -> {
                // Parked waiting for the escalation inbox answer
                // (escalationMode=ASK_USER). drainPending will
                // flip status when the user answers; this turn
                // is a no-op.
            }
            // Handled by terminal-check at top of runTurn.
            case DONE, FAILED, ESCALATED -> {
                // no-op
            }
        }

        // Safety-net: clear the consumed recovery if the dispatched
        // phase didn't (e.g. stub or no-op phase). Real LLM-driven
        // phases like DecomposingPhase clear it as part of their
        // execute() when they incorporate the hint into the prompt;
        // this catch-all prevents a stale pendingRecovery from
        // triggering an immediate second rollback on the next turn.
        if (consumedRecovery != null
                && state.getPendingRecovery() == consumedRecovery) {
            state.setPendingRecovery(null);
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
        de.mhus.vance.api.slartibartfast.ConfirmationMode confirmationMode =
                parseConfirmationMode(stringParam(p, CONFIRMATION_MODE_KEY));
        de.mhus.vance.api.slartibartfast.EscalationMode escalationMode =
                parseEscalationMode(stringParam(p, ESCALATION_MODE_KEY));
        return ArchitectState.builder()
                .runId(generateRunId())
                .userDescription(userDescription)
                .outputSchemaType(schemaType)
                .confirmationMode(confirmationMode)
                .escalationMode(escalationMode)
                .status(ArchitectStatus.READY)
                .build();
    }

    private static de.mhus.vance.api.slartibartfast.ConfirmationMode parseConfirmationMode(
            String raw) {
        if (raw.isBlank()) {
            return de.mhus.vance.api.slartibartfast.ConfirmationMode.DROP_LOW_CONF;
        }
        String norm = raw.trim().toUpperCase().replace('-', '_');
        try {
            return de.mhus.vance.api.slartibartfast.ConfirmationMode.valueOf(norm);
        } catch (IllegalArgumentException e) {
            log.warn("Slartibartfast unknown confirmationMode '{}', "
                            + "defaulting to DROP_LOW_CONF", raw);
            return de.mhus.vance.api.slartibartfast.ConfirmationMode.DROP_LOW_CONF;
        }
    }

    private static de.mhus.vance.api.slartibartfast.EscalationMode parseEscalationMode(
            String raw) {
        if (raw.isBlank()) {
            return de.mhus.vance.api.slartibartfast.EscalationMode.FAIL;
        }
        String norm = raw.trim().toUpperCase().replace('-', '_');
        try {
            return de.mhus.vance.api.slartibartfast.EscalationMode.valueOf(norm);
        } catch (IllegalArgumentException e) {
            log.warn("Slartibartfast unknown escalationMode '{}', "
                            + "defaulting to FAIL", raw);
            return de.mhus.vance.api.slartibartfast.EscalationMode.FAIL;
        }
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
