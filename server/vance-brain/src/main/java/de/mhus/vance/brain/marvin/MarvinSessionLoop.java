package de.mhus.vance.brain.marvin;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.marvin.NodeStatus;
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
import de.mhus.vance.brain.thinkengine.SystemPromptComposer;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.brain.tools.ToolErrorPayload;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.inbox.MaximegalonService;
import de.mhus.vance.shared.marvin.MarvinNodeDocument;
import de.mhus.vance.shared.marvin.MarvinNodeService;
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
import java.util.Optional;
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
 * The session-mode agent identity of Marvin — the Ford-adapted chat loop
 * over the task-tree machine (planning/marvin-agent-identity.md §3.1).
 *
 * <p><b>Adapted from Ford via Vogon</b> (the loop, streaming, compaction,
 * history strength filtering, guards), deliberately slim: no gate block,
 * no console — everything Marvin's identity does not need. The identity
 * is the <b>supervisor of the deep-think tree</b>: it starts and stops
 * runs, relays open inbox questions, narrates the terminal result in the
 * chat (the live finding: a parentless Marvin's result never reached the
 * chat — {@code emitFinalReplyIfTreeTerminal} skips parentless processes
 * and the process closed DONE) and takes the next goal. It never executes
 * a node phase and never edits a tree node — the tree is the authority on
 * its own work.
 *
 * <p><b>No run service of its own</b> — the tree driver is already
 * turn-decoupled (one node phase per runTurn, scheduleTurn self-drive,
 * parking on external waits), so the engine lane is free between steps.
 * This loop reads the node documents for its status block and writes
 * {@code [tree]} history notes for the tree's ProcessEvents (decision F3:
 * the terminal survives an LLM failure and stays queryable via
 * {@code process_history_text}).
 *
 * <p>Lazy identity: no pending user message, no turn — the tree works
 * alone; the identity costs only its own turns.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MarvinSessionLoop {

    private static final String SYSTEM_PROMPT = "You are Marvin — the supervisor of the deep-think "
            + "tree. The node machine is your body: it walks the task tree in the background "
            + "while you converse. Speak in the first person and use your tools.";

    private static final String DEFAULT_PROMPT_PATH = "_vance/prompts/marvin-prompt.md";

    /** Same backstop rationale as Ford's/Wowbagger's/Hactar's/Vogon's. */
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
    private final MarvinNodeService nodeService;
    private final MaximegalonService inboxItemService;

    // ──────────────────── Turn outcome (engine contract) ────────────────────

    /**
     * What one agent turn ended with — the engine maps this to the process
     * exit status and the pause handling (Vogon parity).
     */
    public record TurnOutcome(
            String finalText, boolean awaitingUserInput, boolean interrupted, boolean interruptForcePause) {}

    // ──────────────────── One turn (Ford-adapted) ────────────────────

    /**
     * Runs one agent turn over the drained inbox batch. Mirrors
     * {@code VogonSessionLoop.turnFor}: engine status RUNNING, guards, one
     * LLM tool loop, reply persistence and emission, exit status in the
     * finally block. Tree and child events in the batch are written to the
     * history as {@code [tree]} notes by {@link #splitInbox} (decision F3)
     * and ride the turn as extras.
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
                    engineChatFactory.forProcess(process, ctx, MarvinEngine.NAME);
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
                        "Marvin.turn id='{}' compaction ok: {} msgs → {} chars",
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
                        "Marvin.turn id='{}' interrupted (forcePause={}) — parking, no answer surfaced",
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
            log.info("Marvin.turn id='{}' awaiting={} -> '{}'", process.getId(), awaitingUserInput, preview);
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
     * <p><b>Tree events get a {@code [tree]} history note</b> (decision F3):
     * the pending event is only the wake trigger, not a transcript. This
     * loop writes the durable copy — including the terminal result the
     * engine hands over — so {@code process_history_text} forensics see
     * what the run produced, and an agent turn that fails on an LLM error
     * still leaves the result in the conversation. Best-effort by
     * construction: a note failure must not kill the turn.
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
                    appendTreeNote(chatLog, process, event);
                }
                extras.add(m);
            }
        }
        return extras;
    }

    private static void appendTreeNote(
            ChatMessageService chatLog, ThinkProcessDocument process, SteerMessage.ProcessEvent event) {
        String note = event.humanSummary() == null || event.humanSummary().isBlank()
                ? "tree reported " + String.valueOf(event.type()).toLowerCase(Locale.ROOT)
                : event.humanSummary();
        try {
            chatLog.append(ChatMessageDocument.builder()
                    .tenantId(process.getTenantId())
                    .sessionId(process.getSessionId())
                    .thinkProcessId(process.getId())
                    .role(ChatRole.ASSISTANT)
                    .content("[tree] " + note)
                    .build());
        } catch (RuntimeException e) {
            log.warn("Marvin id='{}' [tree] note failed: {}", process.getId(), e.toString());
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
                log.info("Marvin id='{}' tool-loop interrupt (status={}) — exiting", process.getId(), liveStatus);
                return new ToolLoopResult("", false, true, false);
            }
            if (thinkProcessService.isHaltRequested(process.getId())) {
                log.info("Marvin id='{}' tool-loop halt requested — exiting (PAUSED)", process.getId());
                // Deliberately NOT clearing the halt flag (Arthur/Hactar/Vogon
                // parity): the pause lane task owns the clearing — clearing
                // it here would let the engine's drain-loop head re-drain any
                // message that arrived mid-turn into a fresh LLM turn despite
                // the pause.
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
                            "Marvin id='{}' tool-loop LLM failure ({}) — recovering with best Free-Text ({} chars)",
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
                    "Marvin id='{}' exceeded {} tool iterations — recovering with best Free-Text",
                    process.getId(),
                    maxIters);
            return new ToolLoopResult(bestFreeText, true, false, false);
        }
        throw new AiChatException(
                "Marvin exceeded " + maxIters + " tool iterations — no recoverable text, aborting turn.");
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
                    log.warn("Marvin chunk-publish threw: {}", e.toString());
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
            throw new AiChatException("Marvin streaming timed out after " + STREAM_TIMEOUT_MINUTES + "m", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new AiChatException("Marvin streaming failed: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiChatException("Marvin streaming interrupted", e);
        }
    }

    /** Dispatches one tool call; failures are stringified for the model (Ford policy). */
    private String invokeOne(ContextToolsApi tools, ToolExecutionRequest call, String processId) {
        Map<String, Object> params;
        try {
            params = parseArgs(call.arguments());
        } catch (RuntimeException e) {
            log.warn("Marvin id='{}' tool='{}' bad arguments: {}", processId, call.name(), e.getMessage());
            return errorJson("Invalid tool arguments: " + e.getMessage());
        }
        try {
            Map<String, Object> result = tools.invoke(call.name(), params);
            return objectMapper.writeValueAsString(result);
        } catch (ToolException e) {
            log.info("Marvin id='{}' tool='{}' returned error: {}", processId, call.name(), e.getMessage());
            return errorJson(e);
        } catch (RuntimeException e) {
            log.warn("Marvin id='{}' tool='{}' unexpected failure: {}", processId, call.name(), e.toString());
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
     * <b>tree status block</b> — a fresh render of the node documents after
     * the base prompt (dynamic, before the cache boundary matters: it
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
                PromptContextBuilder.forProcess(process, modelInfo).tier(tier).engine(MarvinEngine.NAME);
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
        // announced here by name + hint (Hactar/Vogon/Arthur parity).
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
     * Renders the tree status as the prompt status block: run state, root
     * goal, node statistics, the current DFS node with its phase, open
     * inbox questions and the last result — everything the agent needs to
     * answer "how is it going?" without a tool call. The node documents
     * are the authority (the tree is the authority on itself — a copy kept
     * here could only disagree).
     */
    private String statusBlock(ThinkProcessDocument process) {
        // Fresh load (Vogon/Hactar L7 parity): the turn's process document
        // may hold a stale engineParams snapshot against the tool writes.
        ThinkProcessDocument fresh =
                thinkProcessService.findById(process.getId()).orElse(process);
        List<MarvinNodeDocument> all = nodeService.listAll(fresh.getId());
        StringBuilder sb = new StringBuilder("## Your tree — current run state\n\n");
        sb.append("run: ");
        if (all.isEmpty()) {
            sb.append("no tree yet (start one with marvin_start — pass the user's request "
                    + "as the goal, distilling what earlier runs found when they matter)\n");
            appendUserControls(sb);
            return sb.toString();
        }
        MarvinNodeDocument root =
                all.stream().filter(n -> n.getParentId() == null).findFirst().orElse(null);
        int pending = 0;
        int running = 0;
        int waiting = 0;
        int done = 0;
        int failed = 0;
        MarvinNodeDocument current = null;
        for (MarvinNodeDocument n : all) {
            switch (n.getStatus() == null ? NodeStatus.PENDING : n.getStatus()) {
                case PENDING -> pending++;
                case RUNNING -> {
                    running++;
                    if (current == null) current = n;
                }
                case WAITING -> {
                    waiting++;
                    if (current == null) current = n;
                }
                default -> {
                    if (n.getStatus() == NodeStatus.DONE) done++;
                    else if (n.getStatus() == NodeStatus.FAILED) failed++;
                }
            }
        }
        boolean live = running + waiting + pending > 0;
        if (live) {
            sb.append("live (")
                    .append(done)
                    .append(" done, ")
                    .append(running)
                    .append(" running, ")
                    .append(waiting)
                    .append(" waiting, ")
                    .append(pending)
                    .append(" pending, ")
                    .append(failed)
                    .append(" failed — ")
                    .append(all.size())
                    .append(" nodes total)\n");
        } else {
            sb.append("finished (")
                    .append(done)
                    .append(" done, ")
                    .append(failed)
                    .append(" failed — ")
                    .append(all.size())
                    .append(" nodes total)\n");
        }
        if (root != null && root.getGoal() != null) {
            sb.append("goal: ")
                    .append(preview(root.getGoal(), RESULT_PREVIEW_CHARS))
                    .append('\n');
        }
        if (current != null) {
            sb.append("current node: ")
                    .append(preview(current.getGoal(), RESULT_PREVIEW_CHARS))
                    .append(" (phase ")
                    .append(current.getCurrentPhase() == null ? "?" : current.getCurrentPhase())
                    .append(")\n");
        }
        // Open inbox questions: USER_INPUT nodes waiting for the human. The
        // identity relays them; the answer goes through the inbox form —
        // it has no tool that could write it (decision F6).
        for (MarvinNodeDocument n : all) {
            if (n.getStatus() != NodeStatus.WAITING || n.getInboxItemId() == null) continue;
            Optional<MaximegalonDocument> item = inboxItemService.findById(fresh.getTenantId(), n.getInboxItemId());
            if (item.isPresent() && item.get().getStatus() == de.mhus.vance.api.inbox.MaximegalonStatus.PENDING) {
                sb.append("open question (waiting for the HUMAN — they answer via the inbox "
                                + "form, you relay and remind, you never answer it): ")
                        .append(
                                item.get().getTitle() == null
                                                || item.get().getTitle().isBlank()
                                        ? n.getGoal()
                                        : item.get().getTitle())
                        .append('\n');
            }
        }
        Object result = root == null || root.getArtifacts() == null
                ? null
                : root.getArtifacts().get("result");
        if (!live && result instanceof String s && !s.isBlank()) {
            sb.append("last result: ").append(preview(s, RESULT_PREVIEW_CHARS)).append('\n');
        }
        appendUserControls(sb);
        return sb.toString();
    }

    private static void appendUserControls(StringBuilder sb) {
        sb.append("\nUser controls: marvin_start (goal — required; a running tree must be "
                + "stopped first, free restart after the terminal including a refined goal "
                + "built on what the previous run found — the tree reads ONLY the goal), "
                + "marvin_stop (halt the live tree; partial results stay partial — say so), "
                + "marvin_status (fuller read on demand). Open questions belong to the "
                + "human: relay them, never answer them for them.");
    }

    private static String preview(String s, int max) {
        String trimmed = s.strip();
        return trimmed.length() > max ? trimmed.substring(0, max) + "…" : trimmed;
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

    static @Nullable String renderForLlm(SteerMessage m) {
        if (m instanceof SteerMessage.UserChatInput) {
            return null;
        }
        if (m instanceof SteerMessage.ProcessEvent pe) {
            StringBuilder sb = new StringBuilder();
            sb.append("<process-event type=\"")
                    .append(pe.type().name().toLowerCase(Locale.ROOT))
                    .append("\">");
            // Machine facts FIRST, the human summary second (Hactar Live-
            // Fund 8): the payload carries the engine-verified truth and
            // must never be dropped.
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
