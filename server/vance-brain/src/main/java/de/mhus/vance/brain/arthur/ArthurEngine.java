package de.mhus.vance.brain.arthur;

import de.mhus.vance.api.chat.ChatMessageChunkData;
import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiChatException;
import de.mhus.vance.brain.ai.ChatBehaviorBuilder;
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
import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.brain.recipe.ResolvedRecipe;
import de.mhus.vance.brain.thinkengine.ParentReport;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.tools.context.RespondTool;
import de.mhus.vance.brain.thinkengine.SystemPrompts;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.brain.usermemory.UserMemoryService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
     * Arthur's engine-base allow-set is intentionally <b>empty</b>
     * (unrestricted). The recipe cascade drives all per-mode
     * classification (primary / deferred / removed) via
     * {@link de.mhus.vance.brain.tools.ContextToolsApi#classify} —
     * when {@code base.isEmpty()} that function expands the pool to
     * every dispatchable tool and applies the recipe's
     * Add/Remove/Defer overlays on top, gated by the active
     * profile and per-tool
     * {@link de.mhus.vance.toolpack.Tool#allowedForProfile}
     * declarations.
     *
     * <p>That keeps the source of truth in one place — {@code
     * arthur.yaml} — and removes the per-tool maintenance burden of
     * a hard-coded Java list that silently misses any newly-added
     * tool. Destructive / executive scoping happens via label
     * filters ({@code @executive}, {@code @write},
     * {@code @side-effect}, …) and a handful of explicit excludes in
     * the recipe; see {@code arthur.yaml} for the canonical
     * configuration.
     */
    private static final Set<String> ALLOWED_TOOLS = Set.of();

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
            ArthurActionSchema.TYPE_TODO_UPDATE,
            ArthurActionSchema.TYPE_DISCOVER);

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
    private static final String DEFAULT_PROMPT_PATH = "_vance/prompts/arthur-prompt.md";

    private final ThinkProcessService thinkProcessService;
    private final ArthurProperties arthurProperties;
    private final RecipeLoader recipeLoader;
    private final ModelCatalog modelCatalog;
    private final de.mhus.vance.brain.memory.MemoryContextLoader memoryContextLoader;
    private final de.mhus.vance.brain.thinkengine.EnginePromptResolver enginePromptResolver;
    private final de.mhus.vance.brain.ai.EngineChatFactory engineChatFactory;
    private final de.mhus.vance.brain.skill.SkillTriggerMatcher skillTriggerMatcher;
    private final de.mhus.vance.brain.enginemessage.EngineMessageRouter messageRouter;
    private final PlanModeEventEmitter planModeEventEmitter;
    private final de.mhus.vance.brain.thinkengine.plan.PlanModeService planModeService;
    private final de.mhus.vance.brain.ai.attachment.AttachmentResolver attachmentResolver;
    private final de.mhus.vance.shared.workspace.WorkspaceService workspaceService;
    /**
     * Used by {@link #summarizeForParent} to pull the last ASSISTANT
     * chat message and re-emit its {@code meta.askUserOptions} (when
     * present) into the parent-event payload — the structured
     * transport for cross-engine ASK_USER pickers (see
     * specification/eddie-engine.md §5.8).
     */
    private final de.mhus.vance.shared.chat.ChatMessageService chatMessageService;
    private final de.mhus.vance.brain.prak.HistoryStrengthFilter historyStrengthFilter;
    private final de.mhus.vance.brain.memory.MemoryCompactionService memoryCompactionService;
    private final UserMemoryService userMemoryService;
    private final de.mhus.vance.brain.discovery.DiscoveryService discoveryService;
    private final de.mhus.vance.brain.tools.client.CortexPromptResolver cortexPromptResolver;
    private final de.mhus.vance.brain.chat.CollabContextResolver collabContextResolver;
    private final de.mhus.vance.brain.applications.ActiveAppPromptResolver activeAppPromptResolver;
    private final ObjectMapper objectMapper;
    private final de.mhus.vance.brain.thinkengine.action.ActionLoopJudgeService
            actionLoopJudgeService;

    /**
     * Per-process flag tracking whether the in-flight turn was triggered
     * by a fresh USER_CHAT_INPUT (vs. purely by an in-bound process-event
     * like a child closing). Populated at the start of
     * {@link #runTurnFor} and consulted by {@link #handleAction} to gate
     * spawn-actions: an event-only turn must not emit DELEGATE / ASK_USER,
     * because the user didn't actually ask for more work — the LLM is
     * just reacting to an internal lifecycle signal and would spam new
     * children otherwise (the classic infinite-respawn cascade observed
     * when Slart children close without producing the expected artifact).
     *
     * <p>ConcurrentHashMap because runTurn for different processes runs
     * on different virtual threads via the lane scheduler. Entries are
     * cleaned up in the {@code finally} block of {@link #runTurnFor}.
     */
    private final ConcurrentMap<String, Boolean> currentTurnHadUserInput =
            new ConcurrentHashMap<>();

    /**
     * Per-process map of {@code eventId → SteerMessage.ProcessEvent}
     * built fresh at the start of each {@link #runTurnFor} from the
     * drained inbox. The RELAY handler looks up the LLM-supplied
     * {@code eventRef} here and rejects anything that wasn't in the
     * current drain — so a stale {@code <process-event>} from a
     * previous turn (the Marvin-spawn / Ford-stale-relay bug pattern)
     * can no longer be relayed as if it were fresh worker output.
     * See {@code planning/arthur-process-event-attribution.md}.
     *
     * <p>ConcurrentHashMap to match {@link #currentTurnHadUserInput};
     * entries are cleaned up in the {@code finally} block of
     * {@link #runTurnFor}.
     */
    private final ConcurrentMap<String, Map<String, SteerMessage.ProcessEvent>>
            currentTurnEventsByRef = new ConcurrentHashMap<>();

    /**
     * Action types Arthur is forbidden from emitting on a turn that was
     * triggered without any USER_CHAT_INPUT. RELAY / ANSWER / ASK_USER /
     * WAIT / REJECT stay allowed — those report state to the user
     * (or ask for clarification) without spawning anything, which is
     * exactly what an event-triggered turn should do. ASK_USER was
     * originally listed here but removed once we confirmed it's a
     * plain conversational question that pauses the lane on BLOCKED —
     * no spawn, no cascade.
     */
    private static final Set<String> SPAWN_ACTIONS_FORBIDDEN_ON_EVENT_TURNS = Set.of(
            ArthurActionSchema.TYPE_DELEGATE);

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
            de.mhus.vance.brain.thinkengine.plan.PlanModeService planModeService,
            de.mhus.vance.brain.ai.attachment.AttachmentResolver attachmentResolver,
            de.mhus.vance.brain.thinkengine.SystemPromptComposer composer,
            de.mhus.vance.shared.workspace.WorkspaceService workspaceService,
            de.mhus.vance.shared.chat.ChatMessageService chatMessageService,
            de.mhus.vance.brain.prak.HistoryStrengthFilter historyStrengthFilter,
            de.mhus.vance.brain.memory.MemoryCompactionService memoryCompactionService,
            UserMemoryService userMemoryService,
            @org.springframework.context.annotation.Lazy
                    de.mhus.vance.brain.discovery.DiscoveryService discoveryService,
            de.mhus.vance.brain.tools.client.CortexPromptResolver cortexPromptResolver,
            de.mhus.vance.brain.chat.CollabContextResolver collabContextResolver,
            de.mhus.vance.brain.applications.ActiveAppPromptResolver activeAppPromptResolver,
            de.mhus.vance.brain.thinkengine.action.ActionLoopJudgeService actionLoopJudgeService) {
        super(streamingProperties, llmCallTracker, objectMapper, composer);
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
        this.planModeService = planModeService;
        this.attachmentResolver = attachmentResolver;
        this.workspaceService = workspaceService;
        this.chatMessageService = chatMessageService;
        this.historyStrengthFilter = historyStrengthFilter;
        this.memoryCompactionService = memoryCompactionService;
        this.userMemoryService = userMemoryService;
        this.discoveryService = discoveryService;
        this.cortexPromptResolver = cortexPromptResolver;
        this.collabContextResolver = collabContextResolver;
        this.activeAppPromptResolver = activeAppPromptResolver;
        this.objectMapper = objectMapper;
        this.actionLoopJudgeService = actionLoopJudgeService;
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

    /**
     * Parent-event report. On {@code BLOCKED} transitions (typical
     * trigger: Arthur emitted {@code ASK_USER}), reach into the chat
     * history and re-emit the last ASSISTANT message's
     * {@code meta.askUserOptions} into {@link ParentReport#payload}
     * if present — that gives Eddie's RELAY handler the structured
     * options it needs to re-render the picker on the user-facing
     * side. See specification/eddie-engine.md §5.8.
     *
     * <p>Other event types fall through to the default generic
     * summary.
     */
    @Override
    public ParentReport summarizeForParent(
            ThinkProcessDocument process, ProcessEventType eventType) {
        if (eventType != ProcessEventType.BLOCKED) {
            return ParentReport.of(genericChildSummary(process, eventType));
        }
        Map<String, Object> payload = extractAskUserOptionsPayload(process);
        if (payload == null) {
            return ParentReport.of(genericChildSummary(process, eventType));
        }
        return new ParentReport(
                "Child process " + process.getId()
                        + " status=" + eventType.name().toLowerCase()
                        + " — awaiting user clarification (picker options attached)",
                payload);
    }

    /** Same text the {@link ThinkEngine#summarizeForParent default} produces. */
    private static String genericChildSummary(
            ThinkProcessDocument process, ProcessEventType eventType) {
        return "Child process " + process.getId()
                + " status=" + eventType.name().toLowerCase();
    }

    /**
     * Reads the last ASSISTANT chat message of {@code process} and
     * returns a payload map carrying its {@code askUserOptions} meta
     * — or {@code null} if no such message or no options. Defensive:
     * any read error returns null and is logged at debug-level.
     */
    @SuppressWarnings("unchecked")
    private @Nullable Map<String, Object> extractAskUserOptionsPayload(ThinkProcessDocument process) {
        try {
            java.util.List<de.mhus.vance.shared.chat.ChatMessageDocument> history =
                    chatMessageService.activeHistory(
                            process.getTenantId(),
                            process.getSessionId(),
                            process.getId());
            de.mhus.vance.shared.chat.ChatMessageDocument lastAssistant = null;
            for (int i = history.size() - 1; i >= 0; i--) {
                de.mhus.vance.shared.chat.ChatMessageDocument m = history.get(i);
                if (m.getRole() == de.mhus.vance.api.chat.ChatRole.ASSISTANT) {
                    lastAssistant = m;
                    break;
                }
            }
            if (lastAssistant == null || lastAssistant.getMeta() == null) {
                return null;
            }
            Object rawOptions = lastAssistant.getMeta().get(
                    de.mhus.vance.shared.chat.ChatMessageDocument.META_ASK_USER_OPTIONS);
            if (!(rawOptions instanceof java.util.List<?> list) || list.isEmpty()) {
                return null;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(
                    de.mhus.vance.shared.chat.ChatMessageDocument.META_ASK_USER_OPTIONS,
                    list);
            return payload;
        } catch (RuntimeException e) {
            log.debug("Arthur.summarizeForParent: failed to extract askUserOptions for "
                    + "process='{}': {}", process.getId(), e.toString());
            return null;
        }
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
        currentTurnHadUserInput.remove(process.getId());
        currentTurnEventsByRef.remove(process.getId());
    }

    @Override
    public void stop(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.info("Arthur.stop id='{}'", process.getId());
        thinkProcessService.closeProcess(process.getId(), CloseReason.STOPPED);
        currentTurnHadUserInput.remove(process.getId());
        currentTurnEventsByRef.remove(process.getId());
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

        // (Auto-WAIT removed alongside the REPLY-channel migration.
        // Parking-only BLOCKED events no longer reach the parent —
        // ParentNotificationListener#mapStatus(BLOCKED)=null — so the
        // predicate would never fire. The original noise source it
        // guarded against — phantom ASK_USER/process_steer on stale
        // Active-Workers snapshots — is also gone because the LLM no
        // longer sees a `<process-event type="blocked">` for parking
        // children. See planning/process-engine-reply-channel.md §5.)

        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.RUNNING);
        // Per-turn flag for handleAction: was this turn triggered by a
        // fresh user message, or purely by an in-bound process-event?
        // Event-only turns must not emit spawn-actions (DELEGATE /
        // ASK_USER) — see SPAWN_ACTIONS_FORBIDDEN_ON_EVENT_TURNS.
        boolean hadUserInput = false;
        // RELAY-attribution map: { shortToken → ProcessEvent } drawn
        // from THIS drain so handleRelay can validate the LLM's
        // eventRef against fresh events only. Keys are short stable
        // tokens (ev1, ev2, ...) assigned in inbox-order, not the
        // underlying UUIDs. LLMs handle 3-char tokens reliably; 36-
        // char UUIDs invite hallucination. The full UUID stays in the
        // event for logs / cross-pod tracing.
        Map<String, SteerMessage.ProcessEvent> eventsByRef = new LinkedHashMap<>();
        int eventCounter = 0;
        for (SteerMessage m : inbox) {
            if (m instanceof SteerMessage.UserChatInput uci
                    && uci.content() != null && !uci.content().isBlank()) {
                hadUserInput = true;
            }
            if (m instanceof SteerMessage.ProcessEvent pe
                    && pe.eventId() != null && !pe.eventId().isBlank()) {
                String token = "ev" + (++eventCounter);
                eventsByRef.put(token, pe);
            }
            // REPLY-channel pilot: surface explicit Reply messages
            // through the same eventRef machinery so handleRelay can
            // resolve them. Synthesize a ProcessEvent with the
            // legacy BEGIN/END-CHILD-REPLY wrapper around the content
            // — unwrapChildReply already knows how to peel that. The
            // synthetic event uses type=BLOCKED so the existing RELAY
            // path treats it identically to a worker BLOCKED-event.
            if (m instanceof SteerMessage.Reply r
                    && r.content() != null && !r.content().isBlank()) {
                String token = "ev" + (++eventCounter);
                String wrapped = "Child reply from "
                        + (r.sourceProcessName() == null
                                ? r.sourceProcessId() : r.sourceProcessName())
                        + "\n\nLast assistant reply from this child (verbatim):\n"
                        + "--- BEGIN CHILD REPLY ---\n"
                        + r.content()
                        + "\n--- END CHILD REPLY ---";
                SteerMessage.ProcessEvent synthetic = new SteerMessage.ProcessEvent(
                        r.at(),
                        r.idempotencyKey(),
                        r.sourceProcessId(),
                        de.mhus.vance.api.thinkprocess.ProcessEventType.BLOCKED,
                        wrapped,
                        r.payload(),
                        java.util.UUID.randomUUID().toString(),
                        r.inResponseToAt());
                eventsByRef.put(token, synthetic);
            }
        }
        currentTurnHadUserInput.put(process.getId(), hadUserInput);
        currentTurnEventsByRef.put(process.getId(), eventsByRef);

        // Reconcile workerLinks against the inbox: REPLY/PROGRESS-style
        // events refresh lastSeen + workerStatus; terminal events
        // (DONE/FAILED/STOPPED) drop the link entirely and clear the
        // delegation pointer if it matched the closed worker.
        reconcileWorkerLinksFromInbox(process, inbox);

        // Default IDLE on any abnormal exit — matches legacy lifecycle.
        // Reset to outcome.awaitingUserInput() inside the try.
        boolean awaitingUserInput = false;
        // True iff this turn exited through the action-loop fallback
        // path (LLM couldn't produce a parseable action within the
        // budget). For sub-process workers this triggers a terminal
        // close — the worker has emitted its best free-text reply
        // already, and leaving it BLOCKED would pin the parent's
        // {@code activeDelegationWorkerId} to a dead-end. Top-level
        // chat processes keep the legacy BLOCKED-awaits-user behaviour.
        boolean actionLoopFallback = false;
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
                            .senderUserId(uci.fromUser())
                            .senderDisplayName(uci.fromUserDisplayName())
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
            ModelInfo modelInfo = modelCatalog.lookupOrDefault(
                    process.getTenantId(), process.getProjectId(),
                    config.providerInstance(), config.provider(), config.modelName());
            // params.modelSize: SMALL/LARGE force the prompt variant
            // independently of the catalog; AUTO/missing falls back
            // to the catalog's classification.
            ModelSize effectiveSize = ModelSize.parseOrAuto(
                    paramString(process, "modelSize", null), modelInfo.size());

            List<ChatMessage> messages = buildPromptMessages(
                    process, chatLog, inbox, effectiveSize, ctx, config, modelInfo);
            // Strength-aware compaction trigger: SOFT/HARD/EMERGENCY
            // based on est-tokens vs context window. Compacts via
            // MemoryCompactionService and rebuilds the prompt if so.
            de.mhus.vance.brain.memory.CompactionResult cr =
                    memoryCompactionService.compactIfNeeded(process, config, messages, modelInfo);
            if (cr.compacted()) {
                log.info("Arthur.turn id='{}' compaction ok: {} msgs → {} chars (memory='{}')",
                        process.getId(), cr.messagesCompacted(),
                        cr.summaryChars(), cr.memoryId());
                messages = buildPromptMessages(
                        process, chatLog, inbox, effectiveSize, ctx, config, modelInfo);
            }
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
            // ContextToolsApi.primaryAsLc4j() returns. The action loop
            // re-derives this on every iteration so describe_tool
            // activations propagate within the turn.
            ActionLoopResult loopResult = runStructuredActionLoop(
                    aiChat, ContextToolsApi::primaryAsLc4j,
                    messages, ctx, process, maxIters, modelAlias,
                    modelInfo.actionLoopCorrections());

            // Action-loop judge: when the loop max-iters out (and the
            // plan-mode-yield path below isn't applicable), consult the
            // judge to decide between extending the budget or
            // synthesising an answer from what's already gathered.
            // Without this, the legacy fallback surfaces the LLM's most
            // recent free-text — typically a mid-research "let me look
            // that up" placeholder — as the user-facing reply.
            //
            // The extension is capped to keep cost bounded: at most
            // {@code JUDGE_MAX_EXTENSIONS} extra rounds of
            // {@code JUDGE_EXTENSION_ITERS} iterations each. Plan-mode
            // turns are skipped because the engine's own multi-turn
            // continuation already handles their iteration overrun.
            int extensionsLeft = de.mhus.vance.brain.thinkengine.action.ActionLoopJudgeHelpers
                    .JUDGE_MAX_EXTENSIONS;
            while ("max-iters".equals(loopResult.fallbackReason())
                    && !de.mhus.vance.brain.thinkengine.action.ActionLoopJudgeHelpers
                            .isPlanModeYieldCase(process, loopResult)) {
                de.mhus.vance.brain.thinkengine.action.ActionLoopJudgeService.JudgeRequest req =
                        new de.mhus.vance.brain.thinkengine.action.ActionLoopJudgeService.JudgeRequest(
                                process,
                                de.mhus.vance.brain.thinkengine.action.ActionLoopJudgeHelpers
                                        .lastUserGoal(inbox, process),
                                loopResult.fallbackText() == null
                                        ? "" : loopResult.fallbackText(),
                                de.mhus.vance.brain.thinkengine.action.ActionLoopJudgeHelpers
                                        .extractToolCallNames(messages),
                                loopResult.toolInvocations(),
                                extensionsLeft);
                de.mhus.vance.brain.thinkengine.action.ActionLoopJudgeService.Judgment j =
                        actionLoopJudgeService.judge(req);
                if (j.extend() && extensionsLeft > 0) {
                    log.info("Arthur.turn id='{}' judge extends action loop "
                                    + "(+{} iters, {} extension(s) left after this, reason='{}')",
                            process.getId(),
                            de.mhus.vance.brain.thinkengine.action.ActionLoopJudgeHelpers
                                    .JUDGE_EXTENSION_ITERS,
                            extensionsLeft - 1, j.reason());
                    extensionsLeft--;
                    loopResult = runStructuredActionLoop(
                            aiChat, ContextToolsApi::primaryAsLc4j,
                            messages, ctx, process,
                            de.mhus.vance.brain.thinkengine.action.ActionLoopJudgeHelpers
                                    .JUDGE_EXTENSION_ITERS,
                            modelAlias, modelInfo.actionLoopCorrections());
                    continue;
                }
                log.info("Arthur.turn id='{}' judge synthesises "
                                + "(answer-chars={}, reason='{}')",
                        process.getId(),
                        j.synthesizedAnswer() == null ? 0 : j.synthesizedAnswer().length(),
                        j.reason());
                loopResult = new ActionLoopResult(
                        null, j.synthesizedAnswer(),
                        "judge-synthesize", null, loopResult.toolInvocations());
                break;
            }

            ActionTurnOutcome outcome;
            // actionType is non-null only when the LLM produced a parseable
            // action (success path). Free-text fallbacks below leave it
            // null; the Web-UI then renders them as plain Markdown.
            String actionType = null;
            if (loopResult.isAction()) {
                actionType = loopResult.action().type();
                outcome = handleAction(loopResult.action(), process, ctx);
                // Post-LEARN consolidation: small LLM pass that resolves
                // contradictions / trims the just-updated persona-or-
                // facts file. Same pattern as Eddie; runs only after a
                // successful LEARN so other actions stay cheap.
                if (ArthurActionSchema.TYPE_LEARN.equals(loopResult.action().type())) {
                    runLearnConsolidation(loopResult.action(), aiChat,
                            process, ctx, modelAlias);
                }
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
                actionLoopFallback = true;
                String text = loopResult.fallbackText();
                if (text != null && !text.isBlank()) {
                    // LLM emitted free text but no action — surface it
                    // as the user-facing reply rather than the internal
                    // diagnostic. The validator gave it
                    // actionLoopCorrections chances; this is the best
                    // we can do without making something up.
                    outcome = new ActionTurnOutcome(text, true);
                } else if (process.getMode()
                        == de.mhus.vance.api.thinkprocess.ProcessMode.EXECUTING
                        && allTodosCompleted(process)) {
                    // Graceful plan-completion close: tool calls ran
                    // until everything in the plan was done, then the
                    // model went silent (Gemini Pro empty STOP after
                    // long tool chains). Don't leak the "internal:
                    // action loop ..." string — synthesise a brief
                    // summary from the TodoList so the user sees a
                    // real reply. Mirror of EddieEngine.runTurnFor.
                    outcome = new ActionTurnOutcome(
                            renderPlanCompletionSummary(process), true);
                } else {
                    outcome = new ActionTurnOutcome(
                            "_Mir ist gerade die Spur verloren gegangen "
                                    + "— sag mir kurz wo es weitergehen soll._",
                            true);
                    log.warn("Arthur.turn id='{}' action-loop fallback with no usable "
                                    + "text (reason={}) — posting placeholder reply",
                            process.getId(), loopResult.fallbackReason());
                }
            }
            awaitingUserInput = outcome.awaitingUserInput();

            String chatMessage = outcome.chatMessage();
            boolean appendedChat = chatMessage != null && !chatMessage.isBlank();
            if (appendedChat) {
                ChatMessageDocument.ChatMessageDocumentBuilder cmBuilder = ChatMessageDocument.builder()
                        .tenantId(process.getTenantId())
                        .sessionId(process.getSessionId())
                        .thinkProcessId(process.getId())
                        .role(ChatRole.ASSISTANT)
                        .content(chatMessage);
                Map<String, Object> outcomeMeta = outcome.chatMessageMeta();
                Map<String, Object> mergedMeta = null;
                if (outcomeMeta != null && !outcomeMeta.isEmpty()) {
                    mergedMeta = new LinkedHashMap<>(outcomeMeta);
                }
                if (actionType != null) {
                    if (mergedMeta == null) mergedMeta = new LinkedHashMap<>();
                    mergedMeta.put(ChatMessageDocument.META_ACTION_TYPE, actionType);
                }
                if (mergedMeta != null) cmBuilder.meta(mergedMeta);
                ChatMessageDocument saved = chatLog.append(cmBuilder.build());
                // Flush buffered history tags onto the freshly persisted
                // assistant message. The tool-dispatcher hook + plan-mode
                // hooks above buffer their markers via ctx.historyTagSink();
                // this is where they land on a concrete turn id.
                if (saved != null && saved.getId() != null) {
                    ctx.historyTagSink().flushTo(saved.getId(), chatLog);
                }
                String preview = chatMessage.length() > 120
                        ? chatMessage.substring(0, 120) + "…" : chatMessage;
                log.info("Arthur.turn id='{}' awaiting={} -> '{}'",
                        process.getId(), awaitingUserInput, preview);
            } else {
                // No assistant turn this round — drop any buffered tags
                // rather than letting them leak onto the next turn.
                ctx.historyTagSink().discard();
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
            currentTurnHadUserInput.remove(process.getId());
            currentTurnEventsByRef.remove(process.getId());
            if (actionLoopFallback && awaitingUserInput
                    && process.getParentProcessId() != null) {
                // Sub-process worker fell out of the action loop without
                // a parseable action. The best Free-Text reply has
                // already been appended to chat history; close
                // terminally so the parent's delegation pointer releases
                // (ParentNotificationListener turns CLOSED+DONE into a
                // DONE ProcessEvent on the parent's inbox). Without
                // this the worker stays BLOCKED and every subsequent
                // user message auto-forwards into a dead-end.
                log.info("Arthur id='{}' worker action-loop fallback — closing DONE so parent '{}' releases delegation pointer",
                        process.getId(), process.getParentProcessId());
                thinkProcessService.closeProcess(process.getId(), CloseReason.DONE);
            } else {
                ThinkProcessStatus exitStatus = awaitingUserInput
                        ? ThinkProcessStatus.BLOCKED
                        : ThinkProcessStatus.IDLE;
                thinkProcessService.updateStatus(process.getId(), exitStatus);
            }
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
            // Worker is still RUNNING (or transient state like INIT /
            // IDLE while reaching the next turn). Auto-forward is only
            // safe when the worker is explicitly BLOCKED awaiting user
            // input — anything else risks routing a new topic to a
            // worker mid-task. Fall through to the LLM so it can
            // decide via the Active-Workers prompt block, BUT keep
            // the pointer so the LLM still sees there's an active
            // delegation (fixes the spurious re-DELEGATE noted in
            // analysis/sess_97483d45/FINDINGS.md Bug #2).
            log.info(
                    "Arthur id='{}' delegation target '{}' is {} (not BLOCKED) — falling through to LLM with pointer intact",
                    process.getId(), target.getName(), target.getStatus());
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
     * Lifecycle / status reconciliation between Arthur's drained
     * inbox and her own {@code workerLinks} snapshot of spawned
     * workers. Called once per turn at the top of
     * {@code runTurnFor}.
     *
     * <ul>
     *   <li>{@link SteerMessage.Reply} or non-terminal
     *       {@link SteerMessage.ProcessEvent} from a tracked worker
     *       → refresh {@code lastSeen} + {@code workerStatus} so the
     *       Active-Workers prompt block has fresh data.</li>
     *   <li>{@link SteerMessage.ProcessEvent} with
     *       {@link ProcessEventType#DONE} / {@link ProcessEventType#FAILED}
     *       / {@link ProcessEventType#STOPPED} → drop the link via
     *       {@code removeWorkerLink}; if the closed worker was the
     *       active delegation target, clear the pointer too.</li>
     * </ul>
     *
     * <p>Pre-Phase-3 sessions can have {@code workerLinks} empty —
     * the helpers are no-op when the link doesn't exist, so older
     * processes degrade gracefully without crashing.
     */
    private void reconcileWorkerLinksFromInbox(
            ThinkProcessDocument process, List<SteerMessage> inbox) {
        java.time.Instant now = java.time.Instant.now();
        String pointer = process.getActiveDelegationWorkerId();
        for (SteerMessage m : inbox) {
            String sourceId = null;
            ThinkProcessStatus sourceStatus = null;
            boolean terminal = false;
            if (m instanceof SteerMessage.ProcessEvent pe) {
                sourceId = pe.sourceProcessId();
                ProcessEventType type = pe.type();
                if (type == ProcessEventType.DONE
                        || type == ProcessEventType.FAILED
                        || type == ProcessEventType.STOPPED) {
                    terminal = true;
                } else if (type == ProcessEventType.BLOCKED) {
                    sourceStatus = ThinkProcessStatus.BLOCKED;
                }
            } else if (m instanceof SteerMessage.Reply r) {
                sourceId = r.sourceProcessId();
                // Reply implies the worker reached a turn-end; the
                // exit status (BLOCKED for awaiting=true, IDLE for
                // awaiting=false) lands as a separate ProcessEvent
                // later if at all. Refresh lastSeen only.
            }
            if (sourceId == null || sourceId.isBlank()) {
                continue;
            }
            if (terminal) {
                boolean removed = thinkProcessService.removeWorkerLink(
                        process.getId(), sourceId);
                if (removed) {
                    log.debug("Arthur id='{}' workerLink removed for terminated child '{}'",
                            process.getId(), sourceId);
                }
                if (sourceId.equals(pointer)) {
                    thinkProcessService.updateActiveDelegationWorkerId(
                            process.getId(), null);
                    process.setActiveDelegationWorkerId(null);
                    pointer = null;
                    log.info("Arthur id='{}' delegation pointer cleared (worker '{}' closed)",
                            process.getId(), sourceId);
                }
                continue;
            }
            // Non-terminal: refresh the snapshot. findWorkerLink + upsert
            // is cheaper than re-resolving identity from the worker doc.
            // Final aliases for the lambda — lookup happens per-iteration.
            final ThinkProcessStatus statusForLambda = sourceStatus;
            final java.time.Instant lastSeenForLambda = now;
            thinkProcessService.findWorkerLink(process.getId(), sourceId)
                    .ifPresent(snap -> {
                        if (statusForLambda != null) {
                            snap.setWorkerStatus(statusForLambda);
                        }
                        snap.setLastSeen(lastSeenForLambda);
                        thinkProcessService.upsertWorkerLink(process.getId(), snap);
                    });
        }
    }

    /**
     * Pointer maintenance is now entirely event-driven —
     * {@link #handleDelegate} arms the pointer on spawn, and
     * {@link #reconcileWorkerLinksFromInbox} clears it when the
     * pointer-worker terminates. The post-action callback no longer
     * mutates the pointer; the legacy "single BLOCKED source pickup
     * after RELAY" path went away with the BLOCKED-listener slim-down
     * (planning/process-engine-reply-channel.md §9). The method
     * remains as a single call site so future hooks have one place
     * to add post-action policy.
     */
    private void updateDelegationPointer(
            ThinkProcessDocument process,
            List<SteerMessage> inbox,
            boolean awaitingUserInput) {
        // No-op — see javadoc. Intentionally kept for symmetry with
        // the runTurnFor call site so the action-handler contract
        // documents where pointer policy lives.
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

    /**
     * Event-only turn gate — see {@link #currentTurnHadUserInput} and
     * {@link #SPAWN_ACTIONS_FORBIDDEN_ON_EVENT_TURNS}. When the turn
     * was triggered solely by a process-event (child closed,
     * tool-result, …) the LLM must not spawn new work — exactly the
     * path that drove the multi-Slartibartfast respawn cascade.
     * Routing the rejection through the action loop's correction
     * mechanism (instead of {@link #handleAction}) keeps the engine-
     * internal hint out of the user-facing chat: the LLM gets the
     * hint as the action tool's tool-result and re-emits a valid
     * action (RELAY / ANSWER / WAIT / REJECT). If it keeps insisting,
     * the loop falls back to the longest free-text seen this turn —
     * same path as any other unrecoverable schema error.
     */
    @Override
    protected @Nullable String validateActionSemantics(
            de.mhus.vance.brain.thinkengine.action.EngineAction action,
            ThinkProcessDocument process,
            de.mhus.vance.brain.thinkengine.ThinkEngineContext ctx) {
        if (!Boolean.TRUE.equals(currentTurnHadUserInput.get(process.getId()))
                && SPAWN_ACTIONS_FORBIDDEN_ON_EVENT_TURNS.contains(action.type())) {
            log.warn("Arthur id='{}' rejected spawn-action '{}' on event-only turn"
                            + " (no fresh user-input in inbox) — reason: '{}'",
                    process.getId(), action.type(), action.reason());
            return "Action '" + action.type() + "' is not allowed on a turn "
                    + "triggered without fresh user-input. The current inbox carries "
                    + "only process-events (worker closed / tool-result / similar). "
                    + "Spawning new work without a user prompt led to the multi-"
                    + "Slartibartfast respawn cascade we just stopped. "
                    + "Emit RELAY (engine auto-picks the single event in the "
                    + "drain, or pass `eventRef` token if multiple are present), "
                    + "or ANSWER (with a short status note), or WAIT — and let "
                    + "the user decide whether more work is wanted.";
        }
        return null;
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
            return planModeService.dispatch(
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
        // Plan-Mode actions go through the shared service first — if
        // it recognises the action it returns the outcome; otherwise
        // null and we fall through to Arthur-specific actions.
        ActionTurnOutcome planOutcome = planModeService.dispatch(action, process, ctx);
        if (planOutcome != null) return planOutcome;
        return switch (action.type()) {
            case ArthurActionSchema.TYPE_ANSWER          -> handleAnswer(action);
            case ArthurActionSchema.TYPE_ASK_USER        -> handleAskUser(action);
            case ArthurActionSchema.TYPE_DELEGATE        -> handleDelegate(action, process, ctx);
            case ArthurActionSchema.TYPE_RELAY           -> handleRelay(action, process, ctx);
            case ArthurActionSchema.TYPE_WAIT            -> handleWait(action);
            case ArthurActionSchema.TYPE_REJECT          -> handleReject(action);
            case ArthurActionSchema.TYPE_LEARN           -> handleLearn(action, process, ctx);
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
        // DISCOVER short-circuits the standard handleAction dispatch —
        // it never touches process.todos or the chat, just runs a
        // synchronous lookup and feeds the JSON result back to the LLM
        // as the action's tool-result. Next iteration of the action
        // loop the LLM picks a real action (ANSWER / DELEGATE / …)
        // with the discovery in hand.
        if (ArthurActionSchema.TYPE_DISCOVER.equals(action.type())) {
            return handleDiscover(action, process, ctx);
        }
        // Reuse the same dispatch as terminal actions — handleTodoUpdate
        // is idempotent and persists the new state in process.todos.
        // Discard the ActionTurnOutcome (chatMessage / awaiting) since
        // continuing actions don't produce chat messages directly. We
        // add a small user-visible chat note for COMPLETED transitions
        // (below) so the user sees plan progress live instead of
        // long silent runs. Mirror of EddieEngine.applyContinuingAction.
        java.util.List<de.mhus.vance.api.thinkprocess.TodoItem> todosBefore =
                snapshotTodos(process);
        ActionTurnOutcome ignored = handleAction(action, process, ctx);
        if (ArthurActionSchema.TYPE_TODO_UPDATE.equals(action.type())) {
            appendProgressChatForCompletions(process, ctx, todosBefore);
            return renderTodoUpdateFeedback(process);
        }
        return "Action " + action.type() + " applied.";
    }

    /**
     * DISCOVER handler — synchronous lookup against the central
     * {@link de.mhus.vance.brain.discovery.DiscoveryService} for a
     * user-mentioned term. Returns the JSON-formatted result as the
     * tool-result for the action call so the next action-loop
     * iteration sees it in the prompt.
     *
     * <p>Mirrors the response shape of {@code how_do_i} — same
     * {@code loaded} / {@code alternatives} / {@code hint} keys — so
     * the LLM already knows how to read it from manuals and prompts.
     */
    private String handleDiscover(
            de.mhus.vance.brain.thinkengine.action.EngineAction action,
            ThinkProcessDocument process,
            ThinkEngineContext ctx) {
        Object raw = action.params().get(ArthurActionSchema.PARAM_INTENT);
        if (!(raw instanceof String intent) || intent.isBlank()) {
            return "DISCOVER: missing 'intent' — emit a non-blank "
                    + "user-mentioned term or phrase.";
        }
        try {
            de.mhus.vance.brain.discovery.DiscoveryResult result =
                    discoveryService.discover(
                            intent,
                            process.getTenantId(),
                            process.getProjectId(),
                            process.getId());
            String json = serializeDiscoveryResult(result);
            log.info("Arthur id='{}' DISCOVER intent='{}' loaded={} alternatives={}",
                    process.getId(), intent,
                    result.getLoaded() != null ? result.getLoaded().getName() : null,
                    result.getAlternatives() == null ? 0
                            : result.getAlternatives().size());
            return json;
        } catch (RuntimeException e) {
            log.warn("Arthur id='{}' DISCOVER intent='{}' failed: {}",
                    process.getId(), intent, e.toString());
            return "DISCOVER failed: " + e.getMessage()
                    + " — fall back to manual_list / manual_read or just answer "
                    + "with what you know.";
        }
    }

    private String serializeDiscoveryResult(
            de.mhus.vance.brain.discovery.DiscoveryResult result) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("intent", result.getIntent());
        out.put("loaded", result.getLoaded() == null
                ? null : matchToMap(result.getLoaded()));
        java.util.List<java.util.Map<String, Object>> alternatives =
                new java.util.ArrayList<>();
        if (result.getAlternatives() != null) {
            for (de.mhus.vance.brain.discovery.DiscoveryResult.Match m
                    : result.getAlternatives()) {
                alternatives.add(matchToMap(m));
            }
        }
        out.put("alternatives", alternatives);
        out.put("hint", result.getHint());
        try {
            return objectMapper.writeValueAsString(out);
        } catch (RuntimeException e) {
            log.warn("Arthur DISCOVER: JSON serialize failed: {}", e.toString());
            return out.toString();
        }
    }

    private static java.util.Map<String, Object> matchToMap(
            de.mhus.vance.brain.discovery.DiscoveryResult.Match m) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("type", m.getType());
        out.put("name", m.getName());
        if (m.getSource() != null) out.put("source", m.getSource());
        if (m.getSummary() != null) out.put("summary", m.getSummary());
        if (m.getScore() != null) out.put("score", m.getScore());
        if (m.getContent() != null) out.put("content", m.getContent());
        return out;
    }

    /**
     * Snapshot of the process's todos used to detect transitions
     * inside {@link #applyContinuingAction}. Returns an empty list
     * when no todos are persisted yet.
     */
    private static java.util.List<de.mhus.vance.api.thinkprocess.TodoItem> snapshotTodos(
            ThinkProcessDocument process) {
        java.util.List<de.mhus.vance.api.thinkprocess.TodoItem> todos = process.getTodos();
        if (todos == null) return java.util.List.of();
        return new java.util.ArrayList<>(todos);
    }

    /**
     * Appends a brief assistant chat message for every todo that just
     * transitioned to COMPLETED. Mirror of
     * {@code EddieEngine.appendProgressChatForCompletions} — closes
     * the silent-execution UX gap during plan-mode runs.
     */
    private void appendProgressChatForCompletions(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            java.util.List<de.mhus.vance.api.thinkprocess.TodoItem> before) {
        java.util.Map<String, de.mhus.vance.api.thinkprocess.TodoStatus> prior =
                new java.util.HashMap<>();
        for (de.mhus.vance.api.thinkprocess.TodoItem t : before) {
            if (t.getId() != null) prior.put(t.getId(), t.getStatus());
        }
        java.util.List<de.mhus.vance.api.thinkprocess.TodoItem> now = process.getTodos();
        if (now == null) return;
        java.util.List<String> completedTitles = new java.util.ArrayList<>();
        for (de.mhus.vance.api.thinkprocess.TodoItem t : now) {
            if (t.getStatus() != de.mhus.vance.api.thinkprocess.TodoStatus.COMPLETED) continue;
            de.mhus.vance.api.thinkprocess.TodoStatus previously = prior.get(t.getId());
            if (previously == de.mhus.vance.api.thinkprocess.TodoStatus.COMPLETED) continue;
            String label = t.getContent();
            if (label == null || label.isBlank()) label = t.getId();
            completedTitles.add(label);
        }
        if (completedTitles.isEmpty()) return;
        StringBuilder msg = new StringBuilder();
        for (int i = 0; i < completedTitles.size(); i++) {
            if (i > 0) msg.append('\n');
            msg.append("✓ ").append(completedTitles.get(i));
        }
        try {
            ctx.chatMessageService().append(
                    de.mhus.vance.shared.chat.ChatMessageDocument.builder()
                            .tenantId(process.getTenantId())
                            .sessionId(process.getSessionId())
                            .thinkProcessId(process.getId())
                            .role(de.mhus.vance.api.chat.ChatRole.ASSISTANT)
                            .content(msg.toString())
                            .build());
        } catch (RuntimeException e) {
            log.warn("Arthur id='{}' failed to append plan-progress chat note: {}",
                    process.getId(), e.toString());
        }
    }

    /**
     * True when the process has at least one todo and every entry is
     * COMPLETED. Mirror of {@code EddieEngine.allTodosCompleted}.
     */
    private static boolean allTodosCompleted(ThinkProcessDocument process) {
        java.util.List<de.mhus.vance.api.thinkprocess.TodoItem> todos = process.getTodos();
        if (todos == null || todos.isEmpty()) return false;
        for (de.mhus.vance.api.thinkprocess.TodoItem t : todos) {
            if (t.getStatus() != de.mhus.vance.api.thinkprocess.TodoStatus.COMPLETED) {
                return false;
            }
        }
        return true;
    }

    /**
     * Plan-completion summary stand-in when the LLM emitted no text
     * after running through every todo. Lists the todo titles so the
     * user sees what got done. Mirror of
     * {@code EddieEngine.renderPlanCompletionSummary}.
     */
    private static String renderPlanCompletionSummary(ThinkProcessDocument process) {
        java.util.List<de.mhus.vance.api.thinkprocess.TodoItem> todos = process.getTodos();
        if (todos == null || todos.isEmpty()) {
            return "Plan abgeschlossen.";
        }
        StringBuilder sb = new StringBuilder("Plan abgeschlossen — alle Schritte erledigt:");
        for (de.mhus.vance.api.thinkprocess.TodoItem t : todos) {
            sb.append("\n- ");
            String label = t.getContent();
            if (label == null || label.isBlank()) label = t.getId();
            sb.append(label == null ? "" : label);
        }
        sb.append("\n\nSchau in deine Dokumente — die Ergebnisse sind dort abgelegt.");
        return sb.toString();
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
                            + "work_file_read, work_file_grep, etc.) to "
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
        Object optionsRaw = action.params().get(ArthurActionSchema.PARAM_OPTIONS);
        String rendered = renderAskUserOptions(message, optionsRaw);
        Map<String, Object> meta = buildAskUserMeta(optionsRaw);
        return new ActionTurnOutcome(rendered, /*awaitingUserInput*/ true, meta);
    }

    /**
     * Builds the {@code meta} payload attached to an ASK_USER chat
     * message. Returns {@code null} when no usable options were given
     * — keeps the persisted document clean for open-ended questions.
     * Mirror of {@code EddieEngine.buildAskUserMeta}.
     */
    @SuppressWarnings("unchecked")
    private static @Nullable Map<String, Object> buildAskUserMeta(@Nullable Object optionsRaw) {
        if (!(optionsRaw instanceof List<?> rawList) || rawList.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> cleaned = new ArrayList<>();
        for (Object item : rawList) {
            if (!(item instanceof Map<?, ?> m)) continue;
            Object label = m.get("label");
            if (!(label instanceof String l) || l.isBlank()) continue;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("label", l.trim());
            Object desc = m.get("description");
            if (desc instanceof String d && !d.isBlank()) {
                entry.put("description", d.trim());
            }
            cleaned.add(entry);
        }
        if (cleaned.isEmpty()) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(ChatMessageDocument.META_ASK_USER_OPTIONS, cleaned);
        return out;
    }

    /**
     * Appends structured ASK_USER options to the question text as a
     * Markdown list the UI / voice channel can present. Mirror of
     * {@code EddieEngine.renderAskUserOptions}; kept in Arthur because
     * Arthur sometimes runs without Eddie (foot- or web-session
     * direct on a project Arthur). When Arthur sits under Eddie, the
     * cross-engine relay path (eddie-engine.md §5.8) takes over and
     * Eddie re-renders the picker on the user-facing side.
     *
     * <p>Pass-through when {@code options} is null / empty / not a
     * list — keeps the free-text question shape intact.
     */
    @SuppressWarnings("unchecked")
    private static String renderAskUserOptions(
            String baseMessage, @Nullable Object optionsRaw) {
        if (!(optionsRaw instanceof List<?> rawList) || rawList.isEmpty()) {
            return baseMessage;
        }
        StringBuilder sb = new StringBuilder(baseMessage == null ? "" : baseMessage);
        boolean any = false;
        for (Object item : rawList) {
            if (!(item instanceof Map<?, ?> m)) continue;
            Object label = ((Map<String, Object>) m).get("label");
            if (!(label instanceof String l) || l.isBlank()) continue;
            if (!any) {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
                    sb.append("\n\n");
                }
                any = true;
            }
            sb.append("- **").append(l.trim()).append("**");
            Object desc = ((Map<String, Object>) m).get("description");
            if (desc instanceof String d && !d.isBlank()) {
                sb.append(" — ").append(d.trim());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * Spawn a worker via the unified {@code process_create} tool.
     * Two modes feed the same tool:
     *
     * <ul>
     *   <li>The LLM supplied an explicit {@code preset} recipe name →
     *       pass it as {@code recipe} on the tool call. Strict
     *       resolution; unknown names surface as a tool error with a
     *       suggestion list, which Arthur reports back so the LLM
     *       retries with a corrected name on the next turn.</li>
     *   <li>No {@code preset} → omit {@code recipe} so the tool's
     *       built-in selector routes from {@code goal}, with the
     *       Slartibartfast NONE-fallback enabled (mirroring the
     *       user-facing intent of "do this somehow, even if no
     *       existing recipe fits").</li>
     * </ul>
     *
     * <p>Either way the engine derives a unique worker name — never
     * trusts the LLM with naming. The optional pre-announcement
     * {@code message} is shown to the user; absent message = silent
     * spawn (no chat append, no filler).
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
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("name", workerName);
            params.put("goal", prompt);
            params.put("steerContent", prompt);
            if (explicitRecipe) {
                params.put("recipe", preset);
            }
            ctx.tools().invokeInternal("process_create", params);
            if (explicitRecipe) {
                log.info("Arthur id='{}' DELEGATE recipe='{}' worker='{}' reason='{}'",
                        process.getId(), preset, workerName, summariseReason(action.reason()));
            } else {
                log.info("Arthur id='{}' DELEGATE via selector worker='{}' reason='{}'",
                        process.getId(), workerName, summariseReason(action.reason()));
            }
            // Arm the delegation pointer immediately so subsequent
            // user input auto-forwards to the worker once it goes
            // BLOCKED, and register the worker in workerLinks so the
            // Active-Workers prompt block + lifecycle-cleanup paths
            // see it. See planning/process-engine-reply-channel.md
            // §9 (Arthur pointer + workerLinks consolidation).
            thinkProcessService.findByName(
                            process.getTenantId(),
                            process.getSessionId(),
                            workerName)
                    .ifPresent(spawned -> {
                        thinkProcessService.updateActiveDelegationWorkerId(
                                process.getId(), spawned.getId());
                        process.setActiveDelegationWorkerId(spawned.getId());
                        de.mhus.vance.shared.thinkprocess.WorkerLinkSnapshot snapshot =
                                de.mhus.vance.shared.thinkprocess.WorkerLinkSnapshot.builder()
                                        .workerProcessId(spawned.getId())
                                        .workerProcessName(spawned.getName() == null
                                                ? workerName : spawned.getName())
                                        .workerTenantId(spawned.getTenantId() == null
                                                ? process.getTenantId() : spawned.getTenantId())
                                        .workerProjectName(spawned.getProjectId() == null
                                                ? process.getProjectId() : spawned.getProjectId())
                                        .workerSessionId(spawned.getSessionId() == null
                                                ? process.getSessionId() : spawned.getSessionId())
                                        .workerStatus(spawned.getStatus())
                                        .lastSeen(java.time.Instant.now())
                                        .build();
                        thinkProcessService.upsertWorkerLink(process.getId(), snapshot);
                        log.info(
                                "Arthur id='{}' delegation pointer armed → worker '{}' (id='{}') on DELEGATE",
                                process.getId(), workerName, spawned.getId());
                    });
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
     * Pass a specific {@code <process-event>} from the current
     * drain through to the user as Arthur's own answer. Zero LLM
     * tokens for the content — the engine looks up the event by
     * {@code eventRef}, validates that it belongs to THIS turn's
     * drain (so a stale event from a previous turn can no longer
     * be relayed as if it were fresh), and emits the verbatim
     * worker reply that the event carries plus a deterministic
     * {@code **[Worker {name} → {type}]**} header. The LLM does
     * not pick the source-name or the prefix text — both are
     * derived from the referenced event's metadata so a
     * {@code Marvin-wording / Ford-source} mismatch becomes
     * structurally impossible. See
     * {@code planning/arthur-process-event-attribution.md}.
     */
    private ActionTurnOutcome handleRelay(
            de.mhus.vance.brain.thinkengine.action.EngineAction action,
            ThinkProcessDocument process,
            ThinkEngineContext ctx) {
        Map<String, SteerMessage.ProcessEvent> available =
                currentTurnEventsByRef.getOrDefault(process.getId(), Map.of());

        SteerMessage.ProcessEvent event = resolveRelayEvent(action, available);
        if (event == null) {
            log.warn("Arthur id='{}' RELAY could not be resolved "
                            + "(drain size={}, eventRef='{}', legacy source='{}') "
                            + "— reason='{}'",
                    process.getId(), available.size(),
                    action.stringParam(ArthurActionSchema.PARAM_EVENT_REF),
                    action.stringParam("source"),
                    action.reason());
            return new ActionTurnOutcome(
                    relayFallbackMessage(available),
                    true);
        }
        String eventRef = event.eventId();

        // Resolve the source-worker name for the deterministic header.
        // The event already carries the verbatim child reply in its
        // humanSummary (set by ParentNotificationListener.enrichWith-
        // LastReply), so we render straight from that — no second
        // lookup against the worker's chat-history that could pick up
        // a fresher reply than the one this event captured.
        String sourceProcessId = event.sourceProcessId();
        Optional<ThinkProcessDocument> targetOpt = thinkProcessService.findById(sourceProcessId)
                .filter(p -> process.getTenantId().equals(p.getTenantId())
                        && process.getSessionId().equals(p.getSessionId()));
        String sourceName = targetOpt.map(ThinkProcessDocument::getName).orElse(sourceProcessId);

        String body = unwrapChildReply(event.humanSummary());
        if (body == null || body.isBlank()) {
            log.warn("Arthur id='{}' RELAY eventRef '{}' has empty body — reason='{}'",
                    process.getId(), eventRef, action.reason());
            return new ActionTurnOutcome(
                    "_Der Worker `" + sourceName + "` hat eine leere Antwort "
                            + "zurückgegeben. Sag mir kurz wie wir weitermachen._",
                    true);
        }

        // The worker-attribution header that used to prefix this
        // body ("**[Worker X → done]**") moved out of the chat text:
        // since engine-output-translator renders Hactar/Slart plumbing
        // into a natural answer, Arthur's RELAY now reads exactly like
        // a direct reply and the header was visual noise. UI clients
        // that need the source for display do so via the message's
        // ProcessEvent metadata, not by re-parsing the body.
        StringBuilder out = new StringBuilder();
        out.append(body);

        log.info(
                "Arthur id='{}' RELAY eventRef='{}' source='{}' ({} chars) reason='{}'",
                process.getId(), eventRef, sourceName, body.length(),
                summariseReason(action.reason()));

        // The engine layer (runTurnFor) appends the chat message —
        // we just return the composed text. By going through the
        // normal awaitingUserInput=true exit, Arthur ends the turn
        // BLOCKED waiting for the user's next message, which is the
        // expected state after delivering an answer.
        return new ActionTurnOutcome(out.toString(), /*awaitingUserInput*/ true);
    }

    /**
     * Resolve which {@code <process-event>} the LLM is RELAYing.
     * Three-tier strategy minimises hallucination surface:
     * <ol>
     *   <li>{@code eventRef} present → look up by short token
     *       (e.g. {@code "ev1"}). Token map matches what was
     *       rendered in the prompt, so the only way to fail is a
     *       fabricated token.</li>
     *   <li>{@code eventRef} absent → collapse events per
     *       {@code sourceProcessId} (Reply + lifecycle DONE/FAILED
     *       from the same worker are semantic duplicates) and
     *       auto-pick if the effective drain has exactly one event.
     *       Catches the common case where a worker that finishes
     *       its turn produces both an {@code emitReply} and a
     *       lifecycle DONE notification — both refer to the same
     *       answer text, so the LLM should not be forced to
     *       disambiguate.</li>
     *   <li>Legacy {@code source} field present (older prompt) →
     *       resolve to a single event by process-id or process-name.
     *       Logged as drift warning.</li>
     * </ol>
     * Returns {@code null} when none of these produce a single,
     * unambiguous event from THIS drain — caller emits a user-
     * friendly fallback.
     */
    private SteerMessage.@org.jspecify.annotations.Nullable ProcessEvent
    resolveRelayEvent(
            de.mhus.vance.brain.thinkengine.action.EngineAction action,
            Map<String, SteerMessage.ProcessEvent> available) {
        String eventRef = action.stringParam(ArthurActionSchema.PARAM_EVENT_REF);
        if (eventRef != null && !eventRef.isBlank()) {
            // Tier 1: short-token lookup against the FULL drain.
            // Also tolerate the LLM pasting the bare UUID (some
            // models echo what they see in logs); scan values for
            // matching eventId.
            SteerMessage.ProcessEvent byToken = available.get(eventRef);
            if (byToken != null) return byToken;
            for (SteerMessage.ProcessEvent ev : available.values()) {
                if (eventRef.equals(ev.eventId())) return ev;
            }
            return null;
        }
        // Tier 2: auto-pick on single-event drain — collapsing per
        // sourceProcessId first so a worker that emitted both a
        // Reply and a lifecycle DONE doesn't force the LLM to choose
        // between two events that carry the same answer.
        if (available.size() == 1) {
            return available.values().iterator().next();
        }
        Map<String, SteerMessage.ProcessEvent> collapsed =
                collapseBySourceProcessId(available);
        if (collapsed.size() == 1) {
            return collapsed.values().iterator().next();
        }
        // Tier 3: legacy `source` field, resolve to a single match
        // against the full drain (don't lose options to the collapse).
        String legacySource = action.stringParam("source");
        if (legacySource == null || legacySource.isBlank()) return null;
        List<SteerMessage.ProcessEvent> matches = available.values().stream()
                .filter(ev -> matchesLegacySource(ev, legacySource))
                .toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    /**
     * Collapse drained events that share a {@code sourceProcessId},
     * keeping one representative per worker. Used by Tier 2 of
     * {@link #resolveRelayEvent} so a worker that emits both an
     * {@code emitReply} (synthesised as a {@code BLOCKED}-typed
     * event by the inbox renderer above) and a lifecycle
     * {@code DONE} notification from {@code ParentNotificationListener}
     * collapses to a single semantic event.
     *
     * <p>Preference order per group (lower priority value wins):
     * <ol>
     *   <li>{@code BLOCKED} — synthetic Reply event; carries the
     *       worker's actual answer.</li>
     *   <li>{@code SUMMARY} — deliberate progress summary.</li>
     *   <li>{@code DONE} — terminal lifecycle, enriched by
     *       {@link de.mhus.vance.brain.thinkengine.ParentNotificationListener}
     *       with the last assistant reply.</li>
     *   <li>{@code FAILED} — terminal lifecycle, failure context.</li>
     *   <li>{@code STOPPED} — terminal lifecycle, force-stopped.</li>
     *   <li>everything else (e.g. {@code STARTED},
     *       {@code SCHEDULED_WAKEUP}, {@code EXEC_*}).</li>
     * </ol>
     * Same-priority ties break in favour of the later
     * {@link SteerMessage.ProcessEvent#at()} timestamp.
     *
     * <p>Package-private for {@link ArthurEngineRelayCollapseTest}.
     */
    static Map<String, SteerMessage.ProcessEvent> collapseBySourceProcessId(
            Map<String, SteerMessage.ProcessEvent> available) {
        // Track the best entry seen per sourceProcessId. We preserve
        // insertion order by remembering the original token under
        // which the winner was rendered to the LLM, so Tier-2's
        // pick still matches what the LLM would see in the prompt.
        Map<String, Map.Entry<String, SteerMessage.ProcessEvent>> bestByPid =
                new LinkedHashMap<>();
        for (Map.Entry<String, SteerMessage.ProcessEvent> entry : available.entrySet()) {
            SteerMessage.ProcessEvent ev = entry.getValue();
            String pid = ev.sourceProcessId();
            if (pid == null) {
                // Events without a source can't be collapsed against anything.
                bestByPid.put(entry.getKey(), entry);
                continue;
            }
            Map.Entry<String, SteerMessage.ProcessEvent> prev = bestByPid.get(pid);
            if (prev == null || preferReplaceForRelay(prev.getValue(), ev)) {
                bestByPid.put(pid, entry);
            }
        }
        Map<String, SteerMessage.ProcessEvent> result = new LinkedHashMap<>();
        for (Map.Entry<String, SteerMessage.ProcessEvent> entry : bestByPid.values()) {
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private static boolean preferReplaceForRelay(
            SteerMessage.ProcessEvent prev, SteerMessage.ProcessEvent next) {
        int prevP = relayPriority(prev.type());
        int nextP = relayPriority(next.type());
        if (nextP < prevP) return true;
        if (nextP > prevP) return false;
        // Same priority: prefer the later event.
        java.time.Instant prevAt = prev.at();
        java.time.Instant nextAt = next.at();
        if (nextAt == null) return false;
        if (prevAt == null) return true;
        return nextAt.isAfter(prevAt);
    }

    private static int relayPriority(de.mhus.vance.api.thinkprocess.ProcessEventType type) {
        if (type == null) return 99;
        return switch (type) {
            case BLOCKED -> 0;   // synthesised from Reply — actual answer
            case SUMMARY -> 1;
            case DONE -> 2;
            case FAILED -> 3;
            case STOPPED -> 4;
            default -> 5;
        };
    }

    private boolean matchesLegacySource(
            SteerMessage.ProcessEvent event, String legacySource) {
        if (legacySource.equals(event.sourceProcessId())) return true;
        // sourceProcessName isn't on the event record — resolve via
        // the registry. Tenant/session scope filter prevents matching
        // a same-named worker in a sibling session.
        return thinkProcessService.findById(event.sourceProcessId())
                .map(ThinkProcessDocument::getName)
                .filter(legacySource::equals)
                .isPresent();
    }

    /**
     * User-visible message when RELAY can't be honoured (missing or
     * stale eventRef). Never leaks the internal validator diagnostic
     * — the user gets a plain "lost the trail" line plus, if the
     * drain still contains events, an action-loop hint that the LLM
     * will re-evaluate on the next iteration (visible via warn-log
     * only).
     */
    private String relayFallbackMessage(
            Map<String, SteerMessage.ProcessEvent> available) {
        if (available.isEmpty()) {
            return "_Ich habe gerade nichts zum Weiterreichen — "
                    + "sag mir kurz wo es weitergehen soll._";
        }
        return "_Beim Übergeben der Worker-Antwort ist mir gerade "
                + "die Spur verloren gegangen — wenn die Antwort fehlt, "
                + "sag's mir und ich starte den Worker neu._";
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

    /**
     * Persists user-related context into the cross-engine per-user
     * memory store ({@link UserMemoryService}). Mirrors
     * {@code EddieEngine.handleLearn} — same {@code scope} (persona |
     * fact) + {@code mode} (replace | append) semantics, same
     * underlying storage in the user's hub project. Arthur sits in a
     * work project, so the user project is resolved explicitly via
     * {@link #resolveUserProjectName(ThinkEngineContext)} — never via
     * {@code process.projectId}, which would write the memory into the
     * wrong project.
     */
    private ActionTurnOutcome handleLearn(
            de.mhus.vance.brain.thinkengine.action.EngineAction action,
            ThinkProcessDocument process,
            ThinkEngineContext ctx) {
        String scope = action.stringParam(ArthurActionSchema.PARAM_SCOPE);
        String content = action.stringParam(ArthurActionSchema.PARAM_CONTENT);
        if (scope == null || scope.isBlank()
                || content == null || content.isBlank()) {
            log.warn("Arthur id='{}' LEARN missing scope/content — reason='{}'",
                    process.getId(), action.reason());
            return new ActionTurnOutcome(
                    "Konnte das nicht speichern — scope oder content fehlte. ("
                            + action.reason() + ")",
                    true);
        }
        if (!ArthurActionSchema.LEARN_SCOPES.contains(scope)) {
            log.warn("Arthur id='{}' LEARN unknown scope='{}' — reason='{}'",
                    process.getId(), scope, action.reason());
            return new ActionTurnOutcome(
                    "Konnte das nicht speichern — unbekannter scope '" + scope
                            + "'. Erlaubt: 'persona', 'fact'.",
                    true);
        }
        String userProject = resolveUserProjectName(ctx);
        if (userProject == null) {
            log.warn("Arthur id='{}' LEARN cannot resolve user project — session/userId missing",
                    process.getId());
            return new ActionTurnOutcome(
                    "Konnte das nicht speichern — kein User-Projekt verfügbar.",
                    true);
        }
        String tenantId = process.getTenantId();
        String authorTag = "arthur:" + process.getId();

        try {
            switch (scope) {
                case ArthurActionSchema.LEARN_SCOPE_PERSONA -> {
                    String mode = action.stringParamOr(
                            ArthurActionSchema.PARAM_MODE,
                            ArthurActionSchema.LEARN_MODE_REPLACE);
                    int chars = userMemoryService.learnPersona(
                            tenantId, userProject, content, mode, authorTag);
                    log.info("Arthur id='{}' LEARN persona mode='{}' ({} chars total) reason='{}'",
                            process.getId(), mode, chars,
                            summariseReason(action.reason()));
                }
                case ArthurActionSchema.LEARN_SCOPE_FACT -> {
                    int chars = userMemoryService.learnFact(
                            tenantId, userProject, content, authorTag);
                    log.info("Arthur id='{}' LEARN fact (journal now {} chars) reason='{}'",
                            process.getId(), chars,
                            summariseReason(action.reason()));
                }
            }
        } catch (RuntimeException e) {
            log.warn("Arthur id='{}' LEARN persistence failed: {}",
                    process.getId(), e.toString());
            return new ActionTurnOutcome(
                    "Konnte das gerade nicht merken — " + e.getMessage(),
                    true);
        }

        // Optional spoken confirmation. Silent by default — the user
        // sees the effect next turn when the persona/facts block
        // reflects the new content.
        String message = action.stringParam(ArthurActionSchema.PARAM_MESSAGE);
        return new ActionTurnOutcome(
                message == null || message.isBlank() ? null : message,
                /*awaitingUserInput*/ message != null && !message.isBlank());
    }

    /**
     * Post-LEARN consolidation. Delegates to {@link UserMemoryService}
     * which runs a small LLM pass that resolves contradictions and
     * trims the just-updated persona / facts file. The callback uses
     * Arthur's lane LLM (same {@link AiChat}, same model alias) so
     * the user sees consistent streaming behaviour with the turn that
     * triggered LEARN. Failures are non-fatal — logged inside the
     * service; raw content stays on disk.
     */
    private void runLearnConsolidation(
            de.mhus.vance.brain.thinkengine.action.EngineAction action,
            AiChat aiChat,
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            String modelAlias) {
        String scope = action.stringParam(ArthurActionSchema.PARAM_SCOPE);
        if (scope == null || scope.isBlank()) return;
        String userProject = resolveUserProjectName(ctx);
        if (userProject == null) return;
        String authorTag = "arthur:" + process.getId();
        userMemoryService.runConsolidation(
                scope, process.getTenantId(), userProject, authorTag,
                (systemPrompt, currentText) -> {
                    List<ChatMessage> messages = List.of(
                            SystemMessage.from(systemPrompt),
                            UserMessage.from(currentText));
                    dev.langchain4j.model.chat.request.ChatRequest req =
                            dev.langchain4j.model.chat.request.ChatRequest.builder()
                                    .messages(messages)
                                    .build();
                    AiMessage reply = streamOneIteration(
                            aiChat, req, ctx, process, modelAlias);
                    return reply.text();
                });
    }

    /**
     * Resolves the user's hub project name ({@code _user_<login>})
     * from {@link ThinkEngineContext#userId()}. Arthur runs in a work
     * project, so the user-memory store lives elsewhere — never read
     * or written via {@code process.projectId}. Returns {@code null}
     * when the session userId is missing.
     */
    private @Nullable String resolveUserProjectName(ThinkEngineContext ctx) {
        String userId = ctx.userId();
        if (userId == null || userId.isBlank()) return null;
        return HomeBootstrapService.hubProjectName(userId);
    }

    private static String summariseReason(String reason) {
        if (reason == null) return "";
        String oneLine = reason.replace("\n", " ").replaceAll("\\s+", " ").trim();
        return oneLine.length() > 80 ? oneLine.substring(0, 77) + "..." : oneLine;
    }

    /**
     * The {@code humanSummary} of a {@link SteerMessage.ProcessEvent}
     * comes pre-wrapped by {@code ParentNotificationListener.enrichWithLastReply}:
     *
     * <pre>
     *   Child process X status=blocked
     *
     *   Last assistant reply from this child (verbatim):
     *   --- BEGIN CHILD REPLY ---
     *   &lt;actual reply&gt;
     *   --- END CHILD REPLY ---
     * </pre>
     *
     * That framing is useful for the LLM-facing {@code <process-event>}
     * marker (tells the model what it's looking at). For a RELAY to the
     * user, only the body between BEGIN/END belongs in the chat — Fix 2's
     * deterministic {@code **[Worker ... → ...]**} header already
     * supplies the attribution. This helper extracts that inner text;
     * returns the input unchanged if the framing isn't present.
     */
    static @Nullable String unwrapChildReply(@Nullable String humanSummary) {
        if (humanSummary == null) return null;
        int begin = humanSummary.indexOf("--- BEGIN CHILD REPLY ---");
        if (begin < 0) return humanSummary;
        int bodyStart = humanSummary.indexOf('\n', begin);
        if (bodyStart < 0) return humanSummary;
        int end = humanSummary.indexOf("--- END CHILD REPLY ---", bodyStart);
        String inner = end < 0
                ? humanSummary.substring(bodyStart + 1)
                : humanSummary.substring(bodyStart + 1, end);
        return inner.trim();
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
        // Voice-mode flag: last UserChatInput in this drain batch
        // wins. Per-turn signal — never persisted on the process. See
        // specification/voice-mode.md §6.
        boolean voiceMode = false;
        String mentionedByDisplayName = null;
        de.mhus.vance.api.thinkprocess.ActiveAppContext activeApp = null;
        for (SteerMessage m : inbox) {
            if (m instanceof SteerMessage.UserChatInput uci) {
                voiceMode = uci.voiceMode();
                mentionedByDisplayName = uci.fromUserDisplayName();
                activeApp = uci.activeApp();
            }
        }
        String appInstructions = activeAppPromptResolver.resolve(process, activeApp);
        // Strict-mode: see EddieEngine for the same pattern.
        if (appInstructions == null) activeApp = null;

        // Cortex-mode: live-checked from the client-tool registry per
        // turn — fires only when a Cortex-view client is currently
        // connected to this session. Persisted SessionDocument fields
        // aren't enough on their own (they'd falsely linger after the
        // user navigates back to plain chat).
        de.mhus.vance.brain.tools.client.CortexPromptResolver.CortexContext cortex =
                cortexPromptResolver.resolve(process.getSessionId());

        // Multi-user collab context — collabActive + participants +
        // mentionedBy variables for the prompt-render. See
        // planning/multi-user-sessions.md §5 / §6.
        de.mhus.vance.brain.chat.CollabContextResolver.CollabContext collab =
                collabContextResolver.resolve(process.getSessionId(), mentionedByDisplayName);

        de.mhus.vance.brain.prompt.PromptContextBuilder ctxBuilder =
                de.mhus.vance.brain.prompt.PromptContextBuilder
                        .forProcess(process, modelInfo)
                        .tier(modelSize)
                        .engine(NAME)
                        .voiceMode(voiceMode)
                        .activeApp(activeApp)
                        .appInstructions(appInstructions)
                        .cortexMode(cortex.active())
                        .cortexBoundDocPath(cortex.boundDocPath())
                        .cortexBoundDocMime(cortex.boundDocMime())
                        .collabActive(collab.active())
                        .participants(collab.participants())
                        .mentionedBy(collab.mentionedBy())
                        .withRootDirTypes(workspaceService.getRootDirTypes(
                                process.getTenantId(), process.getProjectId()));
        String base = composer.compose(process,
                engineDefaultPrompt(process, modelSize), ctxBuilder);
        String discoveryBlock = ctx.tools().discoveryBlockMarkdown();
        if (discoveryBlock != null && !discoveryBlock.isBlank()) {
            base = base + discoveryBlock;
        }
        messages.add(SystemMessage.from(base));

        // Current-date block (recipe-param promptDateGranularity:
        // auto/day/hour, default none). DYNAMIC — date rollover stays
        // behind the cache marker. See PromptDateBlock.
        de.mhus.vance.brain.prompt.PromptDateBlock.appendDynamicMessage(
                messages, process, modelInfo == null ? null : modelInfo.size());

        // ── DYNAMIC blocks — change tenant/project/turn-to-turn ──
        // Recipe catalog: depends on tenant + bundled recipes; mutates
        // when a recipe is added / removed in _vance.
        String catalog = buildRecipeCatalogSection(process);
        if (catalog != null && !catalog.isBlank()) {
            messages.add(VanceSystemMessage.dynamic(catalog));
        }
        // Project-memory block (memory.* settings cascade) — pinned
        // language / tone / persona hints. Mutates on Settings changes.
        // RAG auto-inject (opt-in via recipe-param rag.autoInject)
        // rides inside this block — MemoryContextLoader splices the
        // top-K hits in for any engine that hands it a userQuery.
        // Silent no-op when off / no RAG / empty user text. See
        // specification/rag.md §5.
        String memoryBlock = memoryContextLoader.composeBlock(
                process, latestUserInputText(inbox));
        if (memoryBlock != null && !memoryBlock.isBlank()) {
            messages.add(VanceSystemMessage.dynamic(memoryBlock));
        }
        // Per-user memory — persona summary + facts journal from the
        // user's hub project (_user_<login>), shared with Eddie via
        // UserMemoryService. Each rides its own dynamic block so a
        // LEARN that mutates one doesn't bust the cache marker for the
        // other. Arthur sits in a work project, so we resolve the user
        // project explicitly — never via process.projectId.
        String userProject = resolveUserProjectName(ctx);
        if (userProject != null) {
            String personaBlock = userMemoryService.composePersonaBlock(
                    process.getTenantId(), userProject);
            if (personaBlock != null && !personaBlock.isBlank()) {
                messages.add(VanceSystemMessage.dynamic(personaBlock));
            }
            String factsBlock = userMemoryService.composeFactsBlock(
                    process.getTenantId(), userProject);
            if (factsBlock != null && !factsBlock.isBlank()) {
                messages.add(VanceSystemMessage.dynamic(factsBlock));
            }
        }
        // Active workers — permanent status view of Arthur's children
        // independent of inbox events. Without this, a worker that
        // hasn't sent a PROCESS_EVENT yet (still mid-run) is invisible
        // to Arthur and the LLM has to remember its own spawn-state
        // across turns. Concretely fixes the "did Marvin actually start
        // or did Ford just answer?" confusion from the Vibecoding-Bug
        // (see planning/arthur-process-event-attribution.md §Erweiterungen).
        String activeWorkersBlock = composeActiveWorkersBlock(process);
        if (activeWorkersBlock != null && !activeWorkersBlock.isBlank()) {
            messages.add(VanceSystemMessage.dynamic(activeWorkersBlock));
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
        // Per-pack tool usage notes. Only fires when a reachable tool
        // carries a non-empty promptHint — Jira-MCP says "cloudId is
        // auto-injected", a per-tenant kit-pack might explain its
        // sub-tool naming, etc. See ServerToolConfig.promptHint and
        // ContextToolsApi.activePromptHints.
        java.util.List<String> hints = ctx.tools().activePromptHints();
        if (!hints.isEmpty()) {
            StringBuilder hb = new StringBuilder("## Tool usage notes\n\n");
            for (int i = 0; i < hints.size(); i++) {
                if (i > 0) hb.append("\n\n");
                hb.append(hints.get(i));
            }
            messages.add(VanceSystemMessage.dynamic(hb.toString()));
        }

        // Active history (ARCHIVED_CHAT compaction-aware once we wire
        // memoryService — for v1 just use full active history).
        // HistoryStrengthFilter drops STRENGTH:weak rows when
        // `vance.prak.contextFilterEnabled=true`; otherwise it's a pass-
        // through that returns the input list as-is.
        List<ChatMessageDocument> history = historyStrengthFilter.filter(
                chatLog.activeHistory(
                        process.getTenantId(), process.getSessionId(), process.getId()));

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
            messages.add(toLangchain(history.get(i), collab.active()));
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
                        providerType, modelInfo.capabilities(), collab.active()));
            }
        }

        // Now append the non-user inbox items (events, tool results,
        // external commands) as user-role messages with the XML
        // wrapper Arthur's prompt is trained on. The token map drives
        // short eventRef attributes when more than one event is in
        // the drain (handleRelay validates against the same map).
        Map<String, String> eventIdToToken = invertToShortTokens(
                currentTurnEventsByRef.getOrDefault(process.getId(), Map.of()));
        boolean multiEventDrain = eventIdToToken.size() > 1;
        // Coexistence dedup (process-engine-reply-channel migration):
        // when migrated engines (Ford, Marvin root, Zaphod, Slart, …)
        // emit an explicit Reply via ProgressEmitter, the lane-status
        // BLOCKED/DONE/FAILED transition still fires the legacy
        // ParentNotificationListener path, which queues a redundant
        // ProcessEvent with the same body wrapped in `BEGIN CHILD
        // REPLY ... END CHILD REPLY`. Surface only the explicit Reply
        // to the LLM — otherwise the prompt shows the same text
        // twice and the model RELAYs twice.
        java.util.Set<String> sourcesWithReplyInDrain = new java.util.HashSet<>();
        for (SteerMessage m : inbox) {
            if (m instanceof SteerMessage.Reply r && r.sourceProcessId() != null) {
                sourcesWithReplyInDrain.add(r.sourceProcessId());
            }
        }
        for (SteerMessage m : inbox) {
            if (m instanceof SteerMessage.UserChatInput) continue;
            if (m instanceof SteerMessage.ProcessEvent pe
                    && sourcesWithReplyInDrain.contains(pe.sourceProcessId())
                    && (pe.type() == de.mhus.vance.api.thinkprocess.ProcessEventType.BLOCKED
                            || pe.type() == de.mhus.vance.api.thinkprocess.ProcessEventType.DONE
                            || pe.type() == de.mhus.vance.api.thinkprocess.ProcessEventType.FAILED)) {
                // Skip the redundant legacy lifecycle-with-content
                // event — the Reply already carries the same body.
                // STOPPED still flows (informational cancellation
                // without substantive content overlap).
                continue;
            }
            String wrapped = renderForLlm(m, eventIdToToken, multiEventDrain);
            if (wrapped != null) {
                messages.add(UserMessage.from(wrapped));
            }
        }

        return messages;
    }

    /**
     * Reverse the {token → event} drain map into {eventId → token}
     * so the renderer can attach the short token to each
     * {@code <process-event>} marker.
     */
    private static Map<String, String> invertToShortTokens(
            Map<String, SteerMessage.ProcessEvent> eventsByToken) {
        Map<String, String> out = new LinkedHashMap<>();
        for (var entry : eventsByToken.entrySet()) {
            String token = entry.getKey();
            SteerMessage.ProcessEvent pe = entry.getValue();
            if (pe.eventId() != null && !pe.eventId().isBlank()) {
                out.put(pe.eventId(), token);
            }
        }
        return out;
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
            java.util.Set<de.mhus.vance.brain.ai.ModelCapability> capabilities,
            boolean collabActive) {
        String prefixedContent = de.mhus.vance.brain.chat.ChatHistoryRenderer
                .applySenderPrefix(uci.fromUserDisplayName(), uci.content(), collabActive);
        if (uci.attachments().isEmpty()) {
            return UserMessage.from(prefixedContent);
        }
        List<de.mhus.vance.brain.ai.attachment.ResolvedAttachment> resolved;
        try {
            resolved = attachmentResolver.resolveAll(
                    uci.attachments(), process.getTenantId(), process.getProjectId());
        } catch (de.mhus.vance.brain.ai.attachment.AttachmentException e) {
            log.warn("Arthur: attachment resolution failed for process '{}': {} — "
                            + "falling back to text-only turn",
                    process.getId(), e.getMessage());
            return UserMessage.from(prefixedContent
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
        blocks.add(dev.langchain4j.data.message.TextContent.from(prefixedContent));
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

    /** Max entries in the {@code <active_workers>} block. Mirrors
     *  Eddie's {@code DELEGATED_WORKERS_MAX_RENDER} — Arthur's
     *  child-set is typically small (1–3), so a hard upper bound
     *  just guards against pathological cases like many parallel
     *  micro-workers. */
    private static final int ACTIVE_WORKERS_MAX_RENDER = 8;

    /**
     * Composes the {@code ## Active workers} prompt block — a permanent
     * status view of Arthur's children, independent of inbox events.
     * Mirrors Eddies {@code <delegated_workers>} but uses the
     * parent-child link from {@code ThinkProcessDocument.parentProcessId}
     * (Arthur's children live in the same session) rather than Eddie's
     * cross-project {@code WorkerLinks}.
     *
     * <p>Without this block, a worker that hasn't sent a PROCESS_EVENT
     * yet (still mid-run after spawn) is invisible to Arthur — the LLM
     * has to remember its own spawn-state across turns, which is the
     * exact failure mode in the Vibecoding session (Marvin just
     * spawned, Ford's stale reply arrived, Arthur conflated them).
     */
    private @Nullable String composeActiveWorkersBlock(ThinkProcessDocument process) {
        // Prefer workerLinks (canonical in-doc view, updated by
        // reconcileWorkerLinksFromInbox on every turn). For sessions
        // that predate workerLinks-for-Arthur the snapshot is empty;
        // fall back to the parent-process scan so legacy sessions
        // still see their children.
        List<de.mhus.vance.shared.thinkprocess.WorkerLinkSnapshot> links =
                thinkProcessService.findWorkerLinks(process.getId());
        if (links != null && !links.isEmpty()) {
            return renderActiveWorkersBlockFromLinks(
                    links, ACTIVE_WORKERS_MAX_RENDER, java.time.Instant.now());
        }
        List<ThinkProcessDocument> children = thinkProcessService
                .findByParentProcessId(process.getId());
        return renderActiveWorkersBlock(children, ACTIVE_WORKERS_MAX_RENDER, java.time.Instant.now());
    }

    /**
     * Render active-workers from the persisted {@code workerLinks}
     * snapshot — the new canonical view (set by
     * {@link #handleDelegate}, refreshed by
     * {@link #reconcileWorkerLinksFromInbox}, removed on terminal
     * events). Equivalent format to
     * {@link #renderActiveWorkersBlock(List, int, java.time.Instant)}
     * so the LLM sees no shape difference between the two paths.
     */
    static @Nullable String renderActiveWorkersBlockFromLinks(
            @Nullable List<de.mhus.vance.shared.thinkprocess.WorkerLinkSnapshot> links,
            int maxRender,
            java.time.Instant now) {
        if (links == null || links.isEmpty()) return null;
        var visible = links.stream()
                .filter(l -> l.getWorkerStatus() != ThinkProcessStatus.CLOSED)
                .sorted((a, b) -> {
                    java.time.Instant ai = a.getLastSeen();
                    java.time.Instant bi = b.getLastSeen();
                    if (ai == null && bi == null) return 0;
                    if (ai == null) return 1;
                    if (bi == null) return -1;
                    return bi.compareTo(ai);
                })
                .limit(maxRender)
                .toList();
        if (visible.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("## Active workers\n\n");
        sb.append("Workers you have spawned that have not closed yet. Use this "
                + "to ground RELAY / process_steer / process_stop targets and to "
                + "decide whether to spawn another one — never claim a worker has "
                + "finished if it still appears here.\n\n");
        for (var l : visible) {
            String name = l.getWorkerProcessName() == null
                    || l.getWorkerProcessName().isBlank()
                    ? l.getWorkerProcessId() : l.getWorkerProcessName();
            sb.append("- ").append(name);
            sb.append(" (status=").append(l.getWorkerStatus() == null
                    ? "running"
                    : l.getWorkerStatus().name().toLowerCase(java.util.Locale.ROOT));
            java.time.Instant updated = l.getLastSeen();
            if (updated != null && now != null) {
                long ageSec = Math.max(0, java.time.Duration.between(updated, now).getSeconds());
                sb.append(", last activity ").append(formatAge(ageSec)).append(" ago");
            }
            sb.append(")\n");
        }
        return sb.toString();
    }

    /**
     * Legacy fallback: render active-workers from a Mongo scan over
     * {@code thinkProcessService.findByParentProcessId}. Used for
     * sessions that pre-date Arthur's workerLinks bookkeeping (no
     * {@code workerLinks} entries on the chat-process). Pure function
     * for unit-testability.
     */
    static @Nullable String renderActiveWorkersBlock(
            @Nullable List<ThinkProcessDocument> children,
            int maxRender,
            java.time.Instant now) {
        if (children == null || children.isEmpty()) return null;
        var visible = children.stream()
                .filter(c -> c.getStatus() != ThinkProcessStatus.CLOSED)
                .sorted((a, b) -> {
                    java.time.Instant ai = a.getUpdatedAt();
                    java.time.Instant bi = b.getUpdatedAt();
                    if (ai == null && bi == null) return 0;
                    if (ai == null) return 1;
                    if (bi == null) return -1;
                    return bi.compareTo(ai);
                })
                .limit(maxRender)
                .toList();
        if (visible.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("## Active workers\n\n");
        sb.append("Workers you have spawned that have not closed yet. Use this "
                + "to ground RELAY / process_steer / process_stop targets and to "
                + "decide whether to spawn another one — never claim a worker has "
                + "finished if it still appears here.\n\n");
        for (ThinkProcessDocument c : visible) {
            String name = c.getName() == null || c.getName().isBlank()
                    ? c.getId() : c.getName();
            sb.append("- ").append(name)
                    .append(" (").append(c.getThinkEngine())
                    .append(", status=").append(c.getStatus().name().toLowerCase(java.util.Locale.ROOT));
            java.time.Instant updated = c.getUpdatedAt();
            if (updated != null && now != null) {
                long ageSec = Math.max(0, java.time.Duration.between(updated, now).getSeconds());
                sb.append(", last activity ").append(formatAge(ageSec)).append(" ago");
            }
            sb.append(")\n");
        }
        return sb.toString();
    }

    /** Compact relative-time formatter for the active-workers block. */
    static String formatAge(long seconds) {
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h";
        return (hours / 24) + "d";
    }

    /** Rendering helper that needs access to the {@code thinkProcessService}. */
    private String renderForLlm(
            SteerMessage m,
            Map<String, String> eventIdToToken,
            boolean multiEventDrain) {
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
            // eventRef: short token the LLM passes back when emitting
            // RELAY (e.g. "ev1"). Only rendered when more than one
            // event is in the drain — single-event drains auto-pick
            // in handleRelay so the LLM doesn't need to copy any id
            // at all. UUID stays inside the engine for logs.
            String token = pe.eventId() == null
                    ? null
                    : eventIdToToken.get(pe.eventId());
            if (multiEventDrain && token != null) {
                sb.append(" eventRef=\"")
                        .append(escapeAttr(token))
                        .append("\"");
            }
            // respondingToTurnAt: the user-input turn the emitting
            // worker was processing. Lets the LLM see which user
            // question this is a reply to — a Ford reply that's
            // attributed to a turn older than the current outgoing
            // delegate is a stale reply, NOT a result from the just-
            // spawned worker. See planning/arthur-process-event-
            // attribution.md.
            if (pe.inResponseToAt() != null) {
                sb.append(" respondingToTurnAt=\"")
                        .append(escapeAttr(pe.inResponseToAt().toString()))
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
                        .append("\"");
                if (pe.eventId() != null && !pe.eventId().isBlank()) {
                    sb.append(" eventId=\"")
                            .append(escapeAttr(pe.eventId()))
                            .append("\"");
                }
                if (pe.inResponseToAt() != null) {
                    sb.append(" respondingToTurnAt=\"")
                            .append(escapeAttr(pe.inResponseToAt().toString()))
                            .append("\"");
                }
                sb.append(" type=\"")
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
            case SteerMessage.Reply r -> {
                // A worker explicitly emitted a semantic reply via
                // ProgressEmitter#emitReply — its complete answer for
                // one turn. Carries the full content verbatim. See
                // planning/process-engine-reply-channel.md.
                StringBuilder sb = new StringBuilder();
                sb.append("<process-event sourceProcessId=\"")
                        .append(escapeAttr(r.sourceProcessId()))
                        .append("\"");
                if (r.sourceProcessName() != null && !r.sourceProcessName().isBlank()) {
                    sb.append(" sourceProcessName=\"")
                            .append(escapeAttr(r.sourceProcessName()))
                            .append("\"");
                }
                if (r.inResponseToAt() != null) {
                    sb.append(" respondingToTurnAt=\"")
                            .append(escapeAttr(r.inResponseToAt().toString()))
                            .append("\"");
                }
                sb.append(" type=\"reply\">");
                sb.append(escapeText(r.content()));
                sb.append("</process-event>");
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

    private static ChatMessage toLangchain(ChatMessageDocument msg, boolean collabActive) {
        return de.mhus.vance.brain.chat.ChatHistoryRenderer.toLangchain(msg, collabActive);
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
    /**
     * Concatenates the text of every {@link SteerMessage.UserChatInput}
     * in the current turn's inbox — that's the query the RAG auto-inject
     * embeds against. Returns {@code null} when the inbox carries no
     * user text (e.g. wakeup-only turn).
     */
    private static @org.jspecify.annotations.Nullable String latestUserInputText(
            java.util.List<SteerMessage> inbox) {
        StringBuilder sb = new StringBuilder();
        for (SteerMessage m : inbox) {
            if (m instanceof SteerMessage.UserChatInput uci
                    && uci.content() != null
                    && !uci.content().isBlank()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(uci.content());
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

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
        // Pass the live projectId so the cascade includes user-namespace
        // recipes that Slart's Phase-D persists into
        // `recipes/_user/<name>.yaml` of the active project. Without the
        // projectId, the listing falls back to the _vance system project
        // and Arthur cannot see the user's just-authored recipes — Arthur
        // then either refuses ("rat-der-macher is unknown") or tries to
        // re-create them, which collides at PERSISTING.
        String projectId = process.getProjectId();
        java.util.List<ResolvedRecipe> recipes = recipeLoader.listAll(
                process.getTenantId(),
                projectId == null || projectId.isBlank() ? null : projectId);
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
        return ChatBehaviorBuilder.resolveForProcess(process, settings, modelResolver);
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
