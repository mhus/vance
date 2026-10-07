package de.mhus.vance.brain.thinkengine;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiChatException;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.ai.ModelInfo;
import de.mhus.vance.brain.ai.ModelSize;
import de.mhus.vance.brain.context.PromptDateContextResolver;
import de.mhus.vance.brain.events.StreamingProperties;
import de.mhus.vance.brain.guard.ShootyGuardService;
import de.mhus.vance.brain.memory.MemoryCompactionService;
import de.mhus.vance.brain.memory.MemoryContextLoader;
import de.mhus.vance.brain.prak.HistoryStrengthFilter;
import de.mhus.vance.brain.progress.LlmCallTracker;
import de.mhus.vance.brain.prompt.ClientTurnContextResolver;
import de.mhus.vance.brain.prompt.PromptContextBuilder;
import de.mhus.vance.brain.prompt.ScratchpadPromptContributor;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.brain.tools.ToolErrorPayload;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.memory.MemoryDocument;
import de.mhus.vance.shared.memory.MemoryKind;
import de.mhus.vance.shared.memory.MemoryService;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

/**
 * The shared control loop of the session-mode engine identities — the
 * template-method base of {@code HactarSessionLoop},
 * {@code VogonSessionLoop} and {@code MarvinSessionLoop}
 * (planning/session-loop-extraction.md, decision F1).
 *
 * <p>Everything a Ford-adapted agent turn is made of lives here, final:
 * the turn shell (status RUNNING, guards, inbox split, engine-chat
 * bundle, compaction, tool loop, reply persistence and emission, exit
 * status in the finally), the tool loop (status/halt interrupts,
 * best-free-text recovery, iteration backstop), streaming, prompt
 * assembly and the {@code <process-event>} rendering. The subclasses
 * supply what is genuinely theirs: the engine name (logs, chat factory,
 * prompt builder), the persona fallback prompt and its document path,
 * the status block — their engine's authority projection — and the two
 * {@link #splitInbox} predicates.
 *
 * <p>Precedent: {@code AbstractNutrimat} (turn shell with a closed outer
 * contract). Zaphod's session mode deliberately does NOT extend this —
 * its identity is a synthesizer over head replies (fold → fan-out → one
 * synthesis call), a different control model (planning §1).
 *
 * <p>Lazy identity (all three loops): no pending message, no turn — an
 * identity without traffic makes zero LLM calls.
 */
@Slf4j
public abstract class AbstractEngineSessionLoop {

    /** Same backstop rationale as Ford's/Wowbagger's. */
    private static final int MAX_TOOL_ITERATIONS = 40;

    private static final long STREAM_TIMEOUT_MINUTES = 20;

    protected final ThinkProcessService thinkProcessService;
    protected final ObjectMapper objectMapper;
    private final StreamingProperties streamingProperties;
    private final ModelCatalog modelCatalog;
    private final LlmCallTracker llmCallTracker;
    private final MemoryContextLoader memoryContextLoader;
    private final EnginePromptResolver enginePromptResolver;
    private final SystemPromptComposer composer;
    private final de.mhus.vance.brain.ai.EngineChatFactory engineChatFactory;
    private final MemoryService memoryService;
    private final MemoryCompactionService memoryCompactionService;
    private final PromptDateContextResolver promptDateContextResolver;
    private final ScratchpadPromptContributor scratchpadPromptContributor;
    private final ClientTurnContextResolver clientTurnContextResolver;
    private final TurnContextHandlerRegistry turnContextHandlers;
    private final ShootyGuardService guardService;
    private final WorkspaceService workspaceService;
    private final HistoryStrengthFilter historyStrengthFilter;

    protected AbstractEngineSessionLoop(
            ThinkProcessService thinkProcessService,
            ObjectMapper objectMapper,
            StreamingProperties streamingProperties,
            ModelCatalog modelCatalog,
            LlmCallTracker llmCallTracker,
            MemoryContextLoader memoryContextLoader,
            EnginePromptResolver enginePromptResolver,
            SystemPromptComposer composer,
            de.mhus.vance.brain.ai.EngineChatFactory engineChatFactory,
            MemoryService memoryService,
            MemoryCompactionService memoryCompactionService,
            PromptDateContextResolver promptDateContextResolver,
            ScratchpadPromptContributor scratchpadPromptContributor,
            ClientTurnContextResolver clientTurnContextResolver,
            TurnContextHandlerRegistry turnContextHandlers,
            ShootyGuardService guardService,
            WorkspaceService workspaceService,
            HistoryStrengthFilter historyStrengthFilter) {
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
        this.promptDateContextResolver = promptDateContextResolver;
        this.scratchpadPromptContributor = scratchpadPromptContributor;
        this.clientTurnContextResolver = clientTurnContextResolver;
        this.turnContextHandlers = turnContextHandlers;
        this.guardService = guardService;
        this.workspaceService = workspaceService;
        this.historyStrengthFilter = historyStrengthFilter;
    }

    // ──────────────────── Engine hooks ────────────────────

    /** Engine name — log prefixes, chat factory, prompt builder. */
    protected abstract String engineName();

    /** Embedded fallback system prompt, used when the document cascade has none. */
    protected abstract String fallbackSystemPrompt();

    /** Document-cascade base path for this identity's prompt document. */
    protected abstract String defaultPromptPath();

    /**
     * The engine's authority projection, rendered fresh into every turn's
     * prompt (phase state, journal, node documents — whatever the engine
     * is the authority on). Everything the agent needs to answer
     * "how is it going?" without a tool call.
     */
    protected abstract String statusBlock(ThinkProcessDocument process);

    /**
     * Which chat inputs the split skips. Hactar drops its run service's
     * synthetic wakeups (the run service wrote its own history note — the
     * pending copy is only the trigger); the others skip nothing.
     */
    protected Predicate<SteerMessage.UserChatInput> skipUserInput() {
        return uci -> false;
    }

    /**
     * Best-effort history-note writer for the batch's ProcessEvents (the
     * {@code [run]}/{@code [tree]} notes, decision F3 of the engine
     * plans). {@code null} = this engine's notes come from elsewhere
     * (Hactar: the engine/run service writes them itself).
     */
    protected @Nullable EventNoteWriter eventNoteWriter() {
        return null;
    }

    /** Writes one durable history note for a tree/run event. Best-effort by contract. */
    @FunctionalInterface
    public interface EventNoteWriter {
        void append(ChatMessageService chatLog, ThinkProcessDocument process, SteerMessage.ProcessEvent event);
    }

    // ──────────────────── Turn outcome (engine contract) ────────────────────

    /**
     * What one agent turn ended with — the engine maps this to the process
     * exit status (BLOCKED when awaiting input, IDLE otherwise) and the
     * pause handling.
     */
    public record TurnOutcome(
            String finalText, boolean awaitingUserInput, boolean interrupted, boolean interruptForcePause) {}

    // ──────────────────── One turn (Ford-adapted) ────────────────────

    /**
     * Runs one agent turn over the drained inbox batch: engine status
     * RUNNING, guards, inbox split, engine-chat bundle, compaction, one
     * LLM tool loop, reply persistence and emission, exit status in the
     * finally block. Non-UCI items become turn-local extras — the
     * engine-specific split predicates decide which inputs are skipped
     * and which events get a durable note (decision F3).
     */
    public TurnOutcome turnFor(ThinkProcessDocument process, ThinkEngineContext ctx, List<SteerMessage> inbox) {
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.RUNNING);
        guardService.guardsOnTurnStart(process, inbox);
        boolean awaitingUserInput = false;
        boolean interrupted = false;
        try {
            ChatMessageService chatLog = ctx.chatMessageService();
            List<SteerMessage> extras = splitInbox(chatLog, process, inbox, skipUserInput(), eventNoteWriter());

            de.mhus.vance.brain.ai.EngineChatFactory.EngineChatBundle chatBundle =
                    engineChatFactory.forProcess(process, ctx, engineName());
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
                        "{}.turn id='{}' compaction ok: {} msgs → {} chars",
                        engineName(),
                        process.getId(),
                        compactResult.messagesCompacted(),
                        compactResult.summaryChars());
                messages = buildPromptMessages(process, chatLog, extras, modelInfo, effectiveSize, tools);
            }

            int maxIters = paramInt(process, "maxIterations", MAX_TOOL_ITERATIONS);
            String modelAlias = config.providerInstance() + ":" + config.modelName();
            ToolLoopResult result = runToolLoop(aiChat, toolSpecs, tools, messages, ctx, process, maxIters, modelAlias);
            if (result.interrupted()) {
                interrupted = true;
                ctx.historyTagSink().discard();
                log.info(
                        "{}.turn id='{}' interrupted (forcePause={}) — parking, no answer surfaced",
                        engineName(),
                        process.getId(),
                        result.interruptForcePause());
                return new TurnOutcome("", false, true, result.interruptForcePause());
            }
            awaitingUserInput = result.awaitingUserInput();
            String finalText = result.finalText();

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
            log.info("{}.turn id='{}' awaiting={} -> '{}'", engineName(), process.getId(), awaitingUserInput, preview);
            return new TurnOutcome(finalText, awaitingUserInput, false, false);
        } finally {
            if (!interrupted) {
                ThinkProcessStatus exitStatus =
                        awaitingUserInput ? ThinkProcessStatus.BLOCKED : ThinkProcessStatus.IDLE;
                thinkProcessService.updateStatus(process.getId(), exitStatus);
            }
        }
    }

    /**
     * Splits the drained inbox for the turn: real user input is appended
     * to the chat log — the engine owns that append, the WS steer path only
     * queues — and non-UCI items (ProcessEvent, ToolResult, …) become
     * turn-local extras.
     *
     * <p><b>Event notes (decision F3 of the engine plans)</b>: the pending
     * event is only the wake trigger, not a transcript. A non-null
     * {@code eventNoteWriter} writes the durable copy — so
     * {@code process_history_text} forensics see when the run began
     * waiting or ended, and an agent turn that fails on an LLM error still
     * leaves the terminal in the conversation. Best-effort by construction:
     * a note failure must not kill the turn (the writer swallows).
     *
     * <p>{@code skipUserInput} drops synthetic inputs the engine's own
     * machinery already wrote to the history (Hactar's run-service
     * wakeups) — appending them again would show every wakeup twice in
     * the transcript and mislabel engine output as the user's.
     */
    public static List<SteerMessage> splitInbox(
            ChatMessageService chatLog,
            ThinkProcessDocument process,
            List<SteerMessage> inbox,
            Predicate<SteerMessage.UserChatInput> skipUserInput,
            @Nullable EventNoteWriter eventNoteWriter) {
        List<SteerMessage> extras = new ArrayList<>();
        for (SteerMessage m : inbox) {
            if (m instanceof SteerMessage.UserChatInput uci) {
                if (skipUserInput.test(uci)) {
                    continue;
                }
                if (uci.content() != null && !uci.content().isBlank()) {
                    chatLog.append(ChatMessageDocument.builder()
                            .tenantId(process.getTenantId())
                            .sessionId(process.getSessionId())
                            .thinkProcessId(process.getId())
                            .role(ChatRole.USER)
                            .content(uci.content())
                            .build());
                }
            } else {
                if (eventNoteWriter != null && m instanceof SteerMessage.ProcessEvent event) {
                    eventNoteWriter.append(chatLog, process, event);
                }
                extras.add(m);
            }
        }
        return extras;
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

    // ──────────────────── Tool loop ────────────────────

    private record ToolLoopResult(
            String finalText, boolean awaitingUserInput, boolean interrupted, boolean interruptForcePause) {}

    private ToolLoopResult runToolLoop(
            AiChat aiChat,
            List<ToolSpecification> toolSpecs,
            ContextToolsApi tools,
            List<ChatMessage> messages,
            ThinkEngineContext ctx,
            ThinkProcessDocument process,
            int maxIters,
            String modelAlias) {
        String engine = engineName();
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
                log.info("{} id='{}' tool-loop interrupt (status={}) — exiting", engine, process.getId(), liveStatus);
                return new ToolLoopResult("", false, true, false);
            }
            if (thinkProcessService.isHaltRequested(process.getId())) {
                log.info("{} id='{}' tool-loop halt requested — exiting (PAUSED)", engine, process.getId());
                // Deliberately NOT clearing the halt flag (Arthur parity,
                // Review-16 M3): the pause lane task owns the clearing —
                // SessionLifecycleService.requestPauseOfInterruptible sets
                // the flag, then queues a PAUSED task that clears it.
                // Clearing it here would let the engine's drain-loop head
                // re-drain any message that arrived mid-turn into a fresh
                // LLM turn despite the pause (the Live-Fund 7 race, back
                // door).
                return new ToolLoopResult("", false, true, true);
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
                            "{} id='{}' tool-loop LLM failure ({}) — recovering with best Free-Text ({} chars)",
                            engine,
                            process.getId(),
                            e.toString(),
                            bestFreeText.length());
                    return new ToolLoopResult(bestFreeText, true, false, false);
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
                // A chat-form identity awaits its user; a steered worker
                // answers the parent and goes back to idle.
                boolean waiting = process.getParentProcessId() == null;
                return new ToolLoopResult(finalText.toString(), waiting, false, false);
            }
            messages.add(reply);
            for (ToolExecutionRequest call : reply.toolExecutionRequests()) {
                String result = invokeOne(tools, call, process.getId());
                messages.add(ToolExecutionResultMessage.from(call, result));
            }
        }
        if (!bestFreeText.isEmpty()) {
            log.warn(
                    "{} id='{}' exceeded {} tool iterations — recovering with best Free-Text",
                    engine,
                    process.getId(),
                    maxIters);
            return new ToolLoopResult(bestFreeText, true, false, false);
        }
        throw new AiChatException(
                engine + " exceeded " + maxIters + " tool iterations — no recoverable text, aborting turn.");
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
                    log.warn("{} chunk-publish threw: {}", engineName(), e.toString());
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
            throw new AiChatException(engineName() + " streaming timed out after " + STREAM_TIMEOUT_MINUTES + "m", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new AiChatException(engineName() + " streaming failed: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiChatException(engineName() + " streaming interrupted", e);
        }
    }

    /** Dispatches one tool call; failures are stringified for the model (Ford policy). */
    private String invokeOne(ContextToolsApi tools, ToolExecutionRequest call, String processId) {
        Map<String, Object> params;
        try {
            params = parseArgs(call.arguments());
        } catch (RuntimeException e) {
            log.warn("{} id='{}' tool='{}' bad arguments: {}", engineName(), processId, call.name(), e.getMessage());
            return errorJson("Invalid tool arguments: " + e.getMessage());
        }
        try {
            Map<String, Object> result = tools.invoke(call.name(), params);
            return objectMapper.writeValueAsString(result);
        } catch (ToolException e) {
            log.info("{} id='{}' tool='{}' returned error: {}", engineName(), processId, call.name(), e.getMessage());
            return errorJson(e);
        } catch (RuntimeException e) {
            log.warn("{} id='{}' tool='{}' unexpected failure: {}", engineName(), processId, call.name(), e.toString());
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

    // ──────────────────── Prompt assembly ────────────────────

    /**
     * Ford's prompt assembly minus the skill section; one addition: the
     * engine's <b>status block</b> after the base prompt (dynamic, before
     * the cache boundary matters: it changes on every wakeup anyway).
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
                PromptContextBuilder.forProcess(process, modelInfo).tier(tier).engine(engineName());
        clientTurnContextResolver.resolve(process, inboxExtras).applyTo(ctxBuilder);
        ctxBuilder
                .withRootDirTypes(workspaceService.getRootDirTypes(process.getTenantId(), process.getProjectId()))
                .withAvailableTools(tools.primary());
        String base = composer.compose(process, engineDefaultPrompt(process), ctxBuilder);
        String memoryBlock = memoryContextLoader.composeBlock(process);
        if (memoryBlock != null && !memoryBlock.isBlank()) {
            base = base + "\n\n" + memoryBlock;
        }
        base = base + "\n\n" + statusBlock(process);
        // Deferred-tool discovery (the §14 contract): tools held back from
        // the manifest — by design or by budget — stay callable and are
        // announced here by name + hint.
        String discoveryBlock = tools == null ? "" : tools.discoveryBlockMarkdown();
        if (!discoveryBlock.isBlank()) {
            base = base + discoveryBlock;
        }
        messages.add(SystemMessage.from(base));
        // Budget-demoted half is DYNAMIC on purpose — folding it into the
        // cached prefix would bust the cache marker whenever the ranking
        // shifts (same reasoning as ArthurEngine).
        String demotedBlock = tools == null ? "" : tools.demotedDiscoveryBlockMarkdown();
        if (!demotedBlock.isBlank()) {
            messages.add(de.mhus.vance.brain.ai.VanceSystemMessage.dynamic(demotedBlock));
        }
        List<String> hints = tools == null ? List.of() : tools.activePromptHints();
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
        promptDateContextResolver.appendDynamicMessage(messages, process, modelInfo == null ? null : modelInfo.size());
        promptDateContextResolver.appendClientEnvMessage(messages, process);
        scratchpadPromptContributor.appendDynamicMessage(messages, process);
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

    /**
     * Renders one payload value for the {@code <process-event>} machine-facts
     * block: scalars verbatim, lists of scalars comma-joined. Anything nested
     * is skipped — the block is for engine-verified handles, not a generic
     * JSON dump.
     */
    private static @Nullable String renderPayloadValue(@Nullable Object value) {
        if (value == null) return null;
        if (value instanceof String s) {
            return s.isBlank() ? null : s;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof java.util.Collection<?> c) {
            java.util.List<String> items = new ArrayList<>();
            for (Object item : c) {
                if (item instanceof String s && !s.isBlank()) items.add(s);
                else if (item instanceof Number || item instanceof Boolean) items.add(String.valueOf(item));
            }
            return items.isEmpty() ? null : String.join(", ", items);
        }
        return null;
    }

    /**
     * Renders one steer message for the LLM. User input returns
     * {@code null} — it is chat history by then; ProcessEvents carry the
     * {@code <process-event>} block with the machine facts FIRST (the
     * payload is the engine-verified truth and must never be dropped, the
     * human summary is the child LLM's own description of what it did and
     * can be plain wrong); ToolResults render as {@code <tool-result>}.
     */
    public static @Nullable String renderForLlm(SteerMessage m) {
        if (m instanceof SteerMessage.UserChatInput) {
            return null;
        }
        if (m instanceof SteerMessage.ProcessEvent pe) {
            StringBuilder sb = new StringBuilder();
            sb.append("<process-event type=\"")
                    .append(pe.type().name().toLowerCase(Locale.ROOT))
                    .append("\">");
            if (pe.payload() != null && !pe.payload().isEmpty()) {
                sb.append("machine facts:\n");
                for (Map.Entry<String, Object> e : pe.payload().entrySet()) {
                    String rendered = renderPayloadValue(e.getValue());
                    if (rendered != null) {
                        sb.append("  ")
                                .append(e.getKey())
                                .append(": ")
                                .append(escapeText(rendered))
                                .append('\n');
                    }
                }
            }
            if (pe.humanSummary() != null) {
                if (pe.humanSummary().isBlank()) {
                    sb.append("report: (none)\n");
                } else {
                    sb.append("report: ").append(escapeText(pe.humanSummary())).append('\n');
                }
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
        String basePath = paramString(process, "promptDocument", defaultPromptPath());
        return enginePromptResolver.resolve(process, basePath, fallbackSystemPrompt());
    }

    /** Preview helper for the status blocks — capped with an ellipsis. */
    protected static String preview(String s, int max) {
        String trimmed = s.strip();
        return trimmed.length() > max ? trimmed.substring(0, max) + "…" : trimmed;
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
}
