package de.mhus.vance.brain.deepthought;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.deepthought.DeepThoughtState;
import de.mhus.vance.api.deepthought.DeepThoughtState.ValidationError;
import de.mhus.vance.api.deepthought.DeepThoughtStatus;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.ai.EngineChatFactory;
import de.mhus.vance.brain.progress.LlmCallTracker;
import de.mhus.vance.brain.recipe.AppliedRecipe;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.scheduling.LaneScheduler;
import de.mhus.vance.brain.prompt.PromptContextBuilder;
import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.api.skills.SkillReferenceDocLoadMode;
import de.mhus.vance.brain.script.JsValidationService;
import de.mhus.vance.brain.script.ScriptExecutionException;
import de.mhus.vance.brain.script.ScriptExecutor;
import de.mhus.vance.brain.script.ScriptRequest;
import de.mhus.vance.brain.script.ScriptResult;
import de.mhus.vance.brain.skill.ResolvedSkill;
import de.mhus.vance.brain.skill.SkillResolver;
import de.mhus.vance.brain.skill.SkillScopeContext;
import de.mhus.vance.brain.skill.UnknownSkillException;
import de.mhus.vance.brain.thinkengine.EnginePromptResolver;
import de.mhus.vance.brain.thinkengine.ParentReport;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.brain.tools.ToolDispatcher;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.skill.ActiveSkillRefEmbedded;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Deep Thought — script-architect engine. Reads a goal, drafts a
 * JavaScript body, validates it parse-only, recovers on syntax errors
 * up to {@link DeepThoughtState#getMaxRecoveries()} times, and (optionally)
 * hands off to a Script Cortex runner.
 *
 * <p>The accepted script lives in {@code DeepThoughtState.generatedCode}
 * — there is no separate persistence to a project document. Debug
 * inspection happens through the {@code engineParams.deepThoughtState}
 * blob itself (Mongo find / Insights view); parents read the final
 * code through {@code summarizeForParent}.
 *
 * <p>State persists on
 * {@code ThinkProcessDocument.engineParams.deepThoughtState}; each
 * {@code runTurn} performs <em>one</em> phase, then yields and
 * schedules the next turn — same lane-discipline as Zaphod and
 * Vogon. The state machine:
 *
 * <pre>
 *   READY → DRAFTING → VALIDATING → DONE
 *                  ↑       │
 *                  └───────┘  (recovery loop: syntax error → re-draft
 *                             with error hint, up to maxRecoveries)
 *                                  │
 *                                  └→ EXECUTING → DONE (if executeOnDone)
 * </pre>
 *
 * <p>Phase 0 implementation: phase methods are stubbed so the
 * lifecycle round-trips; real DRAFTING/VALIDATING logic
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

    /** {@code engineParams[EXECUTE_ON_DONE_KEY]} — boolean; default false. */
    public static final String EXECUTE_ON_DONE_KEY = "executeOnDone";

    /** {@code engineParams[MAX_RECOVERIES_KEY]} — int; default 5. */
    public static final String MAX_RECOVERIES_KEY = "maxRecoveries";

    /** {@code engineParams[FRAMING_ENABLED_KEY]} — plan-mode opt-in.
     *  Default false: READY goes straight to DRAFTING (existing
     *  one-shot behaviour). When true: READY → FRAMING → REVIEWING
     *  → DRAFTING, with a recovery loop on REJECTED. */
    public static final String FRAMING_ENABLED_KEY = "framingEnabled";

    /** {@code engineParams[REVIEWER_RECIPE_KEY]} — explicit sub-recipe
     *  name for the REVIEWING phase. When unset, the engine falls
     *  back to {@code <recipeName>-reviewer} derived from the
     *  spawning recipe; when neither resolves, REVIEWING is skipped
     *  and FRAMING transitions straight to DRAFTING. */
    public static final String REVIEWER_RECIPE_KEY = "reviewerRecipe";

    /** {@code engineParams[MAX_FRAMING_RECOVERIES_KEY]} — soft-cap on
     *  FRAMING→REVIEWING retry cycles. Default 3 (shorter than
     *  DRAFTING's recovery budget because each cycle costs two LLM
     *  calls: drafter + reviewer). */
    public static final String MAX_FRAMING_RECOVERIES_KEY = "maxFramingRecoveries";

    /** {@code engineParams[MANUAL_PATHS_KEY]} — list of project folder
     *  paths whose Markdown documents are enumerated at DRAFTING and
     *  surfaced as {@code manualInventory} in the system prompt.
     *  Mirrors the same key {@code ManualPaths} uses for the running-
     *  LLM {@code manual_list}/{@code manual_read} tools, so the same
     *  config drives both. Missing/empty → no manuals block rendered. */
    public static final String MANUAL_PATHS_KEY = "manualPaths";

    /** {@code engineParams[SCRIPT_ARGS_KEY]} — map handed to the
     *  generated script as the top-level {@code args} binding when
     *  EXECUTING runs. Lets the caller pass typed inputs in (the
     *  same shape skill-scripts expect). {@code null} or missing →
     *  empty map. Ignored unless {@code executeOnDone=true}. */
    public static final String SCRIPT_ARGS_KEY = "scriptArgs";

    /** {@code engineParams[EXECUTION_TIMEOUT_KEY]} — fallback timeout
     *  for EXECUTING when the script body has no {@code @timeout}
     *  header. Accepted as a number-of-seconds (int/long) or a
     *  parseable duration string ({@code "30s"}, {@code "2m"}). {@code null}
     *  → ScriptEngine defaults from application.yaml apply. */
    public static final String EXECUTION_TIMEOUT_KEY = "executionTimeoutSeconds";

    /** {@code engineParams[SCRIPT_ALLOWED_TOOLS_KEY]} — list of tool
     *  names the <em>generated script</em> will be permitted to call
     *  via {@code vance.tools.call(name, args)}. Used at DRAFTING time
     *  to render a concrete tool inventory (name + description +
     *  declared params) into the system prompt so the LLM can pick
     *  real tool names rather than hallucinate them. {@code null} or
     *  empty → no tool block rendered; the LLM is told the script must
     *  not call any tools. */
    public static final String SCRIPT_ALLOWED_TOOLS_KEY = "scriptAllowedTools";

    /** Cascade path for the FRAMING system prompt. Same Pebble
     *  variables as DRAFTING (goal, toolInventory, manualInventory,
     *  skillGuidance) plus {@code recoveryHint} when re-framing. */
    private static final String FRAMING_PROMPT_PATH = "prompts/deepthought-framing.md";

    /** Last-resort Java fallback if the bundled FRAMING resource is
     *  missing (e.g. dev hot-reload without test resources). */
    private static final String FRAMING_FALLBACK_PROMPT =
            "You are the FRAMING node of Deep Thought. Write a "
                    + "structured plan sketch for a JavaScript orchestrator "
                    + "script that fulfils the goal: {{ goal }}. Do not "
                    + "write code yet — just the plan.";

    /** Cascade path for the Deep Thought DRAFTING system prompt.
     *  Loaded via {@link EnginePromptResolver} — project / _vance /
     *  bundled — and rendered as a Pebble template with {@code goal}
     *  injected as a variable. */
    private static final String DRAFTING_PROMPT_PATH = "prompts/deepthought-drafting.md";

    /** Last-resort Java fallback if the bundled prompt resource is
     *  missing (e.g. dev hot-reload without test resources). Real
     *  source-of-truth is {@link #DRAFTING_PROMPT_PATH}. */
    private static final String DRAFTING_FALLBACK_PROMPT =
            "You are the DRAFTING node of the Deep Thought engine. "
                    + "Reply with EXACTLY one ```javascript fenced block "
                    + "containing an IIFE that fulfils the goal: {{ goal }}";

    /** Tag a skill must carry to participate in DRAFTING. Skills
     *  without this tag are ignored even when active on the process —
     *  prevents Arthur/Ford-side skills (essay-writer, etc.) from
     *  bleeding into the script-architect prompt. */
    public static final String SCRIPT_ARCHITECT_TAG = "script-architect";

    /** Matches the first ```javascript / ```js / ``` fenced block
     *  in the LLM reply. DOTALL so {@code .} spans line breaks. */
    private static final Pattern JS_FENCE = Pattern.compile(
            "```(?:javascript|js)?\\s*\\R([\\s\\S]*?)\\R```",
            Pattern.MULTILINE);

    private final ThinkProcessService thinkProcessService;
    private final ProcessEventEmitter eventEmitter;
    private final ObjectMapper objectMapper;
    private final EngineChatFactory engineChatFactory;
    private final EnginePromptResolver enginePromptResolver;
    private final PromptTemplateRenderer promptTemplateRenderer;
    private final LlmCallTracker llmCallTracker;
    private final JsValidationService jsValidationService;
    private final ToolDispatcher toolDispatcher;
    private final ScriptExecutor scriptExecutor;
    private final DocumentService documentService;
    private final SkillResolver skillResolver;
    private final SessionService sessionService;
    private final RecipeResolver recipeResolver;
    private final LaneScheduler laneScheduler;
    private final ChatMessageService chatMessageService;
    private final ObjectProvider<ThinkEngineService> thinkEngineServiceProvider;

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
        // v1: none. DRAFTING uses a direct LLM call (no tool_use),
        // VALIDATING is in-process via JsValidationService, and no
        // document is written. EXECUTING (v1.1) will widen this to
        // process_run / execute_javascript when Script Cortex lands.
        return Set.of();
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
                        + "executeOnDone={} maxRecoveries={}",
                process.getTenantId(), process.getSessionId(), process.getId(),
                state.isExecuteOnDone(), state.getMaxRecoveries());
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
            case READY -> state.isFramingEnabled()
                    ? DeepThoughtStatus.FRAMING
                    : DeepThoughtStatus.DRAFTING;
            case FRAMING -> runFraming(process, ctx, state);
            case REVIEWING -> runReviewing(process, ctx, state);
            case DRAFTING -> runDrafting(process, ctx, state);
            case VALIDATING -> runValidating(process, ctx, state);
            case EXECUTING -> runExecuting(process, ctx, state);
            // DONE/FAILED handled before dispatch; defensive fall-through.
            case DONE -> DeepThoughtStatus.DONE;
            case FAILED -> DeepThoughtStatus.FAILED;
        };
    }

    // ──────────────────── Phases ────────────────────

    /**
     * FRAMING — single LLM call that produces a Markdown plan sketch
     * (Goal recap / Approach / Steps / Tools called / Edge cases /
     * Return value). No code in this phase. Same prompt enrichment as
     * DRAFTING (toolInventory, manualInventory, skillGuidance) plus a
     * {@code recoveryHint} when re-framing after a REJECTED review.
     *
     * <p>Always transitions to REVIEWING. The REVIEWING phase then
     * decides whether to advance to DRAFTING (APPROVED / no reviewer
     * configured) or loop back here (REJECTED).
     */
    DeepThoughtStatus runFraming(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            DeepThoughtState state) {
        EngineChatFactory.EngineChatBundle bundle =
                engineChatFactory.forProcess(process, ctx, NAME);
        String modelAlias = bundle.primaryConfig().provider()
                + ":" + bundle.primaryConfig().modelName();

        String basePath = paramString(process, "framingPromptDocument", FRAMING_PROMPT_PATH);
        String systemTpl = enginePromptResolver.resolve(
                process, basePath, FRAMING_FALLBACK_PROMPT);
        List<ResolvedSkill> architectSkills = resolveScriptArchitectSkills(process);
        Map<String, Object> ctxMap = new LinkedHashMap<>(
                PromptContextBuilder.forProcess(process, null)
                        .engine(NAME)
                        .build());
        ctxMap.put("goal", state.getGoal() == null ? "" : state.getGoal());
        ctxMap.put("toolInventory", renderToolInventory(process, ctx));
        ctxMap.put("manualInventory", renderManualInventory(process, architectSkills));
        ctxMap.put("skillGuidance", renderSkillGuidance(architectSkills));
        ctxMap.put("recoveryHint",
                state.getFramingRecoveryCount() > 0 && state.getReviewerNotes() != null
                        ? state.getReviewerNotes() : "");
        String renderedSystem = promptTemplateRenderer.render(systemTpl, ctxMap);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(renderedSystem == null ? "" : renderedSystem));
        messages.add(UserMessage.from(
                "Goal:\n" + (state.getGoal() == null ? "" : state.getGoal())
                        + "\n\nProduce the plan sketch now. Follow the "
                        + "section structure exactly."));

        long startMs = System.currentTimeMillis();
        ChatRequest request = ChatRequest.builder().messages(messages).build();
        ChatResponse response = bundle.chat().chatModel().chat(request);
        llmCallTracker.record(process, request, response,
                System.currentTimeMillis() - startMs, modelAlias);

        String reply = response.aiMessage() == null
                ? null : response.aiMessage().text();
        if (reply == null || reply.isBlank()) {
            state.setFailureReason(
                    "FRAMING returned an empty reply from the LLM");
            return DeepThoughtStatus.FAILED;
        }
        state.setPlanSketch(reply.trim());
        // Clear the previous reviewer fields — REVIEWING repopulates.
        state.setReviewerVerdict(null);
        state.setReviewerNotes(null);
        log.info("DeepThought.runFraming id='{}' attempt {} produced {} chars",
                process.getId(), state.getFramingRecoveryCount() + 1,
                reply.length());
        return DeepThoughtStatus.REVIEWING;
    }

    /**
     * REVIEWING — spawns the configured reviewer sub-recipe as a child
     * process, hands it the plan sketch as steer content, awaits the
     * reply synchronously (same Zaphod-style drive pattern as multi-
     * head council), and parses a VERDICT line.
     *
     * <p>Verdict-parsing is lenient: the first line containing
     * {@code APPROVED} or {@code REJECTED} (case-insensitive) wins.
     * Everything else is captured into {@code reviewerNotes}.
     *
     * <p>If no reviewer recipe is configured AND no
     * {@code <parentRecipe>-reviewer} resolves: REVIEWING is skipped
     * (verdict left {@code null}) and the engine transitions to
     * DRAFTING with the unreviewed plan sketch as context. Plan-mode
     * still adds value (LLM thinks twice) — external critique just
     * isn't available.
     */
    DeepThoughtStatus runReviewing(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            DeepThoughtState state) {
        String reviewerRecipe = resolveReviewerRecipe(process);
        if (reviewerRecipe == null) {
            log.info("DeepThought.runReviewing id='{}' no reviewer recipe "
                            + "configured/resolvable — skipping review",
                    process.getId());
            state.setReviewerVerdict("SKIPPED");
            state.setReviewerNotes("No reviewer recipe configured.");
            return DeepThoughtStatus.DRAFTING;
        }

        AppliedRecipe applied;
        try {
            applied = recipeResolver.apply(
                    process.getTenantId(), ctx.projectId(), reviewerRecipe,
                    process.getConnectionProfile(), null);
        } catch (RecipeResolver.UnknownRecipeException ure) {
            log.warn("DeepThought.runReviewing id='{}' reviewer recipe '{}' "
                            + "unknown — skipping review",
                    process.getId(), reviewerRecipe);
            state.setReviewerVerdict("SKIPPED");
            state.setReviewerNotes(
                    "Reviewer recipe '" + reviewerRecipe + "' not found.");
            return DeepThoughtStatus.DRAFTING;
        }

        ThinkEngineService engineService = thinkEngineServiceProvider.getObject();
        ThinkEngine targetEngine = engineService.resolve(applied.engine())
                .orElseThrow(() -> new IllegalStateException(
                        "Reviewer recipe '" + applied.name()
                                + "' references unknown engine '"
                                + applied.engine() + "'"));

        String childName = "deepthought-reviewer-" + process.getId()
                + "-" + (state.getFramingRecoveryCount() + 1);
        ThinkProcessDocument child;
        try {
            child = thinkProcessService.create(
                    process.getTenantId(),
                    process.getProjectId(),
                    process.getSessionId(),
                    childName,
                    targetEngine.name(),
                    targetEngine.version(),
                    "Deep Thought plan reviewer for " + process.getId(),
                    process.getGoal(),
                    process.getId(),
                    applied.params(),
                    applied.name(),
                    applied.promptOverride(),
                    applied.promptOverrideAppend(),
                    applied.promptMode(),
                    applied.dataRelayCorrection(),
                    applied.effectiveAllowedTools(),
                    applied.connectionProfile(),
                    applied.defaultActiveSkills(),
                    applied.allowedSkills() == null
                            ? null : java.util.Set.copyOf(applied.allowedSkills()));
            engineService.start(child);
        } catch (RuntimeException e) {
            log.warn("DeepThought.runReviewing id='{}' spawn failed: {} — "
                            + "treating as SKIPPED, advancing to DRAFTING",
                    process.getId(), e.toString());
            state.setReviewerVerdict("SKIPPED");
            state.setReviewerNotes("Reviewer spawn failed: " + e.getMessage());
            return DeepThoughtStatus.DRAFTING;
        }

        String reply;
        try {
            driveReviewerTurn(child, process.getId(), buildReviewerSteerContent(state));
            reply = readLastAssistantText(
                    process.getTenantId(), process.getSessionId(), child.getId());
        } catch (RuntimeException e) {
            log.warn("DeepThought.runReviewing id='{}' drive failed: {} — "
                            + "treating as SKIPPED",
                    process.getId(), e.toString());
            state.setReviewerVerdict("SKIPPED");
            state.setReviewerNotes("Reviewer drive failed: " + e.getMessage());
            cleanupReviewerChild(child);
            return DeepThoughtStatus.DRAFTING;
        }
        cleanupReviewerChild(child);

        if (reply == null || reply.isBlank()) {
            log.warn("DeepThought.runReviewing id='{}' reviewer produced no "
                            + "reply — treating as SKIPPED",
                    process.getId());
            state.setReviewerVerdict("SKIPPED");
            state.setReviewerNotes("Reviewer produced no reply.");
            return DeepThoughtStatus.DRAFTING;
        }

        // Lenient verdict parsing: first line containing APPROVED or
        // REJECTED (case-insensitive). Default to REJECTED when neither
        // appears — better to loop once with the unparseable reply as
        // critique than silently accept a malformed verdict.
        ReviewerVerdict parsed = parseVerdict(reply);
        state.setReviewerVerdict(parsed.verdict());
        state.setReviewerNotes(reply.trim());
        log.info("DeepThought.runReviewing id='{}' verdict={} reply chars={}",
                process.getId(), parsed.verdict(), reply.length());

        if ("APPROVED".equals(parsed.verdict())) {
            return DeepThoughtStatus.DRAFTING;
        }

        // REJECTED — bump counter and either loop back to FRAMING or
        // bail into FAILED.
        state.setFramingRecoveryCount(state.getFramingRecoveryCount() + 1);
        if (state.getFramingRecoveryCount() >= state.getMaxFramingRecoveries()) {
            state.setFailureReason(
                    "Exceeded maxFramingRecoveries ("
                            + state.getMaxFramingRecoveries()
                            + ") — last reviewer critique: "
                            + abbreviateForReason(reply));
            return DeepThoughtStatus.FAILED;
        }
        return DeepThoughtStatus.FRAMING;
    }

    private String buildReviewerSteerContent(DeepThoughtState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Original goal\n")
                .append(state.getGoal() == null ? "" : state.getGoal())
                .append("\n\n## Plan sketch to review\n")
                .append(state.getPlanSketch() == null
                        ? "(empty)" : state.getPlanSketch())
                .append("\n\n## Your task\n")
                .append("Judge whether this plan is sound. Begin your reply "
                        + "with one of:\n")
                .append("    VERDICT: APPROVED\n")
                .append("    VERDICT: REJECTED\n")
                .append("After the verdict, list concrete concerns or "
                        + "improvements (numbered).\n");
        return sb.toString();
    }

    private void driveReviewerTurn(
            ThinkProcessDocument child, String parentId, String content) {
        SteerMessage.UserChatInput message = new SteerMessage.UserChatInput(
                Instant.now(),
                /*idempotencyKey*/ null,
                "deepthought:" + parentId,
                content);
        try {
            laneScheduler.submit(child.getId(),
                    () -> thinkEngineServiceProvider.getObject().steer(child, message))
                    .get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(
                    "Reviewer interrupted child='" + child.getId() + "'", ie);
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() == null ? ee : ee.getCause();
            throw new RuntimeException(
                    "Reviewer turn failed child='" + child.getId() + "': "
                            + cause.getMessage(), cause);
        }
    }

    private @Nullable String readLastAssistantText(
            String tenantId, String sessionId, String workerProcessId) {
        List<ChatMessageDocument> history = chatMessageService.history(
                tenantId, sessionId, workerProcessId);
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessageDocument m = history.get(i);
            if (m.getRole() == ChatRole.ASSISTANT && m.getContent() != null
                    && !m.getContent().isBlank()) {
                return m.getContent();
            }
        }
        return null;
    }

    private void cleanupReviewerChild(ThinkProcessDocument child) {
        try {
            thinkEngineServiceProvider.getObject().stop(child);
        } catch (RuntimeException e) {
            log.warn("Reviewer cleanup failed child='{}': {}",
                    child.getId(), e.toString());
        }
    }

    /**
     * Resolves the reviewer recipe name. Explicit param wins; otherwise
     * falls back to the {@code <parentRecipe>-reviewer} convention. The
     * actual cascade-lookup happens in {@code recipeResolver.apply}
     * inside REVIEWING — this method just produces the candidate name
     * (or {@code null} when neither source supplies one).
     */
    private static @Nullable String resolveReviewerRecipe(ThinkProcessDocument process) {
        String explicit = paramString(process, REVIEWER_RECIPE_KEY, null);
        if (explicit != null && !explicit.isBlank()) return explicit;
        String parent = process.getRecipeName();
        if (parent == null || parent.isBlank()) return null;
        return parent + "-reviewer";
    }

    private record ReviewerVerdict(String verdict) {}

    private static ReviewerVerdict parseVerdict(String reply) {
        for (String line : reply.split("\\R", 6)) {
            String lower = line.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("approved")) return new ReviewerVerdict("APPROVED");
            if (lower.contains("rejected")) return new ReviewerVerdict("REJECTED");
        }
        return new ReviewerVerdict("REJECTED");
    }

    private static String abbreviateForReason(String s) {
        String trimmed = s.trim();
        return trimmed.length() > 200 ? trimmed.substring(0, 197) + "..." : trimmed;
    }

    /**
     * DRAFTING — one LLM call that produces a {@code ```javascript}
     * fenced body. The system prompt comes from the cascade path
     * {@link #DRAFTING_PROMPT_PATH} (Pebble-rendered with the goal);
     * the user message carries the goal verbatim plus, on a recovery
     * attempt, the previous draft and the validation errors that
     * killed it.
     *
     * <p>Always transitions to VALIDATING. A reply without a parseable
     * fence is stored verbatim as {@code generatedCode} and recorded as
     * a synthetic validation error — the regular recovery loop then
     * kicks in (so a malformed reply costs one recovery slot, same as
     * a syntax error).
     */
    DeepThoughtStatus runDrafting(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            DeepThoughtState state) {
        EngineChatFactory.EngineChatBundle bundle =
                engineChatFactory.forProcess(process, ctx, NAME);
        String modelAlias = bundle.primaryConfig().provider()
                + ":" + bundle.primaryConfig().modelName();

        // System prompt: cascade-resolved + Pebble-rendered with goal
        // and an optional tool inventory. The inventory is the highest
        // quality lever on generated scripts — without it the LLM
        // routinely hallucinates tool names ("file_write" instead of
        // "doc_write_text"); the validator can't catch that because
        // such errors only surface at run time.
        String basePath = paramString(process, "promptDocument", DRAFTING_PROMPT_PATH);
        String systemTpl = enginePromptResolver.resolve(
                process, basePath, DRAFTING_FALLBACK_PROMPT);
        Map<String, Object> ctxMap = new LinkedHashMap<>(
                PromptContextBuilder.forProcess(process, null)
                        .engine(NAME)
                        .build());
        // Resolve script-architect-tagged skills ONCE per turn; both
        // the manual-inventory and the skill-guidance sections key off
        // the same list so a second resolve call would be wasted.
        List<ResolvedSkill> architectSkills = resolveScriptArchitectSkills(process);

        ctxMap.put("goal", state.getGoal() == null ? "" : state.getGoal());
        ctxMap.put("toolInventory", renderToolInventory(process, ctx));
        ctxMap.put("manualInventory", renderManualInventory(process, architectSkills));
        ctxMap.put("skillGuidance", renderSkillGuidance(architectSkills));
        String renderedSystem = promptTemplateRenderer.render(systemTpl, ctxMap);

        // User message: recovery hint first when applicable, then the
        // draft-now instruction. The header banner is large on purpose
        // — easy for the LLM to miss subtle "fix this" text buried mid-
        // prompt, especially after a long system message.
        String userMessage = buildDraftingUserMessage(state);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(renderedSystem == null ? "" : renderedSystem));
        messages.add(UserMessage.from(userMessage));

        long startMs = System.currentTimeMillis();
        ChatRequest request = ChatRequest.builder().messages(messages).build();
        ChatResponse response = bundle.chat().chatModel().chat(request);
        llmCallTracker.record(process, request, response,
                System.currentTimeMillis() - startMs, modelAlias);

        String reply = response.aiMessage() == null
                ? null : response.aiMessage().text();
        String body = extractJsBody(reply == null ? "" : reply);
        if (body == null || body.isBlank()) {
            // No fence — keep the raw reply for inspection and queue a
            // synthetic validation error so VALIDATING accounts the
            // attempt against the recovery budget.
            log.warn("DeepThought.runDrafting id='{}' reply had no parseable "
                            + "```javascript fence (reply chars={})",
                    process.getId(), reply == null ? 0 : reply.length());
            state.setGeneratedCode(reply == null ? "" : reply);
            List<ValidationError> errs = new ArrayList<>();
            errs.add(ValidationError.builder()
                    .sourceName("draft.js")
                    .line(0).column(0)
                    .message("LLM reply contained no ```javascript fenced "
                            + "block — re-emit with proper fences.")
                    .build());
            state.setValidationErrors(errs);
            return DeepThoughtStatus.VALIDATING;
        }

        state.setGeneratedCode(body);
        state.getValidationErrors().clear();
        log.info("DeepThought.runDrafting id='{}' attempt {} drafted {} chars",
                process.getId(), state.getRecoveryCount() + 1, body.length());
        return DeepThoughtStatus.VALIDATING;
    }

    /**
     * VALIDATING — parse-only check via {@link JsValidationService}.
     * On success → DONE (or EXECUTING when {@code executeOnDone}). On
     * failure → increment {@code recoveryCount}; back to DRAFTING with
     * the errors copied into the state, or FAILED once
     * {@code recoveryCount >= maxRecoveries}.
     */
    DeepThoughtStatus runValidating(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            DeepThoughtState state) {
        String code = state.getGeneratedCode();
        JsValidationService.JsValidationResult result =
                jsValidationService.validate(code, "draft.js");
        if (result.ok()) {
            state.getValidationErrors().clear();
            log.info("DeepThought.runValidating id='{}' OK after {} recovery attempt(s)",
                    process.getId(), state.getRecoveryCount());
            return state.isExecuteOnDone()
                    ? DeepThoughtStatus.EXECUTING
                    : DeepThoughtStatus.DONE;
        }

        // Translate JsValidationError → API ValidationError so the
        // state can serialize over the wire.
        List<ValidationError> errors = new ArrayList<>();
        for (JsValidationService.JsValidationError e : result.errors()) {
            errors.add(ValidationError.builder()
                    .sourceName(e.sourceName())
                    .line(e.line())
                    .column(e.column())
                    .message(e.message())
                    .build());
        }
        state.setValidationErrors(errors);
        state.setRecoveryCount(state.getRecoveryCount() + 1);
        log.info("DeepThought.runValidating id='{}' FAIL — attempt {}/{}, "
                        + "errors: {}",
                process.getId(), state.getRecoveryCount(),
                state.getMaxRecoveries(),
                errors.isEmpty() ? "?" : errors.get(0).getMessage());

        if (state.getRecoveryCount() >= state.getMaxRecoveries()) {
            state.setFailureReason("Exceeded maxRecoveries ("
                    + state.getMaxRecoveries()
                    + ") — last error: "
                    + (errors.isEmpty() ? "(none)" : errors.get(0).getMessage()));
            return DeepThoughtStatus.FAILED;
        }
        return DeepThoughtStatus.DRAFTING;
    }

    /**
     * EXECUTING — actually runs the validated script via
     * {@link ScriptExecutor}. Builds a {@link ContextToolsApi} narrowed
     * to {@code scriptAllowedTools} so the script can only call tools
     * the caller explicitly approved (same allow-filter the LLM tool
     * loop uses). Input bindings come from
     * {@code engineParams.scriptArgs}.
     *
     * <p>On success the return value (mapped to a JSON-friendly Java
     * object — primitives stay primitives, JS objects become Maps, JS
     * arrays become Lists) and the wall-clock duration are stored on
     * state, then DONE. On {@link ScriptExecutionException} the
     * error message + class are recorded and the engine transitions
     * to FAILED — runtime errors are <em>not</em> fed back into the
     * recovery loop (DRAFTING corrects syntax, not runtime semantics,
     * and a loop on runtime errors burns LLM tokens for an unknowable
     * outcome).
     */
    DeepThoughtStatus runExecuting(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            DeepThoughtState state) {
        String code = state.getGeneratedCode();
        if (code == null || code.isBlank()) {
            state.setFailureReason(
                    "EXECUTING entered with empty generatedCode — "
                            + "DRAFTING/VALIDATING must run first");
            return DeepThoughtStatus.FAILED;
        }

        ToolInvocationContext scope = new ToolInvocationContext(
                process.getTenantId(),
                process.getProjectId(),
                process.getSessionId(),
                process.getId(),
                /*userId*/ null);
        Set<String> scriptTools = new java.util.LinkedHashSet<>(
                scriptAllowedTools(process));
        ContextToolsApi tools = new ContextToolsApi(toolDispatcher, scope, scriptTools);

        Map<String, @Nullable Object> bindings = scriptArgsBindings(process);
        Duration timeout = executionTimeout(process);

        try {
            ScriptResult result = scriptExecutor.run(
                    new ScriptRequest(
                            "js", code, "deepthought:" + process.getId(),
                            tools, timeout, bindings));
            state.setExecutionResult(result.value());
            state.setExecutionDurationMs(result.duration().toMillis());
            state.setExecutionError(null);
            state.setExecutionErrorClass(null);
            log.info("DeepThought.runExecuting id='{}' OK — duration={}ms, "
                            + "valueClass={}",
                    process.getId(), result.duration().toMillis(),
                    result.value() == null ? "null"
                            : result.value().getClass().getSimpleName());
            return DeepThoughtStatus.DONE;
        } catch (ScriptExecutionException e) {
            state.setExecutionError(e.getMessage());
            state.setExecutionErrorClass(e.errorClass().name());
            state.setExecutionResult(null);
            // Use the failureReason field too so terminal-state
            // summarizeForParent picks it up uniformly.
            state.setFailureReason("Script execution failed ("
                    + e.errorClass().name() + "): " + e.getMessage());
            log.warn("DeepThought.runExecuting id='{}' FAIL class={} msg={}",
                    process.getId(), e.errorClass(), e.getMessage());
            return DeepThoughtStatus.FAILED;
        }
    }

    // ──────────────────── Phase helpers ────────────────────

    private static String buildDraftingUserMessage(DeepThoughtState state) {
        StringBuilder sb = new StringBuilder();
        // Approved plan sketch (when FRAMING ran) comes first — gives
        // the DRAFTING LLM the structural anchor. On a DRAFTING
        // recovery the previous-draft block sits below this so the
        // plan stays the dominant context.
        if (state.getPlanSketch() != null && !state.getPlanSketch().isBlank()) {
            sb.append("## Approved plan sketch\n\n")
                    .append(state.getPlanSketch())
                    .append("\n\n");
            if (state.getReviewerNotes() != null
                    && !state.getReviewerNotes().isBlank()
                    && "APPROVED".equals(state.getReviewerVerdict())) {
                sb.append("## Reviewer notes (incorporate these)\n\n")
                        .append(state.getReviewerNotes())
                        .append("\n\n");
            }
        }
        if (state.getRecoveryCount() > 0 && !state.getValidationErrors().isEmpty()) {
            sb.append("================================================\n");
            sb.append("⚠  PREVIOUS DRAFT FAILED VALIDATION ⚠\n");
            sb.append("================================================\n\n");
            if (state.getGeneratedCode() != null && !state.getGeneratedCode().isBlank()) {
                sb.append("Previous draft:\n```javascript\n")
                        .append(state.getGeneratedCode())
                        .append("\n```\n\n");
            }
            sb.append("Errors that must be fixed:\n");
            for (ValidationError e : state.getValidationErrors()) {
                sb.append("  - ");
                if (e.getLine() > 0 || e.getColumn() > 0) {
                    sb.append("line ").append(e.getLine())
                            .append(", col ").append(e.getColumn())
                            .append(": ");
                }
                sb.append(e.getMessage() == null ? "(no message)" : e.getMessage())
                        .append('\n');
            }
            sb.append("\nRe-emit the COMPLETE corrected script. Keep the "
                    + "structure of the previous draft; only fix the listed "
                    + "errors.\n");
            sb.append("================================================\n\n");
        }
        sb.append("Goal:\n").append(state.getGoal() == null ? "" : state.getGoal());
        sb.append("\n\nReply ONLY with a single ```javascript fenced block. "
                + "No prose before or after.");
        return sb.toString();
    }

    /**
     * Pulls the first fenced JavaScript body out of an LLM reply.
     * Accepts ```javascript, ```js, or unmarked ``` fences (the prompt
     * asks for ```javascript but production LLMs sometimes pick the
     * shorter alias). Returns {@code null} when no fence is found —
     * caller treats that as a validation error.
     */
    static @Nullable String extractJsBody(@Nullable String reply) {
        if (reply == null || reply.isBlank()) return null;
        Matcher m = JS_FENCE.matcher(reply);
        if (!m.find()) return null;
        String body = m.group(1);
        return body == null || body.isBlank() ? null : body;
    }

    private static @Nullable String paramString(
            ThinkProcessDocument process, String key, @Nullable String fallback) {
        Map<String, Object> p = process.getEngineParams();
        Object v = p == null ? null : p.get(key);
        return v instanceof String s && !s.isBlank() ? s : fallback;
    }

    /**
     * Renders the {@code scriptAllowedTools} list as a Markdown
     * inventory the DRAFTING prompt embeds. Returns an empty string
     * when no tools are configured — the Pebble template uses
     * {@code {% if toolInventory %}…{% endif %}} so the section is
     * silently omitted in that case.
     *
     * <p>Format per entry: {@code - **name** — description.
     * Args: {required}, {optional}}. The full JSON-schema would be
     * too verbose for a single prompt; the LLM gets enough to pick
     * the right tool, then can re-check params at validate-time.
     */
    private String renderToolInventory(
            ThinkProcessDocument process, ThinkEngineContext ctx) {
        List<String> wanted = scriptAllowedTools(process);
        if (wanted.isEmpty()) return "";

        ToolInvocationContext scope = new ToolInvocationContext(
                process.getTenantId(),
                process.getProjectId(),
                process.getSessionId(),
                process.getId(),
                /*userId*/ null);

        StringBuilder sb = new StringBuilder();
        for (String name : wanted) {
            ToolDispatcher.Resolved resolved =
                    toolDispatcher.resolve(name, scope).orElse(null);
            if (resolved == null) {
                // Unknown tool name in scriptAllowedTools — render a
                // placeholder so the LLM at least sees the name; the
                // recovery loop can't help here (runtime issue), so we
                // leave detection to the caller's pre-check.
                sb.append("- **").append(name).append("** — ")
                        .append("(tool not registered in this scope)\n");
                continue;
            }
            Tool tool = resolved.tool();
            sb.append("- **").append(tool.name()).append("** — ")
                    .append(oneLine(tool.description())).append("\n");
            String args = describeParams(tool.paramsSchema());
            if (!args.isEmpty()) {
                sb.append("  ").append(args).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Builds the {@code args} top-level binding for the executed
     * script. Reads the caller-supplied
     * {@code engineParams.scriptArgs} map; non-map / missing values
     * yield an empty binding (the script sees {@code args = {}}).
     * The {@code "vance"} key is reserved by the host API and is
     * stripped if present.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, @Nullable Object> scriptArgsBindings(
            ThinkProcessDocument process) {
        Map<String, Object> p = process.getEngineParams();
        Object raw = p == null ? null : p.get(SCRIPT_ARGS_KEY);
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of("args", new LinkedHashMap<String, Object>());
        }
        Map<String, Object> args = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getKey() instanceof String key && !"vance".equals(key)) {
                args.put(key, e.getValue());
            }
        }
        return Map.of("args", args);
    }

    /**
     * Resolves the EXECUTING-phase timeout. Order:
     * {@code engineParams.executionTimeoutSeconds} (number-of-seconds
     * or duration string) → 5 minutes as last-resort default. The
     * per-script JSDoc {@code @timeout} header takes precedence over
     * this when the script is parsed by the executor — this is just
     * the wall-clock cap when the script omits its own.
     */
    private static Duration executionTimeout(ThinkProcessDocument process) {
        Map<String, Object> p = process.getEngineParams();
        Object raw = p == null ? null : p.get(EXECUTION_TIMEOUT_KEY);
        if (raw instanceof Number n && n.longValue() > 0) {
            return Duration.ofSeconds(n.longValue());
        }
        if (raw instanceof String s && !s.isBlank()) {
            try {
                // Allow "30s", "2m", "1h" as well as plain seconds.
                if (s.matches("\\d+")) {
                    return Duration.ofSeconds(Long.parseLong(s.trim()));
                }
                return Duration.parse("PT" + s.trim().toUpperCase());
            } catch (RuntimeException e) {
                // Fall through to default — bad input shouldn't kill
                // the run.
            }
        }
        return Duration.ofMinutes(5);
    }

    /**
     * Renders the configured manual folders as a Markdown listing the
     * DRAFTING prompt embeds. Each {@code manualPaths} folder is
     * resolved through the document cascade (project → {@code _vance} →
     * classpath:vance-defaults); resulting {@code .md} documents are
     * listed by name + folder + source.
     *
     * <p>Content is <em>not</em> inlined — manuals can run to tens of
     * KB and the LLM picks what it needs from the names. Names are
     * stable identifiers like {@code "data-export"} or {@code "tool-conventions"};
     * the goal text can reference them and the LLM has the catalogue.
     *
     * <p>Returns an empty string when no folders are configured or no
     * manuals exist under them. The Pebble template gates the section
     * with {@code {% if manualInventory %}…{% endif %}}.
     *
     * <p>Skill-supplied paths join in via the {@code architectSkills}
     * list (resolved once per turn upstream). Recipe paths take
     * precedence — same first-wins rule as
     * {@link de.mhus.vance.brain.tools.manual.ManualListTool}.
     */
    private String renderManualInventory(
            ThinkProcessDocument process, List<ResolvedSkill> architectSkills) {
        java.util.LinkedHashSet<String> folders =
                new java.util.LinkedHashSet<>(manualPaths(process));
        for (ResolvedSkill skill : architectSkills) {
            if (skill.manualPaths() == null) continue;
            for (String p : skill.manualPaths()) {
                if (p == null || p.isBlank()) continue;
                String norm = p.trim();
                if (!norm.endsWith("/")) norm = norm + "/";
                folders.add(norm);
            }
        }
        if (folders.isEmpty()) return "";
        if (process.getTenantId() == null || process.getTenantId().isBlank()) {
            return "";
        }

        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        StringBuilder sb = new StringBuilder();
        for (String folder : folders) {
            Map<String, LookupResult> hits = documentService.listByPrefixCascade(
                    process.getTenantId(), process.getProjectId(), folder);
            // Sort within a folder so output is deterministic across
            // backends — listByPrefixCascade returns a Map, order
            // depends on impl.
            List<String> paths = new ArrayList<>(hits.keySet());
            paths.sort(String::compareTo);
            for (String path : paths) {
                if (!path.endsWith(".md")) continue;
                String filename = path.substring(folder.length());
                String stem = filename.substring(
                        0, filename.length() - ".md".length());
                if (stem.isBlank()) continue;
                if (!seen.add(stem)) continue;
                LookupResult result = hits.get(path);
                sb.append("- **").append(stem).append("** ")
                        .append("(folder: ").append(folder)
                        .append(", source: ")
                        .append(result.source().name().toLowerCase())
                        .append(")\n");
            }
        }
        return sb.toString();
    }

    private static List<String> manualPaths(ThinkProcessDocument process) {
        Map<String, Object> p = process.getEngineParams();
        Object raw = p == null ? null : p.get(MANUAL_PATHS_KEY);
        if (!(raw instanceof List<?> list) || list.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (Object entry : list) {
            if (!(entry instanceof String s) || s.isBlank()) continue;
            String norm = s.trim();
            if (!norm.endsWith("/")) norm = norm + "/";
            out.add(norm);
        }
        return out;
    }

    /**
     * Resolves the process's active skills, filtered to those carrying
     * the {@link #SCRIPT_ARCHITECT_TAG} tag. Engine-driven push: no
     * trigger matching (DRAFTING has no user-input string to match
     * against), only skills that landed on the process via recipe
     * binding ({@code fromRecipe=true}) or out-of-band activation
     * ({@code ProcessSkillHandler}) reach this list.
     *
     * <p>Unknown skill references (skill renamed / removed since
     * activation) are logged + skipped — matches Ford's "warn rather
     * than fail the turn" behaviour.
     */
    private List<ResolvedSkill> resolveScriptArchitectSkills(
            ThinkProcessDocument process) {
        List<ActiveSkillRefEmbedded> active = process.getActiveSkills();
        if (active == null || active.isEmpty()) return List.of();
        SkillScopeContext scope = scopeFor(process);
        List<ResolvedSkill> out = new ArrayList<>();
        for (ActiveSkillRefEmbedded ref : active) {
            if (ref == null || ref.getName() == null) continue;
            try {
                java.util.Optional<ResolvedSkill> resolved =
                        skillResolver.resolve(scope, ref.getName());
                if (resolved.isEmpty()) {
                    log.warn("DeepThought id='{}' active skill '{}' no longer "
                                    + "resolves — skipping",
                            process.getId(), ref.getName());
                    continue;
                }
                ResolvedSkill skill = resolved.get();
                if (skill.tags() != null
                        && skill.tags().contains(SCRIPT_ARCHITECT_TAG)) {
                    out.add(skill);
                }
            } catch (UnknownSkillException e) {
                log.warn("DeepThought id='{}' active skill '{}' unknown — skipping",
                        process.getId(), ref.getName());
            }
        }
        return out;
    }

    private SkillScopeContext scopeFor(ThinkProcessDocument process) {
        SessionDocument session = process.getSessionId() == null
                ? null
                : sessionService.findBySessionId(process.getSessionId()).orElse(null);
        String userId = session != null && session.getUserId() != null
                && !session.getUserId().isBlank() ? session.getUserId() : null;
        String projectId = session != null && session.getProjectId() != null
                && !session.getProjectId().isBlank() ? session.getProjectId() : null;
        return SkillScopeContext.of(process.getTenantId(), userId, projectId);
    }

    /**
     * Builds the Markdown body for the prompt's
     * {@code {{ skillGuidance }}} variable. Concatenates each matching
     * skill's {@code promptExtension} plus its INLINE
     * {@code referenceDocs} under a header naming the skill. Same
     * "INLINE only, ON_DEMAND treated as INLINE" rule
     * {@link de.mhus.vance.brain.skill.SkillPromptComposer} uses in
     * v1, so Deep Thought-side and Ford-side rendering stay aligned.
     *
     * <p>Returns an empty string when no skills match; the Pebble
     * template gates the section with {@code {% if skillGuidance %}…{% endif %}}.
     */
    private String renderSkillGuidance(List<ResolvedSkill> architectSkills) {
        if (architectSkills.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (ResolvedSkill skill : architectSkills) {
            String prompt = skill.promptExtension();
            boolean hasPrompt = prompt != null && !prompt.isBlank();
            boolean hasDocs = skill.referenceDocs() != null
                    && !skill.referenceDocs().isEmpty();
            if (!hasPrompt && !hasDocs) continue;
            sb.append("### ").append(skill.title() == null
                    ? skill.name() : skill.title()).append('\n');
            if (hasPrompt) {
                sb.append(prompt.trim()).append('\n');
            }
            if (hasDocs) {
                for (ResolvedSkill.ReferenceDoc doc : skill.referenceDocs()) {
                    // ON_DEMAND treated as INLINE in v1, same as
                    // SkillPromptComposer (planning/deepthought-engine.md
                    // §"Prompt context enrichment").
                    if (doc.loadMode() == SkillReferenceDocLoadMode.INLINE
                            || doc.loadMode() == SkillReferenceDocLoadMode.ON_DEMAND) {
                        sb.append("\n#### ").append(doc.title()).append('\n');
                        if (doc.content() != null && !doc.content().isBlank()) {
                            sb.append(doc.content().trim()).append('\n');
                        }
                    }
                }
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private static List<String> scriptAllowedTools(ThinkProcessDocument process) {
        Map<String, Object> p = process.getEngineParams();
        Object raw = p == null ? null : p.get(SCRIPT_ALLOWED_TOOLS_KEY);
        if (!(raw instanceof List<?> list) || list.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (Object entry : list) {
            if (entry instanceof String s && !s.isBlank()) out.add(s.trim());
        }
        return out;
    }

    /**
     * Compact "Args: ..." line built from the JSON-Schema-like
     * {@code paramsSchema}. We separate required and optional so the
     * LLM picks up the mandatory bits at a glance. Schema-less tools
     * (raw map without {@code properties}) yield an empty string —
     * the description alone must carry the contract.
     */
    @SuppressWarnings("unchecked")
    private static String describeParams(@Nullable Map<String, Object> schema) {
        if (schema == null) return "";
        Object propsRaw = schema.get("properties");
        if (!(propsRaw instanceof Map<?, ?> props) || props.isEmpty()) return "";
        Object reqRaw = schema.get("required");
        List<String> required = reqRaw instanceof List<?> rl
                ? rl.stream().filter(o -> o instanceof String)
                    .map(o -> (String) o).toList()
                : List.of();
        // Preserve the schema-declared order for required: that's the
        // author's intent and how docs typically read. Optional then
        // gets whatever's left in props order.
        List<String> requiredOut = new ArrayList<>(required);
        List<String> optionalOut = new ArrayList<>();
        for (Map.Entry<?, ?> e : props.entrySet()) {
            if (!(e.getKey() instanceof String key)) continue;
            if (required.contains(key)) continue;
            optionalOut.add(key);
        }
        StringBuilder sb = new StringBuilder();
        if (!requiredOut.isEmpty()) {
            sb.append("Required: ").append(String.join(", ", requiredOut)).append(".");
        }
        if (!optionalOut.isEmpty()) {
            if (sb.length() > 0) sb.append(" ");
            sb.append("Optional: ").append(String.join(", ", optionalOut)).append(".");
        }
        return sb.toString();
    }

    /**
     * Renders the script's return value for the parent's chat
     * surface. Strings come back as-is (most natural readout for a
     * "give me the answer" script); structured values get a fenced
     * JSON block so the parent's LLM can pick them apart. Used by
     * {@link #summarizeForParent} on the execute-on-done success path.
     */
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

    private static String oneLine(@Nullable String raw) {
        if (raw == null) return "";
        String s = raw.replaceAll("\\s+", " ").trim();
        return s.length() > 200 ? s.substring(0, 197) + "..." : s;
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

        if (state.getStatus() == DeepThoughtStatus.DONE
                && state.getGeneratedCode() != null) {
            // Two flavours of DONE: execute-on-done returns the
            // script's value as the headline (the parent typically
            // cares about the result, not the code); plain DONE
            // returns the code itself so the parent can pipe it
            // wherever. generatedCode is in engineParams either way
            // for direct Mongo inspection.
            if (state.isExecuteOnDone()) {
                payload.put("executionResult", state.getExecutionResult());
                String valueRendered = renderExecutionValue(state.getExecutionResult());
                return new ParentReport(
                        "Deep Thought executed the generated script ("
                                + state.getGeneratedCode().length() + " chars, "
                                + state.getExecutionDurationMs() + "ms). Return value:\n\n"
                                + valueRendered,
                        payload);
            }
            return new ParentReport(
                    "Deep Thought drafted a script (" + state.getGeneratedCode().length()
                            + " chars, " + state.getRecoveryCount()
                            + " recovery attempt(s)):\n\n```javascript\n"
                            + state.getGeneratedCode()
                            + "\n```\n",
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

        boolean executeOnDone = parseBoolean(p.get(EXECUTE_ON_DONE_KEY), false);
        int maxRecoveries = parseInt(p.get(MAX_RECOVERIES_KEY), 5);
        if (maxRecoveries < 0) maxRecoveries = 0;
        boolean framingEnabled = parseBoolean(p.get(FRAMING_ENABLED_KEY), false);
        int maxFramingRecoveries = parseInt(p.get(MAX_FRAMING_RECOVERIES_KEY), 3);
        if (maxFramingRecoveries < 0) maxFramingRecoveries = 0;

        return DeepThoughtState.builder()
                .goal(goal)
                .executeOnDone(executeOnDone)
                .maxRecoveries(maxRecoveries)
                .framingEnabled(framingEnabled)
                .maxFramingRecoveries(maxFramingRecoveries)
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
