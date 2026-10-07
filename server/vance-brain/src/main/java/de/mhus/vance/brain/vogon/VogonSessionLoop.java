package de.mhus.vance.brain.vogon;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.magrathea.MagratheaProcessDto;
import de.mhus.vance.api.magrathea.MagratheaRunStatus;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiChatException;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.ai.ModelInfo;
import de.mhus.vance.brain.ai.ModelSize;
import de.mhus.vance.brain.events.StreamingProperties;
import de.mhus.vance.brain.magrathea.MagratheaGateChatAnswerService;
import de.mhus.vance.brain.memory.MemoryCompactionService;
import de.mhus.vance.brain.memory.MemoryContextLoader;
import de.mhus.vance.brain.prompt.PromptContextBuilder;
import de.mhus.vance.brain.thinkengine.EnginePromptResolver;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.SystemPromptComposer;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.brain.tools.ToolErrorPayload;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.magrathea.MagratheaStateProjector;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The session-mode agent identity of Vogon — the Ford-adapted chat loop over
 * the Magrathea runner (planning/vogon-agent-identity.md §3.1).
 *
 * <p><b>Adapted from Ford via Wowbagger and Hactar</b> (the loop, streaming,
 * compaction, history strength filtering, guards), simplified deliberately:
 * no skills, no data-relay validation. The identity is the <b>operator of
 * written plans</b>: it starts and stops runs, reports what a run does, and
 * routes plan changes to Slartibartfast — it never executes a state itself
 * (the runner is the machinery) and never edits a plan document (the author
 * is Slart). An open gate belongs to the human: the mechanical chat-answer
 * fast-path runs BEFORE this loop ({@code VogonEngine}, decision F1) and this
 * identity has no tool that could answer a gate.
 *
 * <p><b>No run service of its own</b> — unlike Hactar, where the phase machine
 * had to move to a background service first, the Magrathea runner already
 * lives behind the engine lane. This loop only reads the journal projection
 * for its status block and writes {@code [run]} history notes for the run's
 * ProcessEvents (decision F3: the note survives an LLM failure, the pending
 * event alone does not).
 *
 * <p>The run status is part of the prompt (status block, refreshed per turn)
 * — the agent knows without a tool call where the run stands, including the
 * open gate.
 *
 * <p>Lazy identity: no pending message, no turn — a session-mode process
 * without traffic makes zero LLM calls (the runner works alone).
 */
@Component
@ConditionalOnProperty(value = "vance.services.magrathea", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class VogonSessionLoop {

    private static final String SYSTEM_PROMPT = "You are Vogon — the operator of written plans. "
            + "The runner is your machinery: it drives the plan's states in the background "
            + "while you converse. Speak in the first person and use your tools.";

    private static final String DEFAULT_PROMPT_PATH = "_vance/prompts/vogon-prompt.md";

    /** Same backstop rationale as Ford's/Wowbagger's/Hactar's. */
    private static final int MAX_TOOL_ITERATIONS = 40;

    private static final long STREAM_TIMEOUT_MINUTES = 20;

    private static final int RESULT_PREVIEW_CHARS = 400;

    private final ThinkProcessService thinkProcessService;
    private final ObjectMapper objectMapper;
    private final StreamingProperties streamingProperties;
    private final ModelCatalog modelCatalog;
    private final de.mhus.vance.brain.progress.LlmCallTracker llmCallTracker;
    private final MemoryContextLoader memoryContextLoader;
    private final EnginePromptResolver enginePromptResolver;
    private final SystemPromptComposer composer;
    private final de.mhus.vance.brain.ai.EngineChatFactory engineChatFactory;
    private final MemoryService memoryService;
    private final MemoryCompactionService memoryCompactionService;
    private final de.mhus.vance.brain.context.PromptDateContextResolver promptDateContextResolver;
    private final de.mhus.vance.brain.prompt.ScratchpadPromptContributor scratchpadPromptContributor;
    private final de.mhus.vance.brain.prompt.ClientTurnContextResolver clientTurnContextResolver;
    private final de.mhus.vance.brain.thinkengine.TurnContextHandlerRegistry turnContextHandlers;
    private final de.mhus.vance.brain.guard.ShootyGuardService guardService;
    private final de.mhus.vance.shared.workspace.WorkspaceService workspaceService;
    private final de.mhus.vance.brain.prak.HistoryStrengthFilter historyStrengthFilter;
    private final MagratheaStateProjector projector;
    private final MagratheaGateChatAnswerService gateChatAnswerService;

    // ──────────────────── Turn outcome (engine contract) ────────────────────

    /**
     * What one agent turn ended with — the engine maps this to the process
     * exit status (BLOCKED when awaiting input, IDLE otherwise).
     */
    public record TurnOutcome(
            String finalText, boolean awaitingUserInput, boolean interrupted, boolean interruptForcePause) {}

    // ──────────────────── One turn (Ford-adapted) ────────────────────

    /**
     * Runs one agent turn over the drained inbox batch. Mirrors
     * {@code HactarSessionLoop.turnFor}: engine status RUNNING, guards, one
     * LLM tool loop, reply persistence and emission, exit status in the
     * finally block. Run events in the batch are written to the history as
     * {@code [run]} notes by {@link #splitInbox} (decision F3) and ride the
     * turn as extras.
     */
    public TurnOutcome turnFor(ThinkProcessDocument process, ThinkEngineContext ctx, List<SteerMessage> inbox) {
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.RUNNING);
        guardService.guardsOnTurnStart(process, inbox);
        boolean awaitingUserInput = false;
        boolean interrupted = false;
        try {
            ChatMessageService chatLog = ctx.chatMessageService();
            List<SteerMessage> extras = splitInbox(chatLog, process, inbox);

            de.mhus.vance.brain.ai.EngineChatFactory.EngineChatBundle chatBundle =
                    engineChatFactory.forProcess(process, ctx, VogonEngine.NAME);
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
                        "Vogon.turn id='{}' compaction ok: {} msgs → {} chars",
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
                        "Vogon.turn id='{}' interrupted (forcePause={}) — parking, no answer surfaced",
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
            log.info("Vogon.turn id='{}' awaiting={} -> '{}'", process.getId(), awaitingUserInput, preview);
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
     * Splits the drained inbox for the turn (package-private for testing):
     * real user input is appended to the chat log — the engine owns that
     * append, the WS steer path only queues — and non-UCI items
     * (ProcessEvent, ToolResult, …) become turn-local extras.
     *
     * <p><b>Run events get a {@code [run]} history note</b> (decision F3):
     * the Magrathea runner wakes the owner through a pending ProcessEvent
     * only — a trigger, not a transcript. This loop writes the durable copy,
     * so {@code process_history_text} forensics see when the run began
     * waiting or ended, and an agent turn that fails on an LLM error still
     * leaves the terminal in the conversation. Best-effort by construction:
     * a note failure must not kill the turn.
     */
    static List<SteerMessage> splitInbox(
            ChatMessageService chatLog, ThinkProcessDocument process, List<SteerMessage> inbox) {
        List<SteerMessage> extras = new ArrayList<>();
        for (SteerMessage m : inbox) {
            if (m instanceof SteerMessage.UserChatInput uci) {
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
                if (m instanceof SteerMessage.ProcessEvent event) {
                    appendRunNote(chatLog, process, event);
                }
                extras.add(m);
            }
        }
        return extras;
    }

    private static void appendRunNote(
            ChatMessageService chatLog, ThinkProcessDocument process, SteerMessage.ProcessEvent event) {
        String note = event.humanSummary() == null || event.humanSummary().isBlank()
                ? "run reported " + String.valueOf(event.type()).toLowerCase(Locale.ROOT)
                : event.humanSummary();
        try {
            chatLog.append(ChatMessageDocument.builder()
                    .tenantId(process.getTenantId())
                    .sessionId(process.getSessionId())
                    .thinkProcessId(process.getId())
                    .role(ChatRole.ASSISTANT)
                    .content("[run] " + note)
                    .build());
        } catch (RuntimeException e) {
            log.warn("Vogon id='{}' [run] note failed: {}", process.getId(), e.toString());
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
                log.info("Vogon id='{}' tool-loop interrupt (status={}) — exiting", process.getId(), liveStatus);
                return new ToolLoopResult("", false, true, false);
            }
            if (thinkProcessService.isHaltRequested(process.getId())) {
                log.info("Vogon id='{}' tool-loop halt requested — exiting (PAUSED)", process.getId());
                // Deliberately NOT clearing the halt flag (Arthur/Hactar
                // parity): the pause lane task owns the clearing — clearing
                // it here would let the engine's drain-loop head re-drain
                // any message that arrived mid-turn into a fresh LLM turn
                // despite the pause.
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
                            "Vogon id='{}' tool-loop LLM failure ({}) — recovering with best Free-Text ({} chars)",
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
                    "Vogon id='{}' exceeded {} tool iterations — recovering with best Free-Text",
                    process.getId(),
                    maxIters);
            return new ToolLoopResult(bestFreeText, true, false, false);
        }
        throw new AiChatException(
                "Vogon exceeded " + maxIters + " tool iterations — no recoverable text, aborting turn.");
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
                    log.warn("Vogon chunk-publish threw: {}", e.toString());
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
            throw new AiChatException("Vogon streaming timed out after " + STREAM_TIMEOUT_MINUTES + "m", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new AiChatException("Vogon streaming failed: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiChatException("Vogon streaming interrupted", e);
        }
    }

    /** Dispatches one tool call; failures are stringified for the model (Ford policy). */
    private String invokeOne(ContextToolsApi tools, ToolExecutionRequest call, String processId) {
        Map<String, Object> params;
        try {
            params = parseArgs(call.arguments());
        } catch (RuntimeException e) {
            log.warn("Vogon id='{}' tool='{}' bad arguments: {}", processId, call.name(), e.getMessage());
            return errorJson("Invalid tool arguments: " + e.getMessage());
        }
        try {
            Map<String, Object> result = tools.invoke(call.name(), params);
            return objectMapper.writeValueAsString(result);
        } catch (ToolException e) {
            log.info("Vogon id='{}' tool='{}' returned error: {}", processId, call.name(), e.getMessage());
            return errorJson(e);
        } catch (RuntimeException e) {
            log.warn("Vogon id='{}' tool='{}' unexpected failure: {}", processId, call.name(), e.toString());
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
     * <b>run status block</b> — a fresh render of the journal projection
     * after the base prompt (dynamic, before the cache boundary matters: it
     * changes on every wakeup anyway).
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
                PromptContextBuilder.forProcess(process, modelInfo).tier(tier).engine(VogonEngine.NAME);
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
        // announced here by name + hint (Hactar/Arthur parity).
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
     * Renders the run status as the prompt status block: run state, plan
     * name, current state, elapsed time, open gate, last result — everything
     * the agent needs to answer "how is it going?" without a tool call. The
     * journal projection is the authority (the run is the authority on
     * itself; a copy kept here could only disagree — same rule as
     * {@code summarizeForParent}).
     */
    private String statusBlock(ThinkProcessDocument process) {
        // Fresh load (Hactar Review-16 L7 parity): the turn's process
        // document may hold a stale engineParams snapshot — rememberRunId
        // wrote the run id after this document was loaded.
        ThinkProcessDocument fresh =
                thinkProcessService.findById(process.getId()).orElse(process);
        String runId = VogonBaseTool.runId(fresh);
        StringBuilder sb = new StringBuilder("## Your run — current plan state\n\n");
        sb.append("run: ");
        if (runId == null) {
            sb.append("no plan yet (start one with vogon_start — the user may name the plan, "
                    + "a document path, or just describe what should happen)\n");
        } else {
            Optional<MagratheaProcessDto> run = projector.project(fresh.getTenantId(), fresh.getProjectId(), runId);
            if (run.isEmpty()) {
                sb.append("run '").append(runId).append("' left no journal (pruned or foreign)\n");
            } else {
                MagratheaProcessDto dto = run.get();
                sb.append(describeRun(dto)).append('\n');
                sb.append("plan: ")
                        .append(dto.getWorkflowName())
                        .append(" (run ")
                        .append(runId)
                        .append(")\n");
                if (dto.getCurrentState() != null) {
                    sb.append("current state: ").append(dto.getCurrentState()).append('\n');
                }
                if (isTerminal(dto.getStatus())
                        && dto.getResult() != null
                        && !dto.getResult().isEmpty()) {
                    sb.append("last result: ")
                            .append(preview(renderValue(dto.getResult())))
                            .append('\n');
                }
                gateChatAnswerService
                        .findOpenGateItem(fresh.getTenantId(), runId)
                        .ifPresent(item -> {
                            sb.append("open gate: ").append(item.getType());
                            if (item.getTitle() != null && !item.getTitle().isBlank()) {
                                sb.append(" '").append(item.getTitle()).append("'");
                            }
                            sb.append(" — waiting for the HUMAN to answer (in this conversation or via the inbox "
                                    + "form). You explain and remind; you never answer it for them.\n");
                        });
            }
        }
        sb.append("\nUser controls: vogon_start (workflow name or workflowPath, plus plan params — a new "
                + "start requires the previous run stopped), vogon_stop (halt the run; partial task side "
                + "effects are possible), vogon_status (fuller read on demand). Plan changes go through "
                + "a Slartibartfast spawn (current plan + the user's request → new plan version) — you "
                + "never edit a plan document yourself.");
        return sb.toString();
    }

    private static String describeRun(MagratheaProcessDto dto) {
        MagratheaRunStatus status = dto.getStatus();
        if (status == null) return "unknown";
        return switch (status) {
            case RUNNING -> "live (" + elapsedSeconds(dto) + "s elapsed)";
            case PAUSED -> "paused (interrupted mid-run — resume it or start fresh)";
            case DONE -> "finished";
            case FAILED -> "failed";
            case TERMINATED -> "stopped";
        };
    }

    private static boolean isTerminal(@Nullable MagratheaRunStatus status) {
        return status == MagratheaRunStatus.DONE
                || status == MagratheaRunStatus.FAILED
                || status == MagratheaRunStatus.TERMINATED;
    }

    private static String elapsedSeconds(MagratheaProcessDto dto) {
        if (dto.getCreatedAt() == null) return "?";
        Duration d = Duration.between(dto.getCreatedAt(), Instant.now());
        return String.valueOf(d.isNegative() ? 0 : d.getSeconds());
    }

    private String renderValue(Object value) {
        if (value == null) return "(no result)";
        try {
            return "```json\n" + objectMapper.writeValueAsString(value) + "\n```";
        } catch (RuntimeException e) {
            return String.valueOf(value);
        }
    }

    private static String preview(String s) {
        String trimmed = s.strip();
        return trimmed.length() > RESULT_PREVIEW_CHARS ? trimmed.substring(0, RESULT_PREVIEW_CHARS) + "…" : trimmed;
    }

    /**
     * Renders one payload value for the {@code <process-event>} machine-facts
     * block: scalars verbatim, lists of scalars comma-joined. Anything
     * nested (maps, lists of objects) is skipped — the block is for
     * engine-verified handles, not a generic JSON dump.
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

    static @Nullable String renderForLlm(SteerMessage m) {
        if (m instanceof SteerMessage.UserChatInput) {
            return null;
        }
        if (m instanceof SteerMessage.ProcessEvent pe) {
            StringBuilder sb = new StringBuilder();
            sb.append("<process-event type=\"")
                    .append(pe.type().name().toLowerCase(Locale.ROOT))
                    .append("\">");
            // Machine facts FIRST, the child's human summary second
            // (Hactar Live-Fund 8): the payload carries the engine-verified
            // truth and must never be dropped.
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
}
