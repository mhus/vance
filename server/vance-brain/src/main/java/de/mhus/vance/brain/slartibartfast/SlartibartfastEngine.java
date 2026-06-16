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
import de.mhus.vance.brain.recipe.AppliedRecipe;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.slartibartfast.architect.SchemaArchitect;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.slartibartfast.phases.BindingPhase;
import de.mhus.vance.brain.slartibartfast.phases.ClassifyingPhase;
import de.mhus.vance.brain.slartibartfast.phases.ConfirmingPhase;
import de.mhus.vance.brain.slartibartfast.phases.DecomposingPhase;
import de.mhus.vance.brain.slartibartfast.phases.ExecutionValidatingPhase;
import de.mhus.vance.brain.slartibartfast.phases.FramingPhase;
import de.mhus.vance.brain.slartibartfast.phases.GatheringPhase;
import de.mhus.vance.brain.slartibartfast.phases.PersistingPhase;
import de.mhus.vance.brain.slartibartfast.phases.ProposingPhase;
import de.mhus.vance.brain.slartibartfast.phases.ValidatingPhase;
import de.mhus.vance.brain.thinkengine.ParentReport;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.api.thinkprocess.ProcessEventType;
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
import org.springframework.beans.factory.ObjectProvider;
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

    /** {@code engineParams[PROPOSING_HINTS_KEY]} — free-text
     *  caller-supplied guidance appended to Slart's PROPOSING system
     *  prompt. Used by kits / wrapper recipes to inject
     *  recipe-shape conventions (e.g. persistence patterns) without
     *  touching the bundled engine prompt. Empty / missing = default
     *  behaviour. See {@link ArchitectState#getProposingHints()}. */
    public static final String PROPOSING_HINTS_KEY = "proposingHints";

    /** {@code engineParams[CONFIRMATION_MODE_KEY]} — name of a
     *  {@link de.mhus.vance.api.slartibartfast.ConfirmationMode}
     *  value. Default {@code DROP_LOW_CONF}. */
    public static final String CONFIRMATION_MODE_KEY = "confirmationMode";

    /** {@code engineParams[ESCALATION_MODE_KEY]} — name of an
     *  {@link de.mhus.vance.api.slartibartfast.EscalationMode}
     *  value. Default {@code FAIL}. */
    public static final String ESCALATION_MODE_KEY = "escalationMode";

    /** {@code engineParams[PLAN_ONLY_KEY]} — when truthy
     *  ({@code true} / {@code "true"} / {@code "1"}), Slart stops
     *  after PERSISTING with status DONE and skips the EXECUTING
     *  + EXECUTION_VALIDATING phases. Default {@code false}:
     *  Slart plans, executes via a spawned child, and validates
     *  the produced artifacts before reporting DONE. Note:
     *  {@link de.mhus.vance.api.slartibartfast.ExecutionDecision#SKIP}
     *  emitted by EXECUTION_PLANNING has the same effect (stops
     *  after PERSISTING). */
    public static final String PLAN_ONLY_KEY = "planOnly";

    /** {@code engineParams[RECIPE_NAME_KEY]} — optional caller-
     *  supplied name under which to persist the generated recipe.
     *  When set, PERSISTING writes to
     *  {@code recipes/_user/<recipeName>.yaml} instead of the
     *  legacy {@code _slart/<runId>/} sandbox. The FRAMING-LLM
     *  can also emit this from a user description containing
     *  "speicher es unter X" / "save as X". Engine-param value
     *  takes precedence; LLM-emitted value fills in when the
     *  param is empty. */
    public static final String RECIPE_NAME_KEY = "recipeName";

    /** {@code engineParams[TARGET_RECIPE_NAME_KEY]} — optional
     *  caller-supplied target for an EDIT run. When set, Slart
     *  enters EDIT mode and LOADING_EXISTING tries to load
     *  {@code recipes/_user/<targetRecipeName>.yaml}. The
     *  FRAMING-LLM can also detect EDIT-intent from phrases
     *  like "Erweitere 'X' um …". Engine-param wins when both
     *  are set. */
    public static final String TARGET_RECIPE_NAME_KEY = "targetRecipeName";

    /** {@code engineParams[MODIFICATION_SUMMARY_KEY]} — optional
     *  free-text description of the requested modification for
     *  EDIT runs. Surfaces in the PROPOSING user prompt next to
     *  the existing recipe yaml. Usually emitted by the
     *  FRAMING-LLM, but callers can override here. */
    public static final String MODIFICATION_SUMMARY_KEY = "modificationSummary";

    /** {@code engineParams[MODE_KEY]} — optional caller-supplied
     *  {@link de.mhus.vance.api.slartibartfast.ArchitectMode}
     *  override. When unset, the engine derives the mode from the
     *  other params: {@code existingScriptRef} non-blank → UPDATE,
     *  {@code targetRecipeName} non-blank → EDIT, else CREATE.
     *  Explicit values are case-insensitive; unknown values warn-
     *  log and fall through to the derived default. */
    public static final String MODE_KEY = "mode";

    /** {@code engineParams[EXISTING_SCRIPT_REF_KEY]} — document path
     *  to the existing artefact for an UPDATE run (e.g. the
     *  current script the caller wants enhanced). Required when
     *  {@code mode=UPDATE}; LOADING_EXISTING reads the document
     *  via {@code DocumentService} and stashes the body on
     *  {@link ArchitectState#getExistingScriptCode()}. For SCRIPT_JS
     *  the path conventionally lives under {@code scripts/...};
     *  recipe-UPDATE (open point) would use {@code recipes/...}. */
    public static final String EXISTING_SCRIPT_REF_KEY = "existingScriptRef";

    /** {@code engineParams[FAILURE_REASON_KEY]} — optional free-text
     *  hint for an UPDATE run, typically a Hactar
     *  {@code TerminationRationale.failureReason} from a prior
     *  FAILED execution. The architect surfaces it in the
     *  PROPOSING user prompt so the LLM understands what the
     *  previous attempt got wrong. Null when the UPDATE is a
     *  plain feature-add, not a bug-fix. */
    public static final String FAILURE_REASON_KEY = "failureReason";

    private final ThinkProcessService thinkProcessService;
    private final ProcessEventEmitter eventEmitter;
    /** Fires per-phase progress status events so the chat user
     *  sees Slart's lifecycle live instead of staring at silence
     *  for 2-5 minutes. Each phase transition emits one
     *  {@link de.mhus.vance.api.progress.StatusTag#PHASE_DONE}
     *  status with the iteration's output summary. */
    private final de.mhus.vance.brain.progress.ProgressEmitter progressEmitter;
    private final LaneScheduler laneScheduler;
    private final ObjectMapper objectMapper;
    private final InboxItemService inboxItemService;
    private final RecipeResolver recipeResolver;
    private final ObjectProvider<ThinkEngineService> thinkEngineServiceProvider;
    private final FramingPhase framingPhase;
    private final ConfirmingPhase confirmingPhase;
    private final GatheringPhase gatheringPhase;
    private final ClassifyingPhase classifyingPhase;
    private final DecomposingPhase decomposingPhase;
    private final BindingPhase bindingPhase;
    private final ProposingPhase proposingPhase;
    private final ValidatingPhase validatingPhase;
    private final PersistingPhase persistingPhase;
    private final ExecutionValidatingPhase executionValidatingPhase;
    private final de.mhus.vance.brain.slartibartfast.phases.LoadingExistingPhase loadingExistingPhase;
    private final de.mhus.vance.brain.slartibartfast.phases.ExecutionPlanningPhase executionPlanningPhase;
    /** Schema-architect lookup for the EXECUTING phase — non-recipe
     *  schemas (SCRIPT_JS) supply a direct-spawn descriptor instead
     *  of going through the recipe-resolver. Lazy-built from the
     *  Spring-injected {@code List<SchemaArchitect>} on first use. */
    private final java.util.List<de.mhus.vance.brain.slartibartfast.architect.SchemaArchitect>
            schemaArchitects;
    private volatile java.util.Map<OutputSchemaType,
            de.mhus.vance.brain.slartibartfast.architect.SchemaArchitect> architectsMap;
    /**
     * Deterministic lift of file-path conventions from CLASSIFYING's
     * evidence into acceptanceCriteria. Runs between CLASSIFYING and
     * DECOMPOSING. Without this, kit-OUTPUT.md path conventions reach
     * the LLM as evidence claims but never as testable criteria, and
     * the generated Vogon recipe drops the persistence phase. See
     * {@link PathCriteriaLifter} javadoc for the full failure-mode
     * history.
     */
    private final PathCriteriaLifter pathCriteriaLifter;
    /**
     * Writes a child-execution outcome (Hactar DONE / FAILED / STOPPED)
     * as an ASSISTANT message into Slart's own chat history so the
     * parent orchestrator (Arthur) and ad-hoc reasoning tools see the
     * full script return value or error via
     * {@code process_history_text(name=<slart-process>)}. Without
     * this, Slart's history is always empty (0 msgs) and the LLM
     * either re-spawns the worker or guesses the result from the
     * compact event summary.
     */
    private final de.mhus.vance.shared.chat.ChatMessageService chatMessageService;

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

    @Override
    public boolean producesUserFacingOutput() {
        // Slart's DONE summary mixes engine bookkeeping ("Recipe
        // persisted at …") with the script-run report from Hactar.
        // Neither is what a human wanted to read. The
        // ParentNotificationListener routes the terminal event
        // through the engine-output-translator recipe to render a
        // natural-language answer matching the original user goal.
        return false;
    }

    /**
     * Slart's parent-facing summary. Without this, the default
     * {@link ThinkEngine#summarizeForParent} returns only
     * "Child process X status=done" — Arthur's LLM sees that, finds
     * no produced content, hallucinates "delegated worker finished
     * without providing anything" and triggers a spurious ASK_USER
     * cascade through Eddie (observed live on 2026-05-17).
     *
     * <p>The summary names the recipe that was built, the outcome of
     * the auto-executed child (Vogon strategy or Marvin tree), and
     * the persistence paths the kit's OUTPUT.md declared — exactly
     * the data the parent's LLM needs to decide "done, hand to user"
     * vs. "something is missing". For FAILED/STOPPED outcomes the
     * {@code failureReason} is propagated so Arthur can explain the
     * problem instead of guessing.
     *
     * <p>{@code payload} carries the same fields structured so a
     * downstream deterministic orchestrator (Vogon-as-parent) could
     * branch on outcome without re-parsing the markdown.
     */
    @Override
    public ParentReport summarizeForParent(
            ThinkProcessDocument process, ProcessEventType eventType) {
        ArchitectState state;
        try {
            state = loadState(process);
        } catch (RuntimeException e) {
            return ParentReport.of("Slartibartfast process "
                    + process.getId() + " status="
                    + eventType.name().toLowerCase());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", eventType.name());
        payload.put("processId", process.getId());
        payload.put("runId", state.getRunId());
        payload.put("schemaType", state.getOutputSchemaType().name());
        if (state.getPersistedRecipePath() != null) {
            payload.put("recipePath", state.getPersistedRecipePath());
        }
        if (state.getChildExecutionProcessId() != null) {
            payload.put("childProcessId", state.getChildExecutionProcessId());
        }
        if (state.getChildExecutionOutcome() != null) {
            payload.put("childOutcome", state.getChildExecutionOutcome());
        }
        List<String> outputPaths = extractOutputPaths(state);
        if (!outputPaths.isEmpty()) {
            payload.put("outputPaths", outputPaths);
        }
        if (state.getFailureReason() != null) {
            payload.put("failureReason", state.getFailureReason());
        }

        StringBuilder sb = new StringBuilder();
        RecipeDraft draft = state.getProposedRecipe();
        String recipeName = draft == null ? null : draft.getName();

        switch (eventType) {
            case DONE -> {
                // Three terminal flavours — be specific so the parent
                // doesn't misread a deliberately-skipped execution as
                // a failed file-output.
                boolean skipped = state.getExecutionDecision()
                        == de.mhus.vance.api.slartibartfast.ExecutionDecision.SKIP;
                sb.append("Slartibartfast finished")
                        .append(recipeName == null
                                ? "" : " — recipe '" + recipeName + "'");
                if (state.isPlanOnly()) {
                    sb.append(" (plan-only; the recipe was generated "
                            + "and persisted but not executed).");
                } else if (skipped) {
                    sb.append(". The recipe was generated and "
                            + "persisted, but execution was skipped "
                            + "deliberately — the user's request "
                            + "described a reusable recipe without "
                            + "a concrete mission to run.");
                    if (state.getExecutionDecisionReason() != null
                            && !state.getExecutionDecisionReason().isBlank()) {
                        sb.append("\nReason: ")
                                .append(state.getExecutionDecisionReason());
                    }
                } else {
                    sb.append(" and ran it to completion.");
                }
                if (state.getPersistedRecipePath() != null) {
                    sb.append("\nRecipe persisted at `")
                            .append(state.getPersistedRecipePath())
                            .append("`.");
                }
                if (!outputPaths.isEmpty()) {
                    sb.append("\nOutputs written to:");
                    for (String p : outputPaths) {
                        sb.append("\n- `").append(p).append("`");
                    }
                }
                // Result-surface depends on what the child engine
                // produces:
                //   - SCRIPT_JS via Hactar: a return value (lives on
                //     Hactar's executionResult, propagated via
                //     ProcessEvent.humanSummary into childExecutionSummary).
                //     No chat history — Hactar doesn't have one.
                //   - Recipe outputs (Vogon/Marvin/Zaphod): a chat
                //     transcript on the child process if no file
                //     paths were declared.
                // Plan-only / skipped runs have no child at all.
                if (outputPaths.isEmpty()
                        && !state.isPlanOnly() && !skipped) {
                    boolean isScriptOutput =
                            state.getOutputSchemaType()
                                    == de.mhus.vance.api.slartibartfast.OutputSchemaType.SCRIPT_JS;
                    if (isScriptOutput) {
                        // Surface the script's actual return value so
                        // the parent agent can answer the user
                        // directly without polling process_history.
                        if (state.getChildExecutionSummary() != null
                                && !state.getChildExecutionSummary().isBlank()) {
                            sb.append("\n\n")
                                    .append(state.getChildExecutionSummary());
                        } else {
                            sb.append("\nThe script ran to completion; "
                                    + "see the child Hactar process's "
                                    + "executionResult for the return value.");
                        }
                    } else {
                        sb.append("\nThe recipe declared no path-output "
                                + "criteria — the result lives in the child "
                                + "process's chat history, not as a file.");
                    }
                }
                // Skipped runs typically want a follow-up: spawn the
                // recipe with a concrete topic. Tell the parent so
                // the LLM doesn't infer a phantom failure.
                if (skipped) {
                    sb.append("\nTo run the recipe, spawn it with a "
                            + "concrete topic — e.g. `process_create"
                            + "(recipe=\"")
                            .append(recipeName == null
                                    ? "<recipe>" : recipeName)
                            .append("\", goal=\"<concrete topic>\")`.");
                }
            }
            case FAILED -> {
                sb.append("Slartibartfast failed");
                if (recipeName != null) {
                    sb.append(" while working on recipe '")
                            .append(recipeName).append("'");
                }
                sb.append(".");
                if (state.getFailureReason() != null) {
                    sb.append("\nReason: ").append(state.getFailureReason());
                }
                if (state.getChildExecutionOutcome() != null
                        && !"DONE".equals(state.getChildExecutionOutcome())) {
                    sb.append("\nChild execution outcome: ")
                            .append(state.getChildExecutionOutcome());
                }
            }
            case STOPPED -> {
                sb.append("Slartibartfast was stopped");
                if (recipeName != null) {
                    sb.append(" (recipe '").append(recipeName).append("')");
                }
                sb.append(".");
            }
            case BLOCKED -> {
                sb.append("Slartibartfast is blocked at status=")
                        .append(state.getStatus().name().toLowerCase());
                if (state.getPendingInboxKind()
                        != de.mhus.vance.api.slartibartfast.PendingInboxKind.NONE) {
                    sb.append(" awaiting a ")
                            .append(state.getPendingInboxKind().name()
                                    .toLowerCase().replace('_', '-'))
                            .append(" answer from the user.");
                } else {
                    sb.append(".");
                }
            }
        }
        return new ParentReport(sb.toString(), payload);
    }

    /**
     * Pull lifted-path file references out of {@code acceptanceCriteria}.
     * The {@link PathCriteriaLifter} writes criteria of the form
     * "The recipe must persist its output at `<path>` via doc_create."
     * — we extract everything inside back-ticks that matches a known
     * file extension. Matching is intentionally lenient (substring) so
     * user-stated criteria that mention a path verbatim are picked up
     * too; de-duplication preserves first-seen order.
     */
    private static List<String> extractOutputPaths(ArchitectState state) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (state.getAcceptanceCriteria() == null) return List.of();
        java.util.regex.Pattern pat = java.util.regex.Pattern.compile(
                "`((?:[A-Za-z0-9_][A-Za-z0-9_.-]*/)+"
                        + "[A-Za-z0-9_][A-Za-z0-9_.-]*"
                        + "\\.(?:md|markdown|txt|yaml|yml|json|csv|pdf))`");
        for (Criterion c : state.getAcceptanceCriteria()) {
            String t = c.getText();
            if (t == null) continue;
            java.util.regex.Matcher m = pat.matcher(t);
            while (m.find()) out.add(m.group(1));
        }
        return List.copyOf(out);
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
                } else if (msg instanceof SteerMessage.ProcessEvent pe) {
                    handleChildEvent(state, pe, process);
                }
            }

            ArchitectStatus statusBefore = state.getStatus();
            int iterationsBefore = state.getIterations().size();
            advanceOnePhase(process, ctx, state);
            persistState(process, state);
            emitPhaseProgress(process, statusBefore, state, iterationsBefore);

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

            // Park on EXECUTING when a child is in flight — the
            // child's ProcessEvent will arrive via drainPending
            // and handleChildEvent flips status to DONE/FAILED.
            if (state.getStatus() == ArchitectStatus.EXECUTING
                    && state.getChildExecutionProcessId() != null) {
                log.info("Slartibartfast id='{}' parking on child '{}'",
                        process.getId(), state.getChildExecutionProcessId());
                thinkProcessService.updateStatus(
                        process.getId(), ThinkProcessStatus.BLOCKED);
                return;
            }

            if (state.getStatus() == ArchitectStatus.DONE) {
                log.info("Slartibartfast id='{}' DONE — recipe at '{}'",
                        process.getId(), state.getPersistedRecipePath());
                persistAssistantNote(process,
                        "Slartibartfast finished — recipe at `"
                                + state.getPersistedRecipePath() + "`.");
                thinkProcessService.closeProcess(process.getId(), CloseReason.DONE);
                return;
            }
            if (state.getStatus() == ArchitectStatus.FAILED) {
                log.warn("Slartibartfast id='{}' FAILED: {}",
                        process.getId(), state.getFailureReason());
                persistAssistantNote(process,
                        "Slartibartfast FAILED — "
                                + (state.getFailureReason() == null
                                        ? "no reason recorded"
                                        : state.getFailureReason()));
                thinkProcessService.closeProcess(process.getId(), CloseReason.STALE);
                return;
            }
            if (state.getStatus() == ArchitectStatus.ESCALATED) {
                log.info("Slartibartfast id='{}' ESCALATED — inbox item '{}'",
                        process.getId(), state.getEscalationInboxItemId());
                persistAssistantNote(process,
                        "Slartibartfast escalated — inbox item `"
                                + state.getEscalationInboxItemId()
                                + "` awaiting user decision.");
                thinkProcessService.closeProcess(process.getId(), CloseReason.STALE);
                return;
            }

            thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
            eventEmitter.scheduleTurn(process.getId());
        } catch (RuntimeException e) {
            log.warn("Slartibartfast runTurn failed id='{}': {}",
                    process.getId(), e.toString(), e);
            // Persist the crash to the engine's own chat history so
            // process_history_text(name=slart-...) carries a real
            // explanation and the engine-output-translator has facts
            // to render instead of confabulating a plausible-looking
            // story from the user goal alone.
            persistAssistantNote(process,
                    "Slartibartfast aborted: " + e.getClass().getSimpleName()
                            + " — " + (e.getMessage() == null
                                    ? "(no message)" : e.getMessage()));
            thinkProcessService.closeProcess(process.getId(), CloseReason.STALE);
            throw e;
        }
    }

    /**
     * Append a single ASSISTANT chat-message to Slart's own history.
     * Used for lifecycle bookkeeping (phase progress, FAILED reason,
     * ESCALATED, RuntimeException). Best-effort: failures are logged
     * and swallowed — chat-history persistence is a debugging /
     * orchestrator-context surface, never part of the engine's
     * correctness contract.
     */
    private void persistAssistantNote(
            ThinkProcessDocument process, String body) {
        if (chatMessageService == null || body == null || body.isBlank()) {
            return;
        }
        try {
            chatMessageService.append(
                    de.mhus.vance.shared.chat.ChatMessageDocument.builder()
                            .tenantId(process.getTenantId())
                            .sessionId(process.getSessionId())
                            .thinkProcessId(process.getId())
                            .role(de.mhus.vance.api.chat.ChatRole.ASSISTANT)
                            .content(body)
                            .build());
        } catch (RuntimeException e) {
            log.debug(
                    "Slartibartfast id='{}' chat-history append failed: {}",
                    process.getId(), e.toString());
        }
    }

    /**
     * Spawn the child execution process from the persisted artefact
     * iff none has been spawned yet for this run. Idempotent: a
     * second call after the child already exists is a no-op (the
     * engine simply parks until the child's ProcessEvent arrives).
     * Falls back to FAILED with {@code failureReason} on any
     * resolver / spawn error.
     *
     * <p>Two dispatch paths based on the architect:
     * <ul>
     *   <li>Recipe schemas ({@code isRecipeOutput=true},
     *       persistedRecipePath under {@code _vance/recipes/...}):
     *       strip the prefix, recipe-resolver pickup, spawn the
     *       resolved engine with applied params.</li>
     *   <li>Non-recipe schemas ({@code isRecipeOutput=false},
     *       e.g. SCRIPT_JS): architect supplies
     *       {@link SchemaArchitect#directExecutionSpawn} with a
     *       {@code DirectExecutionSpawn}-descriptor (engineName +
     *       engineParams). Slart inherits its own allowed-tools
     *       into the child's {@code scriptAllowedTools}.</li>
     * </ul>
     */
    private void executeChildIfNeeded(
            ThinkProcessDocument process, ArchitectState state) {
        if (state.getChildExecutionProcessId() != null) {
            return;
        }
        String recipePath = state.getPersistedRecipePath();
        if (recipePath == null) {
            state.setFailureReason(
                    "EXECUTING entered but persistedRecipePath is null");
            state.setStatus(ArchitectStatus.FAILED);
            return;
        }
        SchemaArchitect architect = architects().get(state.getOutputSchemaType());
        if (architect == null) {
            state.setFailureReason("EXECUTING has no SchemaArchitect bean for "
                    + state.getOutputSchemaType());
            state.setStatus(ArchitectStatus.FAILED);
            return;
        }

        // Non-recipe dispatch (SCRIPT_JS et al.) — architect-supplied
        // direct-spawn descriptor wins. Skips the recipe-resolver.
        SchemaArchitect.DirectExecutionSpawn direct =
                architect.directExecutionSpawn(state);
        if (direct != null) {
            executeDirectChild(process, state, direct);
            return;
        }

        String recipeName = recipePath.startsWith("_vance/recipes/")
                && recipePath.endsWith(".yaml")
                ? recipePath.substring("_vance/recipes/".length(),
                        recipePath.length() - ".yaml".length())
                : null;
        if (recipeName == null) {
            state.setFailureReason(
                    "persistedRecipePath has unexpected shape: " + recipePath);
            state.setStatus(ArchitectStatus.FAILED);
            return;
        }
        try {
            AppliedRecipe applied = recipeResolver.apply(
                    process.getTenantId(), process.getProjectId(),
                    recipeName, process.getConnectionProfile(), null);
            ThinkEngineService engines = thinkEngineServiceProvider.getObject();
            var targetEngine = engines.resolve(applied.engine())
                    .orElseThrow(() -> new IllegalStateException(
                            "Recipe '" + applied.name()
                                    + "' references unknown engine '"
                                    + applied.engine() + "'"));
            // Recovery cycles re-enter EXECUTING with the same
            // runId; suffix with recoveryCount so child names
            // don't collide with the previous execution's child.
            String childName = "slart-exec-" + state.getRunId()
                    + "-" + state.getRecoveryCount();
            ThinkProcessDocument child = thinkProcessService.create(
                    process.getTenantId(),
                    process.getProjectId(),
                    process.getSessionId(),
                    childName,
                    targetEngine.name(), targetEngine.version(),
                    "Slart-spawned execution of " + applied.name(),
                    // EXECUTION_PLANNING decided which prompt to run with.
                    // Falls back to userDescription only when the new phase
                    // didn't write — e.g. planOnly path or legacy state.
                    state.getExecutionPrompt() != null
                            ? state.getExecutionPrompt()
                            : state.getUserDescription(),
                    process.getId(),
                    applied.params(), applied.name(),
                    applied.promptOverride(),
                    applied.promptOverrideAppend(),
                    applied.promptMode(), applied.dataRelayCorrection(),
                    applied.effectiveAllowedTools(),
                    applied.connectionProfile(),
                    applied.defaultActiveSkills(),
                    applied.allowedSkills() == null
                            ? null : Set.copyOf(applied.allowedSkills()));
            state.setChildExecutionProcessId(child.getId());
            persistState(process, state);
            engines.start(child);
            log.info("Slartibartfast id='{}' EXECUTING — spawned child='{}' "
                            + "engine='{}' recipe='{}'",
                    process.getId(), child.getId(),
                    targetEngine.name(), applied.name());
        } catch (RuntimeException e) {
            log.warn("Slartibartfast id='{}' EXECUTING spawn failed: {}",
                    process.getId(), e.toString(), e);
            state.setFailureReason("Failed to spawn execution child: "
                    + e.getMessage());
            state.setStatus(ArchitectStatus.FAILED);
        }
    }

    /**
     * Direct-spawn execution for non-recipe artefacts (SCRIPT_JS et
     * al.). The architect supplies engineName + engineParams; Slart
     * resolves the engine and spawns a child that inherits the
     * Slart process's effective allowed-tools as
     * {@code scriptAllowedTools} so the spawned executor surfaces
     * the same tool set the parent had.
     */
    private void executeDirectChild(
            ThinkProcessDocument process, ArchitectState state,
            SchemaArchitect.DirectExecutionSpawn direct) {
        try {
            ThinkEngineService engines = thinkEngineServiceProvider.getObject();
            var targetEngine = engines.resolve(direct.engineName())
                    .orElseThrow(() -> new IllegalStateException(
                            "DirectExecutionSpawn references unknown engine '"
                                    + direct.engineName() + "'"));

            java.util.Map<String, Object> engineParams =
                    new java.util.LinkedHashMap<>(direct.engineParams());
            // Inherit Slart's effective allow-set (the
            // allowedToolsOverride field on the parent process) into
            // the child's script-level allow-set. The child's own
            // allowed-tools (recipe-level, for LLM tool_use) is
            // separate and stays unchanged — direct-spawn children
            // typically don't make LLM calls themselves (Hactar has
            // empty allowedTools).
            java.util.Set<String> parentAllowed = process.getAllowedToolsOverride();
            if (parentAllowed != null && !parentAllowed.isEmpty()) {
                engineParams.putIfAbsent(
                        de.mhus.vance.brain.hactar.HactarEngine.SCRIPT_ALLOWED_TOOLS_KEY,
                        java.util.List.copyOf(parentAllowed));
            }

            String childName = "slart-exec-" + state.getRunId()
                    + "-" + state.getRecoveryCount();
            ThinkProcessDocument child = thinkProcessService.create(
                    process.getTenantId(),
                    process.getProjectId(),
                    process.getSessionId(),
                    childName,
                    targetEngine.name(), targetEngine.version(),
                    "Slart-spawned " + direct.engineName() + " run for "
                            + state.getOutputSchemaType(),
                    state.getExecutionPrompt() != null
                            ? state.getExecutionPrompt()
                            : state.getUserDescription(),
                    process.getId(),
                    engineParams,
                    /*recipeName*/ null,
                    /*promptOverride*/ null,
                    /*promptOverrideAppend*/ null,
                    /*promptMode*/ null,
                    /*dataRelayCorrection*/ null,
                    process.getAllowedToolsOverride(),
                    process.getConnectionProfile(),
                    /*defaultActiveSkills*/ null,
                    /*allowedSkills*/ null);
            state.setChildExecutionProcessId(child.getId());
            persistState(process, state);
            engines.start(child);
            log.info("Slartibartfast id='{}' EXECUTING (direct) — spawned "
                            + "child='{}' engine='{}' schema={}",
                    process.getId(), child.getId(),
                    targetEngine.name(), state.getOutputSchemaType());
        } catch (RuntimeException e) {
            log.warn("Slartibartfast id='{}' EXECUTING direct-spawn failed: {}",
                    process.getId(), e.toString(), e);
            state.setFailureReason("Failed to spawn direct execution child: "
                    + e.getMessage());
            state.setStatus(ArchitectStatus.FAILED);
        }
    }

    /**
     * Lazy-build of the {@link SchemaArchitect} lookup map from
     * the Spring-injected list. Identical pattern to
     * {@link de.mhus.vance.brain.slartibartfast.phases.ValidatingPhase}
     * — duplicated here because the engine itself needs the lookup
     * for EXECUTING and we want to keep the @RequiredArgsConstructor
     * + @Component wiring clean.
     */
    private java.util.Map<OutputSchemaType, SchemaArchitect> architects() {
        java.util.Map<OutputSchemaType, SchemaArchitect> m = architectsMap;
        if (m != null) return m;
        java.util.Map<OutputSchemaType, SchemaArchitect> built =
                new java.util.EnumMap<>(OutputSchemaType.class);
        for (SchemaArchitect a : schemaArchitects) {
            SchemaArchitect existing = built.put(a.type(), a);
            if (existing != null) {
                throw new IllegalStateException(
                        "Duplicate SchemaArchitect beans for " + a.type()
                                + ": " + existing.getClass().getName()
                                + " and " + a.getClass().getName());
            }
        }
        architectsMap = java.util.Map.copyOf(built);
        return architectsMap;
    }

    /**
     * Handle the child execution process's terminal
     * {@link SteerMessage.ProcessEvent}. Only events from the
     * tracked {@link ArchitectState#getChildExecutionProcessId()}
     * and with a terminal {@link ProcessEventType} count; others
     * are ignored.
     */
    private void handleChildEvent(
            ArchitectState state, SteerMessage.ProcessEvent pe,
            ThinkProcessDocument process) {
        String childId = state.getChildExecutionProcessId();
        if (childId == null || !childId.equals(pe.sourceProcessId())) {
            return;
        }
        ProcessEventType type = pe.type();
        boolean terminal = type == ProcessEventType.DONE
                || type == ProcessEventType.FAILED
                || type == ProcessEventType.STOPPED;
        if (!terminal) {
            return;
        }
        state.setChildExecutionOutcome(type.name());
        state.setChildExecutionSummary(pe.humanSummary());
        persistChildExecutionToChatHistory(process, type, pe.humanSummary());
        if (type == ProcessEventType.DONE) {
            log.info("Slartibartfast id='{}' child '{}' DONE — flipping "
                            + "to EXECUTION_VALIDATING",
                    process.getId(), childId);
            state.setStatus(ArchitectStatus.EXECUTION_VALIDATING);
        } else {
            log.warn("Slartibartfast id='{}' child '{}' terminated {}: {}",
                    process.getId(), childId, type, pe.humanSummary());
            state.setFailureReason("Execution child closed " + type
                    + (pe.humanSummary() == null
                            ? "" : ": " + pe.humanSummary()));
            state.setStatus(ArchitectStatus.FAILED);
        }
    }

    /**
     * Append an ASSISTANT message to Slart's own chat history when
     * the execution child terminates. Three goals:
     *
     * <ol>
     *   <li><b>Make {@code process_history_text(name=slart-...)}
     *       useful.</b> Parent engines (Arthur, Eddie) and the LLM
     *       reach for that tool to learn what a worker produced;
     *       Slart's history was empty until now so the tool
     *       returned {@code messageCount=0} and the LLM either
     *       re-spawned the worker or guessed.</li>
     *   <li><b>Forensics.</b> A persistent, queryable record of
     *       what the script returned (or how it failed) survives
     *       beyond the in-memory state object.</li>
     *   <li><b>Cheap.</b> One row, one in-process call — no extra
     *       LLM round-trip, no tool dispatch.</li>
     * </ol>
     *
     * <p>The message is best-effort: chat-history is a debugging
     * surface, not part of the engine's correctness contract.
     * Failures (Mongo down, oversize payload) are logged and
     * swallowed so the lifecycle keeps moving.
     */
    private void persistChildExecutionToChatHistory(
            ThinkProcessDocument process,
            ProcessEventType type,
            @org.jspecify.annotations.Nullable String childSummary) {
        if (chatMessageService == null) {
            return; // unit-test wiring may skip the service
        }
        try {
            StringBuilder body = new StringBuilder();
            switch (type) {
                case DONE -> body.append("Script execution completed.");
                case FAILED -> body.append("Script execution failed.");
                case STOPPED -> body.append("Script execution was stopped.");
                default -> body.append("Script execution event: ").append(type);
            }
            if (childSummary != null && !childSummary.isBlank()) {
                body.append("\n\n").append(childSummary);
            }
            chatMessageService.append(
                    de.mhus.vance.shared.chat.ChatMessageDocument.builder()
                            .tenantId(process.getTenantId())
                            .sessionId(process.getSessionId())
                            .thinkProcessId(process.getId())
                            .role(de.mhus.vance.api.chat.ChatRole.ASSISTANT)
                            .content(body.toString())
                            .build());
        } catch (RuntimeException e) {
            log.warn(
                    "Slartibartfast id='{}' failed to persist child execution "
                            + "outcome to chat history: {}",
                    process.getId(), e.toString());
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
    /**
     * Surface the just-completed phase transition to the chat user
     * via {@link de.mhus.vance.brain.progress.ProgressEmitter} —
     * Slart's runtime is a 2-5 minute pipeline with no inherent
     * sub-process output, so without these status events the user
     * stares at a silent chat. Emits a
     * {@link de.mhus.vance.api.progress.StatusTag#PHASE_DONE}
     * carrying the phase name + the new PhaseIteration's
     * output-summary when a new iteration was appended. Skips
     * spam-events on null-transitions (recovery rollbacks that
     * don't add an iteration).
     */
    /**
     * Heads-up ping emitted BEFORE each phase's heavy work runs. The
     * companion to {@link #emitPhaseProgress} which fires AFTER the
     * phase with the output summary — together they bracket every
     * Slart phase so the chat user sees:
     *
     * <pre>
     *   "Slartibartfast PROPOSING…"        ← this ping (phase START)
     *   …30-60s of LLM-driven silence…
     *   "Slartibartfast PROPOSING: <summary>"  ← emitPhaseProgress
     * </pre>
     *
     * <p>READY transitions are skipped (no work to announce). All
     * other phase entries emit a {@link de.mhus.vance.api.progress.StatusTag#INFO}
     * ping; the filter still honours the per-process
     * {@code ProgressLevel} via {@link de.mhus.vance.brain.progress.ProgressEmitter}.
     */
    private void emitPhaseStart(
            ThinkProcessDocument process, ArchitectState state) {
        ArchitectStatus status = state.getStatus();
        if (status == null
                || status == ArchitectStatus.READY
                || status == ArchitectStatus.DONE
                || status == ArchitectStatus.FAILED
                || status == ArchitectStatus.ESCALATED
                || status == ArchitectStatus.ESCALATING) {
            return;
        }
        // Recovery suffix: when this phase has been entered before
        // (recovery loop bumped us back to DECOMPOSING or PROPOSING),
        // surface the attempt counter so the user knows the phase is
        // re-running, not stuck on its first pass. Counts iterations
        // of THIS phase in the audit chain — distinct from the
        // global recoveryCount which mixes BINDING + VALIDATING.
        int phaseAttempt = countPhaseIterations(state, status);
        String suffix = phaseAttempt > 1
                ? " (attempt " + phaseAttempt + "/"
                        + Math.max(state.getMaxRecoveries(), phaseAttempt) + ")"
                : "";
        try {
            progressEmitter.emitStatus(process,
                    de.mhus.vance.api.progress.StatusTag.INFO,
                    "Slartibartfast " + status.name() + suffix + "…");
        } catch (RuntimeException e) {
            log.debug("Slartibartfast id='{}' progress-start emit failed: {}",
                    process.getId(), e.toString());
        }
    }

    /**
     * Count how many times the given phase has already produced a
     * PhaseIteration entry in the audit chain. The phase-START ping
     * fires BEFORE the current iteration is appended, so a return of
     * {@code n} means "this is the (n+1)-th attempt". The label uses
     * {@code n+1} directly.
     */
    private static int countPhaseIterations(
            ArchitectState state, ArchitectStatus phase) {
        int count = 1;  // the attempt we're about to start
        for (de.mhus.vance.api.slartibartfast.PhaseIteration it
                : state.getIterations()) {
            if (it.getPhase() == phase) count++;
        }
        return count;
    }

    private void emitPhaseProgress(
            ThinkProcessDocument process,
            ArchitectStatus statusBefore,
            ArchitectState state,
            int iterationsBefore) {
        java.util.List<de.mhus.vance.api.slartibartfast.PhaseIteration> iters =
                state.getIterations();
        if (iters.size() <= iterationsBefore) {
            return;  // phase didn't append an iteration; nothing to report
        }
        de.mhus.vance.api.slartibartfast.PhaseIteration latest =
                iters.get(iters.size() - 1);
        String summary = latest.getOutputSummary() == null
                ? "" : latest.getOutputSummary();
        if (summary.length() > 200) summary = summary.substring(0, 200) + "…";
        String label = latest.getPhase().name();
        if (statusBefore != null
                && statusBefore != latest.getPhase()
                && statusBefore != ArchitectStatus.READY) {
            label = statusBefore.name() + "→" + latest.getPhase().name();
        }
        String text = "Slartibartfast " + label
                + (summary.isBlank() ? "" : ": " + summary);
        try {
            progressEmitter.emitStatus(process,
                    de.mhus.vance.api.progress.StatusTag.PHASE_DONE, text);
        } catch (RuntimeException e) {
            log.debug("Slartibartfast id='{}' progress emit failed: {}",
                    process.getId(), e.toString());
        }
        // Mirror phase progress into the engine's own chat history.
        // Without this, process_history_text(name=slart-...) stays
        // empty until a terminal event lands, which means a Slart that
        // crashes mid-lifecycle has no audit trail at all and parent
        // orchestrators (Arthur) plus the engine-output-translator
        // have to confabulate from the userGoal. One short line per
        // phase iteration is enough — the full evidence trail lives
        // in ArchitectState.iterations.
        persistAssistantNote(process, text);
    }

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

            // Recovery from EXECUTION_VALIDATING: the next
            // proposing→validating→persisting→executing cycle
            // needs a fresh child. Clear the previous child's
            // reference so executeChildIfNeeded re-spawns.
            if (consumedRecovery.getFromPhase()
                    == ArchitectStatus.EXECUTION_VALIDATING) {
                state.setChildExecutionProcessId(null);
                state.setChildExecutionOutcome(null);
                state.setChildExecutionSummary(null);
            }
        }

        // Phase-START ping — surfaces "Slart is now in PROPOSING…"
        // BEFORE the (possibly minute-long) LLM call kicks off, so the
        // chat user sees activity instead of staring at silence between
        // PHASE_DONE events. PHASE_DONE (emitted by emitPhaseProgress
        // after the phase) carries the actual output summary; this ping
        // is just a heads-up.
        emitPhaseStart(process, state);

        switch (state.getStatus()) {
            case READY -> {
                state.setStatus(ArchitectStatus.FRAMING);
            }
            case FRAMING -> {
                framingPhase.execute(state, process, ctx);
                if (state.getFailureReason() != null) {
                    state.setStatus(ArchitectStatus.FAILED);
                } else if (state.getMode()
                        == de.mhus.vance.api.slartibartfast.ArchitectMode.EDIT
                        || state.getMode()
                        == de.mhus.vance.api.slartibartfast.ArchitectMode.UPDATE) {
                    state.setStatus(ArchitectStatus.LOADING_EXISTING);
                } else {
                    state.setStatus(ArchitectStatus.CONFIRMING);
                }
            }
            case LOADING_EXISTING -> {
                loadingExistingPhase.execute(state, process, ctx);
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
                // Promote file-path conventions from evidence claims
                // into synthetic acceptance criteria so PROPOSING /
                // VALIDATING treat them as required deliverables. Pure
                // function, no LLM. See PathCriteriaLifter javadoc.
                if (state.getFailureReason() == null) {
                    pathCriteriaLifter.lift(state);
                }
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
                } else if (state.isPlanOnly()) {
                    state.setStatus(ArchitectStatus.DONE);
                } else {
                    state.setStatus(ArchitectStatus.EXECUTION_PLANNING);
                }
            }
            case EXECUTION_PLANNING -> {
                executionPlanningPhase.execute(state, process, ctx);
                if (state.getFailureReason() != null) {
                    state.setStatus(ArchitectStatus.FAILED);
                } else if (state.getExecutionDecision()
                        == de.mhus.vance.api.slartibartfast.ExecutionDecision.SKIP) {
                    state.setStatus(ArchitectStatus.DONE);
                } else {
                    state.setStatus(ArchitectStatus.EXECUTING);
                }
            }
            case EXECUTING -> {
                executeChildIfNeeded(process, state);
                // No status change here when the child is still
                // running — the park-check below blocks the
                // process until a ProcessEvent arrives via
                // drainPending and handleChildEvent flips status.
            }
            case EXECUTION_VALIDATING -> {
                // Schema-aware skip: non-recipe outputs (e.g. SCRIPT_JS)
                // have no .md/.json/.yaml file artefacts the structural
                // check would find. The child's own EXECUTING outcome
                // (Hactar DONE/FAILED) is the success signal we use.
                SchemaArchitect arch = architects().get(state.getOutputSchemaType());
                boolean skip = arch != null && !arch.wantsExecutionValidation();
                if (skip) {
                    log.debug("Slartibartfast id='{}' EXECUTION_VALIDATING "
                                    + "skipped — schema {} declines via "
                                    + "wantsExecutionValidation()",
                            process.getId(), state.getOutputSchemaType());
                    state.setStatus(ArchitectStatus.DONE);
                } else {
                    executionValidatingPhase.execute(state, process, ctx);
                    if (state.getFailureReason() != null) {
                        state.setStatus(ArchitectStatus.FAILED);
                    } else if (state.getPendingRecovery() != null) {
                        // Stay in EXECUTION_VALIDATING — next runTurn
                        // picks up the recovery and rolls back to
                        // PROPOSING.
                    } else {
                        state.setStatus(ArchitectStatus.DONE);
                    }
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
        boolean planOnly = parseBooleanParam(p, PLAN_ONLY_KEY);
        String proposingHints = stringParam(p, PROPOSING_HINTS_KEY);

        // Phase-D engine-params: caller-supplied recipe naming + edit-target.
        // FRAMING-LLM can populate these from the user description too;
        // engine-params win when both are set (explicit > inferred).
        String recipeName = stringParam(p, RECIPE_NAME_KEY);
        String targetRecipeName = stringParam(p, TARGET_RECIPE_NAME_KEY);
        String modificationSummary = stringParam(p, MODIFICATION_SUMMARY_KEY);
        String existingScriptRef = stringParam(p, EXISTING_SCRIPT_REF_KEY);
        String failureReason = stringParam(p, FAILURE_REASON_KEY);
        String modeRaw = stringParam(p, MODE_KEY);

        // Mode derivation: explicit param > inferred-from-other-params.
        // - CREATE: default
        // - EDIT:   targetRecipeName set OR explicit mode=EDIT (recipe in-place)
        // - UPDATE: existingScriptRef set OR explicit mode=UPDATE (sandbox bucket)
        de.mhus.vance.api.slartibartfast.ArchitectMode initialMode =
                resolveInitialMode(modeRaw, targetRecipeName, existingScriptRef);
        validateModeInputs(initialMode, targetRecipeName, existingScriptRef);

        return ArchitectState.builder()
                .runId(generateRunId())
                .userDescription(userDescription)
                .proposingHints(proposingHints.isBlank() ? null : proposingHints)
                .outputSchemaType(schemaType)
                .confirmationMode(confirmationMode)
                .escalationMode(escalationMode)
                .planOnly(planOnly)
                .mode(initialMode)
                .recipeName(recipeName.isBlank() ? null : recipeName)
                .targetRecipeName(targetRecipeName.isBlank() ? null : targetRecipeName)
                .modificationSummary(modificationSummary.isBlank() ? null : modificationSummary)
                .existingScriptRef(existingScriptRef.isBlank() ? null : existingScriptRef)
                .priorFailureReason(failureReason.isBlank() ? null : failureReason)
                .status(ArchitectStatus.READY)
                .build();
    }

    /**
     * Resolves the run's initial {@link
     * de.mhus.vance.api.slartibartfast.ArchitectMode} from the
     * explicit {@code mode} engine-param (if set) or by inferring
     * from the other params. Explicit param wins on conflict;
     * unknown values warn-log and fall through to the inferred
     * default.
     */
    private static de.mhus.vance.api.slartibartfast.ArchitectMode resolveInitialMode(
            String modeRaw, String targetRecipeName, String existingScriptRef) {
        if (!modeRaw.isBlank()) {
            String norm = modeRaw.trim().toUpperCase().replace('-', '_');
            try {
                return de.mhus.vance.api.slartibartfast.ArchitectMode.valueOf(norm);
            } catch (IllegalArgumentException iae) {
                log.warn("Slartibartfast unknown mode '{}' — "
                                + "falling back to inferred default", modeRaw);
            }
        }
        if (!existingScriptRef.isBlank()) {
            return de.mhus.vance.api.slartibartfast.ArchitectMode.UPDATE;
        }
        if (!targetRecipeName.isBlank()) {
            return de.mhus.vance.api.slartibartfast.ArchitectMode.EDIT;
        }
        return de.mhus.vance.api.slartibartfast.ArchitectMode.CREATE;
    }

    /**
     * Hard-fails the spawn when the mode + paired params don't
     * match: UPDATE requires {@code existingScriptRef}; EDIT
     * requires {@code targetRecipeName}. CREATE accepts neither
     * (extraneous params are ignored). Throws
     * {@link IllegalStateException} so {@code start(...)} surfaces
     * the misconfiguration before any phase runs.
     */
    private static void validateModeInputs(
            de.mhus.vance.api.slartibartfast.ArchitectMode mode,
            String targetRecipeName, String existingScriptRef) {
        switch (mode) {
            case UPDATE -> {
                if (existingScriptRef.isBlank()) {
                    throw new IllegalStateException(
                            "Slartibartfast mode=UPDATE requires "
                                    + EXISTING_SCRIPT_REF_KEY + " engine-param");
                }
            }
            case EDIT -> {
                if (targetRecipeName.isBlank()) {
                    // FRAMING-LLM may extract this from the user
                    // description; only warn here, don't hard-fail.
                    log.debug("Slartibartfast mode=EDIT without explicit "
                                    + "targetRecipeName — relying on FRAMING-LLM "
                                    + "to extract it from userDescription");
                }
            }
            case CREATE -> { /* no required pair */ }
        }
    }

    private static boolean parseBooleanParam(Map<String, Object> params, String key) {
        if (params == null) return false;
        Object v = params.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) {
            return "true".equalsIgnoreCase(s.trim()) || "1".equals(s.trim());
        }
        if (v instanceof Number n) return n.intValue() != 0;
        return false;
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
