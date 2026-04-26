package de.mhus.vance.brain.zaphod;

import de.mhus.vance.api.chat.ChatMessageChunkData;
import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiChatException;
import de.mhus.vance.brain.ai.AiChatOptions;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.ai.ModelInfo;
import de.mhus.vance.brain.events.ChunkBatcher;
import de.mhus.vance.brain.events.ClientEventPublisher;
import de.mhus.vance.brain.events.StreamingProperties;
import de.mhus.vance.brain.memory.CompactionResult;
import de.mhus.vance.brain.memory.MemoryCompactionService;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.memory.MemoryDocument;
import de.mhus.vance.shared.memory.MemoryKind;
import de.mhus.vance.shared.memory.MemoryService;
import de.mhus.vance.shared.settings.SettingService;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Zaphod — two heads, no brain.
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
public class Zaphod implements ThinkEngine {

    public static final String NAME = "zaphod";
    public static final String VERSION = "0.3.0";

    public static final String GREETING = "Zaphod here. Ask me anything.";

    private static final String SYSTEM_PROMPT =
            "You are a minimal assistant in a Vance test session. "
                    + "Keep answers short and helpful. "
                    + "Tools are available — call them when they help, "
                    + "and use find_tools / describe_tool to discover the "
                    + "non-primary ones before invoking them via invoke_tool.";

    /** Hard cap on tool-call iterations per turn — a broken model can loop. */
    private static final int MAX_TOOL_ITERATIONS = 8;

    private static final String SETTINGS_REF_TYPE = "tenant";
    private static final String SETTING_AI_PROVIDER = "ai.default.provider";
    private static final String SETTING_AI_MODEL = "ai.default.model";
    /** Provider-specific API-key setting key, e.g. {@code ai.provider.gemini.apiKey}. */
    private static final String SETTING_PROVIDER_API_KEY_FMT = "ai.provider.%s.apiKey";

    private static final String DEFAULT_PROVIDER = "anthropic";
    private static final String DEFAULT_MODEL = "claude-sonnet-4-5";

    private final ThinkProcessService thinkProcessService;
    private final ObjectMapper objectMapper;
    private final StreamingProperties streamingProperties;
    private final ModelCatalog modelCatalog;
    private final ZaphodProperties zaphodProperties;
    private final MemoryService memoryService;
    private final MemoryCompactionService memoryCompactionService;

    // ──────────────────── Metadata ────────────────────

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "Zaphod (Minimal Chat)";
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
        log.info("Zaphod.start tenant='{}' session='{}' id='{}'",
                process.getTenantId(), process.getSessionId(), process.getId());
        ctx.chatMessageService().append(ChatMessageDocument.builder()
                .tenantId(process.getTenantId())
                .sessionId(process.getSessionId())
                .thinkProcessId(process.getId())
                .role(ChatRole.ASSISTANT)
                .content(GREETING)
                .build());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.READY);
    }

    @Override
    public void resume(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.debug("Zaphod.resume id='{}'", process.getId());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.READY);
    }

    @Override
    public void suspend(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.debug("Zaphod.suspend id='{}'", process.getId());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.SUSPENDED);
    }

    @Override
    public void steer(ThinkProcessDocument process, ThinkEngineContext ctx, SteerMessage message) {
        if (!(message instanceof SteerMessage.UserChatInput userInput)) {
            log.warn("Zaphod.steer received unexpected message type '{}' for id='{}' — ignoring",
                    message.getClass().getSimpleName(), process.getId());
            return;
        }
        runTurn(process, ctx, userInput.content());
    }

    @Override
    public void stop(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.info("Zaphod.stop id='{}'", process.getId());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.STOPPED);
    }

    // ──────────────────── One turn ────────────────────

    private String runTurn(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            String userInput) {

        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.RUNNING);
        try {
            ChatMessageService chatLog = ctx.chatMessageService();
            chatLog.append(ChatMessageDocument.builder()
                    .tenantId(process.getTenantId())
                    .sessionId(process.getSessionId())
                    .thinkProcessId(process.getId())
                    .role(ChatRole.USER)
                    .content(userInput)
                    .build());

            AiChatConfig config = resolveAiConfig(process, ctx.settingService());
            AiChat aiChat = ctx.aiModelService().createChat(
                    config, AiChatOptions.builder().build());
            ContextToolsApi tools = ctx.tools();
            List<ToolSpecification> toolSpecs = tools.primaryAsLc4j();
            ModelInfo modelInfo = modelCatalog.lookupOrDefault(
                    config.provider(), config.modelName());

            List<ChatMessage> messages = buildPromptMessages(process, chatLog);
            int estimatedTokens = estimateTokens(messages);
            int triggerTokens = modelInfo.compactionTriggerTokens(
                    zaphodProperties.getCompactionTriggerRatio());
            log.debug("Zaphod.turn id='{}' model={}/{} ctx={} trigger={} est={}",
                    process.getId(),
                    modelInfo.provider(), modelInfo.modelName(),
                    modelInfo.contextWindowTokens(),
                    triggerTokens,
                    estimatedTokens);
            if (estimatedTokens >= triggerTokens) {
                log.info("Zaphod.turn id='{}' triggering compaction (est {} >= trigger {})",
                        process.getId(), estimatedTokens, triggerTokens);
                try {
                    CompactionResult result = memoryCompactionService.compact(process, config);
                    if (result.compacted()) {
                        log.info("Zaphod.turn id='{}' compaction ok: {} msgs → {} chars (memory='{}')",
                                process.getId(),
                                result.messagesCompacted(),
                                result.summaryChars(),
                                result.memoryId());
                        // Rebuild the prompt: the active-history shrunk and a
                        // new ARCHIVED_CHAT memory pinned the summary at top.
                        messages = buildPromptMessages(process, chatLog);
                    } else {
                        log.info("Zaphod.turn id='{}' compaction skipped: {}",
                                process.getId(), result.reason());
                    }
                } catch (RuntimeException e) {
                    // Best-effort: don't crash the user's turn if compaction fails.
                    log.warn("Zaphod.turn id='{}' compaction failed: {}",
                            process.getId(), e.toString());
                }
            }

            String finalText = runToolLoop(aiChat, toolSpecs, tools, messages, ctx, process);

            chatLog.append(ChatMessageDocument.builder()
                    .tenantId(process.getTenantId())
                    .sessionId(process.getSessionId())
                    .thinkProcessId(process.getId())
                    .role(ChatRole.ASSISTANT)
                    .content(finalText)
                    .build());

            String preview = finalText.length() > 120 ? finalText.substring(0, 120) + "…" : finalText;
            log.info("Zaphod.steer id='{}' -> '{}'", process.getId(), preview);
            return finalText;
        } finally {
            thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.READY);
        }
    }

    /**
     * Tool-call loop in streaming mode. Each iteration drives the
     * {@link AiChat#streamingChatModel()} and funnels text partials
     * through a {@link ChunkBatcher} into the event publisher. When
     * the response carries tool-execution-requests, dispatch them and
     * loop. Otherwise return the accumulated text.
     */
    private String runToolLoop(
            AiChat aiChat,
            List<ToolSpecification> toolSpecs,
            ContextToolsApi tools,
            List<ChatMessage> messages,
            ThinkEngineContext ctx,
            ThinkProcessDocument process) {
        StringBuilder finalText = new StringBuilder();
        for (int iter = 0; iter < MAX_TOOL_ITERATIONS; iter++) {
            ChatRequest.Builder req = ChatRequest.builder().messages(messages);
            if (!toolSpecs.isEmpty()) {
                req.toolSpecifications(toolSpecs);
            }

            StreamResult streamed = streamOneIteration(aiChat, req.build(), ctx, process);
            AiMessage reply = streamed.message;

            if (!reply.hasToolExecutionRequests()) {
                String text = reply.text();
                if (text != null) {
                    finalText.append(text);
                }
                return finalText.toString();
            }
            messages.add(reply);
            for (ToolExecutionRequest call : reply.toolExecutionRequests()) {
                String result = invokeOne(tools, call, process.getId());
                messages.add(ToolExecutionResultMessage.from(call, result));
            }
        }
        throw new AiChatException(
                "Zaphod exceeded " + MAX_TOOL_ITERATIONS
                        + " tool iterations — aborting turn to avoid runaway.");
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
            ThinkProcessDocument process) {
        CompletableFuture<ChatResponse> done = new CompletableFuture<>();
        ClientEventPublisher events = ctx.events();
        String sessionId = process.getSessionId();

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
                    log.warn("Zaphod chunk-publish threw: {}", e.toString());
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
            AiMessage reply = response.aiMessage();
            return new StreamResult(reply, reply.text() == null ? "" : reply.text());
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new AiChatException("Zaphod streaming failed: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiChatException("Zaphod streaming interrupted", e);
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
            log.warn("Zaphod id='{}' tool='{}' bad arguments: {}",
                    processId, call.name(), e.getMessage());
            return errorJson("Invalid tool arguments: " + e.getMessage());
        }
        try {
            Map<String, Object> result = tools.invoke(call.name(), params);
            return objectMapper.writeValueAsString(result);
        } catch (ToolException e) {
            log.info("Zaphod id='{}' tool='{}' returned error: {}",
                    processId, call.name(), e.getMessage());
            return errorJson(e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Zaphod id='{}' tool='{}' unexpected failure: {}",
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
     * pinned compaction summary (if any), then active chat history.
     * Re-callable so {@code runTurn} can rebuild after a mid-turn
     * compaction.
     */
    private List<ChatMessage> buildPromptMessages(
            ThinkProcessDocument process, ChatMessageService chatLog) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(SYSTEM_PROMPT));
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
            ThinkProcessDocument process, SettingService settings) {
        String tenantId = process.getTenantId();
        String provider = settings.getStringValue(
                tenantId, SETTINGS_REF_TYPE, tenantId,
                SETTING_AI_PROVIDER, DEFAULT_PROVIDER);
        String model = settings.getStringValue(
                tenantId, SETTINGS_REF_TYPE, tenantId,
                SETTING_AI_MODEL, DEFAULT_MODEL);
        String apiKeySetting = String.format(SETTING_PROVIDER_API_KEY_FMT, provider);
        String apiKey = settings.getDecryptedPassword(
                tenantId, SETTINGS_REF_TYPE, tenantId,
                apiKeySetting);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "No API key configured for provider '" + provider
                            + "' (tenant='" + tenantId
                            + "', setting='" + apiKeySetting + "')");
        }
        return new AiChatConfig(provider, model, apiKey);
    }
}
