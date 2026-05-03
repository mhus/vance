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
import de.mhus.vance.brain.ai.AiModelResolver;
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
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.context.RespondTool;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.memory.MemoryDocument;
import de.mhus.vance.shared.memory.MemoryKind;
import de.mhus.vance.shared.memory.MemoryService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.settings.SettingService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private static final String DEFAULT_PROMPT_PATH = "prompts/ford-prompt.md";

    /** Hard cap on tool-call iterations per turn — a broken model can loop.
     *  Per-process override via {@code params.maxIterations}. */
    private static final int MAX_TOOL_ITERATIONS = 8;

    // ──────────────────── Validation heuristic ────────────────────
    // Opt-in via params.validation == true. One remaining check:
    //   reply-too-brief-after-data-fetch — Ford-specific, catches
    //   "OK, I see the files." after a substantial tool result
    //
    // The intent-without-action check is gone — its role is now filled
    // by the structured {@link RespondTool}: the engine ends every
    // turn with a `respond` call carrying both the user-facing message
    // and an explicit `awaiting_user_input` boolean. See
    // specification/structured-engine-output.md.

    /** Tool result size (chars) above which we expect the data to be
     *  reflected in the reply. */
    private static final int TOOL_DATA_THRESHOLD = 500;

    /** Reply size (chars) below which we suspect the data wasn't relayed. */
    private static final int REPLY_BRIEF_THRESHOLD = 200;

    private static final int MAX_VALIDATION_CORRECTIONS = 2;

    private static final String DATA_RELAY_CORRECTION_TEMPLATE =
            "VALIDATION CHECK: tools returned %d chars, your reply has "
                    + "%d — paste the actual data into the reply text.";

    /**
     * Language-agnostic correction for "model produced free text with
     * no tool call". Replaces the old regex-based intent-without-action
     * validator: the structural check is whether any tool was emitted,
     * not what phrases the text contains.
     */
    private static final String NO_TOOL_CALL_CORRECTION =
            "VALIDATION CHECK: your previous response had no tool call. "
                    + "Every turn must end with at least one tool call: "
                    + "the work tools first (web_search, file_read, …), "
                    + "then `respond(message=..., awaiting_user_input=...)` "
                    + "as the final marker.\n\n"
                    + "If your previous response was a complete answer to "
                    + "the user (any non-trivial assistant text), re-emit "
                    + "that text VERBATIM — same wording, same length, "
                    + "same formatting — as the `message` argument of "
                    + "`respond`. Do not summarise it, do not shorten it, "
                    + "do not replace it with a brief acknowledgement. "
                    + "The text you already wrote IS the answer; just wrap "
                    + "it in `respond(message=<that text>, awaiting_user_input=true)`.\n\n"
                    + "If your previous response was an intent-to-act ("
                    + "\"I will search…\"), emit the action tool now "
                    + "instead. Free assistant text without a tool call "
                    + "is never the right output.";

    /** Provider-specific API-key setting key, e.g. {@code ai.provider.gemini.apiKey}. */
    private static final String SETTING_PROVIDER_API_KEY_FMT = "ai.provider.%s.apiKey";

    private final ThinkProcessService thinkProcessService;
    private final ObjectMapper objectMapper;
    private final StreamingProperties streamingProperties;
    private final ModelCatalog modelCatalog;
    private final de.mhus.vance.brain.progress.LlmCallTracker llmCallTracker;
    private final de.mhus.vance.brain.memory.MemoryContextLoader memoryContextLoader;
    private final de.mhus.vance.brain.thinkengine.EnginePromptResolver enginePromptResolver;
    private final de.mhus.vance.brain.ai.EngineChatFactory engineChatFactory;
    private final FordProperties fordProperties;
    private final MemoryService memoryService;
    private final MemoryCompactionService memoryCompactionService;
    private final SkillResolver skillResolver;
    private final SkillPromptComposer skillPromptComposer;
    private final de.mhus.vance.brain.skill.SkillTriggerMatcher skillTriggerMatcher;
    private final SessionService sessionService;

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
        if (!(message instanceof SteerMessage.UserChatInput userInput)) {
            log.warn("Ford.steer received unexpected message type '{}' for id='{}' — ignoring",
                    message.getClass().getSimpleName(), process.getId());
            return;
        }
        runTurn(process, ctx, userInput.content());
    }

    @Override
    public void stop(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.info("Ford.stop id='{}'", process.getId());
        thinkProcessService.closeProcess(process.getId(), CloseReason.STOPPED);
    }

    // ──────────────────── One turn ────────────────────

    private TurnOutcome runTurn(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            String userInput) {

        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.RUNNING);
        // Default IDLE on any abnormal exit — matches legacy lifecycle.
        // Set to outcome.awaitingUserInput() inside the try when the
        // tool-loop returns cleanly.
        boolean awaitingUserInput = false;
        try {
            ChatMessageService chatLog = ctx.chatMessageService();
            chatLog.append(ChatMessageDocument.builder()
                    .tenantId(process.getTenantId())
                    .sessionId(process.getSessionId())
                    .thinkProcessId(process.getId())
                    .role(ChatRole.USER)
                    .content(userInput)
                    .build());

            // Skill auto-trigger: match the user input against PATTERN/
            // KEYWORDS triggers of visible skills, one-shot activate
            // matches. Filters via process.allowedSkillsOverride. Quiet
            // when nothing fires.
            skillTriggerMatcher.detectAndActivate(process, userInput);

            // Build the chat with primary + ordered fallback chain plus
            // the standard resilience-notifier and (when tracing.llm is
            // on) LLM-trace persistence — see EngineChatFactory.
            de.mhus.vance.brain.ai.EngineChatFactory.EngineChatBundle chatBundle =
                    engineChatFactory.forProcess(process, ctx, NAME);
            AiChat aiChat = chatBundle.chat();
            AiChatConfig config = chatBundle.primaryConfig();

            List<ResolvedSkill> activeSkills = resolveActiveSkills(process);
            String skillSection = skillPromptComposer.compose(activeSkills);

            ContextToolsApi tools = ctx.tools()
                    .withAdditional(skillPromptComposer.mergedTools(activeSkills));
            List<ToolSpecification> toolSpecs = tools.primaryAsLc4j();
            ModelInfo modelInfo = modelCatalog.lookupOrDefault(
                    config.provider(), config.modelName());

            // params.modelSize: SMALL/LARGE force the prompt variant
            // independently of the catalog; AUTO/missing falls back
            // to the catalog's classification.
            ModelSize effectiveSize = ModelSize.parseOrAuto(
                    paramString(process, "modelSize", null), modelInfo.size());
            List<ChatMessage> messages = buildPromptMessages(
                    process, chatLog, effectiveSize, skillSection);
            int estimatedTokens = estimateTokens(messages);
            int triggerTokens = modelInfo.compactionTriggerTokens(
                    fordProperties.getCompactionTriggerRatio());
            log.debug("Ford.turn id='{}' model={}/{} ctx={} trigger={} est={}",
                    process.getId(),
                    modelInfo.provider(), modelInfo.modelName(),
                    modelInfo.contextWindowTokens(),
                    triggerTokens,
                    estimatedTokens);
            if (estimatedTokens >= triggerTokens) {
                log.info("Ford.turn id='{}' triggering compaction (est {} >= trigger {})",
                        process.getId(), estimatedTokens, triggerTokens);
                try {
                    CompactionResult result = memoryCompactionService.compact(process, config);
                    if (result.compacted()) {
                        log.info("Ford.turn id='{}' compaction ok: {} msgs → {} chars (memory='{}')",
                                process.getId(),
                                result.messagesCompacted(),
                                result.summaryChars(),
                                result.memoryId());
                        // Rebuild the prompt: the active-history shrunk and a
                        // new ARCHIVED_CHAT memory pinned the summary at top.
                        messages = buildPromptMessages(
                                process, chatLog, effectiveSize, skillSection);
                    } else {
                        log.info("Ford.turn id='{}' compaction skipped: {}",
                                process.getId(), result.reason());
                    }
                } catch (RuntimeException e) {
                    // Best-effort: don't crash the user's turn if compaction fails.
                    log.warn("Ford.turn id='{}' compaction failed: {}",
                            process.getId(), e.toString());
                }
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
            if (outcome.needsFormatCorrection()) {
                outcome = runFormatCorrectionLoop(
                        aiChat, toolSpecs, process, outcome.finalText(), modelAlias, ctx);
            }
            awaitingUserInput = outcome.awaitingUserInput();
            String finalText = outcome.finalText();

            chatLog.append(ChatMessageDocument.builder()
                    .tenantId(process.getTenantId())
                    .sessionId(process.getSessionId())
                    .thinkProcessId(process.getId())
                    .role(ChatRole.ASSISTANT)
                    .content(finalText)
                    .build());

            String preview = finalText.length() > 120 ? finalText.substring(0, 120) + "…" : finalText;
            log.info("Ford.steer id='{}' awaiting={} -> '{}'",
                    process.getId(), awaitingUserInput, preview);
            return outcome;
        } finally {
            // Drain one-shot skills before the next turn — they only
            // ever apply to the turn that activated them.
            dropOneShotSkills(process);
            ThinkProcessStatus exitStatus = awaitingUserInput
                    ? ThinkProcessStatus.BLOCKED
                    : ThinkProcessStatus.IDLE;
            thinkProcessService.updateStatus(process.getId(), exitStatus);
        }
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

    /** Max iterations of the format-correction sub-loop. */
    private static final int MAX_FORMAT_CORRECTION_ITERS = 2;

    /**
     * Mini-system-prompt for the format-correction sub-loop. The
     * sub-loop runs in a fresh conversation containing only this
     * system prompt, the Free-Text the model produced in the main
     * loop, and an instruction to wrap it. It has only the `respond`
     * tool available — the LLM cannot fall back into tool-search or
     * "answer the question again" territory. Sprach-agnostisch:
     * structural framing, no regex on the text.
     */
    private static final String FORMAT_CORRECTION_SYSTEM =
            "You are a format-correction agent. Your only job is to "
                    + "wrap the assistant text below into a `respond` "
                    + "tool call.\n\n"
                    + "Rules:\n"
                    + "- Emit exactly one tool call: "
                    + "`respond(message=<the text VERBATIM>, awaiting_user_input=true)`.\n"
                    + "- Do NOT modify, summarise, shorten, translate, or rewrite the text.\n"
                    + "- Do NOT add any commentary outside the tool call.\n"
                    + "- Do NOT call any other tool.\n"
                    + "- The text you must wrap is the assistant message that follows.";

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
             * {@code true} when the main loop ended with the model emitting
             * Free-Text instead of a {@code respond} tool call. The engine
             * layer runs a separate {@link #runFormatCorrectionLoop} in a
             * fresh sub-conversation to wrap the text — no main-loop
             * pollution, no validator-induced LLM-collapse spiral.
             */
            boolean needsFormatCorrection) {}

    /**
     * Tool-call loop in streaming mode. Each iteration drives the
     * {@link AiChat#streamingChatModel()} and funnels text partials
     * through a {@link ChunkBatcher} into the event publisher.
     *
     * <p>The {@link RespondTool} marks the end of the turn: when the
     * model emits a {@code respond} call, the loop dispatches any
     * other tool calls that came in the same response (so their
     * results stay in the chat-history audit trail) and then returns
     * the {@code respond.message} as final text plus the
     * {@code awaiting_user_input} flag.
     *
     * <p>Fallback for models that ignore the {@code respond}
     * convention: a response without any tool calls (or with tool
     * calls but no {@code respond} after {@link #MAX_VALIDATION_CORRECTIONS}
     * iterations) hands back the streamed text and defaults to
     * {@code awaitingUserInput=false}, preserving the legacy behaviour
     * of going IDLE between turns.
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
            ChatRequest.Builder req = ChatRequest.builder().messages(messages);
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
                            "Ford id='{}' tool-loop LLM failure ({}) — recovering with best Free-Text seen ({} chars), deferring to format-correction",
                            process.getId(), e.toString(), bestFreeText.length());
                    return new TurnOutcome(bestFreeText, true, /*needsFormatCorrection*/ true);
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
                // Model emitted free text without any tool call. Two
                // sub-cases:
                //
                //  1) Data-relay-gap (validation-gated): big tool data
                //     in the conversation but the reply is brief — the
                //     content is too thin. Stay in the main loop and
                //     correct, because the model needs to re-read tool
                //     results to produce richer content.
                //
                //  2) Otherwise: the reply IS the answer; only the
                //     wrapping `respond` is missing. Defer to the
                //     format-correction sub-loop instead of polluting
                //     the main conversation with format nudges that
                //     tend to confuse the model further.
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
                // No respond emitted — defer to format-correction sub-loop.
                return new TurnOutcome(
                        finalText.toString(), true, /*needsFormatCorrection*/ true);
            }

            // Tool calls present. Split off respond (if any), dispatch
            // the rest, then either finish (respond present, no others)
            // or loop (others present — see results before responding).
            ToolExecutionRequest respondCall = null;
            List<ToolExecutionRequest> others = new ArrayList<>();
            for (ToolExecutionRequest call : reply.toolExecutionRequests()) {
                if (RespondTool.NAME.equals(call.name())) {
                    if (respondCall == null) {
                        respondCall = call;  // first respond wins
                    }
                } else {
                    others.add(call);
                }
            }

            messages.add(reply);
            for (ToolExecutionRequest call : others) {
                String result = invokeOne(tools, call, process.getId());
                if (result != null) toolDataChars += result.length();
                messages.add(ToolExecutionResultMessage.from(call, result));
            }
            if (respondCall != null) {
                if (!others.isEmpty()) {
                    // Model emitted respond *together with* work tools. The
                    // respond payload was speculative ("ich suche…") — it
                    // didn't see the tool results yet. Synthesise a
                    // tool-result for the respond call (so the LLM sees
                    // its premature response was rejected) and let the
                    // loop continue: the next turn produces the real
                    // recipe-shaped output.
                    log.info(
                            "Ford id='{}' rejecting premature respond emitted alongside {} other tool(s) — looping for actual results",
                            process.getId(), others.size());
                    messages.add(ToolExecutionResultMessage.from(respondCall,
                            "{\"error\":\"respond was called together with other tool calls. "
                                    + "respond must be the LAST and ONLY tool call in a turn, "
                                    + "after you have observed the results of all work tools. "
                                    + "Continue the loop: read the tool results above, then "
                                    + "call respond with the synthesised answer.\"}"));
                    continue;
                }
                RespondArgs args = parseRespondArgs(respondCall);
                if (args.message() != null && !args.message().isEmpty()) {
                    finalText.append(args.message());
                }
                return new TurnOutcome(
                        finalText.toString(), args.awaitingUserInput(),
                        /*needsFormatCorrection*/ false);
            }
        }
        // maxIters exhausted. Same recovery path as the LLM-collapse
        // branch: don't throw the work away — emit the best Free-Text
        // we extracted, deferring to format-correction.
        if (!bestFreeText.isEmpty()) {
            log.warn(
                    "Ford id='{}' exceeded {} tool iterations — recovering with best Free-Text seen ({} chars), deferring to format-correction",
                    process.getId(), maxIters, bestFreeText.length());
            return new TurnOutcome(bestFreeText, true, /*needsFormatCorrection*/ true);
        }
        throw new AiChatException(
                "Ford exceeded " + maxIters
                        + " tool iterations — no recoverable text, aborting turn.");
    }

    /**
     * Format-correction sub-loop. Runs in a fresh sub-conversation
     * containing only the system prompt + the Free-Text + a wrap
     * instruction; the only available tool is {@code respond}. The
     * model can therefore not fall back into "search again" or
     * "answer the question differently" territory.
     *
     * <p>If the model emits {@code respond} → returns its args. If
     * not after {@link #MAX_FORMAT_CORRECTION_ITERS} iterations →
     * falls back to the original Free-Text wrapped with
     * {@code awaiting_user_input=true}, so the user still sees the
     * answer the main loop produced.
     */
    private TurnOutcome runFormatCorrectionLoop(
            AiChat aiChat,
            List<ToolSpecification> mainToolSpecs,
            ThinkProcessDocument process,
            String freeText,
            String modelAlias,
            ThinkEngineContext ctx) {
        if (freeText == null || freeText.isBlank()) {
            // Nothing to wrap — fall through with empty content,
            // BLOCKED so the user can re-issue.
            return new TurnOutcome("", true, /*needsFormatCorrection*/ false);
        }
        log.info(
                "Ford id='{}' format-correction sub-loop starting (text {} chars)",
                process.getId(), freeText.length());

        // Sub-conversation: system prompt + a single UserMessage that
        // carries the wrap instruction AND the text to wrap. Gemini
        // rejects requests that end with a system or assistant role
        // ("single turn requests end with a user role"), so the text-
        // to-wrap goes into the UserMessage rather than a separate
        // AiMessage followed by a SystemMessage.
        List<ChatMessage> sub = new ArrayList<>();
        sub.add(SystemMessage.from(FORMAT_CORRECTION_SYSTEM));
        sub.add(UserMessage.from(
                "Wrap the following assistant text VERBATIM in a "
                        + "`respond(message=<that text>, awaiting_user_input=true)` "
                        + "tool call. Do not modify, summarise, or shorten the text. "
                        + "Emit only the tool call, no other text.\n\n"
                        + "--- BEGIN ASSISTANT TEXT ---\n"
                        + freeText
                        + "\n--- END ASSISTANT TEXT ---"));

        // Filter the engine's tool-spec list down to just `respond`.
        List<ToolSpecification> respondOnly = new ArrayList<>();
        for (ToolSpecification spec : mainToolSpecs) {
            if (RespondTool.NAME.equals(spec.name())) {
                respondOnly.add(spec);
                break;
            }
        }
        if (respondOnly.isEmpty()) {
            // RespondTool not in the spec list (shouldn't happen for
            // chat-engines). Best-effort fallback: wrap the Free-Text
            // ourselves and return.
            log.warn(
                    "Ford id='{}' format-correction: respond tool not in spec list — fallback to verbatim",
                    process.getId());
            return new TurnOutcome(freeText, true, /*needsFormatCorrection*/ false);
        }

        for (int iter = 0; iter < MAX_FORMAT_CORRECTION_ITERS; iter++) {
            ChatRequest req = ChatRequest.builder()
                    .messages(sub)
                    .toolSpecifications(respondOnly)
                    .build();
            AiMessage reply;
            try {
                StreamResult streamed = streamOneIteration(
                        aiChat, req, ctx, process, modelAlias);
                reply = streamed.message;
            } catch (RuntimeException e) {
                log.warn(
                        "Ford id='{}' format-correction LLM failure ({}) — falling back to Free-Text verbatim",
                        process.getId(), e.toString());
                return new TurnOutcome(freeText, true, /*needsFormatCorrection*/ false);
            }

            if (reply.hasToolExecutionRequests()) {
                for (ToolExecutionRequest call : reply.toolExecutionRequests()) {
                    if (RespondTool.NAME.equals(call.name())) {
                        RespondArgs args = parseRespondArgs(call);
                        String message = args.message() != null && !args.message().isBlank()
                                ? args.message()
                                : freeText;
                        log.info(
                                "Ford id='{}' format-correction succeeded after {} iter — wrapped {} chars",
                                process.getId(), iter + 1, message.length());
                        return new TurnOutcome(
                                message, args.awaitingUserInput(),
                                /*needsFormatCorrection*/ false);
                    }
                }
            }

            // Model still doesn't emit respond — append the reply and
            // a stronger user nudge for the next iteration. Must end
            // on user role for Gemini compatibility.
            sub.add(reply);
            sub.add(UserMessage.from(
                    "You did not call `respond`. Call it now with the "
                            + "assistant text from my first message as the "
                            + "`message` argument. Emit only the tool call, "
                            + "nothing else."));
        }

        log.warn(
                "Ford id='{}' format-correction exhausted {} iters — falling back to Free-Text verbatim",
                process.getId(), MAX_FORMAT_CORRECTION_ITERS);
        return new TurnOutcome(freeText, true, /*needsFormatCorrection*/ false);
    }

    /** Parsed payload of a {@link RespondTool} call. */
    private record RespondArgs(String message, boolean awaitingUserInput) {}

    private RespondArgs parseRespondArgs(ToolExecutionRequest call) {
        String raw = call.arguments();
        if (raw == null || raw.isBlank()) {
            return new RespondArgs("", true);
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(raw, Map.class);
            Object msgVal = parsed.get(RespondTool.PARAM_MESSAGE);
            Object awaitingVal = parsed.get(RespondTool.PARAM_AWAITING_USER_INPUT);
            String message = msgVal instanceof String s ? s : "";
            boolean awaiting = !(awaitingVal instanceof Boolean b) || b;
            return new RespondArgs(message, awaiting);
        } catch (RuntimeException e) {
            log.warn("Ford: failed to parse respond args (raw='{}'): {}", raw, e.toString());
            return new RespondArgs("", true);  // default to BLOCKED on parse failure
        }
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
            ChatResponse response = done.get();
            llmCallTracker.record(
                    process, response, System.currentTimeMillis() - startMs, modelAlias);
            AiMessage reply = response.aiMessage();
            return new StreamResult(reply, reply.text() == null ? "" : reply.text());
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
            return errorJson(e.getMessage());
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

    private String errorJson(String message) {
        try {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", message);
            return objectMapper.writeValueAsString(err);
        } catch (RuntimeException e) {
            return "{\"error\":\"" + message.replace("\"", "'") + "\"}";
        }
    }

    // ──────────────────── Helpers ────────────────────

    /** Reply message + its text, so callers don't call {@code text()} twice. */
    private record StreamResult(AiMessage message, String text) {}

    private static ChatMessage toLangchain(ChatMessageDocument msg) {
        return switch (msg.getRole()) {
            case USER -> UserMessage.from(msg.getContent());
            case ASSISTANT -> AiMessage.from(msg.getContent());
            case SYSTEM -> SystemMessage.from(msg.getContent());
        };
    }

    /**
     * Builds the prompt-message list for one turn: base system prompt,
     * optional skill-section, pinned compaction summary (if any), then
     * active chat history. Re-callable so {@code runTurn} can rebuild
     * after a mid-turn compaction.
     *
     * @param skillSection composed skill block from
     *        {@link SkillPromptComposer#compose}, or {@code null} when
     *        no skills are active. Appended as a separate
     *        {@link SystemMessage} after the engine-default prompt.
     */
    private List<ChatMessage> buildPromptMessages(
            ThinkProcessDocument process, ChatMessageService chatLog,
            ModelSize modelSize, @Nullable String skillSection) {
        List<ChatMessage> messages = new ArrayList<>();
        String base = SystemPrompts.compose(process,
                engineDefaultPrompt(process, modelSize), modelSize);
        String memoryBlock = memoryContextLoader.composeBlock(process);
        if (memoryBlock != null && !memoryBlock.isBlank()) {
            base = base + "\n\n" + memoryBlock;
        }
        messages.add(SystemMessage.from(base));
        if (skillSection != null && !skillSection.isBlank()) {
            messages.add(SystemMessage.from(skillSection));
        }
        for (MemoryDocument m : memoryService.activeByProcessAndKind(
                process.getTenantId(), process.getId(), MemoryKind.ARCHIVED_CHAT)) {
            messages.add(SystemMessage.from(
                    "[Conversation summary from earlier turns]\n" + m.getContent()));
        }
        for (ChatMessageDocument msg : chatLog.activeHistory(
                process.getTenantId(), process.getSessionId(), process.getId())) {
            messages.add(toLangchain(msg));
        }
        return messages;
    }

    /**
     * Cheap, provider-agnostic token estimator: ≈ 4 chars per token for
     * English-ish text. Conservative enough as a compaction trigger; the
     * proper tokenizer per provider is a future refinement once the
     * compaction loop demands precision.
     */
    private static int estimateTokens(List<ChatMessage> messages) {
        long chars = 0;
        for (ChatMessage m : messages) {
            String text = textOf(m);
            if (text != null) chars += text.length();
        }
        return (int) Math.min(Integer.MAX_VALUE, chars / 4 + messages.size() * 4L);
    }

    private static String textOf(ChatMessage m) {
        if (m instanceof UserMessage u) return u.singleText();
        if (m instanceof AiMessage a) return a.text();
        if (m instanceof SystemMessage s) return s.text();
        if (m instanceof ToolExecutionResultMessage t) return t.text();
        return m.toString();
    }

    private static AiChatConfig resolveAiConfig(
            ThinkProcessDocument process,
            SettingService settings,
            AiModelResolver modelResolver) {
        String tenantId = process.getTenantId();
        String paramModel = paramString(process, "model", null);
        String paramProvider = paramString(process, "provider", null);
        String spec;
        if (paramModel != null && paramModel.contains(":")) {
            spec = paramModel;
        } else if (paramModel != null && paramProvider != null) {
            spec = paramProvider + ":" + paramModel;
        } else if (paramModel != null) {
            spec = "default:" + paramModel;
        } else {
            spec = null;
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

    /**
     * Resolves the engine-default prompt for the current turn through
     * the tier-aware document cascade. Recipe params
     * {@code promptDocument} (base path) and {@code promptDocumentSmall}
     * (optional explicit small-variant path) override the engine
     * defaults; the resolver derives a {@code -small} suffix
     * automatically and falls through to the base when no small variant
     * exists, so engines don't have to maintain two files unless they
     * want differentiated tiers. {@link #SYSTEM_PROMPT} is the
     * last-resort fallback.
     */
    private String engineDefaultPrompt(ThinkProcessDocument process, ModelSize modelSize) {
        String basePath = paramString(process, "promptDocument", DEFAULT_PROMPT_PATH);
        String smallOverride = paramString(process, "promptDocumentSmall", null);
        return enginePromptResolver.resolveTiered(
                process, basePath, smallOverride, modelSize, SYSTEM_PROMPT);
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
