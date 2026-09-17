package de.mhus.vance.brain.wowbagger;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiChatException;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.ai.ModelInfo;
import de.mhus.vance.brain.ai.ModelSize;
import de.mhus.vance.brain.events.StreamingProperties;
import de.mhus.vance.brain.memory.MemoryCompactionService;
import de.mhus.vance.brain.memory.MemoryContextLoader;
import de.mhus.vance.brain.prompt.PromptContextBuilder;
import de.mhus.vance.brain.thinkengine.EnginePromptResolver;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.brain.tools.ToolErrorPayload;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.memory.MemoryDocument;
import de.mhus.vance.shared.memory.MemoryKind;
import de.mhus.vance.shared.memory.MemoryService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Wowbagger — the bulk record processor as a Ford-style chat agent
 * (planning/wowbagger-engine.md §4a).
 *
 * <p>A conversation loop over a mechanical thread pool: the agent receives
 * user input (or pool wakeups), sees the <b>structure status in its
 * prompt</b> — what data, ready or running, pointer, threads, failures —
 * and steers the pool through the {@code wowbagger_*} tools
 * ({@link WowbaggerConfigureTool}, {@link WowbaggerStartTool},
 * {@link WowbaggerStopTool}, {@link WowbaggerSetThreadsTool}). It carries
 * the full tool surface (file_*, doc_*, exec_* …) so it can inspect and
 * import sources itself.
 *
 * <p>Unlike v1's mechanical loop, all judgment lives here: setup is a
 * conversation (analyze the source format with file_read, configure the
 * structure, start), failures arrive as wakeups, and the agent decides —
 * retry, skip, fix the prompt, resize. No skills machinery; recipe params
 * (e.g. language) flow through the standard render context like Ford's.
 *
 * <p><b>Adapted from Ford</b> (the loop, streaming, compaction, history
 * strength filtering, guards), simplified deliberately: no skills, no
 * data-relay validation. Everything else matches Ford's persistence policy:
 * only the user's input and the final assistant text land in the chat log;
 * tool calls stay turn-local.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WowbaggerEngine implements ThinkEngine {

    public static final String NAME = "wowbagger";
    public static final String VERSION = "0.2.0";
    public static final String ROLE = "wowbagger";

    private static final String SYSTEM_PROMPT = "You are Wowbagger, Vance's bulk record processor. "
            + "You steer a mechanical worker-thread pool over a source file. "
            + "Analyze, configure, start, and report. Use your tools.";

    private static final String DEFAULT_PROMPT_PATH = "_vance/prompts/wowbagger-prompt.md";

    /** Same backstop rationale as Ford's. */
    private static final int MAX_TOOL_ITERATIONS = 40;

    private static final long STREAM_TIMEOUT_MINUTES = 20;

    private final ThinkProcessService thinkProcessService;
    private final ObjectMapper objectMapper;
    private final StreamingProperties streamingProperties;
    private final ModelCatalog modelCatalog;
    private final de.mhus.vance.brain.progress.LlmCallTracker llmCallTracker;
    private final MemoryContextLoader memoryContextLoader;
    private final EnginePromptResolver enginePromptResolver;
    private final de.mhus.vance.brain.thinkengine.SystemPromptComposer composer;
    private final de.mhus.vance.brain.ai.EngineChatFactory engineChatFactory;
    private final MemoryService memoryService;
    private final MemoryCompactionService memoryCompactionService;
    private final de.mhus.vance.brain.context.PromptDateContextResolver promptDateContextResolver;
    private final de.mhus.vance.brain.prompt.ScratchpadPromptContributor scratchpadPromptContributor;
    private final de.mhus.vance.shared.workspace.WorkspaceService workspaceService;
    private final de.mhus.vance.brain.prak.HistoryStrengthFilter historyStrengthFilter;
    private final de.mhus.vance.brain.prompt.ClientTurnContextResolver clientTurnContextResolver;
    private final de.mhus.vance.brain.thinkengine.TurnContextHandlerRegistry turnContextHandlers;
    private final de.mhus.vance.brain.guard.ShootyGuardService guardService;
    private final WowbaggerPoolService pool;

    // ──────────────────── Metadata ────────────────────

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "Wowbagger (Bulk Record Processor)";
    }

    @Override
    public String description() {
        return "Chat agent over a mechanical worker-thread pool: you describe the task "
                + "and the source in plain language, Wowbagger analyzes the input structure, "
                + "configures the run, rotates worker threads over the records and reports "
                + "progress on every wakeup. Full tool surface plus its own wowbagger_* "
                + "controls. Built for long runs with pause/resume at any point.";
    }

    @Override
    public String version() {
        return VERSION;
    }

    /** The wowbagger_* tools gate on this role — invisible to other engines. */
    @Override
    public Set<String> roles() {
        return Set.of(ROLE);
    }

    // ──────────────────── Lifecycle ────────────────────

    @Override
    public void start(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.info(
                "Wowbagger.start tenant='{}' session='{}' id='{}'",
                process.getTenantId(),
                process.getSessionId(),
                process.getId());
        // Ford semantics: no greeting; the spawn steer (or the user's first
        // message) drives the turn. The pool stays untouched — starting it
        // is an explicit agent decision (wowbagger_start).
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
    }

    @Override
    public void resume(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.debug("Wowbagger.resume id='{}'", process.getId());
        // A suspended run: the pool stops on the suspend cascade — resume
        // restarts it with the same structure (reconcile decides by doc
        // presence, §6.3) when it was running before.
        if (pool.structure(process.getId()).getThreadsDesired() > 0) {
            try {
                pool.start(process);
            } catch (RuntimeException | java.io.IOException e) {
                log.warn("Wowbagger id='{}' resume pool start failed: {}", process.getId(), e.toString());
            }
        }
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
    }

    @Override
    public void suspend(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.debug("Wowbagger.suspend id='{}'", process.getId());
        // Suspend cascade stops the pool — in-flight chunks stay un-committed
        // and reconcile on the next start (§6.3).
        pool.stop(process.getId());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.SUSPENDED);
    }

    @Override
    public void steer(ThinkProcessDocument process, ThinkEngineContext ctx, SteerMessage message) {
        runTurnFor(process, ctx, List.of(message));
    }

    @Override
    public void runTurn(ThinkProcessDocument process, ThinkEngineContext ctx) {
        while (true) {
            List<SteerMessage> drained = ctx.drainPending();
            if (drained.isEmpty()) {
                return;
            }
            runTurnFor(process, ctx, drained);
        }
    }

    @Override
    public void stop(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.info("Wowbagger.stop id='{}'", process.getId());
        pool.stop(process.getId());
        thinkProcessService.closeProcess(process.getId(), CloseReason.STOPPED);
    }

    @Override
    public de.mhus.vance.brain.thinkengine.ParentReport summarizeForParent(
            ThinkProcessDocument process, de.mhus.vance.api.thinkprocess.ProcessEventType eventType) {
        WowbaggerState s = pool.structure(process.getId());
        String summary = "Wowbagger run: " + s.getRecordsDone() + "/"
                + Math.max(s.getRecordsTotal(), 0) + " records, "
                + s.getFailedChunks().size() + " failed (" + s.getFailureCount() + " unacked), "
                + (s.isFinished() ? "finished" : pool.isRunning(process.getId()) ? "running" : "idle")
                + (isBlank(s.getOutputDocPath()) ? "" : ", result at " + s.getOutputDocPath());
        return de.mhus.vance.brain.thinkengine.ParentReport.of(summary);
    }

    // ──────────────────── One turn (Ford-adapted) ────────────────────

    private TurnOutcome runTurnFor(ThinkProcessDocument process, ThinkEngineContext ctx, List<SteerMessage> inbox) {
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.RUNNING);
        guardService.guardsOnTurnStart(process, inbox);
        boolean awaitingUserInput = false;
        boolean recoveredFromMaxIter = false;
        boolean interrupted = false;
        boolean interruptForcePause = false;
        try {
            ChatMessageService chatLog = ctx.chatMessageService();
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
            // No skill trigger matching — Wowbagger carries no skills (§4a.1).

            de.mhus.vance.brain.ai.EngineChatFactory.EngineChatBundle chatBundle =
                    engineChatFactory.forProcess(process, ctx, NAME);
            AiChat aiChat = chatBundle.chat();
            AiChatConfig config = chatBundle.primaryConfig();

            ContextToolsApi tools = ctx.tools();
            List<ToolSpecification> toolSpecs = tools.primaryAsLc4j();
            ModelInfo modelInfo = modelCatalog.lookupOrDefault(
                    process.getTenantId(),
                    process.getProjectId(),
                    config.providerInstance(),
                    config.provider(),
                    config.modelName());

            ModelSize effectiveSize = ModelSize.parseOrAuto(paramString(process, "modelSize", null), modelInfo.size());
            List<ChatMessage> messages = buildPromptMessages(process, chatLog, extras, modelInfo, effectiveSize, tools);
            de.mhus.vance.brain.memory.CompactionResult compactResult =
                    memoryCompactionService.compactIfNeeded(process, config, messages, modelInfo);
            if (compactResult.compacted()) {
                log.info(
                        "Wowbagger.turn id='{}' compaction ok: {} msgs → {} chars",
                        process.getId(),
                        compactResult.messagesCompacted(),
                        compactResult.summaryChars());
                messages = buildPromptMessages(process, chatLog, extras, modelInfo, effectiveSize, tools);
            }

            int maxIters = paramInt(process, "maxIterations", MAX_TOOL_ITERATIONS);
            String modelAlias = config.providerInstance() + ":" + config.modelName();
            TurnOutcome outcome = runToolLoop(aiChat, toolSpecs, tools, messages, ctx, process, maxIters, modelAlias);
            if (outcome.interrupted()) {
                interrupted = true;
                interruptForcePause = outcome.interruptForcePause();
                ctx.historyTagSink().discard();
                log.info(
                        "Wowbagger.turn id='{}' interrupted (forcePause={}) — parking, no answer surfaced",
                        process.getId(),
                        interruptForcePause);
                return outcome;
            }
            if (outcome.recovered()) {
                recoveredFromMaxIter = true;
            }
            awaitingUserInput = outcome.awaitingUserInput();
            String finalText = outcome.finalText();

            // The pool wakeup text lands in the chat history when the pool
            // writes it; a UserChatInput from the pool would double it. Only
            // turns WITHOUT pool notes (real user turns) carry the reply to
            // the reply-channel — wakeups stay notes.
            if (recoveredFromMaxIter && process.getParentProcessId() != null) {
                finalText = "⚠️ TASK FAILED — this worker was force-stopped after "
                        + "hitting its hard limit of " + maxIters + " processing "
                        + "steps (maxIterations). It is now CLOSED and cannot be "
                        + "resumed. The task is UNFINISHED: the text below is "
                        + "PARTIAL progress only, NOT an answer.\n\nPartial progress:\n\n"
                        + finalText;
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

            if (finalText != null && !finalText.isBlank()) {
                Instant inResponseToAt = lastUserInputAt(inbox);
                ctx.emitReply(finalText, inResponseToAt, null);
            }

            String preview = finalText.length() > 120 ? finalText.substring(0, 120) + "…" : finalText;
            log.info("Wowbagger.steer id='{}' awaiting={} -> '{}'", process.getId(), awaitingUserInput, preview);
            return outcome;
        } finally {
            if (interrupted) {
                if (interruptForcePause) {
                    thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.PAUSED);
                }
            } else if (recoveredFromMaxIter && process.getParentProcessId() != null) {
                log.info("Wowbagger id='{}' worker hit maxIter — closing INCOMPLETE", process.getId());
                thinkProcessService.closeProcess(process.getId(), CloseReason.INCOMPLETE);
            } else {
                ThinkProcessStatus exitStatus =
                        awaitingUserInput ? ThinkProcessStatus.BLOCKED : ThinkProcessStatus.IDLE;
                thinkProcessService.updateStatus(process.getId(), exitStatus);
            }
        }
    }

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
     * Outcome of one tool-loop turn — Ford's semantics, unchanged.
     */
    private record TurnOutcome(
            String finalText,
            boolean awaitingUserInput,
            boolean recovered,
            boolean interrupted,
            boolean interruptForcePause) {

        static TurnOutcome terminal(String text, boolean waiting) {
            return new TurnOutcome(text, waiting, false, false, false);
        }

        static TurnOutcome recovered(String text) {
            return new TurnOutcome(text, true, true, false, false);
        }

        static TurnOutcome interrupted(boolean forcePause) {
            return new TurnOutcome("", false, false, true, forcePause);
        }
    }

    private TurnOutcome runToolLoop(
            AiChat aiChat,
            List<ToolSpecification> toolSpecs,
            ContextToolsApi tools,
            List<ChatMessage> messages,
            ThinkEngineContext ctx,
            ThinkProcessDocument process,
            int maxIters,
            String modelAlias) {
        StringBuilder finalText = new StringBuilder();
        String bestFreeText = "";
        for (int iter = 0; iter < maxIters; iter++) {
            ThinkProcessStatus liveStatus = thinkProcessService
                    .findById(process.getId())
                    .map(ThinkProcessDocument::getStatus)
                    .orElse(process.getStatus());
            if (liveStatus == ThinkProcessStatus.SUSPENDED
                    || liveStatus == ThinkProcessStatus.PAUSED
                    || liveStatus == ThinkProcessStatus.CLOSED) {
                log.info("Wowbagger id='{}' tool-loop interrupt (status={}) — exiting", process.getId(), liveStatus);
                return TurnOutcome.interrupted(false);
            }
            if (thinkProcessService.isHaltRequested(process.getId())) {
                log.info("Wowbagger id='{}' tool-loop halt requested — exiting (PAUSED)", process.getId());
                thinkProcessService.clearHalt(process.getId());
                return TurnOutcome.interrupted(true);
            }

            ChatRequest.Builder req = ChatRequest.builder().messages(turnContextHandlers.apply(messages, ctx, process));
            if (!toolSpecs.isEmpty()) {
                req.toolSpecifications(toolSpecs);
            }

            AiMessage reply;
            try {
                StreamResult streamed = streamOneIteration(aiChat, req.build(), ctx, process, modelAlias);
                reply = streamed.message();
            } catch (RuntimeException e) {
                if (!bestFreeText.isEmpty()) {
                    log.warn(
                            "Wowbagger id='{}' tool-loop LLM failure ({}) — recovering with best Free-Text ({} chars)",
                            process.getId(),
                            e.toString(),
                            bestFreeText.length());
                    return TurnOutcome.recovered(bestFreeText);
                }
                throw e;
            }

            String replyText = reply.text();
            if (replyText != null && replyText.length() > bestFreeText.length()) {
                bestFreeText = replyText;
            }

            if (!reply.hasToolExecutionRequests()) {
                String text = reply.text();
                if (text != null) {
                    finalText.append(text);
                }
                boolean waiting = process.getParentProcessId() == null;
                return TurnOutcome.terminal(finalText.toString(), waiting);
            }
            messages.add(reply);
            for (ToolExecutionRequest call : reply.toolExecutionRequests()) {
                String result = invokeOne(tools, call, process.getId());
                messages.add(ToolExecutionResultMessage.from(call, result));
            }
        }
        if (!bestFreeText.isEmpty()) {
            log.warn(
                    "Wowbagger id='{}' exceeded {} tool iterations — recovering with best Free-Text",
                    process.getId(),
                    maxIters);
            return TurnOutcome.recovered(bestFreeText);
        }
        throw new AiChatException(
                "Wowbagger exceeded " + maxIters + " tool iterations — no recoverable text, aborting turn.");
    }

    private StreamResult streamOneIteration(
            AiChat aiChat,
            ChatRequest request,
            ThinkEngineContext ctx,
            ThinkProcessDocument process,
            String modelAlias) {
        CompletableFuture<ChatResponse> done = new CompletableFuture<>();
        de.mhus.vance.brain.events.ClientEventPublisher events = ctx.events();
        String sessionId = process.getSessionId();
        long startMs = System.currentTimeMillis();

        de.mhus.vance.brain.events.ChunkBatcher batcher = new de.mhus.vance.brain.events.ChunkBatcher(
                streamingProperties.getChunkCharThreshold(), streamingProperties.getChunkFlushMs(), chunk -> {
                    de.mhus.vance.api.chat.ChatMessageChunkData data =
                            de.mhus.vance.api.chat.ChatMessageChunkData.builder()
                                    .thinkProcessId(process.getId())
                                    .processName(process.getName())
                                    .role(ChatRole.ASSISTANT)
                                    .chunk(chunk)
                                    .build();
                    events.publish(sessionId, de.mhus.vance.api.ws.MessageType.CHAT_MESSAGE_STREAM_CHUNK, data);
                });

        aiChat.streamingChatModel().chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {
                if (partial == null || partial.isEmpty()) {
                    return;
                }
                try {
                    batcher.accept(partial);
                } catch (RuntimeException e) {
                    log.warn("Wowbagger chunk-publish threw: {}", e.toString());
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
            ChatResponse response = done.get(STREAM_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            llmCallTracker.record(process, request, response, System.currentTimeMillis() - startMs, modelAlias);
            AiMessage reply = response.aiMessage();
            return new StreamResult(reply, reply.text() == null ? "" : reply.text());
        } catch (TimeoutException e) {
            done.cancel(true);
            throw new AiChatException("Wowbagger streaming timed out after " + STREAM_TIMEOUT_MINUTES + "m", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new AiChatException("Wowbagger streaming failed: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiChatException("Wowbagger streaming interrupted", e);
        }
    }

    /** Dispatches one tool call; failures are stringified for the model (Ford policy). */
    private String invokeOne(ContextToolsApi tools, ToolExecutionRequest call, String processId) {
        Map<String, Object> params;
        try {
            params = parseArgs(call.arguments());
        } catch (RuntimeException e) {
            log.warn("Wowbagger id='{}' tool='{}' bad arguments: {}", processId, call.name(), e.getMessage());
            return errorJson("Invalid tool arguments: " + e.getMessage());
        }
        try {
            Map<String, Object> result = tools.invoke(call.name(), params);
            return objectMapper.writeValueAsString(result);
        } catch (ToolException e) {
            log.info("Wowbagger id='{}' tool='{}' returned error: {}", processId, call.name(), e.getMessage());
            return errorJson(e);
        } catch (RuntimeException e) {
            log.warn("Wowbagger id='{}' tool='{}' unexpected failure: {}", processId, call.name(), e.toString());
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
        return ToolErrorPayload.json(objectMapper, message);
    }

    private String errorJson(ToolException e) {
        return ToolErrorPayload.json(objectMapper, e);
    }

    private record StreamResult(AiMessage message, String text) {}

    /**
     * Ford's prompt assembly minus the skill section; one addition: the
     * <b>structure status block</b> — a fresh render of the pool structure
     * after the base prompt (dynamic, before the cache boundary matters:
     * it changes every wakeup anyway).
     */
    private List<ChatMessage> buildPromptMessages(
            ThinkProcessDocument process,
            ChatMessageService chatLog,
            List<SteerMessage> inboxExtras,
            ModelInfo modelInfo,
            ModelSize tier,
            ContextToolsApi tools) {
        List<ChatMessage> messages = new ArrayList<>();
        PromptContextBuilder ctxBuilder =
                PromptContextBuilder.forProcess(process, modelInfo).tier(tier).engine(NAME);
        clientTurnContextResolver.resolve(process, inboxExtras).applyTo(ctxBuilder);
        ctxBuilder
                .withRootDirTypes(workspaceService.getRootDirTypes(process.getTenantId(), process.getProjectId()))
                .withAvailableTools(tools.primary());
        String base = composer.compose(process, engineDefaultPrompt(process), ctxBuilder);
        String memoryBlock = memoryContextLoader.composeBlock(process);
        if (memoryBlock != null && !memoryBlock.isBlank()) {
            base = base + "\n\n" + memoryBlock;
        }
        // The structure status — the agent's awareness of "where are we".
        base = base + "\n\n" + statusBlock(process);
        messages.add(SystemMessage.from(base));
        java.util.List<String> hints = tools == null ? java.util.List.of() : tools.activePromptHints();
        if (!hints.isEmpty()) {
            StringBuilder hb = new StringBuilder("## Tool usage notes\n\n");
            for (int i = 0; i < hints.size(); i++) {
                if (i > 0) hb.append("\n\n");
                hb.append(hints.get(i));
            }
            messages.add(SystemMessage.from(hb.toString()));
        }
        for (MemoryDocument m : memoryService.activeByProcessAndKind(
                process.getTenantId(), process.getId(), MemoryKind.ARCHIVED_CHAT)) {
            messages.add(SystemMessage.from("[Conversation summary from earlier turns]\n" + m.getContent()));
        }
        de.mhus.vance.brain.context.PromptDateContextResolver.class.cast(null); // not resolved via injector
        // Date + client-env + scratchpad blocks (Ford behavior, unchanged):
        // these resolvers reached Ford as constructor services; here the
        // guardService alone is injected on top of Ford's set — keep the
        // dynamic blocks via the same instances Wowbagger injected above.
        appendDynamicBlocks(messages, process, modelInfo);
        for (ChatMessageDocument msg : historyStrengthFilter.filter(
                chatLog.activeHistory(process.getTenantId(), process.getSessionId(), process.getId()))) {
            messages.add(de.mhus.vance.brain.chat.ChatHistoryRenderer.toLangchain(msg));
        }
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

    private void appendDynamicBlocks(
            List<ChatMessage> messages, ThinkProcessDocument process, @Nullable ModelInfo modelInfo) {
        // Date + client-env + scratchpad blocks (Ford behavior, unchanged):
        promptDateContextResolver.appendDynamicMessage(messages, process, modelInfo == null ? null : modelInfo.size());
        promptDateContextResolver.appendClientEnvMessage(messages, process);
        scratchpadPromptContributor.appendDynamicMessage(messages, process);
    }

    /**
     * Renders the structure as the prompt status block (§4a.1): what data,
     * ready or running, pointer, threads, failures — everything the agent
     * needs to know where the run stands, without tools.
     */
    private String statusBlock(ThinkProcessDocument process) {
        WowbaggerState s = pool.structure(process.getId());
        boolean running = pool.isRunning(process.getId());
        StringBuilder sb = new StringBuilder("## Wowbagger structure status (mechanical pool)\n\n");
        sb.append("run: ")
                .append(
                        s.isFinished()
                                ? "finished"
                                : running
                                        ? "RUNNING (" + runningThreads(process.getId(), s) + " active threads)"
                                        : "idle/ready")
                .append("\n");
        sb.append("task: ")
                .append(s.getTask() == null ? "(not set yet — ask the user / analyze the source)" : s.getTask())
                .append('\n');
        sb.append("source: ")
                .append(
                        s.getSourcePath() == null
                                ? "(not set yet — ask the user / import into the workspace)"
                                : s.getSourcePath())
                .append(" (input format: ")
                .append(s.getInputFormat() == null ? "?" : s.getInputFormat())
                .append(")\n");
        sb.append("output: ")
                .append(s.getOutputDocPath() == null ? "(default _wowbagger/<run>/result)" : s.getOutputDocPath())
                .append(" (output format: ")
                .append(s.getOutputFormat() == null ? "= input" : s.getOutputFormat())
                .append(")\n");
        sb.append("records: ")
                .append(s.getRecordsDone())
                .append(" committed of ")
                .append(Math.max(s.getRecordsTotal(), 0))
                .append(s.getRecordsTotal() < 0 ? " (not yet measured)" : "")
                .append(", pointer at ")
                .append(s.getPointer())
                .append(", chunk size ")
                .append(s.getChunkSize())
                .append(", ~")
                .append(Math.max(s.getChunksTotal(), 0))
                .append(" chunks total\n");
        sb.append("threads: ")
                .append(runningThreads(process.getId(), s))
                .append(" active / ")
                .append(s.getThreadsDesired())
                .append(" desired; wake the agent every ")
                .append(s.getWakeEveryRecords())
                .append(" records; chunkRetries ")
                .append(s.getChunkRetries())
                .append("\n");
        if (!s.getFailedChunks().isEmpty()) {
            sb.append(
                    "failed chunks (decide: fix the prompt/schema, then re-run failed (wowbagger_start reRunFailed)):\n");
            int limit = Math.min(s.getFailedChunks().size(), 5);
            for (int i = 0; i < limit; i++) {
                WowbaggerState.WaveChunk fc = s.getFailedChunks().get(i);
                sb.append("  - #")
                        .append(fc.getIndex())
                        .append(" (records ")
                        .append(fc.getStartRecord())
                        .append("–")
                        .append(fc.getStartRecord() + fc.getRecordCount() - 1)
                        .append(", ")
                        .append(fc.getAttempts())
                        .append(" attempts): ")
                        .append(fc.getLastError())
                        .append('\n');
            }
            if (s.getFailedChunks().size() > limit) {
                sb.append("  … and ").append(s.getFailedChunks().size() - limit).append(" more\n");
            }
        }
        sb.append("worker model: ")
                .append(s.getResolvedWorkerModel() == null ? "(not resolved yet)" : s.getResolvedWorkerModel())
                .append(" (recipe: ")
                .append(s.getWorkerRecipe() == null ? "wowbagger-worker" : s.getWorkerRecipe())
                .append(", approved: ")
                .append(pool.isWorkerModelApproved(process, s))
                .append(")\n");
        sb.append("source backup: ")
                .append(s.getSourceBackupMb() > 0 ? "on (≤" + s.getSourceBackupMb() + " MB)" : "off")
                .append('\n');
        if (s.getWorkTargetName() != null) {
            sb.append("run root: ").append(s.getWorkTargetName()).append('\n');
        }
        if (s.getFailureCount() > 0) {
            sb.append("unacknowledged failure count: ")
                    .append(s.getFailureCount())
                    .append(" (reset with wowbagger_configure resetFailureCount, or implicitly via a re-run)\n");
        }
        sb.append("\nUser controls: wowbagger_configure (set structure), wowbagger_start / wowbagger_stop, ")
                .append("wowbagger_set_threads (0 halts, N>0 resumes), wowbagger_status exact numbers; ")
                .append("re-run failed chunks with wowbagger_start's reRunFailed.");
        return sb.toString();
    }

    private int runningThreads(String processId, WowbaggerState s) {
        return pool.isRunning(processId) ? s.getThreadsDesired() : 0;
    }

    private @Nullable String renderForLlm(SteerMessage m) {
        if (m instanceof SteerMessage.UserChatInput) {
            return null;
        }
        if (m instanceof SteerMessage.ProcessEvent pe) {
            StringBuilder sb = new StringBuilder();
            sb.append("<process-event type=\"")
                    .append(pe.type().name().toLowerCase(Locale.ROOT))
                    .append("\">");
            if (pe.humanSummary() != null) {
                sb.append(escapeText(pe.humanSummary()));
            }
            sb.append("</process-event>");
            return sb.toString();
        }
        if (m instanceof SteerMessage.ToolResult tr) {
            StringBuilder sb = new StringBuilder();
            sb.append("<tool-result toolName=\"")
                    .append(escapeAttr(tr.toolName()))
                    .append("\">");
            if (tr.error() != null) {
                sb.append("error: ").append(escapeText(tr.error()));
            } else if (tr.result() != null) {
                sb.append(escapeText(tr.result().toString()));
            }
            sb.append("</tool-result>");
            return sb.toString();
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

    private String engineDefaultPrompt(ThinkProcessDocument process) {
        String basePath = paramString(process, "promptDocument", DEFAULT_PROMPT_PATH);
        return enginePromptResolver.resolve(process, basePath, SYSTEM_PROMPT);
    }

    // ──────────────────── engineParams helpers ────────────────────

    private static @Nullable Object param(ThinkProcessDocument process, String key) {
        Map<String, Object> p = process.getEngineParams();
        return p == null ? null : p.get(key);
    }

    private static @Nullable String paramString(ThinkProcessDocument process, String key, @Nullable String fallback) {
        Object v = param(process, key);
        return v instanceof String s && !s.isBlank() ? s : fallback;
    }

    private static int paramInt(ThinkProcessDocument process, String key, int fallback) {
        Object v = param(process, key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.isBlank();
    }
}
