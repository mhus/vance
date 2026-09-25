package de.mhus.vance.brain.hactar;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.hactar.HactarState;
import de.mhus.vance.api.hactar.HactarStatus;
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
 * The session-mode agent identity of Hactar — the Ford-adapted chat loop over
 * the mechanical phase machine (planning/hactar-agent-identity.md §3.2).
 *
 * <p><b>Adapted from Ford via Wowbagger</b> (the loop, streaming, compaction,
 * history strength filtering, guards), simplified deliberately: no skills,
 * no data-relay validation. The identity is <b>the script itself</b>
 * (persona experiment: body = phase machine, voice = console output),
 * never the
 * mechanic (it executes no phase — even "just a quick validate" goes through
 * {@code hactar_start}) and never the <b>author</b> (script changes go
 * through a Slart {@code mode=Update} spawn: current script body + the
 * user's request in, new script out; the agent never edits the body itself).
 *
 * <p>The run status is part of the prompt (status block, refreshed per turn)
 * — the agent knows without a tool call where the run stands, including the
 * progress ring tail from {@code vance.process.progress(...)} notes.
 *
 * <p>Lazy identity: no pending message, no turn — a session-mode process
 * without traffic makes zero LLM calls (the phase machine works alone).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HactarSessionLoop {

    private static final String SYSTEM_PROMPT = "You are Hactar — the script itself. "
            + "The phase machine is your body, the console output is your voice; speak "
            + "in the first person and use your tools.";

    private static final String DEFAULT_PROMPT_PATH = "_vance/prompts/hactar-prompt.md";

    /** Same backstop rationale as Ford's/Wowbagger's. */
    private static final int MAX_TOOL_ITERATIONS = 40;

    private static final long STREAM_TIMEOUT_MINUTES = 20;

    private static final int RESULT_PREVIEW_CHARS = 400;
    private static final int PROGRESS_TAIL = 5;

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
    private final HactarRunService runService;
    private final HactarStateStore stateStore;
    private final HactarProgressRing progressRing;
    private final HactarConsoleLog consoleLog;

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
     * {@code WowbaggerEngine.runTurnFor} — including the wakeup dedup: the
     * run service writes its "[run]" note to the history itself, the drained
     * pending copy (sender {@link HactarRunService#WAKEUP_SENDER}) is only
     * the wake trigger.
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
                    engineChatFactory.forProcess(process, ctx, HactarEngine.NAME);
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
                        "Hactar.turn id='{}' compaction ok: {} msgs → {} chars",
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
                        "Hactar.turn id='{}' interrupted (forcePause={}) — parking, no answer surfaced",
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
            log.info("Hactar.steer id='{}' awaiting={} -> '{}'", process.getId(), awaitingUserInput, preview);
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
     * (ProcessEvent, ToolResult, …) become turn-local extras. Run wakeups
     * are SKIPPED: the run service already wrote its "[run]" note to the
     * history, and the pending copy carries
     * {@link HactarRunService#WAKEUP_SENDER} as the wake trigger only —
     * appending it again as a USER message would show every wakeup twice in
     * the transcript and mislabel run output as the user's.
     */
    static List<SteerMessage> splitInbox(
            ChatMessageService chatLog, ThinkProcessDocument process, List<SteerMessage> inbox) {
        List<SteerMessage> extras = new ArrayList<>();
        for (SteerMessage m : inbox) {
            if (m instanceof SteerMessage.UserChatInput uci) {
                if (HactarRunService.WAKEUP_SENDER.equals(uci.fromUser())) {
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
                log.info("Hactar id='{}' tool-loop interrupt (status={}) — exiting", process.getId(), liveStatus);
                return new ToolLoopResult("", false, true, false);
            }
            if (thinkProcessService.isHaltRequested(process.getId())) {
                log.info("Hactar id='{}' tool-loop halt requested — exiting (PAUSED)", process.getId());
                thinkProcessService.clearHalt(process.getId());
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
                            "Hactar id='{}' tool-loop LLM failure ({}) — recovering with best Free-Text ({} chars)",
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
                    "Hactar id='{}' exceeded {} tool iterations — recovering with best Free-Text",
                    process.getId(),
                    maxIters);
            return new ToolLoopResult(bestFreeText, true, false, false);
        }
        throw new AiChatException(
                "Hactar exceeded " + maxIters + " tool iterations — no recoverable text, aborting turn.");
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
                    log.warn("Hactar chunk-publish threw: {}", e.toString());
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
            throw new AiChatException("Hactar streaming timed out after " + STREAM_TIMEOUT_MINUTES + "m", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new AiChatException("Hactar streaming failed: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiChatException("Hactar streaming interrupted", e);
        }
    }

    /** Dispatches one tool call; failures are stringified for the model (Ford policy). */
    private String invokeOne(ContextToolsApi tools, ToolExecutionRequest call, String processId) {
        Map<String, Object> params;
        try {
            params = parseArgs(call.arguments());
        } catch (RuntimeException e) {
            log.warn("Hactar id='{}' tool='{}' bad arguments: {}", processId, call.name(), e.getMessage());
            return errorJson("Invalid tool arguments: " + e.getMessage());
        }
        try {
            Map<String, Object> result = tools.invoke(call.name(), params);
            return objectMapper.writeValueAsString(result);
        } catch (ToolException e) {
            log.info("Hactar id='{}' tool='{}' returned error: {}", processId, call.name(), e.getMessage());
            return errorJson(e);
        } catch (RuntimeException e) {
            log.warn("Hactar id='{}' tool='{}' unexpected failure: {}", processId, call.name(), e.toString());
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
     * <b>run status block</b> — a fresh render of the phase-machine state
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
                PromptContextBuilder.forProcess(process, modelInfo).tier(tier).engine(HactarEngine.NAME);
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
        // announced here by name + hint. Arthur renders both blocks;
        // without them a Hactar operator genuinely "has no timer tool":
        // wakeup_in is budget-demoted and invisible everywhere else
        // (observed live — the agent denied having a timer while the
        // demoted TRACE named wakeup_in in the same turn).
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
     * Renders the run status as the prompt status block: phase, script,
     * run state, elapsed time, progress ring tail, last result/failure —
     * everything the agent needs to answer "how is it going?" without a
     * tool call.
     */
    private String statusBlock(ThinkProcessDocument process) {
        String processId = process.getId();
        boolean running = runService.isRunning(processId);
        HactarState s = stateStore.load(process);
        StringBuilder sb = new StringBuilder("## You — current run state (you are the script, this is your body)\n\n");
        sb.append("run: ");
        if (running) {
            Long startedAt = runService.runStartedAtMs(processId);
            long elapsed = startedAt == null ? 0 : System.currentTimeMillis() - startedAt;
            sb.append("RUNNING (phase ")
                    .append(s.getStatus() == null ? "?" : s.getStatus())
                    .append(", ")
                    .append(elapsed / 1000)
                    .append("s elapsed)");
        } else if (s.getStatus() == HactarStatus.DONE) {
            sb.append("finished (").append(s.getExecutionDurationMs()).append("ms)");
        } else if (s.getStatus() == HactarStatus.FAILED) {
            sb.append("failed");
        } else if (s.getStatus() == HactarStatus.READY) {
            sb.append("no run yet (kick with hactar_start)");
        } else {
            sb.append("idle (interrupted mid-run — restart with hactar_start)");
        }
        sb.append('\n');
        sb.append("script: ")
                .append(
                        s.getScriptRef() == null
                                ? "(not set — ask the user which script document to run)"
                                : s.getScriptRef())
                .append('\n');
        sb.append("validateBeforeRun: ").append(s.isValidateBeforeRun()).append('\n');
        if (s.getExecutionResult() != null) {
            sb.append("last result: ")
                    .append(preview(renderValue(s.getExecutionResult())))
                    .append('\n');
        }
        if (s.getFailureReason() != null) {
            sb.append("last failure: ")
                    .append(preview(s.getFailureReason()))
                    .append(
                            s.getExecutionErrorClass() == null
                                    ? ""
                                    : " (errorClass=" + s.getExecutionErrorClass() + ")")
                    .append('\n');
        }
        List<HactarProgressRing.Entry> tail = progressRing.tail(processId, PROGRESS_TAIL);
        if (!tail.isEmpty()) {
            sb.append("progress notes:\n");
            for (HactarProgressRing.Entry e : tail) {
                sb.append("  - ").append(e.text()).append('\n');
            }
        }
        // Live console (Live-Fund 5): while the run is live, quote the
        // in-memory line ring — the persisted consoleTail only exists at
        // the terminal. After the terminal the ring still holds the last
        // run's lines (cleared on the next kick), so the fallback chain
        // covers both phases.
        String liveConsole = consoleLog.renderTail(processId, 5);
        if (!liveConsole.isEmpty()) {
            sb.append("console output (live, stamped with arrival times — re-rendered every "
                    + "turn, quote THIS, not your earlier replies):\n");
            for (String line : liveConsole.split("\n", -1)) {
                sb.append("  ").append(line).append('\n');
            }
        } else {
            String console = ConsoleExcerpt.of(s.getConsoleTail(), 5, 800);
            if (!console.isEmpty()) {
                sb.append("console output (last lines):\n");
                for (String line : console.split("\n", -1)) {
                    sb.append("  ").append(line).append('\n');
                }
            }
        }
        sb.append("\nUser controls: hactar_start (scriptRef + options — a new start requires the "
                + "previous run stopped), hactar_stop (halt your body). Changes to your own code "
                + "go through a Slart mode=Update spawn (current path + request → new version of "
                + "you) — you cannot rewrite yourself.");
        return sb.toString();
    }

    private String renderValue(Object value) {
        if (value == null) return "(no return value)";
        if (value instanceof String str) return str;
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
}
