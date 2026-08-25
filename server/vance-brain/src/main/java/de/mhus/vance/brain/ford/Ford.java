package de.mhus.vance.brain.ford;

import de.mhus.vance.api.chat.ChatMessageChunkData;
import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiChatException;
import de.mhus.vance.brain.ai.AiChatOptions;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.ai.ModelInfo;
import de.mhus.vance.brain.ai.ModelSize;
import de.mhus.vance.brain.events.ChunkBatcher;
import de.mhus.vance.brain.events.ClientEventPublisher;
import de.mhus.vance.brain.events.StreamingProperties;
import de.mhus.vance.brain.memory.CompactionResult;
import de.mhus.vance.brain.memory.MemoryCompactionService;
import de.mhus.vance.brain.skill.ResolvedSkill;
import de.mhus.vance.brain.skill.SkillPromptComposer;
import de.mhus.vance.brain.skill.SkillResolver;
import de.mhus.vance.brain.skill.SkillScopeContext;
import de.mhus.vance.brain.skill.UnknownSkillException;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.SystemPrompts;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.brain.tools.ToolErrorPayload;
import de.mhus.vance.toolpack.ToolException;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Ford — two heads, no brain.
 *
 * <p>Minimal chat engine with tool support. Keeps a conversation log
 * in {@link ChatMessageService}, replays it as LLM history on every
 * turn, calls the model in streaming mode with primary tools
 * advertised, batches text partials into chunks that the client sees
 * in near-real-time, loops over any {@code toolExecutionRequests} the
 * model emits, and persists the final assistant text as the
 * authoritative record.
 *
 * <p><b>Persistence policy:</b> only the user's input and the model's
 * final text are written to the chat log. Intermediate tool calls
 * and results live only in the per-turn LC4J message list — they
 * steer <em>this</em> turn, not the next one.
 *
 * <p><b>Streaming policy:</b> partial text tokens flow through a
 * {@link ChunkBatcher} into {@link MessageType#CHAT_MESSAGE_STREAM_CHUNK}
 * notifications. Tool-call arguments (streamed token-by-token by
 * some providers, not by others) are ignored — we read the final
 * {@link AiMessage#toolExecutionRequests} from {@code
 * onCompleteResponse}, which langchain4j assembles for us regardless
 * of provider.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class Ford implements ThinkEngine {

    public static final String NAME = "ford";
    public static final String VERSION = "0.3.0";

    /**
     * Bare-minimum fallback when no recipe override is in play —
     * normally never used because the bundled {@code ford} (and
     * specialised) recipes always supply the real prompt. Kept tiny
     * on purpose.
     */
    private static final String SYSTEM_PROMPT =
            "You are Ford, a generalist Vance worker. Use tools to "
                    + "gather concrete data; paste the relevant data into "
                    + "your reply. Don't invent content from training.";

    /**
     * Base cascade path for the Ford engine prompt. Loaded via
     * {@link de.mhus.vance.brain.thinkengine.EnginePromptResolver#resolveTiered};
     * SMALL models automatically pick up {@code prompts/ford-prompt-small.md}
     * when one exists, otherwise fall through to this base path. Tenants
     * override either variant by placing matching files in their
     * {@code _vance} project. Recipes can swap the paths via
     * {@code promptDocument} / {@code promptDocumentSmall} params.
     */
    private static final String DEFAULT_PROMPT_PATH = "_vance/prompts/ford-prompt.md";

    /**
     * Safety-net cap on tool-call iterations per turn. Ford ends a turn
     * by <em>natural stop</em> — an assistant message with no tool call —
     * so this is only a backstop against a broken model that never stops
     * calling tools, not a routine limit. A healthy turn natural-stops well
     * before it. Per-process override via {@code params.maxIterations}.
     */
    private static final int MAX_TOOL_ITERATIONS = 40;

    /**
     * Wall-clock safety-net for a single streaming LLM call. A hung provider
     * stream (never fires onCompleteResponse/onError) would otherwise block the
     * lane virtual-thread forever. Matches {@code StructuredActionEngine}.
     */
    private static final long STREAM_TIMEOUT_MINUTES = 20;

    // ──────────────────── Validation heuristic ────────────────────
    // Opt-in via params.validation == true. One remaining check:
    //   reply-too-brief-after-data-fetch — Ford-specific, catches
    //   "OK, I see the files." after a substantial tool result, and
    //   corrects it before accepting the natural stop.
    //
    // The turn ends by natural stop — an assistant message with no tool
    // call. There is no mandatory terminal tool; awaiting_user_input is
    // inferred from the role (worker → IDLE, primary → BLOCKED).

    /** Tool result size (chars) above which we expect the data to be
     *  reflected in the reply. */
    private static final int TOOL_DATA_THRESHOLD = 500;

    /** Reply size (chars) below which we suspect the data wasn't relayed. */
    private static final int REPLY_BRIEF_THRESHOLD = 200;

    private static final int MAX_VALIDATION_CORRECTIONS = 2;

    private static final String DATA_RELAY_CORRECTION_TEMPLATE =
            "VALIDATION CHECK: tools returned %d chars, your reply has "
                    + "%d — paste the actual data into the reply text.";

    private final ThinkProcessService thinkProcessService;
    private final ObjectMapper objectMapper;
    private final StreamingProperties streamingProperties;
    private final ModelCatalog modelCatalog;
    private final de.mhus.vance.brain.progress.LlmCallTracker llmCallTracker;
    private final de.mhus.vance.brain.memory.MemoryContextLoader memoryContextLoader;
    private final de.mhus.vance.brain.thinkengine.EnginePromptResolver enginePromptResolver;
    private final de.mhus.vance.brain.thinkengine.SystemPromptComposer composer;
    private final de.mhus.vance.brain.ai.EngineChatFactory engineChatFactory;
    private final MemoryService memoryService;
    private final MemoryCompactionService memoryCompactionService;
    private final SkillResolver skillResolver;
    private final SkillPromptComposer skillPromptComposer;
    private final de.mhus.vance.brain.skill.SkillTriggerMatcher skillTriggerMatcher;
    private final SessionService sessionService;
    private final de.mhus.vance.brain.context.PromptDateContextResolver promptDateContextResolver;
    private final de.mhus.vance.brain.prompt.ScratchpadPromptContributor scratchpadPromptContributor;
    private final de.mhus.vance.shared.workspace.WorkspaceService workspaceService;
    private final de.mhus.vance.brain.prak.HistoryStrengthFilter historyStrengthFilter;
    private final de.mhus.vance.brain.prompt.ClientTurnContextResolver clientTurnContextResolver;
    private final de.mhus.vance.brain.thinkengine.TurnContextHandlerRegistry turnContextHandlers;

    // ──────────────────── Metadata ────────────────────

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "Ford (Minimal Chat)";
    }

    @Override
    public String description() {
        return "Minimal walking-skeleton chat engine with tool support and streaming.";
    }

    @Override
    public String version() {
        return VERSION;
    }

    /**
     * Engine-default tool baseline. Until 2026-06-21 Ford returned
     * an empty set ("no restriction" — the LLM saw every primary
     * tool in the tenant). That worked, but the manifest was big
     * enough that Gemini-Flash-class models lost focus and called
     * variants of the same operation interchangeably (see live tests
     * with the work-target wrappers).
     *
     * <p>Ford now follows the Frankie pattern: a curated default
     * set plus the {@link de.mhus.vance.brain.tools.worktarget.BaseEngineTools#WORK_TARGET}
     * layer. Domain tools that a specific recipe needs
     * ({@code python_*}, {@code research_*}, mutating {@code doc_*})
     * are pulled in by that recipe via {@code allowedToolsAdd}.
     */
    private static final Set<String> ENGINE_DEFAULT_TOOLS;
    static {
        java.util.LinkedHashSet<String> base = new java.util.LinkedHashSet<>();
        // Discovery / introspection — Ford's bread-and-butter loop
        base.add("tool_list");
        base.add("tool_description");
        base.add("how_do_i");
        base.add("manual_read");
        base.add("manual_list");
        base.add("recipe_describe");
        base.add("tool_result_read");
        // Sub-worker spawn — Ford recipes occasionally delegate
        base.add("process_spawn");
        base.add("process_status");
        // User-facing signal
        base.add("vance_notify");
        // Basics
        base.add("current_time");
        base.add("whoami");
        // Free-form notes across turns. They have to be named here,
        // because computeAllowed is (engineDefault ∪ recipe.add) ∖
        // recipe.remove and anything missing from a non-empty engine
        // default is excluded outright, not merely undiscovered. Cost is
        // a name + hint line each rather than a schema each, because all
        // four declare deferred()==true — primary() alone would not do
        // it here, since classify() reads deferred() and never asks
        // primary() on a restricted engine.
        // Ford's processes are short-lived and slots are process-scoped
        // (planning/scratchpad-review.md §7.2 R2), so the notes rarely
        // outlive the task — the point here is that a Ford worker can
        // park an intermediate finding at all instead of losing it to
        // compaction mid-task.
        base.add("scratchpad_set");
        base.add("scratchpad_get");
        base.add("scratchpad_list");
        base.add("scratchpad_delete");
        // Read-side document operations — common across Ford recipes
        // (code-read, analyze, quick-lookup). Mutating doc_* / kit_*
        // / scratch-write paths stay opt-in per recipe.
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
        // Research — analyze / web-research / quick-lookup all need
        // these; pulling them into the default avoids per-recipe
        // duplication.
        base.add("web_fetch");
        base.add("web_search");
        base.add("research_search");
        base.add("research_investigate");
        base.add("research_rich");
        base.add("research_providers");
        base.add("memory_search");
        // Generic file/exec dispatch layer (BaseEngineTools.WORK_TARGET)
        // — 12 primary wrappers + 2 meta tools + 24 deferred backends.
        // Recipes pick the active target via params.workTarget and
        // can defer the backend names out of the LLM manifest with
        // allowedToolsDefer (see coding.yaml as the reference).
        base.addAll(de.mhus.vance.brain.tools.worktarget.BaseEngineTools.WORK_TARGET);
        ENGINE_DEFAULT_TOOLS = java.util.Collections.unmodifiableSet(base);
    }

    @Override
    public Set<String> allowedTools() {
        return ENGINE_DEFAULT_TOOLS;
    }

    // ──────────────────── Lifecycle ────────────────────

    @Override
    public void start(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.info("Ford.start tenant='{}' session='{}' id='{}'",
                process.getTenantId(), process.getSessionId(), process.getId());
        // No greeting on start. Workers spawned with steerContent
        // (the recipe-driven default) immediately drain that input —
        // a "Ford here. Ask me anything." message would just be
        // filler that surfaces in the audit trail before the real
        // work. Workers spawned without an initial steer (interactive
        // / manual debug) start with an empty chat-history, which is
        // fine — the user's first /process-steer drives the engine.
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
    }

    @Override
    public void resume(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.debug("Ford.resume id='{}'", process.getId());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
    }

    @Override
    public void suspend(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.debug("Ford.suspend id='{}'", process.getId());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.SUSPENDED);
    }

    @Override
    public void steer(ThinkProcessDocument process, ThinkEngineContext ctx, SteerMessage message) {
        // Single-message entry — wrap in a one-element inbox and route
        // through the same drain-aware path the default runTurn uses.
        runTurnFor(process, ctx, List.of(message));
    }

    /**
     * Override the default {@link ThinkEngine#runTurn} so we drain the
     * whole inbox once per pass and fold it into a single LLM round-trip
     * — same Auto-Wakeup loop as Arthur, only simpler. Without this
     * override Ford would call {@link #steer} per drained message,
     * which would burn an LLM call per ProcessEvent and prevent the
     * model from seeing UserChatInput + worker reply in the same turn.
     */
    @Override
    public void runTurn(ThinkProcessDocument process, ThinkEngineContext ctx) {
        while (true) {
            List<SteerMessage> drained = ctx.drainPending();
            if (drained.isEmpty()) return;
            runTurnFor(process, ctx, drained);
        }
    }

    @Override
    public void stop(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.info("Ford.stop id='{}'", process.getId());
        thinkProcessService.closeProcess(process.getId(), CloseReason.STOPPED);
    }

    // ──────────────────── One turn ────────────────────

    private TurnOutcome runTurnFor(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            List<SteerMessage> inbox) {

        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.RUNNING);
        // Default IDLE on any abnormal exit — matches legacy lifecycle.
        // Set to outcome.awaitingUserInput() inside the try when the
        // tool-loop returns cleanly.
        boolean awaitingUserInput = false;
        // True iff this turn exited via the maxIter / LLM-collapse
        // recovery path (i.e. {@code runToolLoop} returned a
        // {@code recovered} outcome). For sub-process workers this
        // triggers a terminal close — a worker that exhausted its budget
        // cannot make further progress on its own and leaving it BLOCKED
        // pins the parent's {@code activeDelegationWorkerId} to a dead-end.
        boolean recoveredFromMaxIter = false;
        // Set when the tool loop bailed on a mid-loop interrupt (ESC /
        // /pause): no answer is surfaced and the finally leaves the
        // interrupt status as-is (or parks PAUSED for the halt-flag path).
        boolean interrupted = false;
        boolean interruptForcePause = false;
        try {
            ChatMessageService chatLog = ctx.chatMessageService();
            // Persist UserChatInput entries to chat history so future
            // turns see them; collect the joined text for skill-trigger
            // matching. Non-UCI items (ProcessEvent, ToolResult, …) are
            // turn-local — they steer this turn but don't enter the
            // user-visible chat log. Same convention as Arthur.
            StringBuilder userTextForTriggers = new StringBuilder();
            List<SteerMessage> extras = new ArrayList<>();
            for (SteerMessage m : inbox) {
                if (m instanceof SteerMessage.UserChatInput uci
                        && uci.content() != null && !uci.content().isBlank()) {
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
            // Skill auto-trigger runs on the combined fresh user input —
            // ignores non-UCI items, mirroring previous behaviour.
            String userInput = userTextForTriggers.toString();
            if (!userInput.isBlank()) {
                skillTriggerMatcher.detectAndActivate(process, userInput);
            }

            // Build the chat with primary + ordered fallback chain plus
            // the standard resilience-notifier and (when tracing.llm is
            // on) LLM-trace persistence — see EngineChatFactory.
            de.mhus.vance.brain.ai.EngineChatFactory.EngineChatBundle chatBundle =
                    engineChatFactory.forProcess(process, ctx, NAME);
            AiChat aiChat = chatBundle.chat();
            AiChatConfig config = chatBundle.primaryConfig();

            List<ResolvedSkill> activeSkills = resolveActiveSkills(process);

            ContextToolsApi tools = ctx.tools()
                    .withAdditional(skillPromptComposer.mergedTools(activeSkills));
            List<ToolSpecification> toolSpecs = tools.primaryAsLc4j();
            ModelInfo modelInfo = modelCatalog.lookupOrDefault(
                    process.getTenantId(), process.getProjectId(),
                    config.providerInstance(), config.provider(), config.modelName());

            // params.modelSize: SMALL/LARGE force the prompt variant
            // independently of the catalog; AUTO/missing falls back
            // to the catalog's classification.
            ModelSize effectiveSize = ModelSize.parseOrAuto(
                    paramString(process, "modelSize", null), modelInfo.size());
            List<ChatMessage> messages = buildPromptMessages(
                    process, chatLog, extras, modelInfo, effectiveSize, activeSkills, tools);
            // Shared trigger: SOFT / HARD / EMERGENCY based on
            // estimated-tokens-vs-context-window thresholds in
            // vance.prak.*. Compacts via the strength-aware selector
            // when fired; no-op when under the soft threshold.
            CompactionResult compactResult =
                    memoryCompactionService.compactIfNeeded(process, config, messages, modelInfo);
            if (compactResult.compacted()) {
                log.info("Ford.turn id='{}' compaction ok: {} msgs → {} chars (memory='{}')",
                        process.getId(),
                        compactResult.messagesCompacted(),
                        compactResult.summaryChars(),
                        compactResult.memoryId());
                // Rebuild the prompt: the active-history shrunk and a
                // new ARCHIVED_CHAT memory pinned the summary at top.
                messages = buildPromptMessages(
                        process, chatLog, extras, modelInfo, effectiveSize, activeSkills, tools);
            }

            int maxIters = paramInt(process, "maxIterations", MAX_TOOL_ITERATIONS);
            boolean validation = paramBool(process, "validation", false);
            if (validation) {
                log.info("Ford.turn id='{}' validation=on maxIters={}",
                        process.getId(), maxIters);
            }
            String modelAlias = config.provider() + ":" + config.modelName();
            TurnOutcome outcome = runToolLoop(
                    aiChat, toolSpecs, tools, messages, ctx, process,
                    maxIters, validation, modelAlias);
            if (outcome.interrupted()) {
                // ESC / /pause bailed the loop: surface no answer, drop
                // buffered history tags, let the finally park the status.
                interrupted = true;
                interruptForcePause = outcome.interruptForcePause();
                ctx.historyTagSink().discard();
                log.info("Ford.turn id='{}' interrupted (forcePause={}) — parking, no answer surfaced",
                        process.getId(), interruptForcePause);
                return outcome;
            }
            if (outcome.recovered()) {
                recoveredFromMaxIter = true;
            }
            awaitingUserInput = outcome.awaitingUserInput();
            String finalText = outcome.finalText();

            // Budget-exhausted worker: the "best free text" is whatever the
            // model last said mid-task (often a dangling progress note like
            // "I'm now reading the docs"), which a parent orchestrator can't
            // tell apart from a real answer and would either silently WAIT on
            // or — worse — echo forward as its own "let me continue" preamble.
            //
            // For a parent watching this worker over the Working WS the
            // structured FAILED ProcessEvent is SUPPRESSED (live reply already
            // streams), so this reply text is the parent's ONLY signal about
            // the worker's fate — it must state the truth unambiguously.
            // "step budget" read as a soft, resumable shortfall; spell out
            // that this is a hard force-abort at the iteration cap, the worker
            // is closed, and the text below is partial-not-answer.
            if (recoveredFromMaxIter && process.getParentProcessId() != null) {
                finalText = "⚠️ TASK FAILED — this worker was force-stopped after "
                        + "hitting its hard limit of " + maxIters + " processing "
                        + "steps (maxIterations). It is now CLOSED and cannot be "
                        + "resumed. The task is UNFINISHED: the text below is "
                        + "PARTIAL progress only, NOT an answer — do not treat it "
                        + "as done, and do not assume the remaining steps ran. To "
                        + "carry the task further, start a fresh worker (tighter "
                        + "scope or a higher step limit).\n\nPartial progress:\n\n"
                        + finalText;
            }

            ChatMessageDocument saved = chatLog.append(ChatMessageDocument.builder()
                    .tenantId(process.getTenantId())
                    .sessionId(process.getSessionId())
                    .thinkProcessId(process.getId())
                    .role(ChatRole.ASSISTANT)
                    .content(finalText)
                    .build());
            // Flush buffered history tags onto the assistant turn.
            // Tool-dispatcher hook in ContextToolsApi has been emitting
            // TOOL_CALL/RESOURCE/FILE_EDIT markers into the per-turn sink
            // throughout runToolLoop; they land here.
            if (saved != null && saved.getId() != null) {
                ctx.historyTagSink().flushTo(saved.getId(), chatLog);
            }

            // Emit the worker's semantic reply on the explicit channel —
            // independent of the lane-status that follows in the finally
            // block. Parent's inbox gets a SteerMessage.Reply (when a
            // parent exists), the session client gets a PROCESS_PROGRESS
            // REPLY frame. See planning/process-engine-reply-channel.md.
            //
            // Reply is emitted on every Ford turn that produces an
            // ASSISTANT message, including awaiting=false (worker
            // closes itself with a final answer) — that's exactly the
            // case that today's mapStatus(IDLE)→null path swallows.
            if (finalText != null && !finalText.isBlank()) {
                Instant inResponseToAt = lastUserInputAt(inbox);
                ctx.emitReply(finalText, inResponseToAt, null);
            }

            String preview = finalText.length() > 120 ? finalText.substring(0, 120) + "…" : finalText;
            log.info("Ford.steer id='{}' awaiting={} -> '{}'",
                    process.getId(), awaitingUserInput, preview);
            return outcome;
        } finally {
            // Drain one-shot skills before the next turn — they only
            // ever apply to the turn that activated them.
            dropOneShotSkills(process);
            if (interrupted) {
                // ESC / /pause bailed the loop. Halt-flag path parks
                // PAUSED (next message auto-resumes); status-flip path
                // leaves the status the pause handler already set.
                if (interruptForcePause) {
                    thinkProcessService.updateStatus(
                            process.getId(), ThinkProcessStatus.PAUSED);
                }
            } else if (recoveredFromMaxIter && process.getParentProcessId() != null) {
                // Sub-process worker exhausted its iteration budget.
                // The best Free-Text reply has already been appended to
                // chat history and emitted on the REPLY channel above;
                // close terminally so the parent's delegation pointer
                // releases (ParentNotificationListener turns
                // CLOSED+DONE into a DONE ProcessEvent on the parent's
                // inbox). Without this the worker stays BLOCKED and
                // every subsequent user message auto-forwards into a
                // dead-end.
                log.info("Ford id='{}' worker hit maxIter — closing INCOMPLETE so parent '{}' releases delegation pointer and learns the task did not finish",
                        process.getId(), process.getParentProcessId());
                thinkProcessService.closeProcess(process.getId(), CloseReason.INCOMPLETE);
            } else {
                ThinkProcessStatus exitStatus = awaitingUserInput
                        ? ThinkProcessStatus.BLOCKED
                        : ThinkProcessStatus.IDLE;
                thinkProcessService.updateStatus(process.getId(), exitStatus);
            }
        }
    }

    /**
     * Picks the timestamp of the most recent {@code UserChatInput} in
     * the inbox — used as {@code inResponseToAt} attribution on the
     * emitted REPLY so the parent engine can tell a fresh reply from a
     * stale one when multiple delegations interleave (see
     * planning/arthur-process-event-attribution.md). Returns
     * {@code null} when the inbox carries no user input (turn was
     * triggered by a tool result or sibling event).
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

    /**
     * Resolves the process's persisted {@link ActiveSkillRefEmbedded}s
     * into ready-to-use {@link ResolvedSkill}s through the user/project/
     * tenant/bundled cascade. Skills that no longer resolve (e.g. a
     * user deleted their private skill mid-session) are skipped with a
     * warning rather than failing the turn.
     */
    private List<ResolvedSkill> resolveActiveSkills(ThinkProcessDocument process) {
        List<ActiveSkillRefEmbedded> active = process.getActiveSkills();
        if (active == null || active.isEmpty()) {
            return List.of();
        }
        SkillScopeContext scope = scopeFor(process);
        List<ResolvedSkill> out = new ArrayList<>(active.size());
        for (ActiveSkillRefEmbedded ref : active) {
            try {
                skillResolver.resolve(scope, ref.getName())
                        .ifPresentOrElse(out::add, () -> log.warn(
                                "Ford id='{}' active skill '{}' no longer resolves — skipping",
                                process.getId(), ref.getName()));
            } catch (UnknownSkillException e) {
                log.warn("Ford id='{}' active skill '{}' unknown — skipping",
                        process.getId(), ref.getName());
            }
        }
        return out;
    }

    private SkillScopeContext scopeFor(ThinkProcessDocument process) {
        SessionDocument session = sessionService.findBySessionId(process.getSessionId())
                .orElse(null);
        String userId = session != null && !session.getUserId().isBlank()
                ? session.getUserId() : null;
        String projectId = session != null && !session.getProjectId().isBlank()
                ? session.getProjectId() : null;
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

    /**
     * Outcome of one full tool-loop turn — what the engine layer needs
     * to decide on the persistent assistant message and the next
     * process status.
     *
     * <p>{@code finalText} is what gets persisted into the chat log
     * and shown to the user. {@code awaitingUserInput} drives the
     * post-turn status: {@code true} → BLOCKED (user must reply),
     * {@code false} → IDLE (engine is happy to auto-wake on the next
     * pending message — typically a worker's ProcessEvent).
     */
    private record TurnOutcome(
            String finalText,
            boolean awaitingUserInput,
            /**
             * {@code true} when the turn ended on the hard-failure path —
             * the iteration cap was exhausted or the LLM collapsed — rather
             * than a clean natural stop. Drives the terminal close for
             * sub-process workers (INCOMPLETE → FAILED ProcessEvent).
             */
            boolean recovered,
            /** {@code true} when a mid-loop interrupt (ESC / /pause) bailed the loop. */
            boolean interrupted,
            /** Halt-flag interrupt → engine parks PAUSED; status-flip → leave as-is. */
            boolean interruptForcePause) {

        /** Clean natural stop (or respond-less answer): the text is the reply. */
        static TurnOutcome terminal(String text, boolean awaiting) {
            return new TurnOutcome(text, awaiting, false, false, false);
        }

        /** Hard-failure recovery (maxIter exhausted / LLM collapse). */
        static TurnOutcome recovered(String text) {
            return new TurnOutcome(text, true, true, false, false);
        }

        /** Mid-loop interrupt — no answer surfaced. */
        static TurnOutcome interrupted(boolean forcePause) {
            return new TurnOutcome("", false, false, true, forcePause);
        }
    }

    /**
     * Tool-call loop in streaming mode. Each iteration drives the
     * {@link AiChat#streamingChatModel()} and funnels text partials
     * through a {@link ChunkBatcher} into the event publisher.
     *
     * <p>The turn ends by <em>natural stop</em>: as long as the model
     * emits tool calls, the loop dispatches them all and iterates; the
     * first iteration that returns an assistant message with <em>no</em>
     * tool call is the terminal — that text is the reply. There is no
     * mandatory terminal tool. {@code awaiting_user_input} is inferred
     * from the role: a worker (has a parent) goes IDLE, a primary goes
     * BLOCKED.
     *
     * <p>Backstops: a mid-loop ESC / {@code /pause} returns an
     * {@link TurnOutcome#interrupted}, the {@code maxIters} cap and an
     * LLM collapse return a {@link TurnOutcome#recovered} (hard-failure)
     * outcome carrying the best free-text seen.
     */
    private TurnOutcome runToolLoop(
            AiChat aiChat,
            List<ToolSpecification> toolSpecs,
            ContextToolsApi tools,
            List<ChatMessage> messages,
            ThinkEngineContext ctx,
            ThinkProcessDocument process,
            int maxIters,
            boolean validation,
            String modelAlias) {
        StringBuilder finalText = new StringBuilder();
        int corrections = 0;
        int toolDataChars = 0;
        // Best Free-Text seen so far across all iterations. Used as
        // last-resort `respond.message` when the LLM collapses (e.g.
        // Gemini "neither text nor function call" after validator
        // corrections) or maxIters is exhausted — preserves the work
        // the worker already did (web fetches, recipe synthesis, …)
        // instead of throwing the turn away and forcing the parent
        // engine to spawn a new worker from scratch.
        String bestFreeText = "";
        for (int iter = 0; iter < maxIters; iter++) {
            // Mid-loop interrupt — checked before the next LLM call so
            // ESC / /pause stops a running tool loop promptly (mirrors
            // FrankieEngine and the StructuredActionEngine action loop).
            // Status-flip → bail, leave the status as-is; out-of-band halt
            // flag → clear it and park PAUSED so the next message resumes.
            ThinkProcessStatus liveStatus = thinkProcessService.findById(process.getId())
                    .map(ThinkProcessDocument::getStatus).orElse(process.getStatus());
            if (liveStatus == ThinkProcessStatus.SUSPENDED
                    || liveStatus == ThinkProcessStatus.PAUSED
                    || liveStatus == ThinkProcessStatus.CLOSED) {
                log.info("Ford id='{}' tool-loop interrupt (status={}) — exiting",
                        process.getId(), liveStatus);
                return TurnOutcome.interrupted(false);
            }
            if (thinkProcessService.isHaltRequested(process.getId())) {
                log.info("Ford id='{}' tool-loop halt requested — exiting (PAUSED)",
                        process.getId());
                thinkProcessService.clearHalt(process.getId());
                return TurnOutcome.interrupted(true);
            }

            ChatRequest.Builder req = ChatRequest.builder()
                    .messages(turnContextHandlers.apply(messages, ctx, process));
            if (!toolSpecs.isEmpty()) {
                req.toolSpecifications(toolSpecs);
            }

            AiMessage reply;
            try {
                StreamResult streamed = streamOneIteration(
                        aiChat, req.build(), ctx, process, modelAlias);
                reply = streamed.message;
            } catch (RuntimeException e) {
                // LLM collapsed mid-loop (typically: Gemini "neither
                // text nor function call" after validator pings, or
                // Resilient-retry budget exhausted). Don't throw —
                // recover with the best Free-Text we already extracted
                // so the user still gets the recipe / answer the model
                // produced before it got confused.
                if (!bestFreeText.isEmpty()) {
                    log.warn(
                            "Ford id='{}' tool-loop LLM failure ({}) — recovering with best Free-Text seen ({} chars)",
                            process.getId(), e.toString(), bestFreeText.length());
                    return TurnOutcome.recovered(bestFreeText);
                }
                log.warn("Ford id='{}' tool-loop LLM failure with no recoverable text",
                        process.getId());
                throw e;
            }

            // Track the best Free-Text we've seen, regardless of
            // whether this iteration also has tool-calls. The recipe
            // / answer typically lives in the FIRST iteration where
            // the LLM tries to "give a final answer" without `respond`;
            // later validator-driven retries often produce shorter text.
            String replyText = reply.text();
            if (replyText != null && replyText.length() > bestFreeText.length()) {
                bestFreeText = replyText;
            }

            if (!reply.hasToolExecutionRequests()) {
                // Natural stop: the model emitted an assistant message
                // with no tool call — it has nothing more to do, so the
                // text IS the reply and the turn ends here (Frankie /
                // Claude-Code style; no mandatory `respond` wrapper).
                //
                // One exception — the validation-gated data-relay-gap: if
                // big tool data is in the conversation but the reply is
                // thin, the "stop" is premature; correct once and let the
                // model re-read the tool results before it stops.
                String text = reply.text();
                int replyLen = text == null ? 0 : text.length();
                if (validation && corrections < MAX_VALIDATION_CORRECTIONS
                        && toolDataChars >= TOOL_DATA_THRESHOLD
                        && replyLen <= REPLY_BRIEF_THRESHOLD) {
                    String template = nonBlankOr(
                            process.getDataRelayCorrectionOverride(),
                            DATA_RELAY_CORRECTION_TEMPLATE);
                    log.info(
                            "Ford id='{}' validation: data-relay-gap (toolData={}, reply={}), correcting ({}/{})",
                            process.getId(), toolDataChars, replyLen,
                            corrections + 1, MAX_VALIDATION_CORRECTIONS);
                    messages.add(reply);
                    messages.add(SystemMessage.from(
                            formatSafe(template, toolDataChars, replyLen)));
                    corrections++;
                    continue;
                }
                if (text != null) {
                    finalText.append(text);
                }
                if (validation && corrections > 0) {
                    log.info("Ford id='{}' validation: completed after {} correction(s)",
                            process.getId(), corrections);
                }
                // awaiting by role: a worker (has a parent) is done → IDLE
                // so the parent can steer again; a primary (no parent)
                // awaits the user's next message → BLOCKED.
                boolean awaiting = process.getParentProcessId() == null;
                return TurnOutcome.terminal(finalText.toString(), awaiting);
            }

            // Tool calls present — dispatch them all and loop; the model
            // decides it's done by NOT calling a tool on a later turn
            // (natural stop above). There is no terminal tool any more.
            messages.add(reply);
            for (ToolExecutionRequest call : reply.toolExecutionRequests()) {
                String result = invokeOne(tools, call, process.getId());
                if (result != null) toolDataChars += result.length();
                messages.add(ToolExecutionResultMessage.from(call, result));
            }
        }
        // maxIters exhausted — a genuine runaway at this cap (100).
        // Don't throw the work away: emit the best Free-Text as a
        // recovered (hard-failure) outcome; a worker then closes
        // INCOMPLETE so the parent learns the task did not finish.
        if (!bestFreeText.isEmpty()) {
            log.warn(
                    "Ford id='{}' exceeded {} tool iterations — recovering with best Free-Text seen ({} chars)",
                    process.getId(), maxIters, bestFreeText.length());
            return TurnOutcome.recovered(bestFreeText);
        }
        throw new AiChatException(
                "Ford exceeded " + maxIters
                        + " tool iterations — no recoverable text, aborting turn.");
    }

    /**
     * Runs a single streaming request and returns the complete
     * assistant message along with the accumulated text. Text
     * partials are chunk-batched and published as
     * {@link MessageType#CHAT_MESSAGE_STREAM_CHUNK}.
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
                streamingProperties.getChunkCharThreshold(),
                streamingProperties.getChunkFlushMs(),
                chunk -> {
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
                    log.warn("Ford chunk-publish threw: {}", e.toString());
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
            // Bound the wait (Ford implements ThinkEngine directly, so it is NOT
            // covered by StructuredActionEngine's stream-timeout safety-net). A
            // provider whose streaming callback never fires onCompleteResponse/
            // onError would otherwise block the lane virtual-thread forever — the
            // process stays RUNNING (never BLOCKED), so the BLOCKED watchdog never
            // recovers it and the per-process + per-project lane wedge. On timeout
            // throw AiChatException so runToolLoop's bestFreeText/format-correction
            // recovery engages, exactly as the structured engines do.
            ChatResponse response = done.get(STREAM_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            llmCallTracker.record(
                    process, request, response, System.currentTimeMillis() - startMs, modelAlias);
            AiMessage reply = response.aiMessage();
            return new StreamResult(reply, reply.text() == null ? "" : reply.text());
        } catch (TimeoutException e) {
            done.cancel(true);
            throw new AiChatException(
                    "Ford streaming timed out after " + STREAM_TIMEOUT_MINUTES + "m", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new AiChatException("Ford streaming failed: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiChatException("Ford streaming interrupted", e);
        }
    }

    /**
     * Dispatches one tool call and returns the JSON-encoded result
     * (or a readable error string) for the LLM. All failures are
     * stringified rather than thrown — the model should see them and
     * retry or give up gracefully, not crash the turn.
     */
    private String invokeOne(
            ContextToolsApi tools, ToolExecutionRequest call, String processId) {
        Map<String, Object> params;
        try {
            params = parseArgs(call.arguments());
        } catch (RuntimeException e) {
            log.warn("Ford id='{}' tool='{}' bad arguments: {}",
                    processId, call.name(), e.getMessage());
            return errorJson("Invalid tool arguments: " + e.getMessage());
        }
        try {
            Map<String, Object> result = tools.invoke(call.name(), params);
            return objectMapper.writeValueAsString(result);
        } catch (ToolException e) {
            log.info("Ford id='{}' tool='{}' returned error: {}",
                    processId, call.name(), e.getMessage());
            return errorJson(e);
        } catch (RuntimeException e) {
            log.warn("Ford id='{}' tool='{}' unexpected failure: {}",
                    processId, call.name(), e.toString());
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

    /**
     * Renders a tool failure for the model. Delegates to
     * {@link ToolErrorPayload} so every engine reports failures in the
     * same, unmistakable shape.
     */
    private String errorJson(String message) {
        return ToolErrorPayload.json(objectMapper, message);
    }

    /** Same, keeping the failing tool's troubleshooting hint. */
    private String errorJson(ToolException e) {
        return ToolErrorPayload.json(objectMapper, e);
    }

    // ──────────────────── Helpers ────────────────────

    /** Reply message + its text, so callers don't call {@code text()} twice. */
    private record StreamResult(AiMessage message, String text) {}

    private static ChatMessage toLangchain(ChatMessageDocument msg) {
        return de.mhus.vance.brain.chat.ChatHistoryRenderer.toLangchain(msg);
    }

    /**
     * Builds the prompt-message list for one turn: base system prompt,
     * optional skill-section, pinned compaction summary (if any), then
     * active chat history. Re-callable so {@code runTurn} can rebuild
     * after a mid-turn compaction.
     *
     * @param activeSkills skills resolved for this turn. The body of
     *        each skill is rendered through the same Pebble context the
     *        engine-default prompt and recipe {@code promptPrefix} use,
     *        so {@code {% if tier == "small" %}} and friends work in
     *        skill bodies too. Appended as a separate
     *        {@link SystemMessage} after the engine-default prompt.
     */
    private List<ChatMessage> buildPromptMessages(
            ThinkProcessDocument process, ChatMessageService chatLog,
            List<SteerMessage> inboxExtras,
            ModelInfo modelInfo, ModelSize tier, List<ResolvedSkill> activeSkills,
            ContextToolsApi tools) {
        List<ChatMessage> messages = new ArrayList<>();
        de.mhus.vance.brain.prompt.PromptContextBuilder ctxBuilder =
                de.mhus.vance.brain.prompt.PromptContextBuilder
                        .forProcess(process, modelInfo)
                        .tier(tier)
                        .engine(NAME);
        // Per-turn client context — cortex-mode fires only when a Cortex
        // client is currently bound to this session's chat WS (workers
        // delegated to from a Cortex session inherit the same answer);
        // see the {% if cortexMode %} block in ford-prompt.md. Ford's
        // template reads only the cortex half today, and setting the rest
        // costs nothing — see ClientTurnContextResolver.
        clientTurnContextResolver.resolve(process, inboxExtras).applyTo(ctxBuilder);
        ctxBuilder.withRootDirTypes(workspaceService.getRootDirTypes(
                        process.getTenantId(), process.getProjectId()))
                // This turn's manifest, so the template can gate
                // tool-specific text on the tool being callable.
                // Ford already has the classified surface as a
                // parameter — no second classify() needed here.
                .withAvailableTools(tools.primary());
        String base = composer.compose(process,
                engineDefaultPrompt(process), ctxBuilder);
        String memoryBlock = memoryContextLoader.composeBlock(process);
        if (memoryBlock != null && !memoryBlock.isBlank()) {
            base = base + "\n\n" + memoryBlock;
        }
        messages.add(SystemMessage.from(base));
        // Pack-level tool usage notes — see ContextToolsApi.activePromptHints.
        // Fires only when a reachable tool's ServerToolConfig.promptHint
        // is non-empty (Jira: "cloudId is auto-injected", etc.).
        java.util.List<String> hints = tools == null
                ? java.util.List.of() : tools.activePromptHints();
        if (!hints.isEmpty()) {
            StringBuilder hb = new StringBuilder("## Tool usage notes\n\n");
            for (int i = 0; i < hints.size(); i++) {
                if (i > 0) hb.append("\n\n");
                hb.append(hints.get(i));
            }
            messages.add(SystemMessage.from(hb.toString()));
        }
        String skillSection = skillPromptComposer.compose(activeSkills, ctxBuilder.build(),
                de.mhus.vance.brain.skill.SkillTurnSupport.rawArgsByName(process));
        if (skillSection != null && !skillSection.isBlank()) {
            messages.add(SystemMessage.from(skillSection));
        }
        // Compaction summaries first: these are plain SystemMessages, which
        // the Anthropic mapper tags STATIC, and the cache marker goes on the
        // LAST static block. Appending them after the dynamic blocks below
        // would pull those inside the cached prefix, so every date rollover
        // or scratchpad write would re-bill the whole system prompt. See
        // specification/public/prompt-caching.md §5a.
        for (MemoryDocument m : memoryService.activeByProcessAndKind(
                process.getTenantId(), process.getId(), MemoryKind.ARCHIVED_CHAT)) {
            messages.add(SystemMessage.from(
                    "[Conversation summary from earlier turns]\n" + m.getContent()));
        }
        // Current-date block (recipe-param promptDateGranularity:
        // auto/day/hour, default none). DYNAMIC — date rollover stays
        // behind the cache marker. See PromptDateBlock.
        promptDateContextResolver.appendDynamicMessage(
                messages, process, modelInfo == null ? null : modelInfo.size());
        // Client environment (os/shell/cwd/sandbox) — tells the LLM which
        // command dialect its client_exec_run calls run on. DYNAMIC, no-op
        // when no CLIENT connection is bound. See PromptEnvironmentBlock.
        promptDateContextResolver.appendClientEnvMessage(messages, process);
        // Scratchpad slot inventory — DYNAMIC, no-op for a process that
        // took no notes. See ScratchpadPromptBlock.
        scratchpadPromptContributor.appendDynamicMessage(messages, process);
        for (ChatMessageDocument msg : historyStrengthFilter.filter(chatLog.activeHistory(
                process.getTenantId(), process.getSessionId(), process.getId()))) {
            messages.add(toLangchain(msg));
        }
        // Non-UserChatInput inbox items (ProcessEvent, ToolResult,
        // ExternalCommand, …) appended as user-role messages with the
        // same <process-event> XML markers Arthur uses — including
        // eventId / respondingToTurnAt from Fix 1 so Ford can attribute
        // a child worker's reply to the right turn. See
        // planning/arthur-process-event-attribution.md §Erweiterungen.
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
     * Wraps a non-UserChatInput inbox item in the XML marker the LLM
     * is trained on. Returns {@code null} for items that have no
     * separate rendering (UserChatInput is already in chat history).
     * Mirrors Arthur's static renderer; carries the Fix 1 attribution
     * attributes ({@code eventId}, {@code respondingToTurnAt}) so
     * Ford parents can map a child reply to the originating turn.
     */
    private @Nullable String renderForLlm(SteerMessage m) {
        if (m instanceof SteerMessage.UserChatInput) return null;
        if (m instanceof SteerMessage.ProcessEvent pe) {
            StringBuilder sb = new StringBuilder();
            sb.append("<process-event sourceProcessId=\"")
                    .append(escapeAttr(pe.sourceProcessId()))
                    .append("\"");
            String sourceName = thinkProcessService.findById(pe.sourceProcessId())
                    .map(ThinkProcessDocument::getName).orElse(null);
            if (sourceName != null && !sourceName.isBlank()) {
                sb.append(" sourceProcessName=\"")
                        .append(escapeAttr(sourceName))
                        .append("\"");
            }
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
            return "<external-command command=\""
                    + escapeAttr(ec.command()) + "\">"
                    + escapeText(ec.params() == null ? "" : ec.params().toString())
                    + "</external-command>";
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
     * Resolves the engine-default prompt template for the current turn
     * through the document cascade. The recipe param
     * {@code promptDocument} overrides the engine default path. The
     * returned text is a Pebble template; tier/mode variation lives
     * inside via {@code {% if tier == "small" %}…{% endif %}} —
     * {@link SystemPrompts#compose} renders it with the per-turn
     * context. {@link #SYSTEM_PROMPT} is the last-resort fallback.
     */
    private String engineDefaultPrompt(ThinkProcessDocument process) {
        String basePath = paramString(process, "promptDocument", DEFAULT_PROMPT_PATH);
        return enginePromptResolver.resolve(process, basePath, SYSTEM_PROMPT);
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

    /**
     * {@link String#format} that survives recipe-supplied templates
     * with the wrong placeholder count. A misconfigured override
     * shouldn't crash the turn; we log and fall back to a literal
     * concat instead.
     */
    private static String formatSafe(String template, Object... args) {
        try {
            return String.format(template, args);
        } catch (RuntimeException e) {
            log.warn("Ford: validator template format failed ({}), using template verbatim",
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
