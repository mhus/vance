package de.mhus.vance.brain.eddie;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.EngineChatFactory;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.ai.ModelInfo;
import de.mhus.vance.brain.ai.ModelSize;
import de.mhus.vance.brain.ai.VanceSystemMessage;
import de.mhus.vance.brain.eddie.activity.EddieActivityEntry;
import de.mhus.vance.brain.eddie.activity.EddieActivityService;
import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.brain.events.StreamingProperties;
import de.mhus.vance.brain.memory.MemoryContextLoader;
import de.mhus.vance.brain.progress.LlmCallTracker;
import de.mhus.vance.brain.thinkengine.EnginePromptResolver;
import de.mhus.vance.brain.thinkengine.ParentReport;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.SystemPrompts;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.brain.thinkengine.action.EngineAction;
import de.mhus.vance.brain.thinkengine.action.StructuredActionEngine;
import de.mhus.vance.brain.thinkengine.action.StructuredActionEngine.ActionLoopResult;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.PendingMessageType;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Vance — the per-user personal hub engine, running in the user's
 * Home project.
 *
 * <p>Eddie is a structured-action engine ({@link
 * StructuredActionEngine}): she emits one {@link EddieActionSchema}
 * action per turn instead of free-form tool-calls. The vocabulary
 * differs from Arthur's because Eddie has two extra concerns —
 * <b>cross-project orchestration</b> ({@code DELEGATE_PROJECT} +
 * {@code STEER_PROJECT}) and <b>output routing between voice and
 * inbox</b> ({@code RELAY} vs {@code RELAY_INBOX}). Common actions
 * ({@code ANSWER}, {@code ASK_USER}, {@code WAIT}, {@code REJECT})
 * are shared with Arthur.
 *
 * <p>Eddie reads aloud. Her chat-replies are voice-friendly — full
 * sentences, no Markdown lists or headers. Long structured outputs
 * from worker projects (recipes, plans, analyses) go through
 * {@code RELAY_INBOX}: the worker's content lands in the user's
 * inbox, and Eddie speaks a one-line announcement. The decision
 * "speak vs save" is semantic — Eddie judges based on the user's
 * intent and the situation, not a hard size threshold.
 *
 * <p>Hub-specific machinery (Activity-Log writes, peer-recap on
 * resume, peer-event handling) hooks around the action layer.
 * Activity-Log entries are written by the underlying tools
 * ({@code project_create}, {@code project_chat_send}, …) when
 * Eddie's action handlers invoke them — no separate wiring needed.
 *
 * <p>See {@code specification/eddie-engine.md}.
 */
@Component
@Slf4j
public class EddieEngine extends StructuredActionEngine {

    public static final String NAME = "eddie";
    public static final String VERSION = "0.2.0";

    public static final String GREETING =
            "Hi, I'm Eddie. What can I take off your plate?";

    /**
     * Eddie's engine-base allow-set is intentionally <b>empty</b>
     * (unrestricted). The recipe cascade in {@code eddie.yaml} drives
     * primary / deferred / removed classification via
     * {@link de.mhus.vance.brain.tools.ContextToolsApi#classify} —
     * when {@code base.isEmpty()} that function expands the pool to
     * every dispatchable tool and applies the recipe's
     * Add/Remove/Defer overlays.
     *
     * <p>Same pattern as Arthur. The previous static
     * {@code ALLOWED_TOOLS} / {@code LLM_VISIBLE_TOOLS} pair carried
     * the per-tool maintenance burden of remembering every new write
     * tool — that's now label-driven (recipe defers
     * {@code @write}/{@code @executive}/{@code @side-effect}) plus a
     * small handful of explicit destructive excludes in the recipe.
     * Action-internal tools ({@code project_create},
     * {@code project_chat_send}, {@code inbox_post}) are reached via
     * {@code invokeInternal}; they ride the dispatch pool same as
     * everything else.
     */
    private static final Set<String> ALLOWED_TOOLS = Set.of();

    private static final String PROMPT_PATH = "prompts/eddie-prompt.md";
    private static final String PROMPT_RESOURCE = "vance-defaults/prompts/eddie-prompt.md";

    /**
     * Aligned with Arthur's recipe (6) — Eddie's "self-work" path
     * (web_search → web_fetch → doc_create_text → ANSWER) already
     * eats 4 iterations; with Plan-Mode actions now in her schema
     * and the occasional discovery tool (find_tools / describe_tool)
     * pre-amble, 4 leaves no slack for the final ANSWER and the
     * action-loop trips "max-iters" with no user-facing output. Six
     * is generous for the typical hub-turn (most are 1-2 iterations
     * total) and rescues the longer self-work flows.
     */
    /**
     * Fallback for {@code maxIterations} when the recipe doesn't pin
     * it — mirrors {@code eddie.yaml}'s default. Recipe is the source
     * of truth; this is just the last resort.
     */
    private static final int DEFAULT_MAX_ITERATIONS = 6;

    /**
     * Document path inside the per-user hub project for the
     * persona-summary that's always loaded into Eddie's prompt.
     * Eddie maintains this herself via {@code LEARN scope=persona}.
     */
    static final String PERSONA_DOC_PATH = "eddie/persona.md";

    /**
     * Document path for the append-only journal of facts about the
     * user (preferences, dislikes, dates, etc.). Eddie writes via
     * {@code LEARN scope=fact} and reads it back into the prompt
     * each turn so she remembers across sessions.
     */
    static final String FACTS_DOC_PATH = "eddie/facts.md";

    /**
     * Soft limit on the size of the facts file that gets included in
     * the prompt verbatim. If the file exceeds this, only the tail
     * fits and Eddie should consolidate via persona-edits. Keeps the
     * prompt budget bounded.
     */
    private static final int FACTS_PROMPT_BUDGET_CHARS = 8_000;

    /**
     * Max number of {@link de.mhus.vance.shared.eddie.WorkerLinkSnapshot}
     * entries Eddie surfaces in the {@code <delegated_workers>} prompt
     * block. Older / DONE entries beyond this cap stay in Mongo for
     * audit but get clipped from the render so the prompt can't grow
     * unbounded when a session spawns many short-lived workers.
     */
    private static final int DELEGATED_WORKERS_MAX_RENDER = 10;

    /**
     * Cap on the auto-suffix loop in {@link #handleDelegateProject}. If
     * {@code <name>}, {@code <name>-2}, …, {@code <name>-N} are all
     * taken, give up — usually means something else is going on
     * (Mongo write blocked, tenant pre-seeded with conflicting test
     * fixtures, etc.) and continuing to loop won't help.
     */
    private static final int MAX_PROJECT_NAME_TRIES = 10;

    /**
     * Action types Eddie is forbidden from emitting on a turn that was
     * triggered without any USER_CHAT_INPUT. These spawn / push new
     * work that an event-only turn has no business doing —
     * DELEGATE_PROJECT creates new projects + Arthur chats,
     * STEER_PROJECT keeps prodding a child Arthur. Everything else
     * (ANSWER, ASK_USER, RELAY, RELAY_INBOX, LEARN, MEDIATE, WAIT,
     * REJECT) is fine on an event turn: those report state, ask a
     * direct chat question (no inbox detour — ASK_USER is just a
     * spoken/chat question that pauses for the user's reply),
     * absorb context, or yield. Mirrors the Arthur
     * {@code SPAWN_ACTIONS_FORBIDDEN_ON_EVENT_TURNS} gate; ASK_USER
     * was originally listed here but removed once we confirmed that
     * Eddie's ASK_USER is a plain conversational question, not a
     * spawn that could cascade.
     */
    private static final Set<String> SPAWN_ACTIONS_FORBIDDEN_ON_EVENT_TURNS = Set.of(
            EddieActionSchema.TYPE_DELEGATE_PROJECT,
            EddieActionSchema.TYPE_STEER_PROJECT);


    // ──────────────────── Dependencies ────────────────────

    private final ThinkProcessService thinkProcessService;
    private final ModelCatalog modelCatalog;
    private final EngineChatFactory engineChatFactory;
    private final EnginePromptResolver enginePromptResolver;
    private final de.mhus.vance.brain.prompt.PromptTemplateRenderer promptTemplateRenderer;
    private final MemoryContextLoader memoryContextLoader;
    private final EddieActivityService activityService;
    private final de.mhus.vance.shared.session.SessionService sessionService;
    private final EngineMessageRouter messageRouter;
    private final DocumentService documentService;
    private final de.mhus.vance.brain.eddie.connection.EddieWorkerConnectionPool workerConnectionPool;
    private final de.mhus.vance.brain.eddie.connection.EddieFrameRouter workerFrameRouter;
    private final de.mhus.vance.shared.jwt.JwtService jwtService;
    private final de.mhus.vance.shared.access.ProfileRegistry profileRegistry;
    private final de.mhus.vance.brain.thinkengine.plan.PlanModeService planModeService;
    private final de.mhus.vance.shared.workspace.WorkspaceService workspaceService;

    /**
     * Per-process flag tracking whether the in-flight turn was
     * triggered by fresh USER_CHAT_INPUT (vs. purely by a
     * parent-notification / tool-result / external-command). Populated
     * at the start of {@link #runTurnFor} and consulted by
     * {@link #handleAction} to gate spawn-actions — same pattern as
     * Arthur, see {@code SPAWN_ACTIONS_FORBIDDEN_ON_EVENT_TURNS}.
     */
    private final ConcurrentMap<String, Boolean> currentTurnHadUserInput =
            new ConcurrentHashMap<>();

    public EddieEngine(
            StreamingProperties streamingProperties,
            LlmCallTracker llmCallTracker,
            ObjectMapper objectMapper,
            ThinkProcessService thinkProcessService,
            ModelCatalog modelCatalog,
            EngineChatFactory engineChatFactory,
            EnginePromptResolver enginePromptResolver,
            MemoryContextLoader memoryContextLoader,
            EddieActivityService activityService,
            de.mhus.vance.shared.session.SessionService sessionService,
            EngineMessageRouter messageRouter,
            DocumentService documentService,
            de.mhus.vance.brain.eddie.connection.EddieWorkerConnectionPool workerConnectionPool,
            de.mhus.vance.brain.eddie.connection.EddieFrameRouter workerFrameRouter,
            de.mhus.vance.shared.jwt.JwtService jwtService,
            de.mhus.vance.shared.access.ProfileRegistry profileRegistry,
            de.mhus.vance.brain.thinkengine.plan.PlanModeService planModeService,
            de.mhus.vance.brain.prompt.PromptTemplateRenderer promptTemplateRenderer,
            de.mhus.vance.shared.workspace.WorkspaceService workspaceService) {
        super(streamingProperties, llmCallTracker, objectMapper);
        this.thinkProcessService = thinkProcessService;
        this.modelCatalog = modelCatalog;
        this.engineChatFactory = engineChatFactory;
        this.enginePromptResolver = enginePromptResolver;
        this.memoryContextLoader = memoryContextLoader;
        this.activityService = activityService;
        this.sessionService = sessionService;
        this.messageRouter = messageRouter;
        this.documentService = documentService;
        this.workerConnectionPool = workerConnectionPool;
        this.workerFrameRouter = workerFrameRouter;
        this.jwtService = jwtService;
        this.profileRegistry = profileRegistry;
        this.planModeService = planModeService;
        this.promptTemplateRenderer = promptTemplateRenderer;
        this.workspaceService = workspaceService;
    }

    // ──────────────────── Metadata ────────────────────

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "Eddie (Personal Hub)";
    }

    @Override
    public String description() {
        return "Personal hub agent. Lives in the per-user Home project; "
                + "creates, observes, and steers regular projects on the "
                + "user's behalf. Speaks; does not do content work itself.";
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public Set<String> allowedTools() {
        return ALLOWED_TOOLS;
    }

    @Override
    public boolean allowsCrossProjectSpawn() {
        return true;
    }

    // Eddie deliberately does NOT override bundledConfig() — chat
    // spawn falls through to RecipeResolver.applyDefaulting("eddie",
    // …) which loads eddie.yaml from the cascade
    // (project → _vance → classpath:vance-defaults/recipes/). That
    // lets a user override their personal Eddie's params (model,
    // RAG injection, manual paths, …) by dropping their own
    // recipes/eddie.yaml into their user project — same mechanism
    // that already works for Arthur. The default ThinkEngine impl
    // returns Optional.empty() which is what we want.
    //
    // The Eddie system prompt is resolved lazily at turn time via
    // engineDefaultPrompt(process) below; same cascade
    // (project → _vance → classpath:prompts/eddie-prompt.md).

    /**
     * Resolves Eddie's system prompt through the document cascade so
     * a user can drop {@code prompts/eddie-prompt.md} into their own
     * project to customise their hub assistant. Falls back to the
     * bundled classpath resource — never returns blank.
     */
    private String engineDefaultPrompt(ThinkProcessDocument process) {
        String basePath = paramString(process, "promptDocument", PROMPT_PATH);
        return enginePromptResolver.resolveForTenant(
                process.getTenantId(), process.getProjectId(),
                basePath, loadResource(PROMPT_RESOURCE));
    }

    private static String loadResource(String path) {
        ClassPathResource resource = new ClassPathResource(path);
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to load Eddie prompt resource: " + path, e);
        }
    }

    /**
     * Recognise the typed
     * {@link de.mhus.vance.shared.project.ProjectService.ProjectAlreadyExistsException}
     * regardless of whether {@code project_create} surfaced it directly
     * or wrapped it in a {@link ToolException}. Used by the
     * DELEGATE_PROJECT handler to decide between "internal-name retry"
     * and "real failure → propagate to user". Falls back to message
     * matching when the cause chain is empty — defensive against a
     * future refactor that drops the wrapped cause.
     */
    private static boolean isProjectNameTaken(RuntimeException e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof de.mhus.vance.shared.project.ProjectService.ProjectAlreadyExistsException) {
                return true;
            }
            t = t.getCause();
        }
        String msg = e.getMessage();
        return msg != null && msg.contains("already exists in tenant");
    }

    // ──────────────────── Lifecycle ────────────────────

    @Override
    public void start(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.info("Eddie.start tenant='{}' session='{}' id='{}'",
                process.getTenantId(), process.getSessionId(), process.getId());
        String greeting = composeGreetingWithRecap(process);
        ctx.chatMessageService().append(ChatMessageDocument.builder()
                .tenantId(process.getTenantId())
                .sessionId(process.getSessionId())
                .thinkProcessId(process.getId())
                .role(ChatRole.ASSISTANT)
                .content(greeting)
                .build());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
    }

    @Override
    public void resume(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.debug("Eddie.resume id='{}'", process.getId());
        String recap = buildPeerRecap(process);
        if (recap != null) {
            ctx.chatMessageService().append(ChatMessageDocument.builder()
                    .tenantId(process.getTenantId())
                    .sessionId(process.getSessionId())
                    .thinkProcessId(process.getId())
                    .role(ChatRole.ASSISTANT)
                    .content(recap)
                    .build());
        }
        // Re-open Working-WS for every persisted worker link. Pool is
        // pod-local — this rebuild is what makes Eddie's observation
        // channels survive a Mongo-only state transfer to a new pod.
        // Failures are logged and skipped: the worker can still be
        // re-observed via process_observe later if the user prompts it.
        reconnectWorkerLinks(process);
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
    }

    @Override
    public void suspend(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.debug("Eddie.suspend id='{}'", process.getId());
        // Tear down the in-memory Working-WS pool slice for this Eddie
        // process. The persisted WorkerLinkSnapshots stay — resume
        // rebuilds the connections from there.
        try {
            workerConnectionPool.closeAll(process.getId());
        } catch (RuntimeException e) {
            log.debug("Eddie.suspend pool close failed for id='{}': {}",
                    process.getId(), e.toString());
        }
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.SUSPENDED);
    }

    /**
     * Walks {@link ThinkProcessDocument#getWorkerLinks()} and
     * re-establishes a Working-WS for each entry through
     * {@code workerConnectionPool}. Issues a fresh short-lived JWT for
     * the user via {@link de.mhus.vance.shared.jwt.JwtService} so the
     * worker pod can validate the connection just like a direct user
     * connect.
     *
     * <p>Best-effort: a connection failure on one link doesn't stop
     * the others. The link itself stays persisted, so a future call
     * (auto-observe, manual {@code process_observe}, or another resume)
     * can retry.
     */
    private void reconnectWorkerLinks(ThinkProcessDocument process) {
        var links = process.getWorkerLinks();
        if (links == null || links.isEmpty()) return;
        String tenantId = process.getTenantId();
        // Eddie's process is owned by the user; we use the project name
        // (which for the user-project is _user_<username>) to recover
        // the user identity. Defensive: if the project id isn't a
        // _user_ container, fall back to the process's tenant default.
        String userId = process.getProjectId() != null
                && process.getProjectId().startsWith("_user_")
                ? process.getProjectId().substring("_user_".length())
                : tenantId;
        java.time.Instant exp = java.time.Instant.now().plus(java.time.Duration.ofMinutes(15));
        String jwt;
        try {
            jwt = jwtService.createToken(tenantId, userId, exp);
        } catch (RuntimeException e) {
            log.warn("Eddie.resume: cannot issue JWT for reconnect (tenant='{}' user='{}'): {}",
                    tenantId, userId, e.toString());
            return;
        }
        for (var link : links) {
            try {
                workerConnectionPool.openOrReuse(
                        process.getId(), link, jwt, workerFrameRouter);
            } catch (RuntimeException e) {
                log.debug("Eddie.resume: reconnect to worker={} failed: {}",
                        link.getWorkerProcessId(), e.toString());
            }
        }
    }

    @Override
    public void stop(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.info("Eddie.stop id='{}'", process.getId());
        thinkProcessService.closeProcess(process.getId(), CloseReason.STOPPED);
    }

    @Override
    public void steer(ThinkProcessDocument process, ThinkEngineContext ctx, SteerMessage message) {
        runTurnFor(process, ctx, List.of(message));
    }

    @Override
    public void runTurn(ThinkProcessDocument process, ThinkEngineContext ctx) {
        // Plan-Mode self-continuation — mirrors ArthurEngine.runTurn.
        // While in EXPLORING / PLANNING / EXECUTING, an IDLE status
        // after a turn means "Eddie wants to keep working" (the user
        // accepted the plan via START_EXECUTION and didn't say anything
        // else), so we re-fire runTurnFor with an empty inbox and let
        // the LLM emit the next todo step. NORMAL-mode IDLE genuinely
        // means "waiting for the next user message" — no continuation.
        //
        // Budget bounds runaway action loops (model emitting the same
        // idempotent transition repeatedly). At budget exhaustion the
        // process moves to BLOCKED so the user sees the engine paused
        // instead of leaving it silently IDLE.
        //
        // The silent-turn-in-a-row guard is the sharper circuit
        // breaker: if 3 turns in a row produce no chat and no tool
        // use, abort early — the model is stuck.
        final int continuationBudget = 8;
        final int silentTurnsLimit = 3;
        int continuationsRemaining = continuationBudget;
        int silentTurnsInARow = 0;
        boolean continueWithEmptyInbox = false;
        while (true) {
            if (thinkProcessService.isHaltRequested(process.getId())) {
                log.info("Eddie.runTurn id='{}' — halt requested, yielding",
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
                    log.warn("Eddie.runTurn id='{}' — continuation budget "
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
            if (signal.madeProgress()) {
                silentTurnsInARow = 0;
            } else {
                silentTurnsInARow++;
                if (silentTurnsInARow >= silentTurnsLimit) {
                    log.warn("Eddie.runTurn id='{}' — {} silent turns in a row "
                            + "(LLM stuck — no chat, no tool calls); transitioning "
                            + "to BLOCKED so the user can intervene",
                            process.getId(), silentTurnsLimit);
                    thinkProcessService.updateStatus(
                            process.getId(), ThinkProcessStatus.BLOCKED);
                    return;
                }
            }

            ThinkProcessStatus currentStatus = thinkProcessService
                    .findById(process.getId())
                    .map(ThinkProcessDocument::getStatus)
                    .orElse(ThinkProcessStatus.SUSPENDED);
            de.mhus.vance.api.thinkprocess.ProcessMode currentMode = process.getMode();
            boolean activeMode = currentMode != null
                    && currentMode != de.mhus.vance.api.thinkprocess.ProcessMode.NORMAL;
            // Continue if (a) mode changed (entered new plan-mode phase),
            // OR (b) we're in any active plan-mode and the engine isn't
            // waiting on user input. PROPOSE_PLAN / ANSWER / ASK_USER set
            // awaiting=true → BLOCKED → no continuation, the user's next
            // message reactivates via the regular pending pipeline.
            if (currentStatus == ThinkProcessStatus.IDLE
                    && (currentMode != modeBefore || activeMode)) {
                continueWithEmptyInbox = true;
            }
        }
    }

    /**
     * Per-turn signal returned by {@link #runTurnFor}. {@link #runTurn}
     * uses {@link #madeProgress()} to break the plan-mode self-
     * continuation loop early when the LLM produced neither chat nor
     * tool calls — the silent-loop circuit-breaker.
     */
    private record TurnSignal(boolean appendedChat, boolean toolUsed) {
        boolean madeProgress() {
            return appendedChat || toolUsed;
        }
    }

    /**
     * Renders the live plan-mode state for the system prompt. When
     * Plan-Mode is active (EXPLORING / PLANNING / EXECUTING) the
     * block carries the current mode plus mode-specific guidance.
     * In EXECUTING (or anytime todos exist) it includes the live
     * TodoList with status markers ({@code [ ]}/{@code [~]}/{@code [✓]}).
     * In NORMAL with no todos → empty string (block is skipped).
     *
     * <p>The same guidance loop matters in EXPLORING too: without a
     * mode marker the LLM emits {@code START_PLAN} idempotently turn
     * after turn instead of actually exploring or proposing a plan.
     * Same fix as the EXECUTING re-emit loop — tell the LLM where
     * it is and what to do next.
     */
    private String buildTodoListBlock(ThinkProcessDocument process) {
        java.util.List<de.mhus.vance.api.thinkprocess.TodoItem> todos = process.getTodos();
        de.mhus.vance.api.thinkprocess.ProcessMode mode = process.getMode();
        boolean activeMode = mode != null
                && mode != de.mhus.vance.api.thinkprocess.ProcessMode.NORMAL;
        boolean hasTodos = todos != null && !todos.isEmpty();
        if (!activeMode && !hasTodos) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## Current TodoList (mode=")
                .append(mode == null ? "NORMAL" : mode.name())
                .append(")\n\n");

        // Mode-specific guidance for the no-todos cases (EXPLORING
        // straight after START_PLAN, PLANNING straight after a fresh
        // PROPOSE_PLAN where todos haven't persisted yet). Without
        // this the LLM falls into a re-emit loop on the just-emitted
        // transition action.
        if (!hasTodos) {
            String modeGuidance = switch (mode) {
                case EXPLORING -> "Current mode: **EXPLORING** — you just "
                        + "entered this via START_PLAN. NEVER emit START_PLAN "
                        + "again, you're already in it. Now actually explore: "
                        + "`web_search`, `doc_read`, `doc_list`, `manual_read`, "
                        + "or any read-only tool to gather what you need. "
                        + "Once you have enough information, emit `PROPOSE_PLAN` "
                        + "with a structured plan + todos.";
                case PLANNING -> "Current mode: **PLANNING** — plan proposed, "
                        + "awaiting user. The user's next message will either "
                        + "accept (emit START_EXECUTION) or revise (emit a "
                        + "fresh PROPOSE_PLAN). Do not re-emit PROPOSE_PLAN "
                        + "from your own initiative — wait for the user.";
                case EXECUTING -> "Current mode: **EXECUTING** — but the "
                        + "TodoList is empty. This is a degenerate state — "
                        + "emit ANSWER explaining what was done (if anything) "
                        + "and the plan is complete.";
                default -> null;
            };
            if (modeGuidance != null) {
                sb.append(modeGuidance).append("\n");
                return sb.toString();
            }
        }
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
                + "the actual work for that step now — typically "
                + "`web_search` for research items, `doc_create_text` / "
                + "`doc_edit` to persist results, `DELEGATE_PROJECT` / "
                + "`STEER_PROJECT` for substantial worker hand-off — "
                + "then TODO_UPDATE it to COMPLETED in the same turn "
                + "or the next.\n\n"
                + "**Hard rules — never regress state:**\n"
                + "- NEVER emit TODO_UPDATE that downgrades an item: "
                + "[✓] COMPLETED stays COMPLETED forever; [~] IN_PROGRESS "
                + "must not go back to [ ] PENDING.\n"
                + "- NEVER emit START_EXECUTION again — the mode header "
                + "above already says EXECUTING. Emitting it a second "
                + "time is a no-op that wastes a turn; do real work or "
                + "TODO_UPDATE instead.\n"
                + "- If you see [✓] COMPLETED items above, that work is "
                + "**already done in a previous turn** — DO NOT redo it. "
                + "Skip past them to the first non-COMPLETED row.\n"
                + "- If everything is [✓] COMPLETED, emit ANSWER with a "
                + "brief summary of what was done. Do not look for "
                + "additional work the plan didn't list.\n");
        return sb.toString();
    }

    @Override
    public ParentReport summarizeForParent(
            ThinkProcessDocument process, ProcessEventType eventType) {
        // Default: generic summary. The Activity-Log captures detail
        // for cross-Eddie sync; parents (rarely a thing for Eddie since
        // she lives at the top of the scope tree) get the same one-line
        // marker as any other engine.
        return ParentReport.of(
                "Eddie process " + process.getId()
                        + " status=" + eventType.name().toLowerCase());
    }

    /**
     * Initial greeting plus, if there's recent peer activity, a
     * one-line recap.
     */
    private String composeGreetingWithRecap(ThinkProcessDocument process) {
        String recap = buildPeerRecap(process);
        if (recap == null) return GREETING;
        return GREETING + " " + recap;
    }

    private @Nullable String buildPeerRecap(ThinkProcessDocument process) {
        var sessionOpt = sessionService.findBySessionId(process.getSessionId());
        if (sessionOpt.isEmpty()) return null;
        String userId = sessionOpt.get().getUserId();
        if (userId == null || userId.isBlank()) return null;

        List<EddieActivityEntry> peers = activityService.readPeerRecap(
                process.getTenantId(), userId, process.getId());
        if (peers.isEmpty()) return null;
        if (peers.size() == 1) {
            return "Kurzer Stand: " + peers.get(0).getSummary() + ".";
        }
        int top = Math.min(3, peers.size());
        StringBuilder sb = new StringBuilder("Kurzer Stand: ");
        for (int i = 0; i < top; i++) {
            if (i > 0) sb.append(i == top - 1 ? " und " : ", ");
            sb.append(peers.get(i).getSummary());
        }
        if (peers.size() > top) {
            sb.append(" — plus ").append(peers.size() - top).append(" weitere");
        }
        sb.append(".");
        return sb.toString();
    }

    // ──────────────────── One turn ────────────────────

    private TurnSignal runTurnFor(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            List<SteerMessage> inbox) {

        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.RUNNING);
        // Per-turn flag for handleAction: was this turn triggered by a
        // fresh user message, or purely by an in-bound process-event /
        // tool-result? Event-only turns must not emit spawn-actions
        // (DELEGATE_PROJECT / STEER_PROJECT / ASK_USER) — the root cause
        // of the recurring "Eddie keeps trying" cascade the user has
        // observed across sessions.
        boolean hadUserInput = false;
        for (SteerMessage m : inbox) {
            if (m instanceof SteerMessage.UserChatInput uci
                    && uci.content() != null && !uci.content().isBlank()) {
                hadUserInput = true;
                break;
            }
        }
        currentTurnHadUserInput.put(process.getId(), hadUserInput);
        boolean awaitingUserInput = false;
        try {
            ChatMessageService chatLog = ctx.chatMessageService();

            // Persist user-typed messages into the chat log so future
            // turns see them in history. Other inbox kinds are
            // turn-local context.
            for (SteerMessage m : inbox) {
                if (m instanceof SteerMessage.UserChatInput uci) {
                    chatLog.append(ChatMessageDocument.builder()
                            .tenantId(process.getTenantId())
                            .sessionId(process.getSessionId())
                            .thinkProcessId(process.getId())
                            .role(ChatRole.USER)
                            .content(uci.content())
                            .build());
                }
            }

            EngineChatFactory.EngineChatBundle chatBundle =
                    engineChatFactory.forProcess(process, ctx, NAME);
            AiChat aiChat = chatBundle.chat();
            AiChatConfig config = chatBundle.primaryConfig();
            ModelInfo modelInfo = modelCatalog.lookupOrDefault(
                    process.getTenantId(), process.getProjectId(),
                    config.provider(), config.modelName());
            ModelSize effectiveSize = ModelSize.parseOrAuto(
                    paramString(process, "modelSize", null), modelInfo.size());

            List<ChatMessage> messages = buildPromptMessages(
                    process, chatLog, inbox, modelInfo, effectiveSize, ctx);
            int maxIters = paramInt(process, "maxIterations",
                    DEFAULT_MAX_ITERATIONS);
            log.debug("Eddie.turn id='{}' inbox={} historyMsgs={} model={} maxIters={}",
                    process.getId(), inbox.size(), messages.size(),
                    config.modelName(), maxIters);

            String modelAlias = config.provider() + ":" + config.modelName();

            // Recipe-driven manifest: read-only stays primary,
            // @write/@executive/@side-effect drop to the discovery
            // block (auto-activate on direct call). Action-internal
            // tools like project_create / project_chat_send /
            // inbox_post are reached from action handlers via
            // tools.invokeInternal() — same dispatch pool, just
            // bypassing the LLM-visibility gate.
            ActionLoopResult loopResult = runStructuredActionLoop(
                    aiChat, ContextToolsApi::primaryAsLc4j,
                    messages, ctx, process, maxIters, modelAlias,
                    modelInfo.actionLoopCorrections());

            ActionTurnOutcome outcome;
            // actionType is non-null only when the LLM produced a parseable
            // action (success path). Free-text fallbacks below leave it
            // null; the Web-UI then renders them as plain Markdown.
            String actionType = null;
            if (loopResult.isAction()) {
                actionType = loopResult.action().type();
                outcome = handleAction(loopResult.action(), process, ctx);
                // Post-LEARN consolidation: a small LLM pass within
                // Eddie's lane that resolves contradictions and trims
                // the just-updated persona / facts file. Runs ONLY
                // after a successful LEARN — other actions don't need
                // it. Failures are logged and left non-fatal: the raw
                // (un-consolidated) content stays on disk and the
                // user-facing turn is unaffected.
                if (EddieActionSchema.TYPE_LEARN.equals(loopResult.action().type())) {
                    runLearnConsolidation(loopResult.action(), aiChat,
                            process, ctx, modelAlias);
                }
            } else if ("max-iters".equals(loopResult.fallbackReason())
                    && loopResult.madeProgress()
                    && (process.getMode() == de.mhus.vance.api.thinkprocess.ProcessMode.EXECUTING
                        || process.getMode() == de.mhus.vance.api.thinkprocess.ProcessMode.EXPLORING
                        || process.getMode() == de.mhus.vance.api.thinkprocess.ProcessMode.PLANNING)) {
                // Plan-mode mid-execution pause: the LLM was actively
                // calling tools (web search, doc writes, …) and just
                // hit the per-turn cap before reaching a terminal
                // action. Don't BLOCK the user — yield non-awaiting
                // and let the outer self-continuation pick up the
                // next turn with a fresh iter budget. Persist any
                // free-text narration so the next turn's prompt
                // carries cross-turn memory of in-turn work.
                String narration = loopResult.fallbackText();
                String chatNote = (narration == null || narration.isBlank())
                        ? null
                        : narration;
                log.info("Eddie.turn id='{}' max-iters with progress "
                        + "({} tool invocations, narration={} chars) — "
                        + "yielding for outer continuation",
                        process.getId(), loopResult.toolInvocations(),
                        narration == null ? 0 : narration.length());
                outcome = new ActionTurnOutcome(chatNote, /*awaiting*/ false);
            } else {
                String text = loopResult.fallbackText();
                if (text != null && !text.isBlank()) {
                    // LLM emitted free text but no action — surface it
                    // as the user-facing reply rather than the internal
                    // diagnostic. The validator gave it 2 chances; this
                    // is the best we can do.
                    outcome = new ActionTurnOutcome(text, true);
                } else if (process.getMode()
                        == de.mhus.vance.api.thinkprocess.ProcessMode.EXECUTING
                        && allTodosCompleted(process)) {
                    // Graceful plan-completion close: the LLM emitted
                    // tool calls until everything in the plan was done,
                    // then went silent (Gemini 2.5 Pro occasionally
                    // returns empty STOP after many tool calls). Don't
                    // leak the "internal: action loop ..." string —
                    // synthesise a brief summary from the TodoList so
                    // the user sees a real reply.
                    outcome = new ActionTurnOutcome(
                            renderPlanCompletionSummary(process), true);
                } else {
                    // Genuine stuck path — LLM gave nothing usable, no
                    // plan to fall back on. Keep the diagnostic but
                    // mark it clearly as a system hint, not a "user
                    // visible answer" pretending to be Eddie's voice.
                    outcome = new ActionTurnOutcome(
                            "_Mir ist gerade die Spur verloren gegangen "
                                    + "— sag mir kurz wo es weitergehen soll._",
                            true);
                    log.warn("Eddie id='{}' action-loop fallback with no usable "
                                    + "text (reason={}) — posting placeholder reply",
                            process.getId(), loopResult.fallbackReason());
                }
            }
            awaitingUserInput = outcome.awaitingUserInput();

            String chatMessage = outcome.chatMessage();
            boolean appendedChat = chatMessage != null && !chatMessage.isBlank();
            if (appendedChat) {
                ChatMessageDocument.ChatMessageDocumentBuilder builder = ChatMessageDocument.builder()
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
                if (mergedMeta != null) builder.meta(mergedMeta);
                ChatMessageDocument saved = chatLog.append(builder.build());
                // Flush buffered history tags (TOOL_CALL/RESOURCE/FILE_EDIT
                // from the dispatcher hook) onto the assistant turn.
                if (saved != null && saved.getId() != null) {
                    ctx.historyTagSink().flushTo(saved.getId(), chatLog);
                }
                String preview = chatMessage.length() > 120
                        ? chatMessage.substring(0, 120) + "…" : chatMessage;
                log.info("Eddie.turn id='{}' awaiting={} -> '{}'",
                        process.getId(), awaitingUserInput, preview);
            } else {
                // No assistant turn this round — drop buffered tags
                // rather than letting them leak onto the next turn.
                ctx.historyTagSink().discard();
                log.info("Eddie.turn id='{}' awaiting={} (silent — no chat append)",
                        process.getId(), awaitingUserInput);
            }
            return new TurnSignal(appendedChat, loopResult.madeProgress());
        } finally {
            currentTurnHadUserInput.remove(process.getId());
            ThinkProcessStatus exitStatus = awaitingUserInput
                    ? ThinkProcessStatus.BLOCKED
                    : ThinkProcessStatus.IDLE;
            thinkProcessService.updateStatus(process.getId(), exitStatus);
        }
    }

    // ──────────────────── Structured-action contract ────────────────────

    @Override
    protected String actionToolName() {
        return EddieActionSchema.TOOL_NAME;
    }

    @Override
    protected String actionToolDescription() {
        return EddieActionSchema.TOOL_DESCRIPTION;
    }

    @Override
    protected Map<String, Object> actionToolSchema() {
        return EddieActionSchema.schema();
    }

    @Override
    protected Set<String> supportedActionTypes() {
        return EddieActionSchema.SUPPORTED_TYPES;
    }

    /**
     * Plan-mode actions that mutate state but don't end the turn —
     * the LLM should chain real work (read tools, write tools,
     * delegation) immediately after. Without in-loop chaining the
     * LLM emits the same idempotent transition over and over, sees
     * no record of having done it (silent action, no chat append),
     * and burns the silent-turn budget.
     *
     * <p>Mirrors Arthur's CONTINUING_ACTIONS set but covers the
     * mode transitions too. Arthur deliberately keeps mode flips
     * terminal because its tool-set is mode-aware via recipe-modes
     * blocks (EXPLORING / PLANNING strip @write); Eddie's recipe
     * doesn't define mode-specific tool blocks today so the tool
     * manifest stays the same across modes — in-loop chaining is
     * safe.
     */
    private static final Set<String> CONTINUING_ACTIONS = Set.of(
            de.mhus.vance.brain.thinkengine.plan.PlanModeActionSchema.TYPE_START_PLAN,
            de.mhus.vance.brain.thinkengine.plan.PlanModeActionSchema.TYPE_START_EXECUTION,
            de.mhus.vance.brain.thinkengine.plan.PlanModeActionSchema.TYPE_TODO_UPDATE);

    @Override
    protected boolean isTerminalAction(EngineAction action) {
        return !CONTINUING_ACTIONS.contains(action.type());
    }

    @Override
    protected String applyContinuingAction(
            EngineAction action,
            ThinkProcessDocument process,
            ThinkEngineContext ctx) {
        // Reuse the same dispatch as terminal actions — the plan-mode
        // handlers are idempotent and persist new state. Discard the
        // ActionTurnOutcome (chatMessage / awaiting) since continuing
        // actions don't produce chat messages directly. We add a small
        // user-visible chat note for TODO_UPDATE COMPLETED transitions
        // (below) so the user sees plan progress live instead of a
        // 2-minute silent run.
        java.util.List<de.mhus.vance.api.thinkprocess.TodoItem> todosBefore =
                snapshotTodos(process);
        ActionTurnOutcome ignored = handleAction(action, process, ctx);
        String type = action.type();
        if (de.mhus.vance.brain.thinkengine.plan.PlanModeActionSchema.TYPE_START_EXECUTION
                .equals(type)) {
            return renderStartExecutionFeedback(process);
        }
        if (de.mhus.vance.brain.thinkengine.plan.PlanModeActionSchema.TYPE_TODO_UPDATE
                .equals(type)) {
            appendProgressChatForCompletions(process, ctx, todosBefore);
            return renderTodoListFeedback(process, "TODO_UPDATE applied");
        }
        if (de.mhus.vance.brain.thinkengine.plan.PlanModeActionSchema.TYPE_START_PLAN
                .equals(type)) {
            return "START_PLAN applied — mode is now EXPLORING. NEVER emit "
                    + "START_PLAN again. Use read-only tools (web_search, "
                    + "doc_read, doc_list, manual_read) to gather what you "
                    + "need. Once you have enough information, emit "
                    + "PROPOSE_PLAN with a structured plan + todos.";
        }
        return "Action " + type + " applied.";
    }

    /**
     * True when the process has at least one todo and every entry is
     * {@code COMPLETED}. Used by the action-loop-fallback branch to
     * decide whether a graceful plan-completion summary is the right
     * stand-in for a missing terminal action.
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
     * Builds the user-facing summary when Plan-Mode reaches
     * all-COMPLETED but the LLM didn't emit a terminal {@code ANSWER}.
     * Lists the todo titles so the user sees what got done; the
     * exact artifact paths show up via the per-step
     * {@link #appendProgressChatForCompletions} notes already.
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
     * transitioned to COMPLETED. Without this the user stares at a
     * silent chat for the entire plan execution — even though Eddie
     * is actively making progress, the only visible signal is the end-
     * of-plan ANSWER. Posting one short line per step closes the
     * feedback gap.
     *
     * <p>Idempotent: only NEW completions trigger a message; if the
     * LLM re-emits TODO_UPDATE for an already-COMPLETED item nothing
     * new is appended.
     */
    private void appendProgressChatForCompletions(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            java.util.List<de.mhus.vance.api.thinkprocess.TodoItem> before) {
        java.util.Map<String, de.mhus.vance.api.thinkprocess.TodoStatus> prior = new java.util.HashMap<>();
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
            log.warn("Eddie id='{}' failed to append plan-progress chat note: {}",
                    process.getId(), e.toString());
        }
    }

    /**
     * In-loop feedback for {@code START_EXECUTION}: renders the live
     * TodoList and tells the LLM what to do next. Without this the
     * model re-emits {@code START_EXECUTION} every turn because the
     * silent transition leaves no record in the chat history.
     */
    private String renderStartExecutionFeedback(ThinkProcessDocument process) {
        return renderTodoListFeedback(process,
                "START_EXECUTION applied — mode is now EXECUTING");
    }

    /**
     * Shared feedback renderer: header + TodoList with status markers +
     * guidance pointing the LLM at the first non-COMPLETED item. Used
     * for both START_EXECUTION (initial entry into EXECUTING) and
     * TODO_UPDATE (status changes during execution).
     */
    private String renderTodoListFeedback(ThinkProcessDocument process, String header) {
        StringBuilder sb = new StringBuilder(header).append(". Current TodoList:\n");
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
            sb.append(marker).append(" (id=")
                    .append(t.getId() == null ? "" : t.getId())
                    .append(") ")
                    .append(t.getContent() == null ? "" : t.getContent())
                    .append('\n');
            if (firstActive == null
                    && s != de.mhus.vance.api.thinkprocess.TodoStatus.COMPLETED) {
                firstActive = t;
            }
        }
        sb.append('\n');
        if (firstActive == null) {
            sb.append("All todos COMPLETED. Emit ANSWER with a brief summary "
                    + "so the user sees the final result.");
        } else if (firstActive.getStatus()
                == de.mhus.vance.api.thinkprocess.TodoStatus.IN_PROGRESS) {
            sb.append("The first active item (id=")
                    .append(firstActive.getId())
                    .append(") is already IN_PROGRESS. Do NOT emit TODO_UPDATE "
                    + "for it again — that's a no-op. Instead, call read/write "
                    + "tools (web_search, doc_read, doc_create_text, doc_edit, "
                    + "etc.) or DELEGATE_PROJECT / STEER_PROJECT to make real "
                    + "progress. Once the work is done, emit TODO_UPDATE to "
                    + "mark it COMPLETED and pick the next item.");
        } else {
            sb.append("Next: emit TODO_UPDATE setting id=")
                    .append(firstActive.getId())
                    .append(" to IN_PROGRESS, then in the same or next "
                    + "iteration call the read/write tools to do the actual "
                    + "work (web_search, doc_create_text, …) or "
                    + "DELEGATE_PROJECT / STEER_PROJECT for hand-off. "
                    + "NEVER re-emit START_EXECUTION.");
        }
        return sb.toString();
    }

    @Override
    protected ActionTurnOutcome handleAction(
            EngineAction action,
            ThinkProcessDocument process,
            ThinkEngineContext ctx) {
        // Event-only turn gate — same pattern as Arthur. Without this
        // Eddie keeps re-spawning DELEGATE_PROJECT every time a child
        // chat closes (parent-notification arrives in pendingMessages
        // → lane wakes Eddie → LLM sees the event as context → emits
        // DELEGATE_PROJECT again). The idempotency suffix saves the
        // actual project_create but the LLM-spawn cascade still burns
        // budget. RELAY / RELAY_INBOX / ANSWER / WAIT / REJECT / LEARN
        // / MEDIATE stay allowed — they report state without spawning.
        //
        // **Plan-mode exception**: in EXPLORING / PLANNING / EXECUTING
        // the lane runs a *legitimate* self-continuation loop (see
        // runTurn). ASK_USER for clarification on a failed tool call,
        // DELEGATE_PROJECT to hand off a step, STEER_PROJECT to drive
        // an existing worker — all are intentional actions during plan
        // execution, not respawn cascades. Skip the gate so the LLM
        // isn't forced to emit the rejection hint as a user-facing
        // chat message.
        boolean inActivePlanMode = process.getMode() != null
                && process.getMode() != de.mhus.vance.api.thinkprocess.ProcessMode.NORMAL;
        if (!Boolean.TRUE.equals(currentTurnHadUserInput.get(process.getId()))
                && !inActivePlanMode
                && SPAWN_ACTIONS_FORBIDDEN_ON_EVENT_TURNS.contains(action.type())) {
            log.warn("Eddie id='{}' rejected spawn-action '{}' on event-only turn"
                            + " (no fresh user-input in inbox) — reason: '{}'",
                    process.getId(), action.type(), action.reason());
            String hint = "Action '" + action.type() + "' is not allowed on a turn "
                    + "triggered without fresh user-input. The current inbox carries "
                    + "only in-bound process events (child closed / steer reply / "
                    + "tool-result). Spawning new projects or steering existing "
                    + "ones without a user prompt drives the same respawn cascade "
                    + "we just stopped. Emit RELAY (worker output → user), "
                    + "RELAY_INBOX (notification → user), ANSWER (short status), "
                    + "or WAIT — and let the user decide whether more work is wanted.";
            return new ActionTurnOutcome(hint, /*awaitingUserInput*/ true);
        }

        // Plan-Mode actions go through the shared service first. When
        // the service recognises the action it returns the outcome;
        // otherwise null and we fall through to Eddie-specific actions.
        ActionTurnOutcome planOutcome = planModeService.dispatch(action, process, ctx);
        if (planOutcome != null) return planOutcome;
        return switch (action.type()) {
            case EddieActionSchema.TYPE_ANSWER           -> handleAnswer(action);
            case EddieActionSchema.TYPE_ASK_USER         -> handleAskUser(action);
            case EddieActionSchema.TYPE_DELEGATE_PROJECT -> handleDelegateProject(action, process, ctx);
            case EddieActionSchema.TYPE_STEER_PROJECT    -> handleSteerProject(action, process, ctx);
            case EddieActionSchema.TYPE_RELAY            -> handleRelay(action, process, ctx);
            case EddieActionSchema.TYPE_RELAY_INBOX      -> handleRelayInbox(action, process, ctx);
            case EddieActionSchema.TYPE_LEARN            -> handleLearn(action, process);
            case EddieActionSchema.TYPE_MEDIATE          -> handleMediate(action, process, ctx);
            case EddieActionSchema.TYPE_WAIT             -> handleWait(action);
            case EddieActionSchema.TYPE_REJECT           -> handleReject(action);
            default -> {
                log.warn("Eddie id='{}' unknown action type '{}'",
                        process.getId(), action.type());
                yield new ActionTurnOutcome(
                        "(internal: unknown action type '" + action.type()
                                + "', reason was: " + action.reason() + ")",
                        true);
            }
        };
    }

    // ──────────────────── Action handlers ────────────────────

    private ActionTurnOutcome handleAnswer(EngineAction action) {
        String message = action.stringParam(EddieActionSchema.PARAM_MESSAGE);
        if (message == null || message.isBlank()) {
            message = action.reason();
        }
        return new ActionTurnOutcome(message, /*awaitingUserInput*/ true);
    }

    private ActionTurnOutcome handleAskUser(EngineAction action) {
        String message = action.stringParam(EddieActionSchema.PARAM_MESSAGE);
        if (message == null || message.isBlank()) {
            message = action.reason();
        }
        Object optionsRaw = action.params().get(EddieActionSchema.PARAM_OPTIONS);
        String rendered = renderAskUserOptions(message, optionsRaw);
        // Surface the structured options as ChatMessage.meta so picker-
        // aware clients (Web-UI) and the cross-engine relay path
        // (see specification/eddie-engine.md §5.8) can read them without
        // parsing markdown. Empty / non-list → no meta attached.
        Map<String, Object> meta = buildAskUserMeta(optionsRaw);
        return new ActionTurnOutcome(rendered, /*awaitingUserInput*/ true, meta);
    }

    /**
     * Builds the {@code meta} payload attached to an ASK_USER chat
     * message. Returns {@code null} when the LLM did not pass an
     * {@code options} array — keeps the persisted document clean for
     * the open-ended-question case (no empty meta sub-document).
     */
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
        out.put(de.mhus.vance.shared.chat.ChatMessageDocument.META_ASK_USER_OPTIONS, cleaned);
        return out;
    }

    /**
     * Appends structured ASK_USER options to the question text as a
     * Markdown list the UI / voice channel can present. Pure-text
     * fallback so any client renders something useful — a Markdown-
     * aware UI shows it as bullets, a voice channel reads each
     * option aloud, a future picker-aware UI can sniff the options
     * back out of the chat-message or read them from the action
     * params side-channel (not implemented yet).
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
     * Spawn a fresh worker project via the existing
     * {@code project_create} tool. The tool already wires Eddie as the
     * parent of the worker's chat-process and writes an Activity-Log
     * entry, so calling it programmatically gives us all the
     * cross-project bookkeeping for free.
     */
    private ActionTurnOutcome handleDelegateProject(
            EngineAction action, ThinkProcessDocument process, ThinkEngineContext ctx) {
        String projectName = action.stringParam(EddieActionSchema.PARAM_PROJECT_NAME);
        String projectGoal = action.stringParam(EddieActionSchema.PARAM_PROJECT_GOAL);
        if (projectName == null || projectName.isBlank()
                || projectGoal == null || projectGoal.isBlank()) {
            log.warn("Eddie id='{}' DELEGATE_PROJECT missing projectName / projectGoal — reason='{}'",
                    process.getId(), action.reason());
            return new ActionTurnOutcome(
                    "Sorry — interner Fehler: Projekt-Name oder -Ziel fehlte. ("
                            + action.reason() + ")",
                    true);
        }
        String projectTitle = action.stringParam(EddieActionSchema.PARAM_PROJECT_TITLE);
        String kitName = action.stringParam(EddieActionSchema.PARAM_KIT_NAME);
        String message = action.stringParam(EddieActionSchema.PARAM_MESSAGE);

        // Eddie picks the project name from the user's task description —
        // the user never sees or chooses it. So a name-collision against
        // an existing tenant project is an internal naming concern, not
        // a user-facing failure: just append a numeric suffix (-2, -3,
        // …) until the name is free, and proceed. Cap at MAX_NAME_TRIES
        // so a runaway loop on some other persistent failure stops fast.
        Map<String, Object> createResult = null;
        String resolvedName = projectName;
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= MAX_PROJECT_NAME_TRIES; attempt++) {
            try {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("name", resolvedName);
                if (projectTitle != null && !projectTitle.isBlank()) {
                    params.put("title", projectTitle);
                }
                params.put("initialPrompt", projectGoal);
                if (kitName != null && !kitName.isBlank()) {
                    params.put("kitName", kitName);
                }
                createResult = ctx.tools().invokeInternal("project_create", params);
                log.info("Eddie id='{}' DELEGATE_PROJECT name='{}'{} kit='{}' reason='{}'",
                        process.getId(), resolvedName,
                        attempt > 1 ? " (renamed from '" + projectName + "', attempt " + attempt + ")" : "",
                        kitName == null ? "" : kitName,
                        summariseReason(action.reason()));
                break;
            } catch (RuntimeException e) {
                lastError = e;
                if (!isProjectNameTaken(e)) {
                    // Different failure — kit-resolver miss, permission
                    // denied, anything else — surface immediately.
                    log.warn("Eddie id='{}' DELEGATE_PROJECT failed: {}",
                            process.getId(), e.toString());
                    return new ActionTurnOutcome(
                            "Konnte das Projekt nicht anlegen: " + e.getMessage(),
                            true);
                }
                // Name collision — bump the suffix and try again. First
                // collision goes to "<name>-2", then "-3", etc.
                resolvedName = projectName + "-" + (attempt + 1);
                log.info("Eddie id='{}' DELEGATE_PROJECT name '{}' taken, retrying as '{}'",
                        process.getId(), attempt == 1 ? projectName : resolvedName, resolvedName);
            }
        }
        if (createResult == null) {
            log.warn("Eddie id='{}' DELEGATE_PROJECT gave up after {} suffix attempts on base '{}': {}",
                    process.getId(), MAX_PROJECT_NAME_TRIES, projectName,
                    lastError == null ? "(no error)" : lastError.toString());
            return new ActionTurnOutcome(
                    "Konnte keinen freien Projekt-Namen finden auf Basis von '"
                            + projectName + "' (alle bis -" + MAX_PROJECT_NAME_TRIES
                            + " sind belegt).",
                    true);
        }

        // Side-effect: the just-created project becomes Eddie's new
        // "spot" so subsequent STEER_PROJECT calls (and home/spot-aware
        // tools) target it without the LLM having to re-state the
        // project name. Idempotent — if Eddie was already coordinating
        // a different project, this transfers the focus to the fresh
        // one (the typical case after delegation). The home project
        // (process.projectId) is unaffected — only the spot moves.
        try {
            thinkProcessService.setWorkingProjectId(process.getId(), resolvedName);
        } catch (RuntimeException e) {
            // Best-effort — failure here only leaves the old spot in
            // place; the project itself is created either way and the
            // LLM can re-target via project_switch.
            log.debug("Eddie id='{}' failed to set workingProjectId='{}' after DELEGATE: {}",
                    process.getId(), resolvedName, e.toString());
        }

        // Auto-observe: open a Working-WS to the freshly-spawned chat-process
        // so Eddie sees its plan-frames + chat-output live, without the LLM
        // having to call process_observe explicitly. Best-effort — failure
        // here only loses live mirroring; the project is created either way
        // and Eddie can still steer it via project_chat_send / Mongo.
        Object chatProcessId = createResult == null ? null : createResult.get("chatProcessId");
        if (chatProcessId instanceof String workerId && !workerId.isBlank()) {
            try {
                Map<String, Object> observeParams = new LinkedHashMap<>();
                observeParams.put("processId", workerId);
                observeParams.put("channelMode",
                        de.mhus.vance.api.eddie.ChannelMode.MILESTONES.name());
                ctx.tools().invokeInternal("process_observe", observeParams);
            } catch (RuntimeException e) {
                log.debug("Eddie id='{}' auto-observe after DELEGATE failed: {}",
                        process.getId(), e.toString());
            }
        }

        // Silent spawn unless Eddie explicitly wants to say something.
        return new ActionTurnOutcome(
                message == null || message.isBlank() ? null : message,
                /*awaitingUserInput*/ false);
    }

    /**
     * Send a chat-input to the Arthur in an existing worker project.
     */
    private ActionTurnOutcome handleSteerProject(
            EngineAction action, ThinkProcessDocument process, ThinkEngineContext ctx) {
        String project = action.stringParam(EddieActionSchema.PARAM_PROJECT);
        String content = action.stringParam(EddieActionSchema.PARAM_CONTENT);
        // Spot-fallback: when the LLM omits PARAM_PROJECT, default to
        // Eddie's currently-coordinated foreign project
        // (process.workingProjectId). Keeps the LLM from re-stating the
        // project name on every steer of the same worker — the model
        // emits STEER_PROJECT(content="…") and the engine fills in the
        // address from typed state.
        if (project == null || project.isBlank()) {
            project = process.getWorkingProjectId();
        }
        if (project == null || project.isBlank()
                || content == null || content.isBlank()) {
            log.warn("Eddie id='{}' STEER_PROJECT missing project / content — reason='{}'",
                    process.getId(), action.reason());
            return new ActionTurnOutcome(
                    "Sorry — interner Fehler: Projekt oder Nachricht fehlte. ("
                            + action.reason() + ")",
                    true);
        }
        String message = action.stringParam(EddieActionSchema.PARAM_MESSAGE);

        try {
            // project_chat_send's schema names the chat input
            // "message" and the worker project "projectId" (matching
            // EddieContext.resolveProject, which reads the same key).
            // The action vocabulary calls the same payload "content"
            // so it doesn't collide with the optional user-facing
            // "message" field on STEER_PROJECT, and "project" for the
            // worker — translate both at the handler boundary.
            // Passing "project" used to silently land the message in
            // Eddie's own chat because resolveProject only checks
            // "projectId" and falls back to the active project.
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("projectId", project);
            params.put("message", content);
            ctx.tools().invokeInternal("project_chat_send", params);
            log.info("Eddie id='{}' STEER_PROJECT project='{}' reason='{}'",
                    process.getId(), project,
                    summariseReason(action.reason()));
        } catch (RuntimeException e) {
            log.warn("Eddie id='{}' STEER_PROJECT failed: {}",
                    process.getId(), e.toString());
            return new ActionTurnOutcome(
                    "Konnte die Nachricht nicht zustellen: " + e.getMessage(),
                    true);
        }

        return new ActionTurnOutcome(
                message == null || message.isBlank() ? null : message,
                /*awaitingUserInput*/ false);
    }

    /**
     * Read a worker's last reply and speak it as Eddie's voice.
     * Zero-token pass-through: engine copies the worker's content
     * verbatim, with an optional short prefix from Eddie.
     */
    private ActionTurnOutcome handleRelay(
            EngineAction action, ThinkProcessDocument process, ThinkEngineContext ctx) {
        ChatMessageDocument lastReply = resolveWorkerReply(action, process, ctx);
        if (lastReply == null) {
            String source = action.stringParam(EddieActionSchema.PARAM_SOURCE);
            return new ActionTurnOutcome(
                    "(intern: konnte Worker '" + source + "' nicht erreichen.)",
                    true);
        }
        String prefix = action.stringParam(EddieActionSchema.PARAM_PREFIX);
        StringBuilder out = new StringBuilder();
        if (prefix != null && !prefix.isBlank()) {
            out.append(prefix.trim()).append("\n\n");
        }
        out.append(lastReply.getContent());
        // Cross-engine ASK_USER picker — see specification/eddie-engine.md
        // §5.8. When the relayed worker reply was an ASK_USER with
        // structured options, the worker's chat-message carries them
        // in meta.askUserOptions. Re-emit the same options on Eddie's
        // outgoing chat message so picker-aware clients render the
        // picker on Eddie's side too (the worker's options aren't
        // user-facing; only Eddie's are).
        Map<String, Object> relayedMeta = extractAskUserOptionsMeta(lastReply);
        log.info("Eddie id='{}' RELAY source='{}' ({} chars) reason='{}'{}",
                process.getId(),
                action.stringParam(EddieActionSchema.PARAM_SOURCE),
                lastReply.getContent().length(),
                summariseReason(action.reason()),
                relayedMeta != null ? " — forwarding askUserOptions" : "");
        return new ActionTurnOutcome(out.toString(), /*awaitingUserInput*/ true, relayedMeta);
    }

    /**
     * Reads {@code meta.askUserOptions} from a worker's chat message
     * and rebuilds the meta map for Eddie's own outgoing chat message.
     * Returns {@code null} when no options were attached — keeps the
     * relayed message clean for non-ASK_USER reports.
     */
    @SuppressWarnings("unchecked")
    private static @Nullable Map<String, Object> extractAskUserOptionsMeta(
            ChatMessageDocument workerReply) {
        Map<String, Object> meta = workerReply.getMeta();
        if (meta == null || meta.isEmpty()) return null;
        Object options = meta.get(ChatMessageDocument.META_ASK_USER_OPTIONS);
        if (!(options instanceof List<?> list) || list.isEmpty()) return null;
        Map<String, Object> forward = new LinkedHashMap<>();
        forward.put(ChatMessageDocument.META_ASK_USER_OPTIONS, list);
        return forward;
    }

    /**
     * Save a worker's last reply to the user's inbox, and announce it
     * with a short voice-friendly chat message. Engine wires both
     * sides atomically — no risk of orphaned inbox items or missing
     * announcements.
     */
    private ActionTurnOutcome handleRelayInbox(
            EngineAction action, ThinkProcessDocument process, ThinkEngineContext ctx) {
        String inboxTitle = action.stringParam(EddieActionSchema.PARAM_INBOX_TITLE);
        String spoken = action.stringParam(EddieActionSchema.PARAM_SPOKEN);
        if (inboxTitle == null || inboxTitle.isBlank()
                || spoken == null || spoken.isBlank()) {
            log.warn("Eddie id='{}' RELAY_INBOX missing inboxTitle / spoken — reason='{}'",
                    process.getId(), action.reason());
            return new ActionTurnOutcome(
                    "Sorry — interner Fehler: Inbox-Titel oder Ansage fehlte. ("
                            + action.reason() + ")",
                    true);
        }
        ChatMessageDocument lastReply = resolveWorkerReply(action, process, ctx);
        if (lastReply == null) {
            String source = action.stringParam(EddieActionSchema.PARAM_SOURCE);
            return new ActionTurnOutcome(
                    "(intern: konnte Worker '" + source + "' nicht erreichen.)",
                    true);
        }

        // Resolve the user-id from the session — inbox_post needs it
        // as targetUserId (the recipient). Eddie ALWAYS posts to the
        // session's user; cross-user inbox posts aren't a thing here.
        String targetUserId = resolveUserId(process);
        if (targetUserId == null) {
            log.warn("Eddie id='{}' RELAY_INBOX cannot resolve userId from session",
                    process.getId());
            return new ActionTurnOutcome(
                    "Sorry — interner Fehler: konnte den Empfänger nicht ermitteln.",
                    true);
        }

        // Post the body to the inbox via the existing inbox_post tool.
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("targetUserId", targetUserId);
            params.put("type", "OUTPUT_TEXT");
            params.put("title", inboxTitle);
            params.put("body", lastReply.getContent());
            ctx.tools().invokeInternal("inbox_post", params);
            log.info("Eddie id='{}' RELAY_INBOX source='{}' title='{}' ({} chars) reason='{}'",
                    process.getId(),
                    action.stringParam(EddieActionSchema.PARAM_SOURCE),
                    inboxTitle, lastReply.getContent().length(),
                    summariseReason(action.reason()));
        } catch (RuntimeException e) {
            log.warn("Eddie id='{}' RELAY_INBOX inbox_post failed: {}",
                    process.getId(), e.toString());
            return new ActionTurnOutcome(
                    "Konnte das Item nicht in die Inbox legen: " + e.getMessage(),
                    true);
        }

        // The user-facing chat message is just the spoken announcement.
        // The full content lives in the inbox.
        return new ActionTurnOutcome(spoken, /*awaitingUserInput*/ true);
    }

    /**
     * Persists user-related context into Eddie's per-user memory.
     * Two scopes:
     *
     * <ul>
     *   <li><b>persona</b> ({@link #PERSONA_DOC_PATH}) — a freeform
     *       summary of how Eddie should talk to this user. Always
     *       loaded into the prompt. Mode controls whether the new
     *       content replaces (default — clean rewrite) or appends.</li>
     *   <li><b>fact</b> ({@link #FACTS_DOC_PATH}) — append-only
     *       journal of factual entries (birthday, preferences,
     *       dislikes). Each entry is timestamped on write.</li>
     * </ul>
     *
     * <p>Both files live in the user's hub project ({@code _user_<login>}),
     * which is also the {@code projectId} of Eddie's running process —
     * no extra resolution needed.
     */
    private ActionTurnOutcome handleLearn(
            EngineAction action, ThinkProcessDocument process) {
        String scope = action.stringParam(EddieActionSchema.PARAM_SCOPE);
        String content = action.stringParam(EddieActionSchema.PARAM_CONTENT);
        if (scope == null || scope.isBlank()
                || content == null || content.isBlank()) {
            log.warn("Eddie id='{}' LEARN missing scope/content — reason='{}'",
                    process.getId(), action.reason());
            return new ActionTurnOutcome(
                    "Sorry — interner Fehler: scope oder content fehlte beim "
                            + "Lernen. (" + action.reason() + ")",
                    true);
        }
        if (!EddieActionSchema.LEARN_SCOPES.contains(scope)) {
            log.warn("Eddie id='{}' LEARN unknown scope='{}' — reason='{}'",
                    process.getId(), scope, action.reason());
            return new ActionTurnOutcome(
                    "Sorry — interner Fehler: unbekannter scope '" + scope
                            + "'. Erlaubt sind 'persona' und 'fact'.",
                    true);
        }

        String tenantId = process.getTenantId();
        String projectId = process.getProjectId();
        if (projectId == null || projectId.isBlank()) {
            log.warn("Eddie id='{}' LEARN process has no projectId — cannot persist",
                    process.getId());
            return new ActionTurnOutcome(
                    "Sorry — interner Fehler: kein Hub-Projekt verfügbar.",
                    true);
        }

        try {
            switch (scope) {
                case EddieActionSchema.LEARN_SCOPE_PERSONA -> {
                    String mode = action.stringParamOr(
                            EddieActionSchema.PARAM_MODE,
                            EddieActionSchema.LEARN_MODE_REPLACE);
                    String newContent = mergePersona(
                            tenantId, projectId, content, mode);
                    upsertDoc(tenantId, projectId, PERSONA_DOC_PATH,
                            "Eddie persona summary",
                            "eddie", "persona",
                            newContent, process);
                    log.info("Eddie id='{}' LEARN persona mode='{}' ({} chars total) reason='{}'",
                            process.getId(), mode, newContent.length(),
                            summariseReason(action.reason()));
                }
                case EddieActionSchema.LEARN_SCOPE_FACT -> {
                    String today = java.time.LocalDate.now().toString();
                    String entry = "[" + today + "] " + content.trim();
                    String newContent = appendFact(tenantId, projectId, entry);
                    upsertDoc(tenantId, projectId, FACTS_DOC_PATH,
                            "Eddie user facts",
                            "eddie", "facts",
                            newContent, process);
                    log.info("Eddie id='{}' LEARN fact ({} chars) reason='{}'",
                            process.getId(), entry.length(),
                            summariseReason(action.reason()));
                }
            }
        } catch (RuntimeException e) {
            log.warn("Eddie id='{}' LEARN persistence failed: {}",
                    process.getId(), e.toString());
            return new ActionTurnOutcome(
                    "Konnte mir das gerade nicht merken — " + e.getMessage(),
                    true);
        }

        // Optional spoken confirmation. If absent, silent — the user
        // notices that Eddie remembers next time, no chat noise now.
        String message = action.stringParam(EddieActionSchema.PARAM_MESSAGE);
        return new ActionTurnOutcome(
                message == null || message.isBlank() ? null : message,
                /*awaitingUserInput*/ message != null && !message.isBlank());
    }

    private String mergePersona(
            String tenantId, String projectId, String newPart, String mode) {
        if (EddieActionSchema.LEARN_MODE_APPEND.equals(mode)) {
            String existing = readDocText(tenantId, projectId, PERSONA_DOC_PATH);
            if (existing == null || existing.isBlank()) {
                return newPart.trim();
            }
            return existing.stripTrailing() + "\n\n" + newPart.trim();
        }
        // Default = REPLACE (clean rewrite by Eddie).
        return newPart.trim();
    }

    private String appendFact(String tenantId, String projectId, String entry) {
        String existing = readDocText(tenantId, projectId, FACTS_DOC_PATH);
        if (existing == null || existing.isBlank()) {
            return entry;
        }
        return existing.stripTrailing() + "\n" + entry;
    }

    private @Nullable String readDocText(
            String tenantId, String projectId, String path) {
        Optional<DocumentDocument> docOpt =
                documentService.findByPath(tenantId, projectId, path);
        if (docOpt.isEmpty()) {
            return null;
        }
        // Eddie's user-memory docs are always inline (small text).
        // Storage-backed (oversized) docs would need a streaming read,
        // but we keep the user-memory schema in the inline tier on
        // purpose — see FACTS_PROMPT_BUDGET_CHARS.
        return docOpt.get().getInlineText();
    }

    /**
     * System prompt for the post-LEARN consolidation pass on the
     * persona file. Output-only (no headers, no fences) so the
     * result can be written straight back to disk.
     */
    private static final String PERSONA_CONSOLIDATE_SYSTEM = """
            You are a persona-consolidator for an AI assistant. The text below is a
            free-form description of how the assistant should talk to a specific user.
            Over time it accumulates: instructions get added, sometimes contradicted,
            sometimes refined. Your job is to produce a clean, current-state version.

            Rules:
            - Resolve contradictions: the LATER instruction wins. If "be sarcastic"
              comes after "be polite", drop "be polite".
            - Merge complementary instructions ("be concise" + "no bullet lists" →
              "be concise; prose over lists").
            - Drop redundant phrasing.
            - Keep it short — five to ten sentences max. The assistant reads this
              every turn; bloat hurts everything.
            - Preserve the user's voice. If they said "wie Douglas Adams", keep that
              exact reference. Don't substitute generic synonyms.
            - Match the language of the input (German stays German).

            Output ONLY the consolidated persona text. No preamble, no explanation,
            no Markdown headers, no code fences.
            """;

    /**
     * System prompt for the post-LEARN consolidation pass on the
     * facts file. Same output-only contract as persona.
     */
    private static final String FACTS_CONSOLIDATE_SYSTEM = """
            You are a fact-consolidator for an AI assistant's per-user memory.
            Below is a journal of date-stamped facts about a specific user. Over
            time the same topic may be re-stated with new values (favourite color
            changed, address updated, preference flipped). Your job is to produce
            a clean current-state list.

            Rules:
            - For each topic (favourite color, birthday, dislike, role, …), keep
              ONLY the most recent entry. Drop superseded older versions.
            - Preserve each kept entry's original date stamp and wording.
            - Don't merge across distinct topics. Don't invent new facts.
            - Don't add or remove date stamps. Don't reformat dates.
            - Order chronologically, oldest first.
            - One fact per line.

            Output ONLY the consolidated list. No preamble, no explanation, no
            Markdown headers, no code fences.
            """;

    /**
     * Post-LEARN consolidation. Reads the just-written persona /
     * facts file, runs an LLM pass to resolve contradictions / drop
     * superseded entries, writes the consolidated text back. Runs in
     * Eddie's own lane (not a separate worker) — the user's turn has
     * already produced the {@code spoken} confirmation, this is
     * post-processing for the next turn's prompt.
     */
    private void runLearnConsolidation(
            EngineAction action,
            AiChat aiChat,
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            String modelAlias) {
        String scope = action.stringParam(EddieActionSchema.PARAM_SCOPE);
        if (scope == null || scope.isBlank()) return;

        String docPath;
        String systemPrompt;
        switch (scope) {
            case EddieActionSchema.LEARN_SCOPE_PERSONA -> {
                docPath = PERSONA_DOC_PATH;
                systemPrompt = PERSONA_CONSOLIDATE_SYSTEM;
            }
            case EddieActionSchema.LEARN_SCOPE_FACT -> {
                docPath = FACTS_DOC_PATH;
                systemPrompt = FACTS_CONSOLIDATE_SYSTEM;
            }
            default -> {
                return;
            }
        }

        String tenantId = process.getTenantId();
        String projectId = process.getProjectId();
        String current = readDocText(tenantId, projectId, docPath);
        if (current == null || current.isBlank()) return;

        // Skip the LLM call when there's likely nothing to consolidate
        // (single-fact / single-line persona). The threshold is small;
        // a real second entry will exceed it easily.
        String trimmed = current.trim();
        if (trimmed.lines().count() < 2 && trimmed.length() < 80) {
            return;
        }

        try {
            List<ChatMessage> messages = List.of(
                    SystemMessage.from(systemPrompt),
                    UserMessage.from(current));
            dev.langchain4j.model.chat.request.ChatRequest req =
                    dev.langchain4j.model.chat.request.ChatRequest.builder()
                            .messages(messages)
                            .build();
            AiMessage reply = streamOneIteration(aiChat, req, ctx, process, modelAlias);
            String consolidated = reply.text();
            if (consolidated == null || consolidated.isBlank()) {
                log.warn("Eddie id='{}' LEARN consolidation produced no text — keeping raw",
                        process.getId());
                return;
            }
            consolidated = stripFences(consolidated.trim());
            if (consolidated.equals(trimmed)) {
                log.debug("Eddie id='{}' LEARN consolidation: no changes for scope='{}'",
                        process.getId(), scope);
                return;
            }
            // Persist the consolidated version. Same upsert path as
            // handleLearn — the doc already exists at this point.
            upsertDoc(tenantId, projectId, docPath,
                    EddieActionSchema.LEARN_SCOPE_PERSONA.equals(scope)
                            ? "Eddie persona summary"
                            : "Eddie user facts",
                    "eddie",
                    EddieActionSchema.LEARN_SCOPE_PERSONA.equals(scope)
                            ? "persona" : "facts",
                    consolidated, process);
            log.info("Eddie id='{}' LEARN consolidation scope='{}' {} → {} chars",
                    process.getId(), scope, current.length(), consolidated.length());
        } catch (RuntimeException e) {
            // Non-fatal — raw content stays on disk, user already got
            // their "Notiert." reply. Future turn just sees the
            // un-consolidated version, which is still valid.
            log.warn("Eddie id='{}' LEARN consolidation failed for scope='{}': {}",
                    process.getId(), scope, e.toString());
        }
    }

    /**
     * Strips an outer Markdown code-fence (` ``` `) if the LLM wrapped
     * its output despite being told not to. Defensive — some models
     * habitually add fences for "this is text" outputs.
     */
    private static String stripFences(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (!t.startsWith("```")) return t;
        // First newline ends the opening fence (with optional language).
        int firstNl = t.indexOf('\n');
        if (firstNl < 0) return t;
        String inner = t.substring(firstNl + 1);
        if (inner.endsWith("```")) {
            inner = inner.substring(0, inner.length() - 3);
        }
        return inner.stripTrailing();
    }

    private void upsertDoc(
            String tenantId, String projectId, String path,
            String title, String tag1, String tag2,
            String text, ThinkProcessDocument process) {
        Optional<DocumentDocument> existing =
                documentService.findByPath(tenantId, projectId, path);
        if (existing.isPresent()) {
            documentService.update(
                    existing.get().getId(),
                    /*newTitle*/ null,
                    /*newTags*/ null,
                    /*newInlineText*/ text,
                    /*newPath*/ null);
        } else {
            documentService.createText(
                    tenantId, projectId, path, title,
                    java.util.List.of(tag1, tag2),
                    text,
                    "eddie:" + process.getId());
        }
    }

    /**
     * Hands the user-WS over to a worker session — see
     * {@code specification/eddie-engine.md} §8.5.
     *
     * <p>Choreography on Eddie's side:
     * <ol>
     *   <li>Resolve the worker via {@code source} param. Caller picks
     *       a worker name from the {@code <delegated_workers>} block.</li>
     *   <li>Persist a {@link de.mhus.vance.shared.eddie.Mediation}
     *       record on Eddie's process — that flips the LLM lane to
     *       paused on the next turn.</li>
     *   <li>Close the in-memory Working-WS to that worker (avoids
     *       Eddie and the user-client both subscribing to the same
     *       worker session, which would cause double-renders).</li>
     *   <li>Push a {@code todos-updated} with empty list to Eddie's
     *       user-session — wipes the fused plan view; the user-client
     *       starts fresh on the worker session.</li>
     *   <li>Push a {@code mediate-handover} with the worker session
     *       id; the client follows it with a {@code session-resume}
     *       to that session.</li>
     * </ol>
     *
     * <p>v1: capability-check is intentionally lenient — the LLM is
     * trusted to skip MEDIATE when the connected client can't return
     * (mobile / voice-only). A formal {@code ProfileRegistry.canMediate}
     * gate is on the roadmap.
     */
    private ActionTurnOutcome handleMediate(
            EngineAction action,
            ThinkProcessDocument process,
            ThinkEngineContext ctx) {
        // Capability gate: only profiles with canMediate=true (foot, web)
        // can run the close+reopen WS dance. Mobile (voice-only) gets
        // a polite explanation instead.
        de.mhus.vance.shared.access.ProfileCapabilities caps =
                profileRegistry.capabilities(process.getBoundProfile());
        if (!caps.canMediate()) {
            log.info("Eddie id='{}' MEDIATE skipped — profile '{}' canMediate=false",
                    process.getId(), process.getBoundProfile());
            return new ActionTurnOutcome(
                    "Du bist auf einem Client unterwegs, der keinen Rückweg "
                            + "aus einer Direktverbindung hat. Ich bleibe für dich da — "
                            + "wenn du Client-Tools brauchst, wechsle bitte am Desktop "
                            + "in das Projekt selbst.",
                    true);
        }

        String target = action.stringParam(EddieActionSchema.PARAM_TARGET);
        if (target == null || target.isBlank()) {
            log.warn("Eddie id='{}' MEDIATE missing target — reason='{}'",
                    process.getId(), action.reason());
            return new ActionTurnOutcome(
                    "Sorry — interner Fehler: Mediate-Ziel fehlte.", true);
        }
        // Resolve the target by project / worker name. Two paths:
        //   (1) Fast: Eddie delegated this worker herself — workerLinks
        //       carries the connection identity.
        //   (2) Fallback: the project pre-existed (user just named it).
        //       Same lookup chain as ProjectChatSendTool uses for
        //       STEER_PROJECT: tenant + name → most-recent session with
        //       chatProcessId → that's the Arthur to switch into.
        // The frame needs target session + project name for the UI banner.
        MediateTarget resolved = null;
        if (process.getWorkerLinks() != null) {
            var linked = process.getWorkerLinks().stream()
                    .filter(l -> target.equals(l.getWorkerProcessName())
                            || target.equals(l.getWorkerProcessId()))
                    .findFirst();
            if (linked.isPresent()) {
                var l = linked.get();
                resolved = new MediateTarget(
                        l.getWorkerSessionId(),
                        l.getWorkerProjectName());
            }
        }
        if (resolved == null) {
            resolved = resolveMediateTargetByProject(process, target);
        }
        if (resolved == null) {
            log.warn("Eddie id='{}' MEDIATE: no chat-process for target='{}'",
                    process.getId(), target);
            return new ActionTurnOutcome(
                    "Konnte das Projekt '" + target + "' nicht öffnen — kein "
                            + "aktiver Chat-Prozess gefunden.", true);
        }

        // Push the switch-to frame. No server-side state writes — the
        // client owns the back-stack. Eddie's lane keeps running
        // normally; absence of user input is the natural "quiet" state
        // while the user is over at the worker.
        String voice = action.stringParam(EddieActionSchema.PARAM_VOICE_ANNOUNCEMENT);
        de.mhus.vance.api.eddie.SwitchToNotification frame =
                de.mhus.vance.api.eddie.SwitchToNotification.builder()
                        .targetSessionId(resolved.workerSessionId())
                        .targetProjectId(resolved.workerProjectName())
                        .targetProcessName(target)
                        .voiceAnnouncement(voice)
                        .build();
        try {
            ctx.events().publish(process.getSessionId(),
                    de.mhus.vance.api.ws.MessageType.SWITCH_TO, frame);
        } catch (RuntimeException e) {
            log.warn("Eddie MEDIATE: switch-to push failed for session='{}': {}",
                    process.getSessionId(), e.toString());
            return new ActionTurnOutcome(
                    "Konnte den Switch-Frame nicht senden — bleibe für dich da.",
                    true);
        }

        log.info("Eddie id='{}' MEDIATE target='{}' targetSession='{}' reason='{}'",
                process.getId(), target, resolved.workerSessionId(),
                summariseReason(action.reason()));

        return new ActionTurnOutcome(voice == null || voice.isBlank() ? null : voice,
                /*awaitingUserInput=*/ true);
    }

    /** Two fields the switch-to frame needs — keeps the worker-links
     *  fast path and the project-name fallback uniform. */
    private record MediateTarget(
            String workerSessionId,
            String workerProjectName) {}

    /**
     * Resolve a switch target by project name when no worker-link
     * exists (project pre-existed before Eddie). Uses the same lookup
     * chain as {@code ProjectChatSendTool}: project's sessions →
     * most-recent session with {@code chatProcessId} → that's the
     * Arthur to switch into.
     *
     * <p>Returns {@code null} when the project has no chat-process —
     * caller treats that as "no switchable target".
     */
    private @Nullable MediateTarget resolveMediateTargetByProject(
            ThinkProcessDocument process, String projectName) {
        String tenantId = process.getTenantId();
        if (tenantId == null || tenantId.isBlank()) return null;
        java.util.List<de.mhus.vance.shared.session.SessionDocument> sessions;
        try {
            sessions = sessionService.listForProject(tenantId, projectName);
        } catch (RuntimeException e) {
            log.debug("Eddie MEDIATE: session lookup failed for project='{}': {}",
                    projectName, e.toString());
            return null;
        }
        if (sessions == null || sessions.isEmpty()) return null;
        de.mhus.vance.shared.session.SessionDocument best = null;
        for (var s : sessions) {
            if (s.getChatProcessId() == null || s.getChatProcessId().isBlank()) continue;
            if (best == null) { best = s; continue; }
            java.time.Instant ai = s.getCreatedAt();
            java.time.Instant bi = best.getCreatedAt();
            if (ai != null && (bi == null || ai.isAfter(bi))) best = s;
        }
        if (best == null) return null;
        return new MediateTarget(best.getSessionId(), projectName);
    }

    private ActionTurnOutcome handleWait(EngineAction action) {
        String message = action.stringParam(EddieActionSchema.PARAM_MESSAGE);
        return new ActionTurnOutcome(
                message == null || message.isBlank() ? null : message,
                /*awaitingUserInput*/ false);
    }

    private ActionTurnOutcome handleReject(EngineAction action) {
        String message = action.stringParam(EddieActionSchema.PARAM_MESSAGE);
        if (message == null || message.isBlank()) {
            message = "Das geht so leider nicht — " + action.reason();
        }
        return new ActionTurnOutcome(message, /*awaitingUserInput*/ true);
    }

    /**
     * Looks up the user-id that owns the session this Eddie process
     * is bound to. Used by RELAY_INBOX to fill the {@code
     * targetUserId} field of {@code inbox_post}. Returns
     * {@code null} when the session row is missing or the user-id
     * is unset — caller must handle that defensively.
     */
    private @Nullable String resolveUserId(ThinkProcessDocument process) {
        return sessionService.findBySessionId(process.getSessionId())
                .map(de.mhus.vance.shared.session.SessionDocument::getUserId)
                .filter(s -> s != null && !s.isBlank())
                .orElse(null);
    }

    // ──────────────────── Worker-reply lookup ────────────────────

    /**
     * Resolves the {@code source} param to a <em>worker</em> process
     * (cross-project: Eddie's workers live in their own sessions, not
     * in Eddie's session) and returns its last substantive ASSISTANT
     * message.
     *
     * <p>Resolution order, all tenant-scoped for safety, with the
     * additional guard that the resolved process must be Eddie's
     * <em>child</em> (its {@code parentProcessId} matches the calling
     * Eddie). That prevents the LLM from accidentally relaying
     * Eddie's own messages or unrelated processes:
     *
     * <ol>
     *   <li>By Mongo id ({@code findById}) — unique, the preferred
     *       form. The {@code <process-event sourceProcessId="...">}
     *       in the prompt provides exactly this.</li>
     *   <li>By name across all sessions of the tenant, filtered to
     *       Eddie's children — only used when the LLM passed a name
     *       like {@code "chat"} that collides across sessions.</li>
     * </ol>
     */
    private @Nullable ChatMessageDocument resolveWorkerReply(
            EngineAction action, ThinkProcessDocument process, ThinkEngineContext ctx) {
        String source = action.stringParam(EddieActionSchema.PARAM_SOURCE);
        if (source == null || source.isBlank()) {
            log.warn("Eddie id='{}' relay missing 'source' — reason='{}'",
                    process.getId(), action.reason());
            return null;
        }

        // Prefer Mongo-id lookup — unique and disambiguous across
        // sessions. Tenant-scoped + child-of-Eddie filter for safety.
        ThinkProcessDocument target = thinkProcessService.findById(source)
                .filter(p -> process.getTenantId().equals(p.getTenantId()))
                .filter(p -> process.getId().equals(p.getParentProcessId()))
                .orElse(null);

        if (target == null) {
            // Name-based fallback: find any child of Eddie (across all
            // sessions in the tenant) whose name matches. Useful when
            // the LLM passed the worker's process-name from the event.
            // We don't use findByName here because it's session-scoped
            // and Eddie's workers live in different sessions; instead
            // we walk all of Eddie's children and match.
            for (ThinkProcessDocument child : thinkProcessService
                    .findByParentProcessId(process.getId())) {
                if (source.equals(child.getName())) {
                    target = child;
                    break;
                }
            }
        }

        if (target == null) {
            log.warn("Eddie id='{}' relay source '{}' not found among children",
                    process.getId(), source);
            return null;
        }

        List<ChatMessageDocument> history = ctx.chatMessageService().activeHistory(
                target.getTenantId(), target.getSessionId(), target.getId());
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessageDocument m = history.get(i);
            if (m.getRole() == ChatRole.ASSISTANT
                    && m.getContent() != null
                    && !m.getContent().isBlank()) {
                return m;
            }
        }
        log.warn("Eddie id='{}' relay source '{}' (id={}) has no ASSISTANT reply yet",
                process.getId(), source, target.getId());
        return null;
    }

    // ──────────────────── Prompt building ────────────────────

    private List<ChatMessage> buildPromptMessages(
            ThinkProcessDocument process,
            ChatMessageService chatLog,
            List<SteerMessage> inbox,
            ModelInfo modelInfo,
            ModelSize modelSize,
            ThinkEngineContext engineCtx) {
        List<ChatMessage> messages = new ArrayList<>();

        // ── STATIC system prefix — Anthropic cache anchors here ──
        // Engine default + recipe-prompt overlay. The user-context
        // block (displayName / userId) is session-stable too, so it
        // joins the static prefix; the cache marker lands on the
        // last static block. See specification/prompt-caching.md §5.
        java.util.Map<String, Object> promptCtx = de.mhus.vance.brain.prompt.PromptContextBuilder
                .forProcess(process, modelInfo)
                .tier(modelSize)
                .engine(NAME)
                .withRootDirTypes(workspaceService.getRootDirTypes(
                        process.getTenantId(), process.getProjectId()))
                .build();
        // Fall back to the engine's cascade-resolved default prompt
        // (project → _vance → classpath) rather than the short
        // GREETING when the recipe didn't pin a promptOverride. The
        // recipe normally has no promptPrefix — the prompt belongs
        // in the .md cascade, not the YAML — so this path is the
        // common case for Eddie chat-process spawns.
        String base = SystemPrompts.compose(process,
                process.getPromptOverride() == null
                        ? engineDefaultPrompt(process)
                        : process.getPromptOverride(),
                promptTemplateRenderer, promptCtx);
        messages.add(SystemMessage.from(base));
        String userBlock = composeUserContextBlock(process);
        if (userBlock != null && !userBlock.isBlank()) {
            messages.add(SystemMessage.from(userBlock));
        }

        // ── DYNAMIC blocks — mutated by LEARN, ride outside cache ──
        // Persona / facts: rewritten by `LEARN` action. Memory block:
        // mutates on settings cascade changes. Each gets its own
        // system block so the mapper can place the cache_control
        // marker before any of them.
        String personaBlock = composePersonaBlock(process);
        if (personaBlock != null && !personaBlock.isBlank()) {
            messages.add(VanceSystemMessage.dynamic(personaBlock));
        }
        String factsBlock = composeFactsBlock(process);
        if (factsBlock != null && !factsBlock.isBlank()) {
            messages.add(VanceSystemMessage.dynamic(factsBlock));
        }
        // RAG auto-inject (when enabled in recipe) rides inside the
        // memory block — MemoryContextLoader splices it in for any
        // engine that hands it a userQuery. Engine-agnostic on purpose.
        String memoryBlock = memoryContextLoader.composeBlock(
                process, latestUserInputText(inbox));
        if (memoryBlock != null && !memoryBlock.isBlank()) {
            messages.add(VanceSystemMessage.dynamic(memoryBlock));
        }
        String delegatedBlock = composeDelegatedWorkersBlock(process);
        if (delegatedBlock != null && !delegatedBlock.isBlank()) {
            messages.add(VanceSystemMessage.dynamic(delegatedBlock));
        }
        String workingProjectBlock = composeWorkingProjectBlock(process);
        if (workingProjectBlock != null && !workingProjectBlock.isBlank()) {
            messages.add(VanceSystemMessage.dynamic(workingProjectBlock));
        }

        // ── TOOL HINTS — pack-level usage notes for tools that are
        // currently reachable for this user (OAuth-connected, MCP up).
        // The pack-config carries the hint; engines just join them.
        // Rides as a dynamic block: the set changes when the user
        // (dis)connects an integration, not when the recipe changes.
        String toolHintsBlock = composeToolHintsBlock(engineCtx);
        if (toolHintsBlock != null && !toolHintsBlock.isBlank()) {
            messages.add(VanceSystemMessage.dynamic(toolHintsBlock));
        }

        // ── PLAN-MODE TODO LIST — live state for EXPLORING / PLANNING /
        // EXECUTING modes. Without this the LLM sitting in EXECUTING
        // mode (after START_EXECUTION) has no in-prompt indicator of
        // "you're past planning, now do the actual work" and keeps
        // emitting START_EXECUTION idempotently. Mirrors Arthur's
        // buildTodoListBlock — see ArthurEngine for the canonical
        // version.
        String todoBlock = buildTodoListBlock(process);
        if (todoBlock != null && !todoBlock.isBlank()) {
            messages.add(VanceSystemMessage.dynamic(todoBlock));
        }

        List<ChatMessageDocument> history = chatLog.activeHistory(
                process.getTenantId(), process.getSessionId(), process.getId());
        for (ChatMessageDocument msg : history) {
            messages.add(toLangchain(msg));
        }

        for (SteerMessage m : inbox) {
            String wrapped = renderForLlm(m);
            if (wrapped != null) {
                messages.add(UserMessage.from(wrapped));
            }
        }
        return messages;
    }

    /**
     * Builds a "## Your user" block that surfaces the human-readable
     * user identity at the top of every Eddie turn. Eddie is the
     * personal hub — addressing the user by name when it fits is part
     * of the persona. Returns {@code null} if no useful identity is
     * available (defensive — sessions always have a user-id, but the
     * displayName is optional).
     *
     * <p>Format:
     * <pre>
     * ## Your user
     *
     * You're talking to **Mike** (login: `mike`). Use his name when it
     * fits naturally — at the start of a fresh conversation, when
     * confirming something he just asked, when delivering a result.
     * Don't tack it on every line.
     * </pre>
     */
    private @Nullable String composeUserContextBlock(ThinkProcessDocument process) {
        var sessionOpt = sessionService.findBySessionId(process.getSessionId());
        if (sessionOpt.isEmpty()) {
            return null;
        }
        String displayName = sessionOpt.get().getDisplayName();
        String userId = sessionOpt.get().getUserId();
        boolean hasDisplay = displayName != null && !displayName.isBlank();
        boolean hasUserId = userId != null && !userId.isBlank();
        if (!hasDisplay && !hasUserId) {
            return null;
        }
        StringBuilder sb = new StringBuilder("## Your user\n\n");
        if (hasDisplay) {
            sb.append("You're talking to **").append(displayName).append("**");
            if (hasUserId && !displayName.equals(userId)) {
                sb.append(" (login: `").append(userId).append("`)");
            }
            sb.append(".");
        } else {
            sb.append("You're talking to user `").append(userId).append("`.");
        }
        sb.append(" Use the user's name when it fits naturally — at the "
                + "start of a fresh conversation, when confirming "
                + "something they just asked, when delivering a result. "
                + "Don't tack it onto every line.");

        // Hub-project routing hint: when Eddie's chat-process is in
        // the tenant hub (`_tenant`), document writes default to that
        // SYSTEM project and get rejected. Tell the LLM explicitly
        // which user-project to address. Without this hint the LLM
        // remembers the projectId for the first call or two and then
        // drops it on later calls (we saw this in plan-mode loops).
        String currentProjectId = process.getProjectId();
        if (hasUserId
                && de.mhus.vance.shared.home.HomeBootstrapService.TENANT_PROJECT_NAME
                        .equals(currentProjectId)) {
            String userProject = de.mhus.vance.shared.home.HomeBootstrapService
                    .HUB_PROJECT_NAME_PREFIX + userId;
            sb.append("\n\n**Routing — where user-facing artifacts land:** "
                    + "this chat sits in the tenant hub (`_tenant`, "
                    + "SYSTEM). Any document, scratchpad, or workspace "
                    + "write the user asked for must target the user "
                    + "project `")
                    .append(userProject)
                    .append("`. Always pass `projectId=\"")
                    .append(userProject)
                    .append("\"` to `doc_create_text`, `doc_edit`, "
                    + "`doc_write_text`, `scratch_write`, etc. — the "
                    + "default routes to `_tenant` and gets rejected "
                    + "because the hub is SYSTEM-protected.");
        }
        return sb.toString();
    }

    /**
     * Loads {@link #PERSONA_DOC_PATH} from the user's hub project and
     * wraps it in a "## How to talk to this user" block. Returns
     * {@code null} when no persona document exists yet — the LLM
     * just doesn't see the section in that case (fresh user, no
     * persona-learning yet).
     */
    private @Nullable String composePersonaBlock(ThinkProcessDocument process) {
        String projectId = process.getProjectId();
        if (projectId == null || projectId.isBlank()) {
            return null;
        }
        String text = readDocText(process.getTenantId(), projectId, PERSONA_DOC_PATH);
        if (text == null || text.isBlank()) {
            return null;
        }
        return "## How to talk to this user\n\n"
                + text.trim()
                + "\n\n_Maintain this via `LEARN scope=persona`._";
    }

    /**
     * Loads {@link #FACTS_DOC_PATH} from the user's hub project and
     * wraps it in a "## What I know about this user" block. If the
     * file exceeds {@link #FACTS_PROMPT_BUDGET_CHARS}, only the tail
     * fits; older entries are silently dropped from the prompt
     * (still on disk, but a sign that Eddie should consolidate
     * stable preferences into the persona summary).
     */
    private @Nullable String composeFactsBlock(ThinkProcessDocument process) {
        String projectId = process.getProjectId();
        if (projectId == null || projectId.isBlank()) {
            return null;
        }
        String text = readDocText(process.getTenantId(), projectId, FACTS_DOC_PATH);
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.stripTrailing();
        if (trimmed.length() > FACTS_PROMPT_BUDGET_CHARS) {
            // Drop the head — newest facts win, oldest get pushed out.
            int from = trimmed.length() - FACTS_PROMPT_BUDGET_CHARS;
            // Snap to the next newline so we don't cut a fact in half.
            int nl = trimmed.indexOf('\n', from);
            trimmed = (nl >= 0 ? trimmed.substring(nl + 1) : trimmed.substring(from))
                    + "\n\n_(older entries omitted — consider consolidating into persona)_";
        }
        return "## What I know about this user\n\n"
                + trimmed
                + "\n\n_Append new facts via `LEARN scope=fact`._";
    }

    /**
     * Eddie's working-memory render. Walks
     * {@link ThinkProcessDocument#getWorkerLinks()} and produces a
     * {@code <delegated_workers>} prompt block keeping Eddie aware of
     * what every active worker has been doing — without re-reading
     * each worker's chat history.
     *
     * <p>Cleanup heuristic: keeps at most {@link #DELEGATED_WORKERS_MAX_RENDER}
     * entries, sorted by {@code lastSeen} desc (newest first). Empty
     * snapshots and links missing both a status and a triage summary
     * are skipped — they would render an unhelpful blank line.
     *
     * <p>Block is token-cache-stable: it only mutates when a worker
     * frame updates the snapshot (incoming triage / plan / status),
     * not on every Eddie turn.
     */
    private @Nullable String composeDelegatedWorkersBlock(ThinkProcessDocument process) {
        return renderDelegatedWorkersBlock(
                process.getWorkerLinks(),
                DELEGATED_WORKERS_MAX_RENDER,
                java.time.Instant.now());
    }

    /**
     * Renders the "## Working project" block — Eddie's "spot", the
     * foreign project she currently coordinates. Surfaced as its own
     * dynamic block so it sits between cache-stable engine prompt and
     * volatile turn-history, and visibly shifts when the spot moves.
     * When unset, an explicit "no working project yet" line keeps the
     * LLM from guessing.
     *
     * <p>Format when set:
     * <pre>
     * ## Working project
     *
     * Currently coordinating: `naturkatastrophen`. STEER_PROJECT
     * defaults here; switch with `project_switch(name=...)` when the
     * user changes focus.
     * </pre>
     */
    static @Nullable String renderWorkingProjectBlock(@Nullable String workingProjectId) {
        StringBuilder sb = new StringBuilder("## Working project\n\n");
        if (workingProjectId == null || workingProjectId.isBlank()) {
            sb.append("No working project selected yet. STEER_PROJECT, "
                    + "project_chat_send and other spot-bound tools will "
                    + "fail until you pick one. Use `project_switch("
                    + "name=...)` or DELEGATE_PROJECT when the user "
                    + "names a project to focus on. Pure ANSWER / "
                    + "persona / chit-chat turns don't need a spot.");
            return sb.toString();
        }
        sb.append("Currently coordinating: `").append(workingProjectId)
                .append("`. STEER_PROJECT and project_chat_send default ")
                .append("here — no need to repeat the project name. Use ")
                .append("`project_switch(name=...)` when the user shifts ")
                .append("focus to a different project.");
        return sb.toString();
    }

    private @Nullable String composeWorkingProjectBlock(ThinkProcessDocument process) {
        return renderWorkingProjectBlock(process.getWorkingProjectId());
    }

    /**
     * Pure-function render of the {@code <delegated_workers>} block
     * factored out so the format can be unit-tested without spinning up
     * the engine bean.
     */
    static @Nullable String renderDelegatedWorkersBlock(
            @Nullable List<de.mhus.vance.shared.eddie.WorkerLinkSnapshot> links,
            int maxRender,
            java.time.Instant now) {
        if (links == null || links.isEmpty()) return null;

        var visible = links.stream()
                .filter(l -> l.getWorkerStatus() != null
                        || (l.getTriageSummary() != null && !l.getTriageSummary().isBlank()))
                .sorted((a, b) -> {
                    java.time.Instant ai = a.getLastSeen();
                    java.time.Instant bi = b.getLastSeen();
                    if (ai == null && bi == null) return 0;
                    if (ai == null) return 1;
                    if (bi == null) return -1;
                    return bi.compareTo(ai); // newest first
                })
                .limit(maxRender)
                .toList();
        if (visible.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("## Delegated workers\n\n");
        sb.append("Workers currently running on your behalf — surface their status when you "
                + "need to address something they've reported, but don't repeat the summary "
                + "verbatim back to the user.\n\n");
        for (var link : visible) {
            sb.append("- ").append(workerLabel(link)).append(' ')
                    .append('(').append(linkStatus(link, now)).append(')');
            if (link.getTriageSummary() != null && !link.getTriageSummary().isBlank()) {
                sb.append(" — ").append(link.getTriageSummary().strip());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * {@code workerProcessName-workerProjectName} where both are known;
     * falls back to one of them, then to the worker process id when
     * everything is empty (defensive).
     */
    static String workerLabel(de.mhus.vance.shared.eddie.WorkerLinkSnapshot link) {
        String name = link.getWorkerProcessName();
        String project = link.getWorkerProjectName();
        boolean hasName = name != null && !name.isBlank();
        boolean hasProject = project != null && !project.isBlank();
        if (hasName && hasProject) return name + "-" + project;
        if (hasName) return name;
        if (hasProject) return project;
        return link.getWorkerProcessId();
    }

    /**
     * Compact status for the prompt block: worker status, optional
     * mode (only when not {@link de.mhus.vance.api.thinkprocess.ProcessMode#NORMAL}),
     * and a relative timestamp.
     */
    static String linkStatus(
            de.mhus.vance.shared.eddie.WorkerLinkSnapshot link,
            java.time.Instant now) {
        StringBuilder s = new StringBuilder();
        if (link.getWorkerStatus() != null) {
            s.append(link.getWorkerStatus().name().toLowerCase(java.util.Locale.ROOT));
        } else {
            s.append("active");
        }
        if (link.getWorkerMode() != null
                && link.getWorkerMode() != de.mhus.vance.api.thinkprocess.ProcessMode.NORMAL) {
            s.append('/').append(link.getWorkerMode().name().toLowerCase(java.util.Locale.ROOT));
        }
        if (link.getLastSeen() != null) {
            s.append(", ").append(relativeAge(link.getLastSeen(), now));
        }
        return s.toString();
    }

    static String relativeAge(java.time.Instant ts, java.time.Instant now) {
        long sec = java.time.Duration.between(ts, now).getSeconds();
        if (sec < 0) sec = 0;
        if (sec < 90) return sec + "s ago";
        long min = sec / 60;
        if (min < 90) return min + "min ago";
        long h = min / 60;
        if (h < 48) return h + "h ago";
        return (h / 24) + "d ago";
    }

    private @Nullable String lookupProcessName(@Nullable String processId) {
        if (processId == null || processId.isBlank()) return null;
        return thinkProcessService.findById(processId)
                .map(ThinkProcessDocument::getName)
                .orElse(null);
    }

    private @Nullable String renderForLlm(SteerMessage m) {
        if (m instanceof SteerMessage.UserChatInput) {
            return null; // already in chat history
        }
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
        if (m instanceof SteerMessage.PeerEvent pe) {
            StringBuilder sb = new StringBuilder();
            sb.append("<peer-event sourceEddieProcessId=\"")
                    .append(escapeAttr(pe.sourceEddieProcessId()))
                    .append("\" type=\"")
                    .append(pe.type().name().toLowerCase())
                    .append("\">")
                    .append(escapeText(pe.humanSummary()))
                    .append("</peer-event>");
            return sb.toString();
        }
        if (m instanceof SteerMessage.ToolResult tr) {
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
            return sb.toString();
        }
        if (m instanceof SteerMessage.ExternalCommand ec) {
            return "<external-command name=\""
                    + escapeAttr(ec.command())
                    + "\">"
                    + escapeText(String.valueOf(ec.params()))
                    + "</external-command>";
        }
        return null;
    }

    // ──────────────────── Helpers ────────────────────

    private static ChatMessage toLangchain(ChatMessageDocument msg) {
        return switch (msg.getRole()) {
            case USER -> UserMessage.from(msg.getContent());
            case ASSISTANT -> AiMessage.from(msg.getContent());
            case SYSTEM -> SystemMessage.from(msg.getContent());
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

    private static String summariseReason(String reason) {
        if (reason == null) return "";
        String oneLine = reason.replace("\n", " ").replaceAll("\\s+", " ").trim();
        return oneLine.length() > 80 ? oneLine.substring(0, 77) + "..." : oneLine;
    }

    private static @Nullable Object param(ThinkProcessDocument process, String key) {
        Map<String, Object> p = process.getEngineParams();
        return p == null ? null : p.get(key);
    }

    private static @Nullable String paramString(
            ThinkProcessDocument process, String key, @Nullable String fallback) {
        Object v = param(process, key);
        return v instanceof String s && !s.isBlank() ? s : fallback;
    }

    private static int paramInt(ThinkProcessDocument process, String key, int fallback) {
        Object v = param(process, key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s.trim()); }
            catch (NumberFormatException e) { return fallback; }
        }
        return fallback;
    }

    /**
     * Concatenates the {@code content} of every {@link SteerMessage.UserChatInput}
     * in this turn's inbox — the text the {@code MemoryContextLoader}
     * embeds against the project RAG for auto-inject. Returns
     * {@code null} when the inbox has no user text (wakeup-only turn,
     * cross-process notification arriving without a user message).
     */
    private static @Nullable String latestUserInputText(List<SteerMessage> inbox) {
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

    /**
     * Builds the "Tool usage notes" system block from the per-pack
     * {@code promptHint}s of all tools currently reachable. Returns
     * {@code null} when no reachable tool carries a hint — engine
     * skips the block entirely in that case.
     */
    private @org.jspecify.annotations.Nullable String composeToolHintsBlock(
            ThinkEngineContext engineCtx) {
        if (engineCtx == null || engineCtx.tools() == null) return null;
        java.util.List<String> hints = engineCtx.tools().activePromptHints();
        if (hints.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("## Tool usage notes\n\n");
        for (int i = 0; i < hints.size(); i++) {
            if (i > 0) sb.append("\n\n");
            sb.append(hints.get(i));
        }
        return sb.toString();
    }
}
