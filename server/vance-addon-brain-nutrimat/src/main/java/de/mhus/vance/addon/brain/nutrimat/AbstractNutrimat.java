package de.mhus.vance.addon.brain.nutrimat;

import de.mhus.vance.api.chat.ChatMessageChunkData;
import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiChatException;
import de.mhus.vance.brain.ai.EngineChatFactory;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.ai.ModelInfo;
import de.mhus.vance.brain.ai.ModelSize;
import de.mhus.vance.brain.context.PromptDateContextResolver;
import de.mhus.vance.brain.events.ChunkBatcher;
import de.mhus.vance.brain.events.ClientEventPublisher;
import de.mhus.vance.brain.events.StreamingProperties;
import de.mhus.vance.brain.guard.ShootyGuardService;
import de.mhus.vance.brain.memory.CompactionResult;
import de.mhus.vance.brain.memory.MemoryCompactionService;
import de.mhus.vance.brain.memory.MemoryContextLoader;
import de.mhus.vance.brain.prak.HistoryStrengthFilter;
import de.mhus.vance.brain.progress.LlmCallTracker;
import de.mhus.vance.brain.prompt.ClientTurnContextResolver;
import de.mhus.vance.brain.prompt.PromptContextBuilder;
import de.mhus.vance.brain.prompt.ScratchpadPromptContributor;
import de.mhus.vance.brain.skill.ResolvedSkill;
import de.mhus.vance.brain.skill.SkillPromptComposer;
import de.mhus.vance.brain.skill.SkillResolver;
import de.mhus.vance.brain.skill.SkillScopeContext;
import de.mhus.vance.brain.skill.SkillTriggerMatcher;
import de.mhus.vance.brain.skill.SkillTurnSupport;
import de.mhus.vance.brain.skill.UnknownSkillException;
import de.mhus.vance.brain.thinkengine.EnginePromptResolver;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.SystemPromptComposer;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.thinkengine.TurnContextHandlerRegistry;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.brain.tools.ToolErrorPayload;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.memory.MemoryDocument;
import de.mhus.vance.shared.memory.MemoryKind;
import de.mhus.vance.shared.memory.MemoryService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.skill.ActiveSkillRefEmbedded;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.workspace.WorkspaceService;
import de.mhus.vance.toolpack.ToolException;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

/**
 * Nutrimat — the turn shell and loop laboratory base. Every loop nature
 * ({@code janx}, {@code redbull}, {@code mate}, {@code salitos}, …) extends
 * this class and is registered as its own {@link ThinkEngine} bean under the
 * name {@code nutrimat-<nature>}.
 *
 * <p><b>Closed on the outside, open on the inside.</b> The lifecycle methods
 * ({@code start}/{@code steer}/{@code runTurn}/{@code resume}/{@code
 * suspend}/{@code stop}) are {@code final} and implement the Ford-style worker
 * contract every orchestrator already speaks: {@code asyncSteer=false}, one
 * reply per turn, worker → {@code IDLE} / primary → {@code BLOCKED}, runaway →
 * terminal {@code INCOMPLETE}. A nature varies <em>only</em> the loop policy
 * through the hooks below — it cannot break the contract.
 *
 * <p><b>Independence.</b> The mechanics here are adapted from the productive
 * engine strand (Ford's natural-stop loop), deliberately not linked against it:
 * Nutrimat conserves its own loop states, even broken ones. Shared is
 * infrastructure only (AiChat, chat log, memory compaction, guards). See
 * {@code planning/nutrimat-engine.md}.
 *
 * <p><b>Loop policy hooks</b> (override to change the loop, nothing else):
 * <ul>
 *   <li>{@link #onNaturalStopCandidate} — the model stopped calling tools:
 *       accept the text as the reply, push a correction, or decide
 *       "continue working" (the {@code salitos} question).</li>
 *   <li>{@link #onExhausted} — the iteration budget ran out: synthesize,
 *       extend with a fresh budget ({@code mate}'s judge), or throw
 *       {@link NutrimatExhaustedException} for a hard, visible failure
 *       ({@code redbull}).</li>
 *   <li>{@link #onLlmFailure} — the provider call collapsed: same decision
 *       vocabulary.</li>
 * </ul>
 */
@Slf4j
public abstract class AbstractNutrimat implements ThinkEngine {

    /** Engine-name prefix; the full name is {@code nutrimat-<nature>}. */
    public static final String NAME_PREFIX = "nutrimat-";

    /**
     * Bare-minimum fallback when no recipe supplies a real prompt. Kept tiny
     * on purpose — normally never used because the bundled {@code
     * nutrimat-*} recipes always carry a prompt.
     */
    private static final String SYSTEM_PROMPT = "You are a Nutrimat loop-lab worker. Use tools to gather "
            + "concrete data; paste the relevant data into your reply.";

    /**
     * Shared engine-default prompt base for all Nutrimat natures. One base
     * prompt for every nature on purpose — a loop comparison is only honest
     * when the prompt does not vary with the loop. Nature-specific wording
     * rides in the recipe's {@code promptPrefix}; tenants override this file
     * in their {@code _vance} project.
     */
    private static final String DEFAULT_PROMPT_PATH = "_vance/prompts/nutrimat-prompt.md";

    /** Safety-net cap on tool-call iterations per budget (recipe {@code params.maxIterations}). */
    protected static final int MAX_TOOL_ITERATIONS = 40;

    /** Wall-clock safety-net for a single streaming LLM call. */
    private static final long STREAM_TIMEOUT_MINUTES = 20;

    /**
     * Per-turn wallclock safety net spanning the initial budget plus every
     * judge-approved extension — the automatic backstop against a judge that
     * keeps mis-deciding "extend" for headless turns where no human is around
     * to press ESC. Mirrors the structured engines' net.
     */
    protected static final long TURN_WALLCLOCK_MINUTES = 30;

    // ──────────────────── Validation heuristic ────────────────────
    // Opt-in via params.validation == true. One check: reply-too-brief-
    // after-data-fetch — corrects it before accepting the natural stop.

    /** Tool result size (chars) above which we expect the data to be
     *  reflected in the reply. */
    private static final int TOOL_DATA_THRESHOLD = 500;

    /** Reply size (chars) below which we suspect the data wasn't relayed. */
    private static final int REPLY_BRIEF_THRESHOLD = 200;

    private static final int MAX_VALIDATION_CORRECTIONS = 2;

    private static final String DATA_RELAY_CORRECTION_TEMPLATE =
            "VALIDATION CHECK: tools returned %d chars, your reply has "
                    + "%d — paste the actual data into the reply text.";

    // ──────────────────── Engine-default tools ────────────────────

    /**
     * Engine-default tool baseline — the same curated set the Ford worker
     * contract carries (discovery, sub-worker spawn, read-side documents,
     * research, settings read, the work-target layer). A non-empty baseline
     * is exclusive: a tool missing here is excluded outright, so recipes
     * pull domain tools in via {@code allowedToolsAdd}.
     */
    protected static final Set<String> ENGINE_DEFAULT_TOOLS;

    static {
        java.util.LinkedHashSet<String> base = new java.util.LinkedHashSet<>();
        base.add("tool_list");
        base.add("tool_description");
        base.add("how_do_i");
        base.add("manual_read");
        base.add("manual_list");
        base.add("recipe_describe");
        base.add("tool_result_read");
        base.add("process_spawn");
        base.add("process_status");
        base.add("vance_notify");
        base.add("current_time");
        base.add("whoami");
        base.add("scratchpad_set");
        base.add("scratchpad_get");
        base.add("scratchpad_list");
        base.add("scratchpad_delete");
        base.add("doc_read");
        base.add("doc_read_lines");
        base.add("doc_info");
        base.add("doc_summary");
        base.add("doc_list");
        base.add("doc_list_folders");
        base.add("doc_list_in_folder");
        base.add("doc_list_by_tag");
        base.add("doc_find");
        base.add("doc_grep");
        base.add("doc_grep_path");
        base.add("doc_link");
        base.add("web_fetch");
        base.add("web_search");
        base.add("research_search");
        base.add("research_investigate");
        base.add("research_rich");
        base.add("research_providers");
        base.add("memory_search");
        base.add("setting_get");
        base.addAll(de.mhus.vance.brain.tools.worktarget.BaseEngineTools.WORK_TARGET);
        ENGINE_DEFAULT_TOOLS = java.util.Collections.unmodifiableSet(base);
    }

    // ──────────────────── Dependencies ────────────────────

    private final ThinkProcessService thinkProcessService;
    private final ObjectMapper objectMapper;
    private final StreamingProperties streamingProperties;
    private final ModelCatalog modelCatalog;
    private final LlmCallTracker llmCallTracker;
    private final MemoryContextLoader memoryContextLoader;
    private final EnginePromptResolver enginePromptResolver;
    private final SystemPromptComposer composer;
    private final EngineChatFactory engineChatFactory;
    private final MemoryService memoryService;
    private final MemoryCompactionService memoryCompactionService;
    private final SkillResolver skillResolver;
    private final SkillPromptComposer skillPromptComposer;
    private final SkillTriggerMatcher skillTriggerMatcher;
    private final SessionService sessionService;
    private final PromptDateContextResolver promptDateContextResolver;
    private final ScratchpadPromptContributor scratchpadPromptContributor;
    private final WorkspaceService workspaceService;
    private final HistoryStrengthFilter historyStrengthFilter;
    private final ClientTurnContextResolver clientTurnContextResolver;
    private final TurnContextHandlerRegistry turnContextHandlers;
    private final ShootyGuardService guardService;

    protected AbstractNutrimat(
            ThinkProcessService thinkProcessService,
            ObjectMapper objectMapper,
            StreamingProperties streamingProperties,
            ModelCatalog modelCatalog,
            LlmCallTracker llmCallTracker,
            MemoryContextLoader memoryContextLoader,
            EnginePromptResolver enginePromptResolver,
            SystemPromptComposer composer,
            EngineChatFactory engineChatFactory,
            MemoryService memoryService,
            MemoryCompactionService memoryCompactionService,
            SkillResolver skillResolver,
            SkillPromptComposer skillPromptComposer,
            SkillTriggerMatcher skillTriggerMatcher,
            SessionService sessionService,
            PromptDateContextResolver promptDateContextResolver,
            ScratchpadPromptContributor scratchpadPromptContributor,
            WorkspaceService workspaceService,
            HistoryStrengthFilter historyStrengthFilter,
            ClientTurnContextResolver clientTurnContextResolver,
            TurnContextHandlerRegistry turnContextHandlers,
            ShootyGuardService guardService) {
        this.thinkProcessService = thinkProcessService;
        this.objectMapper = objectMapper;
        this.streamingProperties = streamingProperties;
        this.modelCatalog = modelCatalog;
        this.llmCallTracker = llmCallTracker;
        this.memoryContextLoader = memoryContextLoader;
        this.enginePromptResolver = enginePromptResolver;
        this.composer = composer;
        this.engineChatFactory = engineChatFactory;
        this.memoryService = memoryService;
        this.memoryCompactionService = memoryCompactionService;
        this.skillResolver = skillResolver;
        this.skillPromptComposer = skillPromptComposer;
        this.skillTriggerMatcher = skillTriggerMatcher;
        this.sessionService = sessionService;
        this.promptDateContextResolver = promptDateContextResolver;
        this.scratchpadPromptContributor = scratchpadPromptContributor;
        this.workspaceService = workspaceService;
        this.historyStrengthFilter = historyStrengthFilter;
        this.clientTurnContextResolver = clientTurnContextResolver;
        this.turnContextHandlers = turnContextHandlers;
        this.guardService = guardService;
    }

    // ──────────────────── Nature identity ────────────────────

    /**
     * The nature's short id ({@code janx}, {@code redbull}, …). Must match
     * {@code [a-z0-9]+}: the engine name {@code nutrimat-<nature>} is split on
     * exactly one dash, and the id lands in recipe names.
     */
    protected abstract String natureId();

    /**
     * Loop-type one-liner for {@link #description()} — what this nature
     * actually explores (e.g. "natural stop", "hard budget with exhausted
     * error"). English, user-facing.
     */
    protected abstract String loopType();

    private static void requireUsableNatureId(@Nullable String id) {
        if (id == null || !id.matches("[a-z0-9]+")) {
            throw new IllegalStateException(
                    "Nutrimat nature id must match [a-z0-9]+ (it is split out of 'nutrimat-<nature>' "
                            + "and lands in recipe names), got: "
                            + id);
        }
    }

    @Override
    public final String name() {
        // Validated here, not in the constructor: the id comes from an
        // overridable accessor, and the engine registry indexes every bean
        // by name() at startup — so an unusable id still fails at boot.
        String id = natureId();
        requireUsableNatureId(id);
        return NAME_PREFIX + id;
    }

    @Override
    public String title() {
        return "Nutrimat " + natureId() + " (loop lab)";
    }

    @Override
    public String description() {
        return "Experimental Nutrimat loop nature '" + natureId() + "' — " + loopType()
                + ". See specification/public/nutrimat-engine.md.";
    }

    @Override
    public String version() {
        return "0.1.0";
    }

    @Override
    public Set<String> allowedTools() {
        return ENGINE_DEFAULT_TOOLS;
    }

    // ──────────────────── Lifecycle ────────────────────

    @Override
    public final void start(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.info(
                "Nutrimat[{}].start tenant='{}' session='{}' id='{}'",
                natureId(),
                process.getTenantId(),
                process.getSessionId(),
                process.getId());
        // No greeting on start — same reasoning as Ford: workers spawned
        // with steerContent drain that input immediately, and an interactive
        // process's first /process-steer drives the engine.
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
    }

    @Override
    public final void resume(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.debug("Nutrimat[{}].resume id='{}'", natureId(), process.getId());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
    }

    @Override
    public final void suspend(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.debug("Nutrimat[{}].suspend id='{}'", natureId(), process.getId());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.SUSPENDED);
    }

    @Override
    public final void steer(ThinkProcessDocument process, ThinkEngineContext ctx, SteerMessage message) {
        // Single-message entry — wrap in a one-element inbox and route
        // through the same drain-aware path the default runTurn uses.
        runTurnFor(process, ctx, List.of(message));
    }

    /**
     * Drain the whole inbox once per pass and fold it into a single LLM
     * round-trip — the auto-wakeup loop. Without this override the framework
     * would call {@link #steer} per drained message, burning an LLM call per
     * ProcessEvent and preventing the model from seeing UserChatInput +
     * worker reply in the same turn.
     */
    @Override
    public final void runTurn(ThinkProcessDocument process, ThinkEngineContext ctx) {
        while (true) {
            List<SteerMessage> drained = ctx.drainPending();
            if (drained.isEmpty()) return;
            TurnOutcome outcome = runTurnFor(process, ctx, drained);
            // Stop draining after a turn that closed or parked the
            // process. A hard-failure turn (exhausted error, LLM collapse)
            // closes a worker terminally INCOMPLETE — any messages that a
            // mid-turn child worker pushed into the inbox while it was
            // running must NOT spin another turn on the closed process
            // (observed live: redbull exhausted at 40, children replied
            // mid-turn, the drain loop kept the dead worker working). The
            // same applies to an interrupted turn (ESC / /pause parks the
            // process, the pending queue stays for the resume) and to a
            // terminal state anyone else set.
            ThinkProcessStatus status = thinkProcessService
                    .findById(process.getId())
                    .map(ThinkProcessDocument::getStatus)
                    .orElse(ThinkProcessStatus.CLOSED);
            if (status == ThinkProcessStatus.CLOSED
                    || status == ThinkProcessStatus.PAUSED
                    || status == ThinkProcessStatus.SUSPENDED
                    || outcome.interrupted()) {
                log.info(
                        "Nutrimat[{}].runTurn id='{}' stopping drain loop (status={}, interrupted={}) — "
                                + "pending messages stay in the queue",
                        natureId(),
                        process.getId(),
                        status,
                        outcome.interrupted());
                return;
            }
        }
    }

    @Override
    public final void stop(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.info("Nutrimat[{}].stop id='{}'", natureId(), process.getId());
        thinkProcessService.closeProcess(process.getId(), CloseReason.STOPPED);
    }

    // ──────────────────── One turn (the shell) ────────────────────

    private TurnOutcome runTurnFor(ThinkProcessDocument process, ThinkEngineContext ctx, List<SteerMessage> inbox) {

        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.RUNNING);
        // START-point guards fire per genuine user turn, before prompt
        // assembly (skill-trigger matching, active-skill resolution).
        // Fail-open; guard-injected turns fire nothing.
        guardService.guardsOnTurnStart(process, inbox);
        boolean awaitingUserInput = false;

        // Exhausted hard-stop semantics (redbull): once a PRIMARY hit its
        // hard budget, the loop's work is OVER — background events that pile
        // up afterwards (exec_finished & friends) must not spin another
        // loop. Only an explicit user message (e.g. "continue") starts the
        // next loop run. See the awaitingUserContinue flag in nutrimatState.
        if (exhaustedStopsUntilUserInput()
                && process.getParentProcessId() == null
                && awaitingUserContinue(process)
                && inbox.stream().noneMatch(m -> m instanceof SteerMessage.UserChatInput)) {
            narrate(
                    ctx,
                    process,
                    "loop closed after exhausted — discarded " + inbox.size()
                            + " background event(s); send a message ('continue') to start the next loop");
            // Park the process: work is over until the user speaks. This
            // return path is outside the try/finally below — set the status
            // explicitly (the outcome's awaitingUserInput=true mirrors it).
            thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.BLOCKED);
            return TurnOutcome.terminal("", true);
        }
        // True iff this turn exited via a hard-failure path (budget
        // exhausted / LLM collapse / exhausted exception). For sub-process
        // workers this triggers a terminal close — a worker that cannot make
        // further progress on its own must not pin the parent's delegation
        // pointer to a dead-end.
        boolean hardFailure = false;
        boolean interrupted = false;
        boolean interruptForcePause = false;
        try {
            ChatMessageService chatLog = ctx.chatMessageService();
            // Persist UserChatInput entries to chat history so future turns
            // see them; collect the joined text for skill-trigger matching.
            // Non-UCI items (ProcessEvent, ToolResult, …) are turn-local.
            StringBuilder userTextForTriggers = new StringBuilder();
            List<SteerMessage> extras = new ArrayList<>();
            for (SteerMessage m : inbox) {
                if (m instanceof SteerMessage.UserChatInput uci
                        && uci.content() != null
                        && !uci.content().isBlank()) {
                    chatLog.append(ChatMessageDocument.builder()
                            .tenantId(process.getTenantId())
                            .sessionId(process.getSessionId())
                            .thinkProcessId(process.getId())
                            .role(ChatRole.USER)
                            .content(uci.content())
                            .build());
                    if (userTextForTriggers.length() > 0) userTextForTriggers.append('\n');
                    userTextForTriggers.append(uci.content());
                } else if (!(m instanceof SteerMessage.UserChatInput)) {
                    extras.add(m);
                }
            }
            String userInput = userTextForTriggers.toString();
            if (!userInput.isBlank()) {
                skillTriggerMatcher.detectAndActivate(process, userInput);
            }

            EngineChatFactory.EngineChatBundle chatBundle = engineChatFactory.forProcess(process, ctx, name());
            AiChat aiChat = chatBundle.chat();
            AiChatConfig config = chatBundle.primaryConfig();

            List<ResolvedSkill> activeSkills = resolveActiveSkills(process);

            ContextToolsApi tools = ctx.tools().withAdditional(skillPromptComposer.mergedTools(activeSkills));
            List<ToolSpecification> toolSpecs = tools.primaryAsLc4j();
            ModelInfo modelInfo = modelCatalog.lookupOrDefault(
                    process.getTenantId(),
                    process.getProjectId(),
                    config.providerInstance(),
                    config.provider(),
                    config.modelName());

            ModelSize effectiveSize = ModelSize.parseOrAuto(paramString(process, "modelSize", null), modelInfo.size());
            List<ChatMessage> messages =
                    buildPromptMessages(process, chatLog, extras, modelInfo, effectiveSize, activeSkills, tools);
            CompactionResult compactResult =
                    memoryCompactionService.compactIfNeeded(process, config, messages, modelInfo);
            if (compactResult.compacted()) {
                log.info(
                        "Nutrimat[{}].turn id='{}' compaction ok: {} msgs → {} chars (memory='{}')",
                        natureId(),
                        process.getId(),
                        compactResult.messagesCompacted(),
                        compactResult.summaryChars(),
                        compactResult.memoryId());
                // Rebuild the prompt: the active-history shrunk and a new
                // ARCHIVED_CHAT memory pinned the summary at top.
                messages = buildPromptMessages(process, chatLog, extras, modelInfo, effectiveSize, activeSkills, tools);
            }

            LoopInputs in = new LoopInputs(
                    aiChat,
                    toolSpecs,
                    tools,
                    messages,
                    paramInt(process, "maxIterations", MAX_TOOL_ITERATIONS),
                    paramBool(process, "validation", false),
                    config.providerInstance() + ":" + config.modelName(),
                    userInput);
            LoopStats stats = new LoopStats();
            TurnOutcome outcome;
            try {
                outcome = runLoop(process, ctx, in, stats);
            } catch (NutrimatExhaustedException e) {
                // The nature's exhausted policy is a hard failure: the error
                // IS the turn outcome. No best-free-text rescue — a rescued
                // partial would disguise the failure the nature deliberately
                // reports (redbull).
                log.warn("Nutrimat[{}] id='{}' exhausted: {}", natureId(), process.getId(), e.getMessage());
                outcome = TurnOutcome.failed("⚠️ TASK FAILED — " + e.getMessage());
            }
            if (outcome.interrupted()) {
                interrupted = true;
                interruptForcePause = outcome.interruptForcePause();
                ctx.historyTagSink().discard();
                log.info(
                        "Nutrimat[{}].turn id='{}' interrupted (forcePause={}) — parking, no answer surfaced",
                        natureId(),
                        process.getId(),
                        interruptForcePause);
                return outcome;
            }
            if (outcome.recovered()) {
                hardFailure = true;
            }
            awaitingUserInput = outcome.awaitingUserInput();
            persistNutrimatState(process, stats, outcome);
            String finalText = outcome.finalText();

            // Hard-failure worker: the reply is the parent's ONLY signal about
            // the worker's fate (a parent watching over the Working WS does not
            // get the FAILED ProcessEvent rendered) — spell out that this is a
            // force-abort and the text below is partial-not-answer.
            if (hardFailure && outcome.wrapPartial() && process.getParentProcessId() != null) {
                finalText = "⚠️ TASK FAILED — this worker hit its hard limit and was "
                        + "force-stopped (" + in.maxIterations() + " processing steps, maxIterations). "
                        + "It is now CLOSED and cannot be resumed. The task is UNFINISHED: "
                        + "the text below is PARTIAL progress only, NOT an answer — do not "
                        + "treat it as done, and do not assume the remaining steps ran. To "
                        + "carry the task further, start a fresh worker (tighter scope or a "
                        + "higher step limit).\n\nPartial progress:\n\n" + finalText;
            }
            // Primary continue-gate hint: tell the user how the closed loop
            // reopens — background events are being discarded until they speak.
            if (hardFailure && process.getParentProcessId() == null && exhaustedStopsUntilUserInput()) {
                finalText = finalText
                        + "\n\nThe loop is closed — background events are ignored from here. "
                        + "Send a message (e.g. 'continue') to start the next loop run.";
            }

            ChatMessageDocument saved = chatLog.append(ChatMessageDocument.builder()
                    .tenantId(process.getTenantId())
                    .sessionId(process.getSessionId())
                    .thinkProcessId(process.getId())
                    .role(ChatRole.ASSISTANT)
                    .content(finalText)
                    .build());
            if (saved != null && saved.getId() != null) {
                ctx.historyTagSink().flushTo(saved.getId(), chatLog);
            }

            // Emit the semantic reply on the explicit channel — parent inbox
            // + session clients — independent of the lane-status below.
            if (finalText != null && !finalText.isBlank()) {
                Instant inResponseToAt = lastUserInputAt(inbox);
                ctx.emitReply(finalText, inResponseToAt, null);
            }

            String preview = finalText.length() > 120 ? finalText.substring(0, 120) + "…" : finalText;
            log.info(
                    "Nutrimat[{}].steer id='{}' awaiting={} -> '{}'",
                    natureId(),
                    process.getId(),
                    awaitingUserInput,
                    preview);
            return outcome;
        } finally {
            dropOneShotSkills(process);
            if (interrupted) {
                if (interruptForcePause) {
                    thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.PAUSED);
                }
            } else if (hardFailure && process.getParentProcessId() != null) {
                // Sub-process worker hit its hard limit. The reply has already
                // been appended and emitted; close terminally so the parent's
                // delegation pointer releases and the parent learns the task
                // did not finish (CLOSED+INCOMPLETE → FAILED ProcessEvent).
                log.info(
                        "Nutrimat[{}] id='{}' worker hard-failure — closing INCOMPLETE so parent '{}' "
                                + "releases delegation pointer and learns the task did not finish",
                        natureId(),
                        process.getId(),
                        process.getParentProcessId());
                thinkProcessService.closeProcess(process.getId(), CloseReason.INCOMPLETE);
            } else {
                ThinkProcessStatus exitStatus =
                        awaitingUserInput ? ThinkProcessStatus.BLOCKED : ThinkProcessStatus.IDLE;
                thinkProcessService.updateStatus(process.getId(), exitStatus);
            }
        }
    }

    // ──────────────────── Loop kernel ────────────────────

    /**
     * The shared loop skeleton. Mechanics (streaming one iteration,
     * dispatching tool calls, the interrupt check, the best-free-text bookkeeping)
     * are fixed; the policy decisions at the three fork points are the
     * nature's hooks. Judge-approved budget extensions loop through the outer
     * {@code while} — bounded by the per-turn wallclock net, never by a fixed
     * extension ceiling (the hook decides).
     */
    private TurnOutcome runLoop(ThinkProcessDocument process, ThinkEngineContext ctx, LoopInputs in, LoopStats stats) {
        StringBuilder finalText = new StringBuilder();
        // Best Free-Text seen so far across all iterations — last-resort
        // material for the failure/extension hooks.
        String bestFreeText = "";
        int toolDataChars = 0;
        int corrections = 0;
        int stopCandidates = 0;
        Instant turnStart = Instant.now();
        int budget = in.maxIterations();
        int consumed = 0;
        String userGoal = in.userGoal().isBlank() ? "(no user message in this turn)" : in.userGoal();

        while (true) {
            for (int iter = 0; iter < budget; iter++) {
                // Loop narration — a dimmed interim note per iteration so the
                // user can follow the loop live (experiment observability).
                // Never enters the LLM context: interim replies are pure
                // user-progress channel, parent-inbox routing is skipped.
                narrate(
                        ctx,
                        process,
                        "round " + (stats.iterationsConsumed + 1) + "/" + budget
                                + (stats.extensions > 0 ? " (extended " + stats.extensions + "×)" : ""));
                // Mid-loop interrupt — checked before the next LLM call so
                // ESC / /pause stops a running tool loop promptly.
                InterruptKind kind = checkInterrupt(process);
                if (kind != InterruptKind.NONE) {
                    return TurnOutcome.interrupted(kind == InterruptKind.FORCE_PAUSE);
                }

                ChatRequest.Builder req =
                        ChatRequest.builder().messages(turnContextHandlers.apply(in.messages(), ctx, process));
                if (!in.toolSpecs().isEmpty()) {
                    req.toolSpecifications(in.toolSpecs());
                }

                AiMessage reply;
                try {
                    reply = streamOneIteration(in.aiChat(), req.build(), ctx, process, in.modelAlias())
                            .message();
                } catch (RuntimeException e) {
                    ExhaustionDecision d = onLlmFailure(
                            state(
                                    process,
                                    ctx,
                                    userGoal,
                                    consumed,
                                    in,
                                    bestFreeText,
                                    toolDataChars,
                                    corrections,
                                    stopCandidates),
                            e);
                    if (d.kind() == ExhaustionDecision.Kind.EXTEND && wallclockOk(turnStart)) {
                        consumed++;
                        stats.iterationsConsumed = consumed;
                        in.messages().add(UserMessage.from(nudgeText(d)));
                        stats.extensions++;
                        continue;
                    }
                    return toOutcome(d, process, bestFreeText);
                }
                consumed++;
                stats.iterationsConsumed = consumed;

                String replyText = reply.text();
                if (replyText != null && replyText.length() > bestFreeText.length()) {
                    bestFreeText = replyText;
                }

                if (!reply.hasToolExecutionRequests()) {
                    stopCandidates++;
                    stats.stopCandidates = stopCandidates;
                    // Natural-stop candidate: the first assistant message
                    // without a tool call. The nature decides what it is.
                    LoopState st = state(
                            process,
                            ctx,
                            userGoal,
                            consumed,
                            in,
                            bestFreeText,
                            toolDataChars,
                            corrections,
                            stopCandidates);
                    StopDecision d = onNaturalStopCandidate(st, reply);
                    narrate(
                            ctx,
                            process,
                            "stop decision: " + d.kind().name().toLowerCase(java.util.Locale.ROOT)
                                    + (d.kind() == StopDecision.Kind.ACCEPT
                                            ? " — the model's text is the reply"
                                            : " — " + nonBlankOr(d.message(), "")));
                    switch (d.kind()) {
                        case ACCEPT -> {
                            if (replyText != null) {
                                finalText.append(replyText);
                            }
                            if (in.validation() && corrections > 0) {
                                log.info(
                                        "Nutrimat[{}] id='{}' validation: completed after {} correction(s)",
                                        natureId(),
                                        process.getId(),
                                        corrections);
                            }
                            // awaiting by role: a worker (has a parent) is
                            // done → IDLE so the parent can steer again; a
                            // primary (no parent) awaits the user's next
                            // message → BLOCKED.
                            boolean awaiting = process.getParentProcessId() == null;
                            return TurnOutcome.terminal(finalText.toString(), awaiting);
                        }
                        case CORRECT -> {
                            in.messages().add(reply);
                            in.messages()
                                    .add(SystemMessage.from(nonBlankOr(
                                            d.message(), "Continue working — the turn is not complete yet.")));
                            corrections++;
                        }
                        case CONTINUE -> {
                            in.messages().add(reply);
                            in.messages()
                                    .add(UserMessage.from(nonBlankOr(
                                            d.message(), "Continue working toward the goal; do not stop yet.")));
                        }
                    }
                    continue;
                }

                // Tool calls present — dispatch them all and loop; the model
                // decides it's done by NOT calling a tool on a later turn.
                in.messages().add(reply);
                for (ToolExecutionRequest call : reply.toolExecutionRequests()) {
                    String result = invokeOne(in.tools(), call, process.getId());
                    if (result != null) toolDataChars += result.length();
                    in.messages().add(ToolExecutionResultMessage.from(call, result));
                }
            }

            // Budget exhausted — the nature's exhausted policy decides.
            narrate(ctx, process, "budget exhausted after " + consumed + " iterations — asking the loop policy");
            LoopState st = state(
                    process, ctx, userGoal, consumed, in, bestFreeText, toolDataChars, corrections, stopCandidates);
            ExhaustionDecision d = onExhausted(st);
            narrate(
                    ctx,
                    process,
                    "exhausted policy: "
                            + switch (d.kind()) {
                                case EXTEND -> "extend";
                                case SYNTHESIZE -> d.hardFailure() ? "synthesize (hard failure)" : "synthesize";
                                case HARD_ERROR -> "hard error";
                            }
                            + nonBlankOr(d.reason() == null ? "" : " — " + d.reason(), ""));
            if (d.kind() == ExhaustionDecision.Kind.EXTEND && wallclockOk(turnStart)) {
                in.messages().add(UserMessage.from(nudgeText(d)));
                stats.extensions++;
                continue;
            }
            if (d.kind() == ExhaustionDecision.Kind.EXTEND) {
                // Wallclock net tripped: stop extending, synthesize what we
                // have — the turn must not outlive the net.
                log.warn(
                        "Nutrimat[{}] id='{}' wallclock net reached — refusing further extensions",
                        natureId(),
                        process.getId());
                return TurnOutcome.recovered(
                        bestFreeText.isBlank()
                                ? "The run exceeded its " + TURN_WALLCLOCK_MINUTES + "-minute wallclock budget."
                                : bestFreeText);
            }
            // SYNTHESIZE — hardFailure marks whether this is a graceful
            // synthesis (mate's judge: normal terminal) or a fallback after a
            // failure (janx: Ford's recovered outcome, worker closes INCOMPLETE).
            return toOutcome(d, process, bestFreeText);
        }
    }

    private static boolean wallclockOk(Instant turnStart) {
        return Duration.between(turnStart, Instant.now()).toMinutes() < TURN_WALLCLOCK_MINUTES;
    }

    private String nudgeText(ExhaustionDecision d) {
        return nonBlankOr(d.nudge(), "Continue working toward the goal — you have a fresh budget.");
    }

    /** Maps an exhaustion/failure decision onto the turn outcome. */
    private TurnOutcome toOutcome(ExhaustionDecision d, ThinkProcessDocument process, String bestFreeText) {
        return switch (d.kind()) {
            case HARD_ERROR -> TurnOutcome.failed(nonBlankOr(d.text(), "The run failed without an error message."));
            case EXTEND ->
                // Only reached when the wallclock net refused the extension.
                TurnOutcome.recovered(nonBlankOr(bestFreeText, "The run exceeded its wallclock budget."));
            case SYNTHESIZE ->
                d.hardFailure()
                        ? TurnOutcome.recovered(nonBlankOr(d.text(), ""))
                        : TurnOutcome.terminal(nonBlankOr(d.text(), ""), process.getParentProcessId() == null);
        };
    }

    private LoopState state(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            String userGoal,
            int consumed,
            LoopInputs in,
            String bestFreeText,
            int toolDataChars,
            int corrections,
            int stopCandidates) {
        return new LoopState(
                process,
                ctx,
                userGoal,
                consumed,
                in.maxIterations(),
                bestFreeText,
                toolDataChars,
                corrections,
                stopCandidates,
                in.validation());
    }

    // ──────────────────── Loop policy hooks ────────────────────

    /**
     * The model emitted an assistant message with no tool call. The default
     * is Ford's natural-stop semantics: the text IS the reply — with one
     * exception, the opt-in data-relay-gap correction (big tool data, thin
     * reply → correct once or twice, then accept).
     */
    protected StopDecision onNaturalStopCandidate(LoopState state, AiMessage reply) {
        String text = reply.text();
        int replyLen = text == null ? 0 : text.length();
        if (state.validationRequested()
                && state.corrections() < MAX_VALIDATION_CORRECTIONS
                && state.toolDataChars() >= TOOL_DATA_THRESHOLD
                && replyLen <= REPLY_BRIEF_THRESHOLD) {
            return StopDecision.correct(formatSafe(DATA_RELAY_CORRECTION_TEMPLATE, state.toolDataChars(), replyLen));
        }
        return StopDecision.accept();
    }

    /**
     * The iteration budget ran out. The default is Ford's recovery: carry the
     * best free text out as a hard-failure outcome (a worker then closes
     * {@code INCOMPLETE}). Alternatives: {@link ExhaustionDecision#extend}
     * with a fresh budget ({@code mate}), or throw
     * {@link NutrimatExhaustedException} for a visible hard error
     * ({@code redbull}).
     */
    protected ExhaustionDecision onExhausted(LoopState state) {
        String text = state.bestFreeText().isBlank()
                ? "The run exceeded its hard limit of " + state.maxIterations()
                        + " processing steps (maxIterations) without producing an answer."
                : state.bestFreeText();
        return ExhaustionDecision.synthesize(text, true);
    }

    /**
     * The provider call collapsed mid-loop (stream failure, retry budget
     * exhausted). The default preserves the work already done — same
     * recovery as the exhausted default. A nature that wants the failure to
     * surface verbatim throws {@link NutrimatExhaustedException} here too.
     */
    /**
     * Whether a hard-failure turn (exhausted / collapse) ends the process's
     * work for good on a <b>primary</b>: background events that arrive
     * afterwards (exec_finished & friends) are discarded instead of spinning
     * another loop, and only an explicit user message — e.g. "continue" —
     * starts the next loop run (which then runs until it exhausts again).
     *
     * <p>Default {@code false} (Ford-like: a primary hard failure parks
     * BLOCKED and every pending message wakes it). {@code redbull} overrides
     * to {@code true} — its "Hard stop by design" promise must survive the
     * wake-up mechanics. {@code mate} keeps the default: its exhausted path
     * is judge-mediated (extend or synthesize), not a hard stop.
     */
    protected boolean exhaustedStopsUntilUserInput() {
        return false;
    }

    /**
     * Whether the process is parked in the continue-gate: the last
     * hard-failure turn set {@code nutrimatState.awaitingUserContinue}.
     */
    static boolean awaitingUserContinue(ThinkProcessDocument process) {
        return process.getEngineParams() != null
                && process.getEngineParams().get("nutrimatState") instanceof Map<?, ?> state
                && Boolean.TRUE.equals(state.get("awaitingUserContinue"));
    }

    protected ExhaustionDecision onLlmFailure(LoopState state, RuntimeException error) {
        if (!state.bestFreeText().isBlank()) {
            log.warn(
                    "Nutrimat[{}] id='{}' tool-loop LLM failure ({}) — recovering with best Free-Text seen ({} chars)",
                    natureId(),
                    state.process().getId(),
                    error.toString(),
                    state.bestFreeText().length());
            return ExhaustionDecision.synthesize(state.bestFreeText(), true);
        }
        return ExhaustionDecision.hardError(
                "The LLM call failed and no partial work is available: " + error.getMessage());
    }

    // ──────────────────── Loop narration ────────────────────

    /** Narration knob: nothing, round counters only, or rounds + decisions. */
    private static final String NARRATION_OFF = "off";

    private static final String NARRATION_ROUNDS = "rounds";

    /**
     * Emits one dimmed interim note ({@code KIND_INTERIM}) on the user-
     * progress channel — visible in the chat transcript, never part of the
     * LLM context, never routed to the parent's inbox (see
     * {@code ProgressEmitter.emitInterimReply}). Recipe knob
     * {@code params.loopNarration}: {@code all} (default — rounds and every
     * loop decision), {@code rounds} (round counters only), {@code off}.
     */
    private void narrate(ThinkEngineContext ctx, ThinkProcessDocument process, String text) {
        if (text == null || text.isBlank()) return;
        String mode = paramString(process, "loopNarration", "all").toLowerCase(java.util.Locale.ROOT);
        if (NARRATION_OFF.equals(mode)) return;
        // rounds-mode: only the round counters pass, loop decisions are suppressed
        if (NARRATION_ROUNDS.equals(mode) && !text.startsWith("round ")) return;
        // Persist as an interim-kind assistant note (audit/scrollback) — the
        // interim marker filters it out of every LLM-replay / compaction /
        // Prak / RAG path, so the narration never becomes model context.
        // Then live-emit on the user-progress channel (dimmed in the UI).
        // Same shape as Frankie's between-batch narration.
        Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put(ChatMessageDocument.META_KIND, ChatMessageDocument.KIND_INTERIM);
        ctx.chatMessageService()
                .append(ChatMessageDocument.builder()
                        .tenantId(process.getTenantId())
                        .sessionId(process.getSessionId())
                        .thinkProcessId(process.getId())
                        .role(ChatRole.ASSISTANT)
                        .content("[" + natureId() + "] " + text)
                        .meta(meta)
                        .build());
        if (!process.isHiddenFromUi()) {
            ctx.emitInterimReply("[" + natureId() + "] " + text, null);
        }
    }

    // ──────────────────── Loop statistics ────────────────────

    /** Mutable per-turn loop statistics — filled by the kernel, persisted by the shell. */
    protected static final class LoopStats {
        public int iterationsConsumed;
        public int stopCandidates;
        public int extensions;
    }

    /**
     * Persists the turn's loop statistics under {@code engineParams.nutrimatState}
     * so {@code //nutrimat status} can show what the last turn actually did.
     * Never fails the turn — this is observability, not business logic.
     */
    private void persistNutrimatState(ThinkProcessDocument process, LoopStats stats, TurnOutcome outcome) {
        try {
            Map<String, Object> params = new java.util.LinkedHashMap<>();
            if (process.getEngineParams() != null) {
                params.putAll(process.getEngineParams());
            }
            int turns = 1;
            if (params.get("nutrimatState") instanceof Map<?, ?> prev && prev.get("turns") instanceof Number n) {
                turns = n.intValue() + 1;
            }
            String lastOutcome = "terminal";
            if (outcome.recovered()) {
                lastOutcome = "hardFailure";
            } else if (outcome.interrupted()) {
                lastOutcome = "interrupted";
            }
            Map<String, Object> state = new java.util.LinkedHashMap<>();
            state.put("nature", natureId());
            state.put("lastTurnAt", Instant.now().toString());
            state.put("lastOutcome", lastOutcome);
            state.put("iterationsConsumed", stats.iterationsConsumed);
            state.put("stopCandidates", stats.stopCandidates);
            state.put("extensions", stats.extensions);
            state.put("turns", turns);
            // redbull's continue-gate: a hard-failure turn on a primary parks the
            // process "awaiting user input" — background events are discarded
            // until an explicit user message starts the next loop run.
            state.put(
                    "awaitingUserContinue",
                    exhaustedStopsUntilUserInput() && process.getParentProcessId() == null && outcome.recovered());
            params.put("nutrimatState", state);
            process.setEngineParams(params);
            thinkProcessService.replaceEngineParams(process.getId(), params);
        } catch (RuntimeException e) {
            log.debug("Nutrimat[{}] id='{}' state persist failed: {}", natureId(), process.getId(), e.toString());
        }
    }
    // ──────────────────── Loop vocabulary ────────────────────

    /** Everything the loop mechanics need — fixed per turn. */
    public record LoopInputs(
            AiChat aiChat,
            List<ToolSpecification> toolSpecs,
            ContextToolsApi tools,
            List<ChatMessage> messages,
            int maxIterations,
            boolean validation,
            String modelAlias,
            String userGoal) {}

    /**
     * Immutable view of the loop at a decision point. Hooks read it and
     * return a decision — they never mutate the message list directly; the
     * kernel applies the decision.
     */
    public record LoopState(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            String userGoal,
            int iterationsConsumed,
            int maxIterations,
            String bestFreeText,
            int toolDataChars,
            int corrections,
            /** How many natural-stop candidates this turn has seen. */
            int stopCandidates,
            /** Whether the recipe opted into the data-relay validation check. */
            boolean validationRequested) {}

    /** Decision at a natural-stop candidate. */
    public record StopDecision(Kind kind, @Nullable String message) {
        public enum Kind {
            /** The text is the reply; the turn ends. */
            ACCEPT,
            /** Push a system correction and keep looping (Ford validation). */
            CORRECT,
            /** Push a nudge and keep looping ({@code salitos}: "not done yet"). */
            CONTINUE
        }

        public static StopDecision accept() {
            return new StopDecision(Kind.ACCEPT, null);
        }

        public static StopDecision correct(String systemMessage) {
            return new StopDecision(Kind.CORRECT, systemMessage);
        }

        public static StopDecision continueLoop(String nudge) {
            return new StopDecision(Kind.CONTINUE, nudge);
        }
    }

    /** Decision when a budget ran out or the provider call collapsed. */
    public record ExhaustionDecision(
            Kind kind,
            @Nullable String text,
            @Nullable String nudge,
            @Nullable String reason,
            boolean hardFailure) {
        public enum Kind {
            /** Grant a fresh budget and keep looping ({@code mate}'s judge). */
            EXTEND,
            /** End the turn with the given text. */
            SYNTHESIZE,
            /** End the turn as a visible failure with the given text. */
            HARD_ERROR
        }

        public static ExhaustionDecision extend(String nudge, String reason) {
            return new ExhaustionDecision(Kind.EXTEND, null, nudge, reason, true);
        }

        /** {@code hardFailure=true}: worker closes INCOMPLETE (Ford recovery). */
        public static ExhaustionDecision synthesize(String text, boolean hardFailure) {
            return new ExhaustionDecision(Kind.SYNTHESIZE, text, null, "synthesize", hardFailure);
        }

        public static ExhaustionDecision hardError(String message) {
            return new ExhaustionDecision(Kind.HARD_ERROR, message, null, "hard-error", true);
        }
    }

    /** Mid-loop interrupt kinds. */
    protected enum InterruptKind {
        NONE,
        /** Bail, leave the status as-is. */
        BAIL,
        /** Out-of-band halt flag — clear it and park PAUSED. */
        FORCE_PAUSE
    }

    private InterruptKind checkInterrupt(ThinkProcessDocument process) {
        ThinkProcessStatus liveStatus = thinkProcessService
                .findById(process.getId())
                .map(ThinkProcessDocument::getStatus)
                .orElse(process.getStatus());
        if (liveStatus == ThinkProcessStatus.SUSPENDED
                || liveStatus == ThinkProcessStatus.PAUSED
                || liveStatus == ThinkProcessStatus.CLOSED) {
            log.info(
                    "Nutrimat[{}] id='{}' tool-loop interrupt (status={}) — exiting",
                    natureId(),
                    process.getId(),
                    liveStatus);
            return InterruptKind.BAIL;
        }
        if (thinkProcessService.isHaltRequested(process.getId())) {
            log.info("Nutrimat[{}] id='{}' tool-loop halt requested — exiting (PAUSED)", natureId(), process.getId());
            thinkProcessService.clearHalt(process.getId());
            return InterruptKind.FORCE_PAUSE;
        }
        return InterruptKind.NONE;
    }

    // ──────────────────── Turn outcome ────────────────────

    /**
     * Outcome of one full tool-loop turn — what the shell needs to decide on
     * the persistent assistant message and the next process status.
     */
    protected record TurnOutcome(
            String finalText,
            boolean awaitingUserInput,
            /**
             * {@code true} on a hard-failure path (budget exhausted / LLM
             * collapse / exhausted exception). A worker with a parent closes
             * terminal {@code INCOMPLETE} so the delegation pointer releases.
             */
            boolean recovered,
            /** {@code true} when a mid-loop interrupt bailed the loop. */
            boolean interrupted,
            /** Halt-flag interrupt → park PAUSED; status-flip → leave as-is. */
            boolean interruptForcePause,
            /**
             * {@code true} when {@code finalText} is partial-not-answer
             * material that must be framed as such toward a parent. Error
             * outcomes state their own failure and are never wrapped.
             */
            boolean wrapPartial) {

        static TurnOutcome terminal(String text, boolean awaiting) {
            return new TurnOutcome(text, awaiting, false, false, false, false);
        }

        /** Ford-style recovery: partial work carried out of a failed loop. */
        static TurnOutcome recovered(String text) {
            return new TurnOutcome(text, true, true, false, false, true);
        }

        /** Visible error outcome — the text IS the failure report. */
        static TurnOutcome failed(String text) {
            return new TurnOutcome(text, true, true, false, false, false);
        }

        static TurnOutcome interrupted(boolean forcePause) {
            return new TurnOutcome("", false, false, true, forcePause, false);
        }
    }

    // ──────────────────── Streaming + tool mechanics ────────────────────

    /** Reply message + its text, so callers don't call {@code text()} twice. */
    private record StreamResult(AiMessage message, String text) {}

    /**
     * Runs a single streaming request and returns the complete assistant
     * message along with the accumulated text. Text partials are chunk-batched
     * and published as {@link MessageType#CHAT_MESSAGE_STREAM_CHUNK}.
     */
    private StreamResult streamOneIteration(
            AiChat aiChat,
            ChatRequest request,
            ThinkEngineContext ctx,
            ThinkProcessDocument process,
            String modelAlias) {
        CompletableFuture<ChatResponse> done = new CompletableFuture<>();
        ClientEventPublisher events = ctx.events();
        String sessionId = process.getSessionId();
        long startMs = System.currentTimeMillis();

        ChunkBatcher batcher = new ChunkBatcher(
                streamingProperties.getChunkCharThreshold(), streamingProperties.getChunkFlushMs(), chunk -> {
                    ChatMessageChunkData data = ChatMessageChunkData.builder()
                            .thinkProcessId(process.getId())
                            .processName(process.getName())
                            .role(ChatRole.ASSISTANT)
                            .chunk(chunk)
                            .build();
                    events.publish(sessionId, MessageType.CHAT_MESSAGE_STREAM_CHUNK, data);
                });

        aiChat.streamingChatModel().chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {
                if (partial == null || partial.isEmpty()) return;
                try {
                    batcher.accept(partial);
                } catch (RuntimeException e) {
                    log.warn("Nutrimat chunk-publish threw: {}", e.toString());
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse complete) {
                batcher.flush();
                done.complete(complete);
            }

            @Override
            public void onError(Throwable error) {
                batcher.flush();
                done.completeExceptionally(error);
            }
        });

        try {
            // Bound the wait — a provider stream that never fires its
            // callbacks would otherwise block the lane virtual-thread forever.
            ChatResponse response = done.get(STREAM_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            llmCallTracker.record(process, request, response, System.currentTimeMillis() - startMs, modelAlias);
            AiMessage reply = response.aiMessage();
            return new StreamResult(reply, reply.text() == null ? "" : reply.text());
        } catch (TimeoutException e) {
            done.cancel(true);
            throw new AiChatException(name() + " streaming timed out after " + STREAM_TIMEOUT_MINUTES + "m", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new AiChatException(name() + " streaming failed: " + cause.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiChatException(name() + " streaming interrupted", e);
        }
    }

    /**
     * Dispatches one tool call and returns the JSON-encoded result (or a
     * readable error string) for the LLM. All failures are stringified rather
     * than thrown — the model should see them and retry or give up gracefully.
     */
    private String invokeOne(ContextToolsApi tools, ToolExecutionRequest call, String processId) {
        Map<String, Object> params;
        try {
            params = parseArgs(call.arguments());
        } catch (RuntimeException e) {
            log.warn(
                    "Nutrimat[{}] id='{}' tool='{}' bad arguments: {}",
                    natureId(),
                    processId,
                    call.name(),
                    e.getMessage());
            return errorJson("Invalid tool arguments: " + e.getMessage());
        }
        try {
            Map<String, Object> result = tools.invoke(call.name(), params);
            return objectMapper.writeValueAsString(result);
        } catch (ToolException e) {
            log.info(
                    "Nutrimat[{}] id='{}' tool='{}' returned error: {}",
                    natureId(),
                    processId,
                    call.name(),
                    e.getMessage());
            return errorJson(e);
        } catch (RuntimeException e) {
            log.warn(
                    "Nutrimat[{}] id='{}' tool='{}' unexpected failure: {}",
                    natureId(),
                    processId,
                    call.name(),
                    e.toString());
            return errorJson("Tool failed: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArgs(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(raw, Map.class);
    }

    /** Renders a tool failure for the model in the shared, unmistakable shape. */
    private String errorJson(String message) {
        return ToolErrorPayload.json(objectMapper, message);
    }

    private String errorJson(ToolException e) {
        return ToolErrorPayload.json(objectMapper, e);
    }

    // ──────────────────── Prompt assembly ────────────────────

    private static ChatMessage toLangchain(ChatMessageDocument msg) {
        return de.mhus.vance.brain.chat.ChatHistoryRenderer.toLangchain(msg);
    }

    /**
     * Builds the prompt-message list for one turn: base system prompt,
     * optional skill-section, pinned compaction summary (if any), then active
     * chat history. Re-callable so the shell can rebuild after a mid-turn
     * compaction.
     */
    private List<ChatMessage> buildPromptMessages(
            ThinkProcessDocument process,
            ChatMessageService chatLog,
            List<SteerMessage> inboxExtras,
            ModelInfo modelInfo,
            ModelSize tier,
            List<ResolvedSkill> activeSkills,
            ContextToolsApi tools) {
        List<ChatMessage> messages = new ArrayList<>();
        PromptContextBuilder ctxBuilder =
                PromptContextBuilder.forProcess(process, modelInfo).tier(tier).engine(name());
        clientTurnContextResolver.resolve(process, inboxExtras).applyTo(ctxBuilder);
        ctxBuilder
                .withRootDirTypes(workspaceService.getRootDirTypes(process.getTenantId(), process.getProjectId()))
                .withAvailableTools(tools.primary());
        String base = composer.compose(process, engineDefaultPrompt(process), ctxBuilder);
        String memoryBlock = memoryContextLoader.composeBlock(process);
        if (memoryBlock != null && !memoryBlock.isBlank()) {
            base = base + "\n\n" + memoryBlock;
        }
        messages.add(SystemMessage.from(base));
        // Pack-level tool usage notes — fires only when a reachable tool's
        // ServerToolConfig.promptHint is non-empty.
        List<String> hints = tools.activePromptHints();
        if (!hints.isEmpty()) {
            StringBuilder hb = new StringBuilder("## Tool usage notes\n\n");
            for (int i = 0; i < hints.size(); i++) {
                if (i > 0) hb.append("\n\n");
                hb.append(hints.get(i));
            }
            messages.add(SystemMessage.from(hb.toString()));
        }
        String skillSection =
                skillPromptComposer.compose(activeSkills, ctxBuilder.build(), SkillTurnSupport.rawArgsByName(process));
        if (skillSection != null && !skillSection.isBlank()) {
            messages.add(SystemMessage.from(skillSection));
        }
        // Compaction summaries first — plain SystemMessages, so the cache
        // marker stays on the last static block (prompt-caching.md §5a).
        for (MemoryDocument m : memoryService.activeByProcessAndKind(
                process.getTenantId(), process.getId(), MemoryKind.ARCHIVED_CHAT)) {
            messages.add(SystemMessage.from("[Conversation summary from earlier turns]\n" + m.getContent()));
        }
        // Current-date block (recipe-param promptDateGranularity). DYNAMIC.
        promptDateContextResolver.appendDynamicMessage(messages, process, modelInfo == null ? null : modelInfo.size());
        // Client environment (os/shell/cwd/sandbox). DYNAMIC, no-op without
        // a bound CLIENT connection.
        promptDateContextResolver.appendClientEnvMessage(messages, process);
        // Scratchpad slot inventory. DYNAMIC, no-op when nothing was noted.
        scratchpadPromptContributor.appendDynamicMessage(messages, process);
        for (ChatMessageDocument msg : historyStrengthFilter.filter(
                chatLog.activeHistory(process.getTenantId(), process.getSessionId(), process.getId()))) {
            messages.add(toLangchain(msg));
        }
        // Non-UserChatInput inbox items wrapped in the <process-event> /
        // <tool-result> XML markers, with the reply-attribution attributes.
        if (inboxExtras != null) {
            for (SteerMessage m : inboxExtras) {
                String wrapped = renderForLlm(m);
                if (wrapped != null) {
                    messages.add(UserMessage.from(wrapped));
                }
            }
        }
        return messages;
    }

    /**
     * Wraps a non-UserChatInput inbox item in the XML marker the LLM is
     * trained on. Returns {@code null} for items that have no separate
     * rendering (UserChatInput is already in chat history).
     */
    private @Nullable String renderForLlm(SteerMessage m) {
        if (m instanceof SteerMessage.UserChatInput) return null;
        if (m instanceof SteerMessage.ProcessEvent pe) {
            StringBuilder sb = new StringBuilder();
            sb.append("<process-event sourceProcessId=\"")
                    .append(escapeAttr(pe.sourceProcessId()))
                    .append("\"");
            String sourceName = thinkProcessService
                    .findById(pe.sourceProcessId())
                    .map(ThinkProcessDocument::getName)
                    .orElse(null);
            if (sourceName != null && !sourceName.isBlank()) {
                sb.append(" sourceProcessName=\"")
                        .append(escapeAttr(sourceName))
                        .append("\"");
            }
            if (pe.eventId() != null && !pe.eventId().isBlank()) {
                sb.append(" eventId=\"").append(escapeAttr(pe.eventId())).append("\"");
            }
            if (pe.inResponseToAt() != null) {
                sb.append(" respondingToTurnAt=\"")
                        .append(escapeAttr(pe.inResponseToAt().toString()))
                        .append("\"");
            }
            sb.append(" type=\"")
                    .append(pe.type().name().toLowerCase(java.util.Locale.ROOT))
                    .append("\">");
            if (pe.humanSummary() != null) {
                sb.append(escapeText(pe.humanSummary()));
            }
            sb.append("</process-event>");
            return sb.toString();
        }
        if (m instanceof SteerMessage.ToolResult tr) {
            StringBuilder sb = new StringBuilder();
            sb.append("<tool-result toolCallId=\"")
                    .append(escapeAttr(tr.toolCallId()))
                    .append("\" toolName=\"")
                    .append(escapeAttr(tr.toolName()))
                    .append("\" status=\"")
                    .append(tr.status().name().toLowerCase(java.util.Locale.ROOT))
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
            return "<external-command command=\"" + escapeAttr(ec.command()) + "\">"
                    + escapeText(ec.params() == null ? "" : ec.params().toString()) + "</external-command>";
        }
        return null;
    }

    private static String escapeAttr(@Nullable String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }

    private static String escapeText(@Nullable String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;");
    }

    /**
     * Resolves the engine-default prompt template for the current turn through
     * the document cascade. The recipe param {@code promptDocument} overrides
     * the shared base path. The returned text is a Pebble template;
     * {@link SystemPromptComposer} renders it with the per-turn context.
     */
    private String engineDefaultPrompt(ThinkProcessDocument process) {
        String basePath = paramString(process, "promptDocument", DEFAULT_PROMPT_PATH);
        return enginePromptResolver.resolve(process, basePath, SYSTEM_PROMPT);
    }

    // ──────────────────── Skills ────────────────────

    private List<ResolvedSkill> resolveActiveSkills(ThinkProcessDocument process) {
        List<ActiveSkillRefEmbedded> active = process.getActiveSkills();
        if (active == null || active.isEmpty()) {
            return List.of();
        }
        SkillScopeContext scope = scopeFor(process);
        List<ResolvedSkill> out = new ArrayList<>(active.size());
        for (ActiveSkillRefEmbedded ref : active) {
            try {
                skillResolver
                        .resolve(scope, ref.getName())
                        .ifPresentOrElse(
                                out::add,
                                () -> log.warn(
                                        "Nutrimat[{}] id='{}' active skill '{}' no longer resolves — skipping",
                                        natureId(),
                                        process.getId(),
                                        ref.getName()));
            } catch (UnknownSkillException e) {
                log.warn(
                        "Nutrimat[{}] id='{}' active skill '{}' unknown — skipping",
                        natureId(),
                        process.getId(),
                        ref.getName());
            }
        }
        return out;
    }

    private SkillScopeContext scopeFor(ThinkProcessDocument process) {
        SessionDocument session =
                sessionService.findBySessionId(process.getSessionId()).orElse(null);
        String userId = session != null && !session.getUserId().isBlank() ? session.getUserId() : null;
        String projectId = session != null && !session.getProjectId().isBlank() ? session.getProjectId() : null;
        return SkillScopeContext.of(process.getTenantId(), userId, projectId);
    }

    private void dropOneShotSkills(ThinkProcessDocument process) {
        List<ActiveSkillRefEmbedded> active = process.getActiveSkills();
        if (active == null || active.isEmpty()) return;
        boolean anyOneShot = active.stream().anyMatch(ActiveSkillRefEmbedded::isOneShot);
        if (!anyOneShot) return;
        List<ActiveSkillRefEmbedded> kept = new ArrayList<>(active.size());
        for (ActiveSkillRefEmbedded ref : active) {
            if (!ref.isOneShot()) {
                kept.add(ref);
            }
        }
        process.setActiveSkills(kept);
        thinkProcessService.replaceActiveSkills(process.getId(), kept);
    }

    // ──────────────────── Helpers ────────────────────

    /**
     * Picks the timestamp of the most recent {@code UserChatInput} in the
     * inbox — used as {@code inResponseToAt} attribution on the emitted REPLY
     * so a parent can tell a fresh reply from a stale one when multiple
     * delegations interleave. {@code null} when the inbox carries no user
     * input.
     */
    private static @Nullable Instant lastUserInputAt(List<SteerMessage> inbox) {
        Instant best = null;
        for (SteerMessage m : inbox) {
            if (m instanceof SteerMessage.UserChatInput uci) {
                Instant at = uci.at();
                if (at != null && (best == null || at.isAfter(best))) {
                    best = at;
                }
            }
        }
        return best;
    }

    private static @Nullable Object param(ThinkProcessDocument process, String key) {
        // Runtime overlay (engineParamOverrides) wins over the spawn-static
        // recipe params — the //nutrimat set writes live there.
        Map<String, Object> overrides = process.getEngineParamOverrides();
        if (overrides != null && overrides.containsKey(key)) {
            return overrides.get(key);
        }
        Map<String, Object> p = process.getEngineParams();
        return p == null ? null : p.get(key);
    }

    protected static @Nullable String paramString(ThinkProcessDocument process, String key, @Nullable String fallback) {
        Object v = param(process, key);
        return v instanceof String s && !s.isBlank() ? s : fallback;
    }

    private static String nonBlankOr(@Nullable String candidate, String fallback) {
        return candidate != null && !candidate.isBlank() ? candidate : fallback;
    }

    /**
     * {@link String#format} that survives recipe-supplied templates with the
     * wrong placeholder count. A misconfigured override shouldn't crash the
     * turn; we log and fall back to a literal concat instead.
     */
    private static String formatSafe(String template, Object... args) {
        try {
            return String.format(template, args);
        } catch (RuntimeException e) {
            log.warn("Nutrimat: validator template format failed ({}), using template verbatim", e.toString());
            return template;
        }
    }

    protected static int paramInt(ThinkProcessDocument process, String key, int fallback) {
        Object v = param(process, key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    protected static boolean paramBool(ThinkProcessDocument process, String key, boolean fallback) {
        Object v = param(process, key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s.trim());
        return fallback;
    }
}
