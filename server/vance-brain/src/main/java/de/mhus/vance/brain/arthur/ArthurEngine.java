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
import de.mhus.vance.brain.tools.ToolException;
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
     * Tools the runtime may dispatch in Arthur's scope. Two kinds:
     *
     * <ul>
     *   <li><b>Read-only</b> ({@link #LLM_VISIBLE_TOOLS}) — exposed
     *       to the LLM so it can look things up while deliberating
     *       (recipes, manuals, sibling status, …).</li>
     *   <li><b>Action-internal</b> ({@code process_create},
     *       {@code process_steer}, …) — invoked by Arthur's
     *       {@code handleAction} dispatch, never shown to the LLM.
     *       The LLM emits a structured {@code arthur_action}
     *       ({@code DELEGATE} / etc.); the engine layer translates
     *       that into the corresponding tool invocation. Keeping
     *       these in {@code ALLOWED_TOOLS} satisfies the dispatcher's
     *       allow-filter for the engine's own programmatic calls.</li>
     * </ul>
     */
    private static final Set<String> ALLOWED_TOOLS = Set.of(
            "whoami",
            "process_create",
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
            "inbox_post");

    /**
     * Subset of {@link #ALLOWED_TOOLS} the LLM is allowed to see and
     * call directly each turn. Excludes everything that has an
     * equivalent in the structured-action vocabulary — those are
     * routed through {@code arthur_action} ({@code DELEGATE} etc.)
     * instead, removing the free-form-tool / structured-action
     * conflict.
     */
    private static final Set<String> LLM_VISIBLE_TOOLS = Set.of(
            "whoami",
            "process_list",
            "process_status",
            "recipe_list",
            "recipe_describe",
            "manual_list",
            "manual_read",
            "inbox_post");

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
    private final de.mhus.vance.brain.ai.EngineChatFactory engineChatFactory;
    private final de.mhus.vance.brain.skill.SkillTriggerMatcher skillTriggerMatcher;
    private final de.mhus.vance.brain.enginemessage.EngineMessageRouter messageRouter;

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
            de.mhus.vance.brain.enginemessage.EngineMessageRouter messageRouter) {
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
        while (true) {
            // Cooperative halt-check between drain iterations. The
            // pause-task on the lane is queued *behind* this runTurn
            // — without yielding here, a busy drain-loop would keep
            // chewing through pendings and the queued status-PAUSED
            // task would never get to fire. See
            // ThinkProcessDocument.haltRequested.
            if (thinkProcessService.isHaltRequested(process.getId())) {
                log.info("Arthur.runTurn id='{}' — halt requested, yielding",
                        process.getId());
                return;
            }
            List<SteerMessage> drained = ctx.drainPending();
            if (drained.isEmpty()) {
                return;
            }
            runTurnFor(process, ctx, drained);
        }
    }

    // ──────────────────── One turn ────────────────────

    private void runTurnFor(
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
            return;
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
                    process, chatLog, inbox, effectiveSize);
            int maxIters = paramInt(process, "maxIterations",
                    arthurProperties.getMaxToolIterations());
            boolean validation = paramBool(process, "validation", false);
            log.debug("Arthur.turn id='{}' inbox={} historyMsgs={} model={} maxIters={} validation={}",
                    process.getId(), inbox.size(), messages.size(),
                    config.modelName(), maxIters, validation);

            String modelAlias = config.provider() + ":" + config.modelName();

            // Restrict the LLM-visible tool set to read-only tools
            // (recipe_list, manual_read, …). Action-internal tools
            // (process_create, process_steer, …) stay in the dispatcher
            // for engine-internal invocation but are NOT shown to the
            // LLM — orchestration goes through the structured
            // {@code arthur_action} call instead.
            List<ToolSpecification> readToolSpecs = toolSpecs.stream()
                    .filter(t -> LLM_VISIBLE_TOOLS.contains(t.name()))
                    .toList();

            ActionLoopResult loopResult = runStructuredActionLoop(
                    aiChat, readToolSpecs, tools, messages, ctx, process,
                    maxIters, modelAlias);

            ActionTurnOutcome outcome;
            if (loopResult.isAction()) {
                outcome = handleAction(loopResult.action(), process, ctx);
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
            if (chatMessage != null && !chatMessage.isBlank()) {
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
        return switch (action.type()) {
            case ArthurActionSchema.TYPE_ANSWER   -> handleAnswer(action);
            case ArthurActionSchema.TYPE_ASK_USER -> handleAskUser(action);
            case ArthurActionSchema.TYPE_DELEGATE -> handleDelegate(action, process, ctx);
            case ArthurActionSchema.TYPE_RELAY    -> handleRelay(action, process, ctx);
            case ArthurActionSchema.TYPE_WAIT     -> handleWait(action);
            case ArthurActionSchema.TYPE_REJECT   -> handleReject(action);
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
     * Spawn a worker via the {@code process_create} tool. The LLM
     * provides the recipe preset and a self-contained prompt; the
     * engine derives a unique worker name and invokes the spawn
     * programmatically — no LLM-generated names, no hallucination
     * risk. The optional {@code message} is shown to the user as a
     * pre-announcement; absent message = silent spawn (no chat
     * append, no filler).
     */
    private ActionTurnOutcome handleDelegate(
            de.mhus.vance.brain.thinkengine.action.EngineAction action,
            ThinkProcessDocument process,
            ThinkEngineContext ctx) {
        String preset = action.stringParam(ArthurActionSchema.PARAM_PRESET);
        String prompt = action.stringParam(ArthurActionSchema.PARAM_PROMPT);
        if (preset == null || preset.isBlank() || prompt == null || prompt.isBlank()) {
            log.warn("Arthur id='{}' DELEGATE missing preset/prompt — reason='{}'",
                    process.getId(), action.reason());
            return new ActionTurnOutcome(
                    "Sorry — internal: tried to delegate without preset or prompt. "
                            + "Reason was: " + action.reason(),
                    true);
        }

        String workerName = preset + "-" + java.util.UUID.randomUUID().toString().substring(0, 6);
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("recipe", preset);
            params.put("name", workerName);
            params.put("goal", prompt);
            params.put("steerContent", prompt);
            ctx.tools().invoke("process_create", params);
            log.info("Arthur id='{}' DELEGATE recipe='{}' worker='{}' reason='{}'",
                    process.getId(), preset, workerName, summariseReason(action.reason()));
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
            ModelSize modelSize) {
        List<ChatMessage> messages = new ArrayList<>();
        String base = SystemPrompts.compose(process,
                engineDefaultPrompt(process, modelSize), modelSize);
        String withCatalog = base + buildRecipeCatalogSection(process);
        // Append the project-memory block (memory.* settings cascade) so
        // the user can pin language / tone / persona / arbitrary key:value
        // hints at any scope without rewriting the recipe prompt.
        String memoryBlock = memoryContextLoader.composeBlock(process);
        if (memoryBlock != null && !memoryBlock.isBlank()) {
            withCatalog = withCatalog + "\n\n" + memoryBlock;
        }
        messages.add(SystemMessage.from(withCatalog));

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

        // History up to the just-appended user inputs is the "old"
        // conversation. The trailing N user messages are the current
        // turn — they're already represented as USER chat messages.
        for (ChatMessageDocument msg : history) {
            messages.add(toLangchain(msg));
        }

        // Now append the non-user inbox items (events, tool results,
        // external commands) as user-role messages with the XML
        // wrapper Arthur's prompt is trained on.
        for (SteerMessage m : inbox) {
            String wrapped = renderForLlm(m);
            if (wrapped != null) {
                messages.add(UserMessage.from(wrapped));
            }
        }

        return messages;
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
        String smallOverride = paramString(process, "promptDocumentSmall", null);
        return enginePromptResolver.resolveTiered(
                process, basePath, smallOverride, modelSize, ENGINE_FALLBACK_PROMPT);
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
