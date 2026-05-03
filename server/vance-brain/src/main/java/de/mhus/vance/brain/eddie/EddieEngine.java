package de.mhus.vance.brain.eddie;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.api.thinkprocess.PromptMode;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.EngineChatFactory;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.ai.ModelInfo;
import de.mhus.vance.brain.ai.ModelSize;
import de.mhus.vance.brain.eddie.activity.EddieActivityEntry;
import de.mhus.vance.brain.eddie.activity.EddieActivityService;
import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.brain.events.StreamingProperties;
import de.mhus.vance.brain.memory.MemoryContextLoader;
import de.mhus.vance.brain.progress.LlmCallTracker;
import de.mhus.vance.brain.thinkengine.EngineBundledConfig;
import de.mhus.vance.brain.thinkengine.EnginePromptResolver;
import de.mhus.vance.brain.thinkengine.ParentReport;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.SystemPrompts;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.thinkengine.action.EngineAction;
import de.mhus.vance.brain.thinkengine.action.StructuredActionEngine;
import de.mhus.vance.brain.thinkengine.action.StructuredActionEngine.ActionLoopResult;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.PendingMessageType;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import dev.langchain4j.agent.tool.ToolSpecification;
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
     * Eddie's tool-pool. Two layers — same pattern as Arthur:
     *
     * <ul>
     *   <li><b>LLM-visible</b> ({@link #LLM_VISIBLE_TOOLS}): read-only
     *       tools the LLM may call mid-turn to inform its action choice.
     *       Quick research, project listing, doc lookup, scratchpad,
     *       manuals, peer-notify (still a tool because it's atomic and
     *       has no in-conversation effect).</li>
     *   <li><b>Action-internal</b>: {@code project_create},
     *       {@code project_chat_send}, {@code inbox_post} — invoked by
     *       Eddie's action handlers ({@code DELEGATE_PROJECT},
     *       {@code STEER_PROJECT}, {@code RELAY_INBOX}). Not shown to
     *       the LLM. Keeping them in {@code ALLOWED_TOOLS} satisfies
     *       the dispatcher's allow-filter for engine-internal calls.</li>
     * </ul>
     */
    private static final Set<String> ALLOWED_TOOLS = Set.of(
            // Identity / discovery
            "whoami",
            "current_time",
            "find_tools",
            "describe_tool",
            "invoke_tool",
            // Quick research / compute (Eddie self-capable)
            "web_search",
            "web_fetch",
            "execute_javascript",
            // Personal memory
            "scratchpad_get",
            "scratchpad_set",
            "scratchpad_list",
            "scratchpad_delete",
            // Project organisation (some are read-only, some action-internal)
            "project_list",
            "project_switch",
            "project_current",
            "project_create",          // ACTION-INTERNAL (DELEGATE_PROJECT)
            "project_chat_send",       // ACTION-INTERNAL (STEER_PROJECT)
            "recipe_list",
            "recipe_describe",
            // Documents (within active project)
            "doc_list",
            "doc_find",
            "doc_read",
            "doc_create_text",
            "doc_import_url",
            // Teams
            "team_list",
            "team_describe",
            // Inbox — ACTION-INTERNAL (RELAY_INBOX) plus optional LLM-visible
            // calls for ad-hoc inbox posts
            "inbox_post",
            // Cross-hub sync
            "peer_notify",
            // Manuals
            "manual_list",
            "manual_read");

    /**
     * Subset of {@link #ALLOWED_TOOLS} that the LLM is allowed to see
     * and call directly each turn. Excludes the tools that have a
     * structured-action equivalent — those are routed through
     * {@code eddie_action} instead.
     */
    private static final Set<String> LLM_VISIBLE_TOOLS = Set.of(
            "whoami",
            "current_time",
            "find_tools",
            "describe_tool",
            "invoke_tool",
            "web_search",
            "web_fetch",
            "execute_javascript",
            "scratchpad_get",
            "scratchpad_set",
            "scratchpad_list",
            "scratchpad_delete",
            "project_list",
            "project_switch",
            "project_current",
            "recipe_list",
            "recipe_describe",
            "doc_list",
            "doc_find",
            "doc_read",
            "doc_create_text",
            "doc_import_url",
            "team_list",
            "team_describe",
            "peer_notify",
            "manual_list",
            "manual_read");

    private static final String PROMPT_PATH = "prompts/eddie-prompt.md";
    private static final String PROMPT_SMALL_PATH = "prompts/eddie-prompt-small.md";
    private static final String PROMPT_RESOURCE = "vance-defaults/prompts/eddie-prompt.md";
    private static final String PROMPT_SMALL_RESOURCE = "vance-defaults/prompts/eddie-prompt-small.md";

    private static final int DEFAULT_MAX_ITERATIONS = 4;
    private static final String DEFAULT_MODEL_ALIAS = "default:analyze";

    private volatile @Nullable EngineBundledConfig cachedConfig;

    // ──────────────────── Dependencies ────────────────────

    private final ThinkProcessService thinkProcessService;
    private final ModelCatalog modelCatalog;
    private final EngineChatFactory engineChatFactory;
    private final EnginePromptResolver enginePromptResolver;
    private final MemoryContextLoader memoryContextLoader;
    private final EddieActivityService activityService;
    private final de.mhus.vance.shared.session.SessionService sessionService;
    private final EngineMessageRouter messageRouter;

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
            EngineMessageRouter messageRouter) {
        super(streamingProperties, llmCallTracker, objectMapper);
        this.thinkProcessService = thinkProcessService;
        this.modelCatalog = modelCatalog;
        this.engineChatFactory = engineChatFactory;
        this.enginePromptResolver = enginePromptResolver;
        this.memoryContextLoader = memoryContextLoader;
        this.activityService = activityService;
        this.sessionService = sessionService;
        this.messageRouter = messageRouter;
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

    @Override
    public Optional<EngineBundledConfig> bundledConfig() {
        EngineBundledConfig cached = cachedConfig;
        if (cached == null) {
            cached = buildBundledConfig(loadResource(PROMPT_RESOURCE),
                    loadResource(PROMPT_SMALL_RESOURCE));
            cachedConfig = cached;
        }
        return Optional.of(cached);
    }

    @Override
    public Optional<EngineBundledConfig> bundledConfig(
            String tenantId, @Nullable String projectId) {
        String promptFallback = loadResource(PROMPT_RESOURCE);
        String promptSmallFallback = loadResource(PROMPT_SMALL_RESOURCE);
        String prompt = enginePromptResolver.resolveForTenant(
                tenantId, projectId, PROMPT_PATH, promptFallback);
        String promptSmall = enginePromptResolver.resolveForTenant(
                tenantId, projectId, PROMPT_SMALL_PATH, promptSmallFallback);
        return Optional.of(buildBundledConfig(prompt, promptSmall));
    }

    private EngineBundledConfig buildBundledConfig(String prompt, String promptSmall) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("model", DEFAULT_MODEL_ALIAS);
        params.put("validation", true);
        params.put("maxIterations", DEFAULT_MAX_ITERATIONS);
        params.put("manualPaths", List.of("eddie/manuals/", "manuals/"));
        return new EngineBundledConfig(
                params, prompt, promptSmall, PromptMode.OVERWRITE,
                /*dataRelayCorrection*/ null,
                /*allowedTools*/ null);
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
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
    }

    @Override
    public void suspend(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.debug("Eddie.suspend id='{}'", process.getId());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.SUSPENDED);
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
        while (true) {
            if (thinkProcessService.isHaltRequested(process.getId())) {
                log.info("Eddie.runTurn id='{}' — halt requested, yielding",
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

    private void runTurnFor(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            List<SteerMessage> inbox) {

        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.RUNNING);
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
            ContextToolsApi tools = ctx.tools();
            List<ToolSpecification> toolSpecs = tools.primaryAsLc4j();
            ModelInfo modelInfo = modelCatalog.lookupOrDefault(
                    config.provider(), config.modelName());
            ModelSize effectiveSize = ModelSize.parseOrAuto(
                    paramString(process, "modelSize", null), modelInfo.size());

            List<ChatMessage> messages = buildPromptMessages(
                    process, chatLog, inbox, effectiveSize);
            int maxIters = paramInt(process, "maxIterations",
                    DEFAULT_MAX_ITERATIONS);
            log.debug("Eddie.turn id='{}' inbox={} historyMsgs={} model={} maxIters={}",
                    process.getId(), inbox.size(), messages.size(),
                    config.modelName(), maxIters);

            String modelAlias = config.provider() + ":" + config.modelName();

            // LLM sees only read-only tools — action-equivalent tools
            // (project_create, project_chat_send, inbox_post) are
            // engine-internal, invoked by handlers via tools.invoke().
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
                log.info("Eddie.turn id='{}' awaiting={} -> '{}'",
                        process.getId(), awaitingUserInput, preview);
            } else {
                log.info("Eddie.turn id='{}' awaiting={} (silent — no chat append)",
                        process.getId(), awaitingUserInput);
            }
        } finally {
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

    @Override
    protected ActionTurnOutcome handleAction(
            EngineAction action,
            ThinkProcessDocument process,
            ThinkEngineContext ctx) {
        return switch (action.type()) {
            case EddieActionSchema.TYPE_ANSWER           -> handleAnswer(action);
            case EddieActionSchema.TYPE_ASK_USER         -> handleAskUser(action);
            case EddieActionSchema.TYPE_DELEGATE_PROJECT -> handleDelegateProject(action, process, ctx);
            case EddieActionSchema.TYPE_STEER_PROJECT    -> handleSteerProject(action, process, ctx);
            case EddieActionSchema.TYPE_RELAY            -> handleRelay(action, process, ctx);
            case EddieActionSchema.TYPE_RELAY_INBOX      -> handleRelayInbox(action, process, ctx);
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
        return new ActionTurnOutcome(message, /*awaitingUserInput*/ true);
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
        String message = action.stringParam(EddieActionSchema.PARAM_MESSAGE);

        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("name", projectName);
            if (projectTitle != null && !projectTitle.isBlank()) {
                params.put("title", projectTitle);
            }
            params.put("initialPrompt", projectGoal);
            ctx.tools().invoke("project_create", params);
            log.info("Eddie id='{}' DELEGATE_PROJECT name='{}' reason='{}'",
                    process.getId(), projectName,
                    summariseReason(action.reason()));
        } catch (RuntimeException e) {
            log.warn("Eddie id='{}' DELEGATE_PROJECT failed: {}",
                    process.getId(), e.toString());
            return new ActionTurnOutcome(
                    "Konnte das Projekt nicht anlegen: " + e.getMessage(),
                    true);
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
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("project", project);
            params.put("content", content);
            ctx.tools().invoke("project_chat_send", params);
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
        log.info("Eddie id='{}' RELAY source='{}' ({} chars) reason='{}'",
                process.getId(),
                action.stringParam(EddieActionSchema.PARAM_SOURCE),
                lastReply.getContent().length(),
                summariseReason(action.reason()));
        return new ActionTurnOutcome(out.toString(), /*awaitingUserInput*/ true);
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
            ctx.tools().invoke("inbox_post", params);
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
            ModelSize modelSize) {
        List<ChatMessage> messages = new ArrayList<>();
        String base = SystemPrompts.compose(process,
                process.getPromptOverride() == null
                        ? GREETING
                        : process.getPromptOverride(),
                modelSize);
        String userBlock = composeUserContextBlock(process);
        if (userBlock != null && !userBlock.isBlank()) {
            base = base + "\n\n" + userBlock;
        }
        String memoryBlock = memoryContextLoader.composeBlock(process);
        if (memoryBlock != null && !memoryBlock.isBlank()) {
            base = base + "\n\n" + memoryBlock;
        }
        messages.add(SystemMessage.from(base));

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
        return sb.toString();
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
}
