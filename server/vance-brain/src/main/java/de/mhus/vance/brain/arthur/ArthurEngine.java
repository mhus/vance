package de.mhus.vance.brain.arthur;

import de.mhus.vance.api.chat.ChatMessageChunkData;
import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiChatException;
import de.mhus.vance.brain.ai.AiChatOptions;
import de.mhus.vance.brain.ai.AiModelResolver;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.ai.ModelInfo;
import de.mhus.vance.brain.ai.ModelSize;
import de.mhus.vance.brain.ai.VanceSystemMessage;
import de.mhus.vance.brain.events.ChunkBatcher;
import de.mhus.vance.brain.events.ClientEventPublisher;
import de.mhus.vance.brain.events.StreamingProperties;
import de.mhus.vance.brain.progress.LlmCallTracker;
import de.mhus.vance.brain.recipe.RecipeLoader;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.tools.context.RespondTool;
import de.mhus.vance.brain.thinkengine.SystemPrompts;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Arthur — the reactive session-chat engine and reference
 * implementation of the orchestrator pattern. Listens, delegates,
 * synthesises. See {@code specification/arthur-engine.md}.
 *
 * <p><b>Reactive.</b> Arthur never reaches {@code DONE}. Each turn
 * starts when something hits the inbox (user input, sibling
 * {@code ProcessEvent}, async {@code ToolResult}) and ends with the
 * engine returning to {@code READY} or {@code BLOCKED}.
 *
 * <p><b>Drain-once-per-turn.</b> Arthur overrides
 * {@link #runTurn} so the entire inbox is folded into a single LLM
 * round-trip — five queued events are one LLM call, not five.
 *
 * <p><b>Tool pool (restricted).</b> Arthur sees only process control
 * + bundled docs. Filesystem, shell, web, MCP — those belong to
 * workers spawned via {@code process_create}. {@link #allowedTools}
 * publishes the set; the runtime enforces it.
 */
@Component
@Slf4j
public class ArthurEngine extends de.mhus.vance.brain.thinkengine.action.StructuredActionEngine {

    public static final String NAME = "arthur";
    public static final String VERSION = "0.1.0";

    public static final String GREETING =
            "Hi, I'm Arthur. What are we working on?";

    /**
     * Bare-minimum fallback when no recipe override is in play —
     * basically never used in production because the bundled
     * {@code arthur} recipe always supplies the real prompt. Kept
     * tiny on purpose so a misconfigured spawn still produces a
     * coherent (if generic) bot rather than an unprompted LLM.
     */
    private static final String ENGINE_FALLBACK_PROMPT =
            "You are Arthur, the chat agent of a Vance session. "
                    + "Delegate operational work to workers via process_create; "
                    + "synthesise their replies for the user.";

    /**
     * Cached final system prompt (engine-default base + bundled-recipe
     * catalog). Composed once after first turn since bundled-recipe
     * state doesn't change without a brain restart.
     */

    /**
     * Tools the runtime may dispatch in Arthur's scope. Per-mode
     * filtering happens via the recipe-driven cascade (see
     * {@code planning/tool-schema-deferral.md} §14) — Arthur's
     * default recipe pins {@code allowedToolsRemove: ["@write",
     * "@executive", "@side-effect", respond]} for {@code EXPLORING}
     * and {@code PLANNING} so write/orchestration tools physically
     * leave the allow-set there.
     *
     * <p>The list mixes plain read tools (recipe_list, doc_read, …),
     * orchestration tools called via Arthur's structured-action
     * dispatch (process_create, process_steer, …), and client-pushed
     * Foot tools (client_file_*, client_exec_*). The system prompt
     * tells the LLM which path to use when several tools cover the
     * same need (e.g. "DELEGATE action, not direct process_create").
     */
    private static final Set<String> ALLOWED_TOOLS = Set.of(
            "whoami",
            "current_time",
            "find_tools",
            "describe_tool",
            "process_create",
            "process_create_delegate",
            "process_steer",
            "process_stop",
            "process_pause",
            "process_resume",
            "process_list",
            "process_status",
            "recipe_list",
            "recipe_describe",
            "manual_list",
            "manual_read",
            "inbox_post",
            "project_list",
            "project_current",
            "doc_read",
            "doc_list",
            "doc_find",
            "scratchpad_get",
            "scratchpad_list",
            "data_get",
            "web_search",
            "web_fetch",
            "cross_doc_list_projects",
            "relations_find",
            "rag_list",
            "kit_status",
            "exec_status",
            // Client-pushed tools (registered by Foot on session-bind via
            // ClientToolRegistry). Without these in the allow-set Arthur
            // cannot inspect or modify the user's workspace at all.
            "client_file_read",
            "client_file_list",
            "client_file_edit",
            "client_file_write",
            "client_exec_run",
            "client_exec_status",
            "client_exec_kill",
            "client_javascript");

    /**
     * Plan-Mode transition actions whose handlers re-set the same
     * field they target — applying them in the "wrong" mode is a
     * harmless no-op or redundant write, never destructive. The
     * action gate lets these through with a log line instead of
     * bouncing back to the LLM with a re-prompt error, which would
     * otherwise produce action loops when the model double-emits
     * (typically START_EXECUTION right after entering EXECUTING).
     */
    private static final Set<String> PLAN_MODE_IDEMPOTENT_ACTIONS = Set.of(
            ArthurActionSchema.TYPE_START_PLAN,
            ArthurActionSchema.TYPE_PROPOSE_PLAN,
            ArthurActionSchema.TYPE_START_EXECUTION,
            ArthurActionSchema.TYPE_TODO_UPDATE);

    /**
     * Plan-mode actions that mutate state but don't end the turn —
     * the LLM should chain real work (read/write tools) immediately
     * after. The structured action loop applies them in place and
     * feeds the outcome back as a tool-result, instead of returning
     * and letting the next turn rebuild the prompt from scratch
     * (which causes the LLM-amnesia loop where the model repeats
     * the same TODO_UPDATE forever because it has no record of
     * having emitted it).
     *
     * <p>Only TODO_UPDATE qualifies. The mode-transition actions
     * (START_PLAN, START_EXECUTION) are intentionally terminal even
     * though they're idempotent: the per-turn LLM tool-spec is
     * computed at turn start from the mode then, so a continuing
     * mode switch leaves the LLM staring at the OLD mode's tool
     * manifest for the rest of the turn. Returning instead lets the
     * outer self-continuation rebuild the next turn with the new
     * mode's tools (e.g. {@code client_*} after START_PLAN). The
     * mode change is visible to the LLM via the system prompt on
     * that next turn — no in-turn memory needed.
     *
     * <p>{@link ArthurActionSchema#TYPE_PROPOSE_PLAN} stays terminal:
     * it ends the turn with an ASSISTANT message and BLOCKED status
     * waiting for user approval — the chain pauses there.
     */
    private static final Set<String> CONTINUING_ACTIONS = Set.of(
            ArthurActionSchema.TYPE_TODO_UPDATE);

    private static final String SETTING_PROVIDER_API_KEY_FMT = "ai.provider.%s.apiKey";

    // ──────────────────── End-of-turn marker ────────────────────
    // Arthur drives every turn through a single structured action
    // ({@code arthur_action}). See {@link ArthurActionSchema} for the
    // vocabulary and {@link de.mhus.vance.brain.thinkengine.action.StructuredActionEngine}
    // for the loop implementation. The legacy regex-based
    // intent-validator + free-text format-correction sub-loop are
    // gone — the structured action's JSON validation subsumes both.

    /**
     * Base cascade path for the Arthur engine prompt. Loaded via
     * {@link de.mhus.vance.brain.thinkengine.EnginePromptResolver#resolveTiered};
     * SMALL models automatically pick up
     * {@code prompts/arthur-prompt-small.md} when one exists, otherwise
     * fall through to this base path. Tenants override either variant by
     * placing matching files in their {@code _vance} (or per-user) project.
     * Recipes can swap the paths via {@code promptDocument} /
     * {@code promptDocumentSmall} params.
     */
    private static final String DEFAULT_PROMPT_PATH = "prompts/arthur-prompt.md";

    private final ThinkProcessService thinkProcessService;
    private final ArthurProperties arthurProperties;
    private final RecipeLoader recipeLoader;
    private final ModelCatalog modelCatalog;
    private final de.mhus.vance.brain.memory.MemoryContextLoader memoryContextLoader;
    private final de.mhus.vance.brain.thinkengine.EnginePromptResolver enginePromptResolver;
    private final de.mhus.vance.brain.prompt.PromptTemplateRenderer promptTemplateRenderer;
    private final de.mhus.vance.brain.ai.EngineChatFactory engineChatFactory;
    private final de.mhus.vance.brain.skill.SkillTriggerMatcher skillTriggerMatcher;
    private final de.mhus.vance.brain.enginemessage.EngineMessageRouter messageRouter;
    private final PlanModeEventEmitter planModeEventEmitter;
    private final de.mhus.vance.brain.ai.attachment.AttachmentResolver attachmentResolver;

    public ArthurEngine(
            ThinkProcessService thinkProcessService,
            ObjectMapper objectMapper,
            StreamingProperties streamingProperties,
            ArthurProperties arthurProperties,
            RecipeLoader recipeLoader,
            ModelCatalog modelCatalog,
            LlmCallTracker llmCallTracker,
            de.mhus.vance.brain.memory.MemoryContextLoader memoryContextLoader,
            de.mhus.vance.brain.thinkengine.EnginePromptResolver enginePromptResolver,
            de.mhus.vance.brain.ai.EngineChatFactory engineChatFactory,
            de.mhus.vance.brain.skill.SkillTriggerMatcher skillTriggerMatcher,
            de.mhus.vance.brain.enginemessage.EngineMessageRouter messageRouter,
            PlanModeEventEmitter planModeEventEmitter,
            de.mhus.vance.brain.ai.attachment.AttachmentResolver attachmentResolver,
            de.mhus.vance.brain.prompt.PromptTemplateRenderer promptTemplateRenderer) {
        super(streamingProperties, llmCallTracker, objectMapper);
        this.thinkProcessService = thinkProcessService;
        this.arthurProperties = arthurProperties;
        this.recipeLoader = recipeLoader;
        this.modelCatalog = modelCatalog;
        this.memoryContextLoader = memoryContextLoader;
        this.enginePromptResolver = enginePromptResolver;
        this.engineChatFactory = engineChatFactory;
        this.skillTriggerMatcher = skillTriggerMatcher;
        this.messageRouter = messageRouter;
        this.planModeEventEmitter = planModeEventEmitter;
        this.attachmentResolver = attachmentResolver;
        this.promptTemplateRenderer = promptTemplateRenderer;
    }

    // ──────────────────── Metadata ────────────────────

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "Arthur (Session Chat)";
    }

    @Override
    public String description() {
        return "Reactive session-chat agent. Talks to the user, "
                + "delegates deep work to worker processes, "
                + "synthesises their results back into the chat.";
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public Set<String> allowedTools() {
        return ALLOWED_TOOLS;
    }

    // ──────────────────── Lifecycle ────────────────────

    @Override
    public void start(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.info("Arthur.start tenant='{}' session='{}' id='{}'",
                process.getTenantId(), process.getSessionId(), process.getId());
        ctx.chatMessageService().append(ChatMessageDocument.builder()
                .tenantId(process.getTenantId())
                .sessionId(process.getSessionId())
                .thinkProcessId(process.getId())
                .role(ChatRole.ASSISTANT)
                .content(GREETING)
                .build());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
    }

    @Override
    public void resume(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.debug("Arthur.resume id='{}'", process.getId());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
    }

    @Override
    public void suspend(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.debug("Arthur.suspend id='{}'", process.getId());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.SUSPENDED);
    }

    @Override
    public void stop(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.info("Arthur.stop id='{}'", process.getId());
        thinkProcessService.closeProcess(process.getId(), CloseReason.STOPPED);
    }

    /**
     * Single-message entry — used when the runtime delivers a
     * message via the per-message default. Arthur prefers
     * {@link #runTurn} (batch), but if something does call
     * {@code steer} directly we treat it as a one-element inbox.
     */
    @Override
    public void steer(ThinkProcessDocument process, ThinkEngineContext ctx, SteerMessage message) {
        runTurnFor(process, ctx, List.of(message));
    }

    /**
     * Drains the inbox and processes everything in one LLM
     * round-trip. Auto-Wakeup: keep re-draining until the queue
     * stays empty across a full pass — covers messages that arrive
     * during the LLM call.
     */
    @Override
    public void runTurn(ThinkProcessDocument process, ThinkEngineContext ctx) {
        // Plan-Mode self-continuation: while in EXPLORING / PLANNING /
        // EXECUTING, keep driving the engine without waiting for user
        // input — actions like START_PLAN, START_EXECUTION, TODO_UPDATE
        // do not block on the user, so an IDLE status here means
        // "Arthur wants to keep working". We trigger a continuation
        // pass with empty inbox and let the next LLM round-trip emit
        // the next action.
        //
        // Mode NORMAL is the standard chat baseline — IDLE there
        // genuinely means "waiting for user input", no self-continuation.
        //
        // Budget bounds runaway action loops (model emitting the same
        // idempotent transition repeatedly). At budget exhaustion we
        // explicitly transition the process to BLOCKED so the user
        // sees the engine paused and can intervene, instead of leaving
        // it silently IDLE.
        //
        // Tightened from 25 → 8 once continuing actions chain inside
        // a single turn (see {@link StructuredActionEngine#applyContinuingAction}).
        // With in-turn chaining, each turn does multiple LLM iterations
        // and normally produces a chat-producing terminal action; 8
        // outer turns is plenty even for the slowest plan execution.
        //
        // The silent-turn-in-a-row guard is the sharper circuit
        // breaker: if 3 turns in a row produce no chat message
        // (TODO_UPDATE-only loops typically), abort early instead
        // of waiting out the full budget. The model is stuck.
        final int continuationBudget = 8;
        final int silentTurnsLimit = 3;
        int continuationsRemaining = continuationBudget;
        int silentTurnsInARow = 0;
        boolean continueWithEmptyInbox = false;
        while (true) {
            // Cooperative halt-check between drain iterations.
            if (thinkProcessService.isHaltRequested(process.getId())) {
                log.info("Arthur.runTurn id='{}' — halt requested, yielding",
                        process.getId());
                return;
            }
            List<SteerMessage> drained = ctx.drainPending();
            if (drained.isEmpty()) {
                if (!continueWithEmptyInbox) {
                    return;
                }
                continueWithEmptyInbox = false;
                continuationsRemaining--;
                if (continuationsRemaining < 0) {
                    log.warn("Arthur.runTurn id='{}' — continuation budget "
                            + "({}) exhausted; transitioning to BLOCKED so the "
                            + "user can intervene",
                            process.getId(), continuationBudget);
                    thinkProcessService.updateStatus(
                            process.getId(), ThinkProcessStatus.BLOCKED);
                    return;
                }
            }

            de.mhus.vance.api.thinkprocess.ProcessMode modeBefore = process.getMode();
            TurnSignal signal = runTurnFor(process, ctx, drained);
            // "Made progress" = chat appended OR tools dispatched. The
            // silent-loop guard only fires when neither happened — that
            // means the LLM is genuinely stuck (no output at all). A
            // turn that wrote files but ran out of action-loop iters
            // before reaching a terminal action counts as progress and
            // the outer loop continues with a fresh per-turn budget.
            if (signal.madeProgress()) {
                silentTurnsInARow = 0;
            } else {
                silentTurnsInARow++;
                if (silentTurnsInARow >= silentTurnsLimit) {
                    log.warn("Arthur.runTurn id='{}' — {} silent turns in a row "
                            + "(LLM stuck — no chat, no tool calls); transitioning "
                            + "to BLOCKED so the user can intervene",
                            process.getId(), silentTurnsLimit);
                    thinkProcessService.updateStatus(
                            process.getId(), ThinkProcessStatus.BLOCKED);
                    return;
                }
            }

            // Decide whether to keep going. The handlers update
            // process.mode in-place — read the post-turn status from
            // Mongo (handler set it via updateStatus inside runTurnFor).
            ThinkProcessStatus currentStatus = thinkProcessService
                    .findById(process.getId())
                    .map(ThinkProcessDocument::getStatus)
                    .orElse(ThinkProcessStatus.SUSPENDED);
            de.mhus.vance.api.thinkprocess.ProcessMode currentMode = process.getMode();
            boolean activeMode = currentMode != null
                    && currentMode != de.mhus.vance.api.thinkprocess.ProcessMode.NORMAL;
            // Continue if (a) mode changed (entered new plan-mode phase),
            // OR (b) we're in any active plan-mode and the engine isn't
            // waiting on user input (status=IDLE means "the action handler
            // wants the engine to keep running"). PROPOSE_PLAN / ANSWER /
            // ASK_USER set awaiting=true → BLOCKED → no continuation,
            // user's next message reactivates via the regular pending
            // pipeline.
            if (currentStatus == ThinkProcessStatus.IDLE
                    && (currentMode != modeBefore || activeMode)) {
                continueWithEmptyInbox = true;
            }
        }
    }

    // ──────────────────── One turn ────────────────────

    /**
     * Per-turn signal returned by {@link #runTurnFor}. The outer
     * {@link #runTurn} self-continuation logic uses this to decide
     * whether the engine made progress (chat message, tool calls,
     * file writes — anything user-meaningful) or if the LLM stalled.
     * Stalled turns count toward the silent-loop circuit-breaker;
     * productive-but-silent turns (e.g. mid-multi-file refactor that
     * exhausted the per-turn iteration cap) do not, so the outer
     * loop keeps the work flowing.
     */
    private record TurnSignal(boolean appendedChat, boolean toolUsed) {
        boolean madeProgress() {
            return appendedChat || toolUsed;
        }
    }

    /**
     * Runs one turn and reports whether it produced a user-facing
     * chat message and/or invoked tools. The caller ({@link #runTurn})
     * uses the {@link TurnSignal} to break the plan-mode
     * self-continuation loop early when the LLM is genuinely stuck
     * (no progress at all) without aborting on every productive but
     * silent turn (e.g. file writes in a long refactor).
     */
    private TurnSignal runTurnFor(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            List<SteerMessage> inbox) {

        // ─── Routing-Schicht: auto-forward to the active delegated worker
        // when the user is just answering an outstanding clarification.
        // Bypasses the LLM round-trip entirely so worker-name halluci-
        // nation is impossible. Falls through to the normal LLM turn
        // when the conditions don't match (mixed inbox, no active
        // delegation, worker no longer BLOCKED, etc.).
        if (tryAutoForwardToDelegatedWorker(process, ctx, inbox)) {
            return new TurnSignal(/*appendedChat=*/ false, /*toolUsed=*/ true);
        }

        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.RUNNING);
        // Default IDLE on any abnormal exit — matches legacy lifecycle.
        // Reset to outcome.awaitingUserInput() inside the try.
        boolean awaitingUserInput = false;
        try {
            ChatMessageService chatLog = ctx.chatMessageService();

            // Persist user-typed messages into the chat log (so future turns
            // see them in history). Other inbox kinds — ProcessEvent,
            // ToolResult, ExternalCommand — are turn-local: they steer this
            // turn but don't get written back as user-visible chat history.
            StringBuilder userTextForTriggers = new StringBuilder();
            for (SteerMessage m : inbox) {
                if (m instanceof SteerMessage.UserChatInput uci) {
                    chatLog.append(ChatMessageDocument.builder()
                            .tenantId(process.getTenantId())
                            .sessionId(process.getSessionId())
                            .thinkProcessId(process.getId())
                            .role(ChatRole.USER)
                            .content(uci.content())
                            .build());
                    if (uci.content() != null && !uci.content().isBlank()) {
                        if (userTextForTriggers.length() > 0) userTextForTriggers.append('\n');
                        userTextForTriggers.append(uci.content());
                    }
                }
            }

            // Skill auto-trigger: match the joined user-input text against
            // PATTERN/KEYWORDS triggers of visible skills, one-shot
            // activate matches. Filters via process.allowedSkillsOverride.
            if (userTextForTriggers.length() > 0) {
                skillTriggerMatcher.detectAndActivate(process, userTextForTriggers.toString());
            }

            // Build the chat with primary + ordered fallback chain plus
            // the standard resilience-notifier and (when tracing.llm is
            // on) LLM-trace persistence. EngineChatFactory handles the
            // boilerplate that used to sit here — single-entry behaviour
            // is unchanged when params.fallbackModels is empty / missing.
            de.mhus.vance.brain.ai.EngineChatFactory.EngineChatBundle chatBundle =
                    engineChatFactory.forProcess(process, ctx, NAME);
            AiChat aiChat = chatBundle.chat();
            AiChatConfig config = chatBundle.primaryConfig();
            ContextToolsApi tools = ctx.tools();
            List<ToolSpecification> toolSpecs = tools.primaryAsLc4j();
            ModelInfo modelInfo = modelCatalog.lookupOrDefault(
                    config.provider(), config.modelName());
            // params.modelSize: SMALL/LARGE force the prompt variant
            // independently of the catalog; AUTO/missing falls back
            // to the catalog's classification.
            ModelSize effectiveSize = ModelSize.parseOrAuto(
                    paramString(process, "modelSize", null), modelInfo.size());

            List<ChatMessage> messages = buildPromptMessages(
                    process, chatLog, inbox, effectiveSize, ctx, config, modelInfo);
            int maxIters = paramInt(process, "maxIterations",
                    arthurProperties.getMaxToolIterations());
            // Plan-Mode turns chain multiple read/write tool calls
            // before emitting the next action. The default budget
            // (~6) is sized for NORMAL action loops; lift the floor
            // for the entire plan-mode trio (EXPLORING / PLANNING /
            // EXECUTING) so the model has room to settle.
            //
            // EXECUTING gets the highest floor (24) because TODO_UPDATE
            // was promoted to a continuing action — items now chain
            // inside a single turn instead of each ending the turn.
            // A 4-item refactor realistically needs ~16-20 iterations
            // (TODO_UPDATE→read→write→TODO_UPDATE per item, plus a
            // final ANSWER); 12 cuts the LLM off mid-refactor and the
            // free-text fallback puts Arthur into BLOCKED with the
            // work half-done.
            de.mhus.vance.api.thinkprocess.ProcessMode currentMode = process.getMode();
            if (currentMode == de.mhus.vance.api.thinkprocess.ProcessMode.EXECUTING) {
                maxIters = Math.max(maxIters, 24);
            } else if (currentMode == de.mhus.vance.api.thinkprocess.ProcessMode.EXPLORING
                    || currentMode == de.mhus.vance.api.thinkprocess.ProcessMode.PLANNING) {
                maxIters = Math.max(maxIters, 12);
            }
            boolean validation = paramBool(process, "validation", false);
            log.debug("Arthur.turn id='{}' inbox={} historyMsgs={} model={} maxIters={} validation={} mode={} allowedSize={} clientWriteAllowed={}",
                    process.getId(), inbox.size(), messages.size(),
                    config.modelName(), maxIters, validation,
                    process.getMode(), tools.allowed().size(),
                    tools.allowed().contains("client_file_write"));

            String modelAlias = config.provider() + ":" + config.modelName();

            // What the LLM sees this turn comes from the recipe-driven
            // mode cascade (see planning/tool-schema-deferral.md §14).
            // Arthur's bundled recipe pins per-mode allowedToolsRemove /
            // Defer; the resulting primary set is what
            // ContextToolsApi.primaryAsLc4j() returns.
            List<ToolSpecification> readToolSpecs = toolSpecs;

            ActionLoopResult loopResult = runStructuredActionLoop(
                    aiChat, readToolSpecs, tools, messages, ctx, process,
                    maxIters, modelAlias);

            ActionTurnOutcome outcome;
            if (loopResult.isAction()) {
                outcome = handleAction(loopResult.action(), process, ctx);
            } else if ("max-iters".equals(loopResult.fallbackReason())
                    && loopResult.madeProgress()
                    && (process.getMode() == de.mhus.vance.api.thinkprocess.ProcessMode.EXECUTING
                        || process.getMode() == de.mhus.vance.api.thinkprocess.ProcessMode.EXPLORING
                        || process.getMode() == de.mhus.vance.api.thinkprocess.ProcessMode.PLANNING)) {
                // Plan-mode mid-refactor pause: the LLM was actively
                // calling tools (file reads, file writes, exec runs,
                // continuing actions) and just hit the per-turn cap
                // before reaching a terminal action. Don't BLOCK the
                // user — yield non-awaiting and let the outer
                // self-continuation pick up the next turn with a fresh
                // iter budget.
                //
                // Persist the LLM's free-text narration (anything it
                // said alongside tool calls) as an ASSISTANT message
                // before yielding. Without this, the LLM in the next
                // turn rebuilds its prompt from the chat log alone —
                // which contains zero record of its in-turn work — and
                // happily re-emits TODO_UPDATE to "start the first
                // step" again, regressing already-COMPLETED items.
                // The narration acts as cross-turn memory so the LLM
                // sees "I just refactored X, Y" in history.
                String narration = loopResult.fallbackText();
                String chatNote = (narration == null || narration.isBlank())
                        ? null
                        : narration;
                log.info("Arthur.turn id='{}' max-iters with progress "
                        + "({} tool invocations, narration={} chars) — "
                        + "yielding for outer continuation",
                        process.getId(), loopResult.toolInvocations(),
                        narration == null ? 0 : narration.length());
                outcome = new ActionTurnOutcome(chatNote, /*awaiting*/ false);
            } else {
                // Structural fallback: LLM never produced a valid
                // arthur_action despite corrections. Use whatever
                // free-text it generated as a best-effort ANSWER so
                // the user still sees something.
                String text = loopResult.fallbackText();
                outcome = new ActionTurnOutcome(
                        text == null || text.isBlank()
                                ? "(internal: action loop produced no usable output — "
                                        + loopResult.fallbackReason() + ")"
                                : text,
                        true);
            }
            awaitingUserInput = outcome.awaitingUserInput();

            String chatMessage = outcome.chatMessage();
            boolean appendedChat = chatMessage != null && !chatMessage.isBlank();
            if (appendedChat) {
                chatLog.append(ChatMessageDocument.builder()
                        .tenantId(process.getTenantId())
                        .sessionId(process.getSessionId())
                        .thinkProcessId(process.getId())
                        .role(ChatRole.ASSISTANT)
                        .content(chatMessage)
                        .build());
                String preview = chatMessage.length() > 120
                        ? chatMessage.substring(0, 120) + "…" : chatMessage;
                log.info("Arthur.turn id='{}' awaiting={} -> '{}'",
                        process.getId(), awaitingUserInput, preview);
            } else {
                log.info("Arthur.turn id='{}' awaiting={} (silent — no chat append)",
                        process.getId(), awaitingUserInput);
            }

            // Delegation-pointer maintenance: same logic as before.
            // If this turn relayed exactly one BLOCKED ProcessEvent
            // and ended awaiting user input, re-arm the pointer so
            // the user's next reply auto-routes to the worker.
            updateDelegationPointer(process, inbox, awaitingUserInput);
            return new TurnSignal(appendedChat, loopResult.madeProgress());
        } finally {
            ThinkProcessStatus exitStatus = awaitingUserInput
                    ? ThinkProcessStatus.BLOCKED
                    : ThinkProcessStatus.IDLE;
            thinkProcessService.updateStatus(process.getId(), exitStatus);
        }
    }

    /**
     * Auto-forwards user-chat-input to the currently-delegated worker
     * when the conditions match — see
     * {@link ThinkProcessDocument#getActiveDelegationWorkerId()} for
     * the lifecycle. Returns {@code true} when forwarding happened
     * (caller must skip the LLM turn).
     *
     * <p>Eligibility (all required):
     * <ul>
     *   <li>{@code activeDelegationWorkerId} is set on this process</li>
     *   <li>The inbox contains only {@link SteerMessage.UserChatInput}
     *       messages — anything else (ProcessEvent, ToolResult, …)
     *       represents new state the LLM should reason about</li>
     *   <li>The pointed worker still exists in the same tenant +
     *       session and is in {@link ThinkProcessStatus#BLOCKED} —
     *       a worker that finished or was stopped is no target</li>
     * </ul>
     */
    private boolean tryAutoForwardToDelegatedWorker(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            List<SteerMessage> inbox) {
        String workerId = process.getActiveDelegationWorkerId();
        if (workerId == null || workerId.isBlank()) {
            return false;
        }
        if (inbox.isEmpty()) {
            return false;
        }
        StringBuilder combined = new StringBuilder();
        for (SteerMessage m : inbox) {
            if (!(m instanceof SteerMessage.UserChatInput uci)) {
                return false;
            }
            if (uci.content() == null || uci.content().isBlank()) {
                continue;
            }
            if (combined.length() > 0) combined.append('\n');
            combined.append(uci.content());
        }
        if (combined.length() == 0) {
            return false;
        }
        Optional<ThinkProcessDocument> targetOpt =
                thinkProcessService.findById(workerId);
        if (targetOpt.isEmpty()) {
            log.info(
                    "Arthur id='{}' delegation pointer references missing worker '{}' — clearing and falling through to LLM",
                    process.getId(), workerId);
            thinkProcessService.updateActiveDelegationWorkerId(process.getId(), null);
            return false;
        }
        ThinkProcessDocument target = targetOpt.get();
        if (!process.getTenantId().equals(target.getTenantId())
                || !process.getSessionId().equals(target.getSessionId())) {
            log.warn(
                    "Arthur id='{}' delegation pointer crosses scope (tenant/session mismatch) — clearing",
                    process.getId());
            thinkProcessService.updateActiveDelegationWorkerId(process.getId(), null);
            return false;
        }
        if (target.getStatus() != ThinkProcessStatus.BLOCKED) {
            log.info(
                    "Arthur id='{}' delegation target '{}' is {} (not BLOCKED) — clearing pointer, falling through to LLM",
                    process.getId(), target.getName(), target.getStatus());
            thinkProcessService.updateActiveDelegationWorkerId(process.getId(), null);
            return false;
        }

        // Persist the user's text into Arthur's chat log so the
        // history reflects the (passive-relay) round-trip.
        ChatMessageService chatLog = ctx.chatMessageService();
        chatLog.append(ChatMessageDocument.builder()
                .tenantId(process.getTenantId())
                .sessionId(process.getSessionId())
                .thinkProcessId(process.getId())
                .role(ChatRole.USER)
                .content(combined.toString())
                .build());

        // Forward to the worker via the message router. The worker's
        // lane wakes itself; we don't block on the worker's turn —
        // the worker's reply (BLOCKED/DONE event) lands in our inbox
        // through ParentNotificationListener and triggers the next
        // (LLM-mediated) Arthur turn.
        messageRouter.dispatch(
                process.getId(),
                target.getId(),
                de.mhus.vance.shared.thinkprocess.PendingMessageDocument.builder()
                        .type(de.mhus.vance.shared.thinkprocess.PendingMessageType.USER_CHAT_INPUT)
                        .at(java.time.Instant.now())
                        .fromUser("auto-route:" + process.getId())
                        .content(combined.toString())
                        .build());

        log.info(
                "Arthur id='{}' auto-forwarded {} chars to delegated worker '{}' (id='{}')",
                process.getId(), combined.length(), target.getName(), target.getId());

        // Status: stay BLOCKED — Arthur is still waiting (just for
        // the worker now instead of the user). When the worker comes
        // back via ProcessEvent, the next turn runs normally.
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.BLOCKED);
        return true;
    }

    /**
     * Sets / clears {@link ThinkProcessDocument#activeDelegationWorkerId}
     * based on what just happened. The simple rule: pointer survives
     * iff this turn relayed exactly one BLOCKED ProcessEvent and ended
     * awaiting the user — i.e. Arthur is forwarding a worker's
     * clarification question. Any other outcome clears the pointer.
     */
    private void updateDelegationPointer(
            ThinkProcessDocument process,
            List<SteerMessage> inbox,
            boolean awaitingUserInput) {
        String currentWorker = process.getActiveDelegationWorkerId();
        String nextWorker = null;
        if (awaitingUserInput) {
            String single = singleBlockedSourceProcessId(inbox);
            if (single != null) {
                nextWorker = single;
            }
        }
        if (java.util.Objects.equals(currentWorker, nextWorker)) {
            return;
        }
        thinkProcessService.updateActiveDelegationWorkerId(process.getId(), nextWorker);
        if (nextWorker != null) {
            log.info("Arthur id='{}' delegation pointer set → worker id='{}'",
                    process.getId(), nextWorker);
        } else if (currentWorker != null) {
            log.info("Arthur id='{}' delegation pointer cleared", process.getId());
        }
    }

    /**
     * Returns the {@code sourceProcessId} when the inbox contained
     * exactly one BLOCKED-typed {@link SteerMessage.ProcessEvent},
     * otherwise {@code null}. Multi-clarification fan-in stays
     * LLM-mediated — disambiguating two simultaneous worker questions
     * is a planning decision, not a routing one.
     */
    private static @Nullable String singleBlockedSourceProcessId(List<SteerMessage> inbox) {
        String found = null;
        for (SteerMessage m : inbox) {
            if (m instanceof SteerMessage.ProcessEvent pe
                    && pe.type() == de.mhus.vance.api.thinkprocess.ProcessEventType.BLOCKED) {
                if (found != null) {
                    return null;
                }
                found = pe.sourceProcessId();
            }
        }
        return found;
    }

    // ──────────────────── Structured-action contract ────────────────────

    @Override
    protected String actionToolName() {
        return ArthurActionSchema.TOOL_NAME;
    }

    @Override
    protected String actionToolDescription() {
        return ArthurActionSchema.TOOL_DESCRIPTION;
    }

    @Override
    protected Map<String, Object> actionToolSchema() {
        return ArthurActionSchema.schema();
    }

    @Override
    protected Set<String> supportedActionTypes() {
        return ArthurActionSchema.SUPPORTED_TYPES;
    }

    @Override
    protected ActionTurnOutcome handleAction(
            de.mhus.vance.brain.thinkengine.action.EngineAction action,
            ThinkProcessDocument process,
            ThinkEngineContext ctx) {
        // Per-mode action gate. The action schema is intentionally
        // flat (all types visible) for cache stability; this gate
        // enforces what each mode actually allows.
        //
        // Plan-Mode transition actions (START_PLAN, PROPOSE_PLAN,
        // START_EXECUTION, TODO_UPDATE) are intrinsically idempotent —
        // their handlers re-set the same fields they target — so we
        // log + execute them even when emitted in a "wrong" mode. This
        // breaks LLM-action loops where the model re-emits an already-
        // applied transition (typically START_EXECUTION right after
        // entering EXECUTING) without bouncing the user.
        //
        // Non-idempotent actions (DELEGATE, RELAY, ANSWER, …) stay
        // fully gated so an EXPLORING / PLANNING turn can't accidentally
        // spawn a worker or hand a non-plan reply to the user.
        de.mhus.vance.api.thinkprocess.ProcessMode mode = process.getMode();
        if (mode == null) mode = de.mhus.vance.api.thinkprocess.ProcessMode.NORMAL;
        // Common LLM confusion in PLANNING after user approval: the
        // model emits TODO_UPDATE (mark first item IN_PROGRESS) instead
        // of START_EXECUTION, conflating "start working" with "set
        // status". Translate transparently — the user already approved,
        // execution mode is what they want. Without this, TODO_UPDATE
        // squeaks through PLAN_MODE_IDEMPOTENT_ACTIONS, the LLM tries
        // to do real work in the read-only PLANNING tool spec and
        // burns max-iters until it stumbles into START_EXECUTION.
        if (mode == de.mhus.vance.api.thinkprocess.ProcessMode.PLANNING
                && ArthurActionSchema.TYPE_TODO_UPDATE.equals(action.type())) {
            log.info("Arthur id='{}' translating TODO_UPDATE in PLANNING → "
                    + "START_EXECUTION (model conflated mode-transition with "
                    + "status-update). reason: '{}'",
                    process.getId(), action.reason());
            return handleStartExecution(
                    new de.mhus.vance.brain.thinkengine.action.EngineAction(
                            ArthurActionSchema.TYPE_START_EXECUTION,
                            action.reason(),
                            java.util.Map.of()),
                    process, ctx);
        }
        if (!ArthurActionSchema.typesForMode(mode).contains(action.type())) {
            if (PLAN_MODE_IDEMPOTENT_ACTIONS.contains(action.type())) {
                log.info("Arthur id='{}' action '{}' is idempotent in mode {} — "
                        + "executing as no-op-or-redundant transition. reason: '{}'",
                        process.getId(), action.type(), mode, action.reason());
                // fall through — handler is idempotent
            } else {
                String hint = "Action '" + action.type() + "' is not available "
                        + "in mode " + mode + ". Allowed in this mode: "
                        + ArthurActionSchema.typesForMode(mode)
                        + ". Re-emit a valid action.";
                log.warn("Arthur id='{}' rejected action '{}' in mode {} — reason: '{}'",
                        process.getId(), action.type(), mode, action.reason());
                // awaitingUserInput=true → status=BLOCKED so we don't loop
                // re-emitting the same invalid action. The hint surfaces
                // to the user so they can intervene if Arthur is stuck.
                return new ActionTurnOutcome(hint, /*awaitingUserInput*/ true);
            }
        }
        return switch (action.type()) {
            case ArthurActionSchema.TYPE_ANSWER          -> handleAnswer(action);
            case ArthurActionSchema.TYPE_ASK_USER        -> handleAskUser(action);
            case ArthurActionSchema.TYPE_DELEGATE        -> handleDelegate(action, process, ctx);
            case ArthurActionSchema.TYPE_RELAY           -> handleRelay(action, process, ctx);
            case ArthurActionSchema.TYPE_WAIT            -> handleWait(action);
            case ArthurActionSchema.TYPE_REJECT          -> handleReject(action);
            case ArthurActionSchema.TYPE_START_PLAN      -> handleStartPlan(action, process, ctx);
            case ArthurActionSchema.TYPE_PROPOSE_PLAN    -> handleProposePlan(action, process, ctx);
            case ArthurActionSchema.TYPE_START_EXECUTION -> handleStartExecution(action, process, ctx);
            case ArthurActionSchema.TYPE_TODO_UPDATE     -> handleTodoUpdate(action, process, ctx);
            default -> {
                // Should never happen — the base class validates against
                // supportedActionTypes() before reaching here. Surface as
                // a chat message so the user sees something.
                log.warn("Arthur id='{}' unknown action type '{}'",
                        process.getId(), action.type());
                yield new ActionTurnOutcome(
                        "(internal: unknown action type '" + action.type()
                                + "', reason was: " + action.reason() + ")",
                        true);
            }
        };
    }

    @Override
    protected boolean isTerminalAction(
            de.mhus.vance.brain.thinkengine.action.EngineAction action) {
        return !CONTINUING_ACTIONS.contains(action.type());
    }

    @Override
    protected String applyContinuingAction(
            de.mhus.vance.brain.thinkengine.action.EngineAction action,
            ThinkProcessDocument process,
            ThinkEngineContext ctx) {
        // Reuse the same dispatch as terminal actions — handleTodoUpdate
        // is idempotent and persists the new state in process.todos.
        // Discard the ActionTurnOutcome (chatMessage / awaiting) since
        // continuing actions don't produce chat messages and don't end
        // the turn.
        ActionTurnOutcome ignored = handleAction(action, process, ctx);
        if (ArthurActionSchema.TYPE_TODO_UPDATE.equals(action.type())) {
            return renderTodoUpdateFeedback(process);
        }
        return "Action " + action.type() + " applied.";
    }

    /**
     * Builds the LLM-facing feedback for a TODO_UPDATE. Renders the
     * post-update TodoList and tells the model what to do next based
     * on the first non-COMPLETED item — without this nudge the model
     * tends to re-emit TODO_UPDATE for the same id forever, never
     * progressing to the actual read/write tools.
     */
    private String renderTodoUpdateFeedback(ThinkProcessDocument process) {
        StringBuilder sb = new StringBuilder("TODO_UPDATE applied. Current TodoList:\n");
        java.util.List<de.mhus.vance.api.thinkprocess.TodoItem> todos = process.getTodos();
        if (todos == null) todos = java.util.List.of();
        de.mhus.vance.api.thinkprocess.TodoItem firstActive = null;
        for (de.mhus.vance.api.thinkprocess.TodoItem t : todos) {
            de.mhus.vance.api.thinkprocess.TodoStatus s = t.getStatus() == null
                    ? de.mhus.vance.api.thinkprocess.TodoStatus.PENDING
                    : t.getStatus();
            String marker = switch (s) {
                case PENDING -> "[ ]";
                case IN_PROGRESS -> "[~]";
                case COMPLETED -> "[✓]";
            };
            sb.append(marker).append(" (id=").append(t.getId() == null ? "" : t.getId())
                    .append(") ").append(t.getContent() == null ? "" : t.getContent())
                    .append('\n');
            if (firstActive == null
                    && s != de.mhus.vance.api.thinkprocess.TodoStatus.COMPLETED) {
                firstActive = t;
            }
        }
        sb.append('\n');
        if (firstActive == null) {
            sb.append("All todos COMPLETED. Emit ANSWER with a brief summary "
                    + "of what was done so the user can see the final result.");
        } else if (firstActive.getStatus()
                == de.mhus.vance.api.thinkprocess.TodoStatus.IN_PROGRESS) {
            sb.append("The first active item (id=")
                    .append(firstActive.getId())
                    .append(") is already IN_PROGRESS. Do NOT emit TODO_UPDATE "
                            + "for it again — that's a no-op. Instead, in the "
                            + "next iteration call read/write tools "
                            + "(client_file_read, client_file_edit, "
                            + "client_file_write, client_exec_run, "
                            + "workspace_read, workspace_grep, etc.) to "
                            + "actually make progress on this item. Once the "
                            + "work is done, emit TODO_UPDATE to mark it "
                            + "COMPLETED and pick the next item.");
        } else {
            sb.append("The first non-completed item (id=")
                    .append(firstActive.getId())
                    .append(") is still PENDING. Emit TODO_UPDATE to set it "
                            + "IN_PROGRESS, then start the actual work in "
                            + "the same iteration if possible.");
        }
        return sb.toString();
    }

    // ──────────────────── Plan-Mode action handlers ────────────────────

    /**
     * {@code START_PLAN} — switch the process into EXPLORING mode. The
     * LLM has decided the user's request is non-trivial and wants to
     * explore-then-plan-then-execute. Read-only tool filter activates
     * via the recipe-driven mode-cascade (see
     * {@code planning/tool-schema-deferral.md} §14) on the next
     * per-call context build.
     *
     * <p>Recipe property {@code planMode: disabled} blocks this action.
     * {@code planMode: auto} (default) and {@code planMode: required}
     * allow it.
     */
    private ActionTurnOutcome handleStartPlan(
            de.mhus.vance.brain.thinkengine.action.EngineAction action,
            ThinkProcessDocument process,
            ThinkEngineContext ctx) {
        String planMode = paramString(process, "planMode", "auto");
        if ("disabled".equalsIgnoreCase(planMode)) {
            log.info("Arthur id='{}' START_PLAN rejected — planMode=disabled",
                    process.getId());
            return new ActionTurnOutcome(
                    "(plan mode is disabled for this recipe — pick a different "
                            + "action: ANSWER, DELEGATE, ASK_USER, …)",
                    /*awaitingUserInput*/ false);
        }
        de.mhus.vance.api.thinkprocess.ProcessMode prior = process.getMode();
        boolean ok = thinkProcessService.updateMode(
                process.getId(),
                de.mhus.vance.api.thinkprocess.ProcessMode.EXPLORING);
        if (!ok) {
            log.warn("Arthur id='{}' START_PLAN failed — process not found",
                    process.getId());
            return new ActionTurnOutcome(
                    "(internal: failed to enter plan mode)", true);
        }
        process.setMode(de.mhus.vance.api.thinkprocess.ProcessMode.EXPLORING);
        planModeEventEmitter.emitModeChanged(
                process, prior, de.mhus.vance.api.thinkprocess.ProcessMode.EXPLORING);
        log.info("Arthur id='{}' entered EXPLORING — reason='{}'",
                process.getId(), action.reason());
        // No user-facing chat message — the next turn will run in
        // EXPLORING mode and produce the actual exploration output.
        // awaitingUserInput=false so the engine auto-wakes for the
        // next turn instead of waiting on the user.
        return new ActionTurnOutcome(null, /*awaitingUserInput*/ false);
    }

    /**
     * {@code PROPOSE_PLAN} — submit plan + TodoList for user approval.
     * Persists the TodoList atomically, switches mode to PLANNING,
     * emits the plan text as a normal assistant chat message.
     */
    private ActionTurnOutcome handleProposePlan(
            de.mhus.vance.brain.thinkengine.action.EngineAction action,
            ThinkProcessDocument process,
            ThinkEngineContext ctx) {
        String plan = action.stringParam(ArthurActionSchema.PARAM_PLAN);
        String summary = action.stringParam(ArthurActionSchema.PARAM_SUMMARY);
        java.util.List<de.mhus.vance.api.thinkprocess.TodoItem> todos =
                parseTodos(action.params().get(ArthurActionSchema.PARAM_TODOS));
        if (plan == null || plan.isBlank()) {
            log.warn("Arthur id='{}' PROPOSE_PLAN missing plan — reason='{}'",
                    process.getId(), action.reason());
            return new ActionTurnOutcome(
                    "(internal: PROPOSE_PLAN missing plan text — re-emit with the full plan markdown)",
                    false);
        }
        if (todos.isEmpty()) {
            log.warn("Arthur id='{}' PROPOSE_PLAN missing todos — reason='{}'",
                    process.getId(), action.reason());
            return new ActionTurnOutcome(
                    "(internal: PROPOSE_PLAN must include 3–8 todos — re-emit)",
                    false);
        }
        de.mhus.vance.api.thinkprocess.ProcessMode prior = process.getMode();
        thinkProcessService.setTodos(process.getId(), todos);
        thinkProcessService.updateMode(
                process.getId(),
                de.mhus.vance.api.thinkprocess.ProcessMode.PLANNING);
        process.setTodos(todos);
        process.setMode(de.mhus.vance.api.thinkprocess.ProcessMode.PLANNING);
        planModeEventEmitter.emitTodosUpdated(process, todos);
        planModeEventEmitter.emitPlanProposed(process, summary, /*planVersion*/ 1);
        if (prior != de.mhus.vance.api.thinkprocess.ProcessMode.PLANNING) {
            planModeEventEmitter.emitModeChanged(
                    process, prior, de.mhus.vance.api.thinkprocess.ProcessMode.PLANNING);
        }
        log.info("Arthur id='{}' PROPOSE_PLAN summary='{}' todos.size={} reason='{}'",
                process.getId(), summary, todos.size(), action.reason());
        // Plan text becomes the assistant chat message — user sees it
        // directly. awaitingUserInput=true → process goes BLOCKED until
        // user replies (approval / edit / reject).
        return new ActionTurnOutcome(plan, /*awaitingUserInput*/ true);
    }

    /**
     * {@code START_EXECUTION} — user accepted the plan, begin work.
     * Switches mode to EXECUTING. Tool filter relaxes back to
     * Arthur's full pool.
     */
    private ActionTurnOutcome handleStartExecution(
            de.mhus.vance.brain.thinkengine.action.EngineAction action,
            ThinkProcessDocument process,
            ThinkEngineContext ctx) {
        String notes = action.stringParam(ArthurActionSchema.PARAM_NOTES);
        de.mhus.vance.api.thinkprocess.ProcessMode prior = process.getMode();
        thinkProcessService.updateMode(
                process.getId(),
                de.mhus.vance.api.thinkprocess.ProcessMode.EXECUTING);
        process.setMode(de.mhus.vance.api.thinkprocess.ProcessMode.EXECUTING);
        planModeEventEmitter.emitModeChanged(
                process, prior, de.mhus.vance.api.thinkprocess.ProcessMode.EXECUTING);
        log.info("Arthur id='{}' entered EXECUTING — notes='{}' reason='{}'",
                process.getId(), notes, action.reason());
        // No user-facing message; the next turn picks up the first
        // PENDING todo and starts work.
        return new ActionTurnOutcome(null, /*awaitingUserInput*/ false);
    }

    /**
     * {@code TODO_UPDATE} — mark TodoList items as IN_PROGRESS or
     * COMPLETED during execution. No chat message, no mode change.
     */
    private ActionTurnOutcome handleTodoUpdate(
            de.mhus.vance.brain.thinkengine.action.EngineAction action,
            ThinkProcessDocument process,
            ThinkEngineContext ctx) {
        Object updatesRaw = action.params().get(ArthurActionSchema.PARAM_UPDATES);
        java.util.Map<String, de.mhus.vance.api.thinkprocess.TodoStatus> updates =
                parseTodoUpdates(updatesRaw);
        if (updates.isEmpty()) {
            log.warn("Arthur id='{}' TODO_UPDATE empty — reason='{}'",
                    process.getId(), action.reason());
            return new ActionTurnOutcome(null, /*awaitingUserInput*/ false);
        }
        boolean ok = thinkProcessService.updateTodoStatuses(process.getId(), updates);
        log.info("Arthur id='{}' TODO_UPDATE applied={} count={} reason='{}'",
                process.getId(), ok, updates.size(), action.reason());
        if (ok) {
            // Reload to read the persisted list, then publish the full set —
            // clients replace verbatim (no diff/patch protocol).
            thinkProcessService.findById(process.getId()).ifPresent(refreshed -> {
                process.setTodos(refreshed.getTodos());
                planModeEventEmitter.emitTodosUpdated(refreshed, refreshed.getTodos());
            });
        }
        return new ActionTurnOutcome(null, /*awaitingUserInput*/ false);
    }

    @SuppressWarnings("unchecked")
    private java.util.List<de.mhus.vance.api.thinkprocess.TodoItem> parseTodos(
            @org.jspecify.annotations.Nullable Object raw) {
        if (!(raw instanceof java.util.List<?> list)) {
            return java.util.List.of();
        }
        java.util.List<de.mhus.vance.api.thinkprocess.TodoItem> out =
                new java.util.ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof java.util.Map<?, ?> mapRaw)) continue;
            java.util.Map<String, Object> m = (java.util.Map<String, Object>) mapRaw;
            String id = strOrNull(m.get("id"));
            String content = strOrNull(m.get("content"));
            String activeForm = strOrNull(m.get("activeForm"));
            if (id == null || id.isBlank() || content == null || content.isBlank()) {
                continue;
            }
            out.add(de.mhus.vance.api.thinkprocess.TodoItem.builder()
                    .id(id)
                    .status(de.mhus.vance.api.thinkprocess.TodoStatus.PENDING)
                    .content(content)
                    .activeForm(activeForm)
                    .build());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, de.mhus.vance.api.thinkprocess.TodoStatus> parseTodoUpdates(
            @org.jspecify.annotations.Nullable Object raw) {
        java.util.Map<String, de.mhus.vance.api.thinkprocess.TodoStatus> out =
                new java.util.LinkedHashMap<>();
        if (!(raw instanceof java.util.List<?> list)) return out;
        for (Object o : list) {
            if (!(o instanceof java.util.Map<?, ?> mapRaw)) continue;
            java.util.Map<String, Object> m = (java.util.Map<String, Object>) mapRaw;
            String id = strOrNull(m.get("id"));
            String statusStr = strOrNull(m.get("status"));
            if (id == null || id.isBlank() || statusStr == null) continue;
            try {
                out.put(id, de.mhus.vance.api.thinkprocess.TodoStatus.valueOf(statusStr));
            } catch (IllegalArgumentException ignored) {
                // unknown status — skip, validator catches it via re-prompt
            }
        }
        return out;
    }

    private static @org.jspecify.annotations.Nullable String strOrNull(
            @org.jspecify.annotations.Nullable Object o) {
        return o instanceof String s ? s : null;
    }

    /**
     * Direct user-facing reply. Most common action — Arthur knows the
     * answer (or has just synthesised one from a worker's results).
     */
    private ActionTurnOutcome handleAnswer(
            de.mhus.vance.brain.thinkengine.action.EngineAction action) {
        String message = action.stringParam(ArthurActionSchema.PARAM_MESSAGE);
        if (message == null || message.isBlank()) {
            // No message attached but type=ANSWER. Use the reason as
            // a fallback so the user at least sees something instead
            // of silence.
            message = action.reason();
        }
        return new ActionTurnOutcome(message, /*awaitingUserInput*/ true);
    }

    /** Clarification question to the user. Identical persistence to ANSWER but semantically different. */
    private ActionTurnOutcome handleAskUser(
            de.mhus.vance.brain.thinkengine.action.EngineAction action) {
        String message = action.stringParam(ArthurActionSchema.PARAM_MESSAGE);
        if (message == null || message.isBlank()) {
            message = action.reason();
        }
        return new ActionTurnOutcome(message, /*awaitingUserInput*/ true);
    }

    /**
     * Spawn a worker via {@code process_create} (when the LLM
     * supplies an explicit {@code preset} recipe name) or via
     * {@code process_create_delegate} (when {@code preset} is
     * omitted and the system should pick the best-matching recipe
     * for the task). Either path produces a {@link ThinkProcessDocument}
     * for the new worker. The engine derives a unique worker name
     * — never trusts the LLM with naming. The optional
     * {@code message} is shown to the user as a pre-announcement;
     * absent message = silent spawn (no chat append, no filler).
     */
    private ActionTurnOutcome handleDelegate(
            de.mhus.vance.brain.thinkengine.action.EngineAction action,
            ThinkProcessDocument process,
            ThinkEngineContext ctx) {
        String preset = action.stringParam(ArthurActionSchema.PARAM_PRESET);
        String prompt = action.stringParam(ArthurActionSchema.PARAM_PROMPT);
        if (prompt == null || prompt.isBlank()) {
            log.warn("Arthur id='{}' DELEGATE missing prompt — reason='{}'",
                    process.getId(), action.reason());
            return new ActionTurnOutcome(
                    "Sorry — internal: tried to delegate without a prompt. "
                            + "Reason was: " + action.reason(),
                    true);
        }

        boolean explicitRecipe = preset != null && !preset.isBlank();
        String workerNamePrefix = explicitRecipe ? preset : "delegated";
        String workerName = workerNamePrefix + "-"
                + java.util.UUID.randomUUID().toString().substring(0, 6);
        try {
            if (explicitRecipe) {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("recipe", preset);
                params.put("name", workerName);
                params.put("goal", prompt);
                params.put("steerContent", prompt);
                ctx.tools().invokeInternal("process_create", params);
                log.info("Arthur id='{}' DELEGATE recipe='{}' worker='{}' reason='{}'",
                        process.getId(), preset, workerName, summariseReason(action.reason()));
            } else {
                // No preset — let process_create_delegate's selector
                // pick. Fallback to Slart is enabled by default,
                // mirroring the user-facing intent of "do this
                // somehow, even if no existing recipe fits".
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("task", prompt);
                params.put("name", workerName);
                params.put("steerContent", prompt);
                ctx.tools().invokeInternal("process_create_delegate", params);
                log.info("Arthur id='{}' DELEGATE via selector worker='{}' "
                                + "reason='{}'",
                        process.getId(), workerName, summariseReason(action.reason()));
            }
        } catch (RuntimeException e) {
            log.warn("Arthur id='{}' DELEGATE failed: {}", process.getId(), e.toString());
            return new ActionTurnOutcome(
                    "Internal: konnte den Worker nicht starten ("
                            + e.getMessage() + ").",
                    true);
        }

        // The whole point of structured DELEGATE: when message is
        // absent, the spawn is silent — no "Okay, I'll start a
        // worker" filler in the user-facing chat. The worker's own
        // assistant messages will surface through the
        // ChatMessageNotificationDispatcher when ready.
        String preText = action.stringParam(ArthurActionSchema.PARAM_MESSAGE);
        return new ActionTurnOutcome(
                preText == null || preText.isBlank() ? null : preText,
                /*awaitingUserInput*/ false);
    }

    /**
     * Pass a worker's last reply through to the user as Arthur's
     * own answer. Zero LLM tokens for the content — the engine
     * looks up the worker by name (with id fallback so the LLM can
     * use either the {@code sourceProcessName} or the
     * {@code sourceProcessId} from the most recent
     * {@code <process-event>} marker), reads the worker's last
     * substantive ASSISTANT message verbatim, and persists it as a
     * fresh ASSISTANT message under Arthur's process id with
     * {@code originatingProcessId} provenance.
     *
     * <p>The optional {@code prefix} param prepends a brief Arthur
     * line ("Hier ist das Rezept, das ich gefunden habe:") before
     * the relayed text — useful when Arthur wants to frame the
     * worker's output in the conversation. Empty / missing prefix
     * = clean pass-through.
     */
    private ActionTurnOutcome handleRelay(
            de.mhus.vance.brain.thinkengine.action.EngineAction action,
            ThinkProcessDocument process,
            ThinkEngineContext ctx) {
        String source = action.stringParam(ArthurActionSchema.PARAM_SOURCE);
        if (source == null || source.isBlank()) {
            log.warn("Arthur id='{}' RELAY missing 'source' — reason='{}'",
                    process.getId(), action.reason());
            return new ActionTurnOutcome(
                    "(internal: RELAY without 'source'. Reason was: "
                            + action.reason() + ")",
                    true);
        }

        // Same name-or-id resolution as the process tools so the LLM
        // can use either sourceProcessName="web-research-7b9124" or
        // sourceProcessId="69f7..." from the <process-event>.
        Optional<ThinkProcessDocument> targetOpt = thinkProcessService
                .findByName(process.getTenantId(), process.getSessionId(), source)
                .or(() -> thinkProcessService.findById(source)
                        .filter(p -> process.getTenantId().equals(p.getTenantId())
                                && process.getSessionId().equals(p.getSessionId())));
        if (targetOpt.isEmpty()) {
            log.warn("Arthur id='{}' RELAY source '{}' not found in session",
                    process.getId(), source);
            return new ActionTurnOutcome(
                    "(internal: RELAY target '" + source
                            + "' not found in this session.)",
                    true);
        }
        ThinkProcessDocument target = targetOpt.get();

        // Find the worker's last substantive ASSISTANT message. We
        // walk the active history backwards because compacted-out
        // messages (archived into a memory) shouldn't be relayed —
        // they're old context, not the latest reply.
        java.util.List<de.mhus.vance.shared.chat.ChatMessageDocument> workerHistory =
                ctx.chatMessageService().activeHistory(
                        target.getTenantId(),
                        target.getSessionId(),
                        target.getId());
        de.mhus.vance.shared.chat.ChatMessageDocument lastReply = null;
        for (int i = workerHistory.size() - 1; i >= 0; i--) {
            de.mhus.vance.shared.chat.ChatMessageDocument m = workerHistory.get(i);
            if (m.getRole() == ChatRole.ASSISTANT
                    && m.getContent() != null
                    && !m.getContent().isBlank()) {
                lastReply = m;
                break;
            }
        }
        if (lastReply == null) {
            log.warn("Arthur id='{}' RELAY source '{}' has no ASSISTANT reply yet",
                    process.getId(), source);
            return new ActionTurnOutcome(
                    "(internal: worker '" + source
                            + "' has no reply to relay yet.)",
                    true);
        }

        String prefix = action.stringParam(ArthurActionSchema.PARAM_PREFIX);
        StringBuilder out = new StringBuilder();
        if (prefix != null && !prefix.isBlank()) {
            out.append(prefix.trim()).append("\n\n");
        }
        out.append(lastReply.getContent());

        log.info(
                "Arthur id='{}' RELAY source='{}' ({} chars) reason='{}'",
                process.getId(), target.getName(),
                lastReply.getContent().length(),
                summariseReason(action.reason()));

        // The engine layer (runTurnFor) appends the chat message —
        // we just return the composed text. By going through the
        // normal awaitingUserInput=true exit, Arthur ends the turn
        // BLOCKED waiting for the user's next message, which is the
        // expected state after delivering an answer.
        return new ActionTurnOutcome(out.toString(), /*awaitingUserInput*/ true);
    }

    /** Async work in flight, nothing to add. Engine goes IDLE. */
    private ActionTurnOutcome handleWait(
            de.mhus.vance.brain.thinkengine.action.EngineAction action) {
        String message = action.stringParam(ArthurActionSchema.PARAM_MESSAGE);
        return new ActionTurnOutcome(
                message == null || message.isBlank() ? null : message,
                /*awaitingUserInput*/ false);
    }

    /** Out-of-scope refusal. */
    private ActionTurnOutcome handleReject(
            de.mhus.vance.brain.thinkengine.action.EngineAction action) {
        String message = action.stringParam(ArthurActionSchema.PARAM_MESSAGE);
        if (message == null || message.isBlank()) {
            message = "Das geht so leider nicht — " + action.reason();
        }
        return new ActionTurnOutcome(message, /*awaitingUserInput*/ true);
    }

    private static String summariseReason(String reason) {
        if (reason == null) return "";
        String oneLine = reason.replace("\n", " ").replaceAll("\\s+", " ").trim();
        return oneLine.length() > 80 ? oneLine.substring(0, 77) + "..." : oneLine;
    }

    // ──────────────────── Prompt building ────────────────────

    /**
     * Builds the LLM input: system prompt → chat history (assistant +
     * past user messages) → current-turn inbox rendered as user-role
     * messages with the {@code <process-event>} XML wrapper for
     * non-user inputs.
     */
    private List<ChatMessage> buildPromptMessages(
            ThinkProcessDocument process,
            ChatMessageService chatLog,
            List<SteerMessage> inbox,
            ModelSize modelSize,
            ThinkEngineContext ctx,
            de.mhus.vance.brain.ai.AiChatConfig chatConfig,
            ModelInfo modelInfo) {
        List<ChatMessage> messages = new ArrayList<>();

        // ── STATIC system prefix — Anthropic cache anchors here ──
        // Engine default + recipe-prompt overlay + deferred-tool
        // discovery block. Stable per recipe + mode; the dynamic blocks
        // below ride outside the cache hash. See
        // specification/prompt-caching.md §5 and
        // planning/tool-schema-deferral.md §4.5 / §7.
        java.util.Map<String, Object> promptCtx = de.mhus.vance.brain.prompt.PromptContextBuilder
                .forProcess(process, modelInfo)
                .tier(modelSize)
                .engine(NAME)
                .build();
        String base = SystemPrompts.compose(process,
                engineDefaultPrompt(process, modelSize),
                promptTemplateRenderer, promptCtx);
        String discoveryBlock = ctx.tools().discoveryBlockMarkdown();
        if (discoveryBlock != null && !discoveryBlock.isBlank()) {
            base = base + discoveryBlock;
        }
        messages.add(SystemMessage.from(base));

        // ── DYNAMIC blocks — change tenant/project/turn-to-turn ──
        // Recipe catalog: depends on tenant + bundled recipes; mutates
        // when a recipe is added / removed in _vance.
        String catalog = buildRecipeCatalogSection(process);
        if (catalog != null && !catalog.isBlank()) {
            messages.add(VanceSystemMessage.dynamic(catalog));
        }
        // Project-memory block (memory.* settings cascade) — pinned
        // language / tone / persona hints. Mutates on Settings changes.
        String memoryBlock = memoryContextLoader.composeBlock(process);
        if (memoryBlock != null && !memoryBlock.isBlank()) {
            messages.add(VanceSystemMessage.dynamic(memoryBlock));
        }
        // Plan-Mode TodoList block — surfaces the live task list so the
        // LLM in EXECUTING / PLANNING knows which step is current
        // (IN_PROGRESS marker) and which are still PENDING. Without
        // this, the model would re-emit "I'll mark step 1 in progress"
        // turn after turn because each turn would look like the start
        // of execution.
        String todoBlock = buildTodoListBlock(process);
        if (todoBlock != null && !todoBlock.isBlank()) {
            messages.add(VanceSystemMessage.dynamic(todoBlock));
        }

        // Active history (ARCHIVED_CHAT compaction-aware once we wire
        // memoryService — for v1 just use full active history).
        List<ChatMessageDocument> history = chatLog.activeHistory(
                process.getTenantId(), process.getSessionId(), process.getId());

        // The inbox messages we just persisted (UserChatInput) are
        // already in `history`. Render the rest separately so the LLM
        // sees them clearly tagged.
        int userInputCount = 0;
        for (SteerMessage m : inbox) {
            if (m instanceof SteerMessage.UserChatInput) userInputCount++;
        }

        // Old history first — every entry except the trailing N user
        // chat messages that correspond to this turn's UserChatInput
        // items in the inbox. Those trailing entries are rendered
        // fresh from the inbox below so we can attach multimodal
        // content blocks for any attachments the user sent.
        int oldHistorySize = Math.max(0, history.size() - userInputCount);
        for (int i = 0; i < oldHistorySize; i++) {
            messages.add(toLangchain(history.get(i)));
        }

        // Current-turn user messages: rebuilt from the inbox so
        // attachments (image / PDF / text) ride along as content
        // blocks. Text-only inputs match the legacy path 1:1.
        de.mhus.vance.brain.ai.ProviderType providerType =
                de.mhus.vance.brain.ai.ProviderType.requireWireName(chatConfig.provider());
        for (SteerMessage m : inbox) {
            if (m instanceof SteerMessage.UserChatInput uci) {
                messages.add(buildUserMessageWithAttachments(
                        uci, process, chatConfig.fullName(),
                        providerType, modelInfo.capabilities()));
            }
        }

        // Now append the non-user inbox items (events, tool results,
        // external commands) as user-role messages with the XML
        // wrapper Arthur's prompt is trained on.
        for (SteerMessage m : inbox) {
            if (m instanceof SteerMessage.UserChatInput) continue;
            String wrapped = renderForLlm(m);
            if (wrapped != null) {
                messages.add(UserMessage.from(wrapped));
            }
        }

        return messages;
    }

    /**
     * Build a {@link UserMessage} for one current-turn
     * {@link SteerMessage.UserChatInput}. Text-only when there are no
     * attachments (fast path). When attachments are present, resolves
     * them via {@link de.mhus.vance.brain.ai.attachment.AttachmentResolver}
     * (validates project scope + size) and produces a multimodal
     * message — image / PDF / text content blocks ahead of the
     * question text. Provider/capability-aware via
     * {@link de.mhus.vance.brain.ai.StandardAiChat#toContentBlock}.
     *
     * <p>Attachment resolution failures (missing doc, foreign project,
     * oversize) are caught and downgraded to a clear text-only message
     * — Arthur should reply gracefully rather than crash the turn. The
     * underlying error is logged so operators can see what happened.
     */
    private UserMessage buildUserMessageWithAttachments(
            SteerMessage.UserChatInput uci,
            ThinkProcessDocument process,
            String chatName,
            de.mhus.vance.brain.ai.ProviderType providerType,
            java.util.Set<de.mhus.vance.brain.ai.ModelCapability> capabilities) {
        if (uci.attachments().isEmpty()) {
            return UserMessage.from(uci.content());
        }
        List<de.mhus.vance.brain.ai.attachment.ResolvedAttachment> resolved;
        try {
            resolved = attachmentResolver.resolveAll(
                    uci.attachments(), process.getTenantId(), process.getProjectId());
        } catch (de.mhus.vance.brain.ai.attachment.AttachmentException e) {
            log.warn("Arthur: attachment resolution failed for process '{}': {} — "
                            + "falling back to text-only turn",
                    process.getId(), e.getMessage());
            return UserMessage.from(uci.content()
                    + "\n\n[Attachment resolution failed: " + e.getMessage() + "]");
        }
        List<dev.langchain4j.data.message.Content> blocks = new ArrayList<>();
        for (de.mhus.vance.brain.ai.attachment.ResolvedAttachment att : resolved) {
            try {
                blocks.add(de.mhus.vance.brain.ai.StandardAiChat.toContentBlock(
                        att, chatName, providerType, capabilities));
            } catch (de.mhus.vance.brain.ai.attachment.AttachmentException e) {
                log.warn("Arthur: attachment '{}' rejected by model '{}': {} — skipping",
                        att.originalFilename(), chatName, e.getMessage());
            }
        }
        blocks.add(dev.langchain4j.data.message.TextContent.from(uci.content()));
        return UserMessage.from(blocks);
    }

    /**
     * Looks up the process name for a {@code ProcessEvent}'s source id —
     * used so {@code <process-event>} markers carry the human-readable
     * name the LLM should pass to {@code process_steer / process_stop /
     * etc.} instead of the Mongo id.
     */
    private @Nullable String lookupProcessName(@Nullable String processId) {
        if (processId == null || processId.isBlank()) {
            return null;
        }
        return thinkProcessService.findById(processId)
                .map(ThinkProcessDocument::getName)
                .orElse(null);
    }

    /** Rendering helper that needs access to the {@code thinkProcessService}. */
    private String renderForLlm(SteerMessage m) {
        if (m instanceof SteerMessage.ProcessEvent pe) {
            StringBuilder sb = new StringBuilder();
            sb.append("<process-event sourceProcessId=\"")
                    .append(escapeAttr(pe.sourceProcessId()))
                    .append("\"");
            String sourceName = lookupProcessName(pe.sourceProcessId());
            if (sourceName != null && !sourceName.isBlank()) {
                sb.append(" sourceProcessName=\"")
                        .append(escapeAttr(sourceName))
                        .append("\"");
            }
            sb.append(" type=\"").append(pe.type().name().toLowerCase()).append("\">");
            if (pe.humanSummary() != null) {
                sb.append(escapeText(pe.humanSummary()));
            }
            sb.append("</process-event>");
            return sb.toString();
        }
        return renderStaticForLlm(m);
    }

    /**
     * Returns the LLM-facing rendering of one inbox message, or
     * {@code null} if the message has no separate rendering (the
     * UserChatInput case — already in chat history). Mirrors the
     * {@code <task-notification>} convention from Claude Code.
     */
    /**
     * Static rendering fallback for non-ProcessEvent messages. The
     * instance method {@link #renderForLlm(SteerMessage)} dispatches
     * here for everything that doesn't need the process-name lookup.
     */
    private static String renderStaticForLlm(SteerMessage m) {
        return switch (m) {
            case SteerMessage.UserChatInput uci -> null; // already in chat history
            case SteerMessage.ProcessEvent pe -> {
                // Should never hit this branch — the instance overload
                // above handles ProcessEvent. Keep for type-completeness.
                StringBuilder sb = new StringBuilder();
                sb.append("<process-event sourceProcessId=\"")
                        .append(escapeAttr(pe.sourceProcessId()))
                        .append("\" type=\"")
                        .append(pe.type().name().toLowerCase())
                        .append("\">");
                if (pe.humanSummary() != null) {
                    sb.append(escapeText(pe.humanSummary()));
                }
                sb.append("</process-event>");
                yield sb.toString();
            }
            case SteerMessage.ToolResult tr -> {
                StringBuilder sb = new StringBuilder();
                sb.append("<tool-result toolCallId=\"")
                        .append(escapeAttr(tr.toolCallId()))
                        .append("\" toolName=\"")
                        .append(escapeAttr(tr.toolName()))
                        .append("\" status=\"")
                        .append(tr.status().name().toLowerCase())
                        .append("\">");
                if (tr.error() != null) {
                    sb.append("error: ").append(escapeText(tr.error()));
                } else if (tr.result() != null) {
                    sb.append(escapeText(tr.result().toString()));
                }
                sb.append("</tool-result>");
                yield sb.toString();
            }
            case SteerMessage.ExternalCommand ec -> {
                StringBuilder sb = new StringBuilder();
                sb.append("<external-command name=\"")
                        .append(escapeAttr(ec.command()))
                        .append("\">")
                        .append(escapeText(String.valueOf(ec.params())))
                        .append("</external-command>");
                yield sb.toString();
            }
            case SteerMessage.InboxAnswer ia -> {
                StringBuilder sb = new StringBuilder();
                sb.append("<inbox-answer itemId=\"")
                        .append(escapeAttr(ia.inboxItemId()))
                        .append("\" type=\"")
                        .append(ia.itemType().name().toLowerCase())
                        .append("\" outcome=\"")
                        .append(ia.answer().getOutcome().name().toLowerCase())
                        .append("\">");
                if (ia.answer().getReason() != null) {
                    sb.append("reason: ").append(escapeText(ia.answer().getReason()));
                } else if (ia.answer().getValue() != null) {
                    sb.append(escapeText(String.valueOf(ia.answer().getValue())));
                }
                sb.append("</inbox-answer>");
                yield sb.toString();
            }
            case SteerMessage.PeerEvent pe -> {
                // PeerEvents only ever reach Arthur when she's
                // acting as the chat-machinery for the Eddie hub
                // engine (delegate target). Render them so the
                // LLM sees what a peer hub did and can react
                // appropriately. Other engines (Marvin, Vogon)
                // never receive PeerEvents — their switch-cases
                // stay no-op.
                StringBuilder sb = new StringBuilder();
                sb.append("<peer-event sourceEddieProcessId=\"")
                        .append(escapeAttr(pe.sourceEddieProcessId()))
                        .append("\" type=\"")
                        .append(pe.type().name().toLowerCase())
                        .append("\">")
                        .append(escapeText(pe.humanSummary()))
                        .append("</peer-event>");
                yield sb.toString();
            }
        };
    }

    private static String escapeAttr(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("\"", "&quot;");
    }

    private static String escapeText(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static ChatMessage toLangchain(ChatMessageDocument msg) {
        return switch (msg.getRole()) {
            case USER -> UserMessage.from(msg.getContent());
            case ASSISTANT -> AiMessage.from(msg.getContent());
            case SYSTEM -> SystemMessage.from(msg.getContent());
        };
    }

    /**
     * Engine-default base — used by {@link SystemPrompts#compose}
     * when the process has no recipe override (rare; the bundled
     * {@code arthur} recipe normally supplies the rich content).
     * Recipe content always wins via APPEND/OVERWRITE; the
     * bundled-recipe catalog is appended <em>after</em> compose so it
     * sits at the end of the system prompt regardless.
     */
    /**
     * Resolves the engine-default prompt for the current turn through the
     * tier-aware document cascade. Recipe params {@code promptDocument}
     * (base path) and {@code promptDocumentSmall} (optional explicit
     * small-variant path) override the engine defaults; the resolver
     * automatically derives a {@code -small} suffix and falls through to
     * the base path when no small variant exists, so engines don't need
     * to maintain two separate files unless they want differentiated
     * tiers.
     */
    private String engineDefaultPrompt(ThinkProcessDocument process, ModelSize modelSize) {
        String basePath = paramString(process, "promptDocument", DEFAULT_PROMPT_PATH);
        return enginePromptResolver.resolveForMode(
                process, basePath, process.getMode(), ENGINE_FALLBACK_PROMPT);
    }

    /**
     * Builds the bullet-list of recipes that gets appended to the
     * system prompt. Pulled fresh per call from
     * Renders the live TodoList as a Markdown block for the system
     * prompt. Empty list → empty string. Status markers mirror the
     * foot-side rendering: {@code [ ]} pending, {@code [~]} active,
     * {@code [✓]} done.
     */
    private String buildTodoListBlock(ThinkProcessDocument process) {
        java.util.List<de.mhus.vance.api.thinkprocess.TodoItem> todos = process.getTodos();
        if (todos == null || todos.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## Current TodoList (mode=")
                .append(process.getMode() == null
                        ? "NORMAL" : process.getMode().name())
                .append(")\n\n");
        for (de.mhus.vance.api.thinkprocess.TodoItem t : todos) {
            de.mhus.vance.api.thinkprocess.TodoStatus s = t.getStatus() == null
                    ? de.mhus.vance.api.thinkprocess.TodoStatus.PENDING
                    : t.getStatus();
            String marker = switch (s) {
                case PENDING -> "[ ]";
                case IN_PROGRESS -> "[~]";
                case COMPLETED -> "[✓]";
            };
            sb.append(marker).append(' ')
                    .append("(id=").append(t.getId() == null ? "" : t.getId()).append(") ");
            String content = t.getContent() == null ? "" : t.getContent();
            if (s == de.mhus.vance.api.thinkprocess.TodoStatus.IN_PROGRESS
                    && t.getActiveForm() != null && !t.getActiveForm().isBlank()) {
                content = t.getActiveForm();
            }
            sb.append(content).append('\n');
        }
        sb.append("\nGuidance: pick the first item that is **not [✓] "
                + "COMPLETED**. If it's [ ] PENDING, your next TODO_UPDATE "
                + "should set it IN_PROGRESS. If it's [~] IN_PROGRESS, do "
                + "the actual work for that step now (e.g. call "
                + "client_file_write / client_file_edit / client_exec_run "
                + "to create / edit the relevant files), then TODO_UPDATE "
                + "it to COMPLETED in the same turn or the next.\n\n"
                + "**Hard rules — never regress state:**\n"
                + "- NEVER emit TODO_UPDATE that downgrades an item: "
                + "[✓] COMPLETED stays COMPLETED forever; [~] IN_PROGRESS "
                + "must not go back to [ ] PENDING.\n"
                + "- If you see [✓] COMPLETED items above, that work is "
                + "**already done in a previous turn** — DO NOT redo it. "
                + "Skip past them to the first non-COMPLETED row.\n"
                + "- If everything is [✓] COMPLETED, emit ANSWER with a "
                + "brief summary of what was done. Do not look for "
                + "additional work the plan didn't list.\n");
        return sb.toString();
    }

    /**
     * Builds the bullet-list of recipes that gets appended to the
     * system prompt. Pulled fresh per call from
     * {@link RecipeLoader#listAll} so tenant- and project-recipes are
     * included alongside the bundled defaults — same source as
     * {@code recipe_list} at runtime.
     */
    private String buildRecipeCatalogSection(ThinkProcessDocument process) {
        // No projectId on a ThinkProcessDocument — RecipeLoader falls back
        // to the _vance system project, which is what Arthur wants here:
        // the catalog in the system prompt covers tenant-wide + bundled
        // recipes. Project-scoped recipes show up at runtime through
        // recipe_list (which has the full ToolInvocationContext).
        java.util.List<ResolvedRecipe> recipes = recipeLoader.listAll(
                process.getTenantId(), null);
        if (recipes.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## Available worker recipes\n\n")
                .append("Use one of these names for `process_create(recipe=…)`. "
                        + "Call `recipe_list` at runtime if you want the live catalog.\n\n");
        for (ResolvedRecipe r : recipes) {
            sb.append("- `").append(r.name()).append("` — ")
                    .append(oneLine(r.description())).append("\n");
        }
        return sb.toString();
    }

    private static String oneLine(String s) {
        if (s == null) return "";
        String trimmed = s.trim().replace("\n", " ").replaceAll("\\s+", " ");
        return trimmed;
    }

    // ──────────────────── Config resolve (mirrors Ford) ────────────────────

    private static AiChatConfig resolveAiConfig(
            ThinkProcessDocument process,
            SettingService settings,
            AiModelResolver modelResolver) {
        String tenantId = process.getTenantId();
        // params.model wins (recipe alias or direct provider:model).
        // params.provider is honoured for backward-compat: if it's
        // set and params.model contains no colon, we synthesise
        // "<provider>:<model>". Otherwise we fall through to the
        // resolver's own default-handling.
        String paramModel = paramString(process, "model", null);
        String paramProvider = paramString(process, "provider", null);
        String spec;
        if (paramModel != null && paramModel.contains(":")) {
            spec = paramModel;
        } else if (paramModel != null && paramProvider != null) {
            spec = paramProvider + ":" + paramModel;
        } else if (paramModel != null) {
            // Bare model name with no colon and no separate provider —
            // assume it's an alias key in the default namespace, which
            // also covers the legacy "claude-sonnet-4-5" style by
            // routing through the alias map.
            spec = "default:" + paramModel;
        } else {
            spec = null; // resolver picks tenant default
        }
        AiModelResolver.Resolved resolved = modelResolver.resolveOrDefault(
                spec, tenantId, process.getProjectId(), process.getId());

        String apiKeySetting = String.format(
                SETTING_PROVIDER_API_KEY_FMT, resolved.provider());
        String apiKey = settings.getDecryptedPasswordCascade(
                tenantId, process.getProjectId(), process.getId(), apiKeySetting);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "No API key configured for provider '" + resolved.provider()
                            + "' (tenant='" + tenantId
                            + "', setting='" + apiKeySetting + "')");
        }
        return new AiChatConfig(resolved.provider(), resolved.modelName(), apiKey);
    }

    // ──────────────────── engineParams helpers ────────────────────

    private static @Nullable Object param(ThinkProcessDocument process, String key) {
        Map<String, Object> p = process.getEngineParams();
        return p == null ? null : p.get(key);
    }

    private static @Nullable String paramString(
            ThinkProcessDocument process, String key, @Nullable String fallback) {
        Object v = param(process, key);
        return v instanceof String s && !s.isBlank() ? s : fallback;
    }

    private static String nonBlankOr(@Nullable String candidate, String fallback) {
        return candidate != null && !candidate.isBlank() ? candidate : fallback;
    }

    /** {@link String#format} that survives misformatted templates. */
    private static String formatSafe(String template, Object... args) {
        try {
            return String.format(template, args);
        } catch (RuntimeException e) {
            log.warn("Arthur: validator template format failed ({}), using verbatim",
                    e.toString());
            return template;
        }
    }

    private static int paramInt(
            ThinkProcessDocument process, String key, int fallback) {
        Object v = param(process, key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s.trim()); }
            catch (NumberFormatException e) { return fallback; }
        }
        return fallback;
    }

    private static boolean paramBool(
            ThinkProcessDocument process, String key, boolean fallback) {
        Object v = param(process, key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s.trim());
        return fallback;
    }
}
