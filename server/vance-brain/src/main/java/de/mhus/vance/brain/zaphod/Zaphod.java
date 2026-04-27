package de.mhus.vance.brain.zaphod;

import de.mhus.vance.api.chat.ChatMessageChunkData;
import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiChatException;
import de.mhus.vance.brain.ai.AiChatOptions;
import de.mhus.vance.brain.ai.AiModelResolver;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.ai.ModelInfo;
import de.mhus.vance.brain.events.ChunkBatcher;
import de.mhus.vance.brain.events.ClientEventPublisher;
import de.mhus.vance.brain.events.StreamingProperties;
import de.mhus.vance.brain.memory.CompactionResult;
import de.mhus.vance.brain.memory.MemoryCompactionService;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.SystemPrompts;
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
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
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
                    + "non-primary ones before invoking them via invoke_tool. "
                    + "When a tool returns concrete data the user (or a "
                    + "calling orchestrator) is asking for — file lists, "
                    + "file contents, command output, search results — "
                    + "include the actual data in your reply text. Do not "
                    + "summarise it as 'done' or 'I see the files'; paste "
                    + "the relevant content. The reply text is the only "
                    + "channel callers can read; tool results are invisible "
                    + "to them otherwise.\n\n"
                    + "Hard rule: if the user asks about a SPECIFIC file, "
                    + "directory, project, or system state, USE A TOOL to "
                    + "read it — never answer from training data with a "
                    + "generic 'a typical Maven project looks like...'. If "
                    + "you don't have the right tool, say so plainly. "
                    + "Inventing plausible-looking content from training "
                    + "data is the worst failure mode here — the caller "
                    + "will pass it on as fact.\n\n"
                    + "Hard rule: if you state an intent to act ('I'll "
                    + "read the file', 'let me check'), you MUST emit the "
                    + "tool call in the same response. Don't end a turn "
                    + "with words of intent and no tool call.";

    /** Hard cap on tool-call iterations per turn — a broken model can loop.
     *  Per-process override via {@code params.maxIterations}. */
    private static final int MAX_TOOL_ITERATIONS = 8;

    // ──────────────────── Validation heuristic ────────────────────
    // Opt-in via params.validation == true. Two checks:
    //   1. intent-without-action — same as Arthur's
    //   2. reply-too-brief-after-data-fetch — Zaphod-specific, catches
    //      "OK, I see the files." after a substantial tool result
    //
    // Both inject a corrective SystemMessage and let the model retry,
    // bounded by MAX_VALIDATION_CORRECTIONS.

    private static final List<Pattern> INTENT_PATTERNS = List.of(
            Pattern.compile("(?im)(^|[.!?]\\s+|\\b)(I'?ll|I will|I'?m going to|Let me)\\s+\\w+"),
            Pattern.compile(
                    "(?im)(^|[.!?]\\s+|\\b)"
                            + "(Ich (werde|weise|frage|sage|prüfe|lese|starte|beauftrage)"
                            + "|Lass mich|Soll ich|Okay,?\\s+ich (werde|weise|prüfe|frage))"
                            + "\\b"),
            Pattern.compile(
                    "(?im)(^|[.!?]\\s+|\\b)"
                            + "(I'?ll (now |just )?(ask|tell|check|fetch|read|run|call|invoke)"
                            + "|Let me (now |just )?(ask|tell|check|fetch|read|run|call|invoke))"
                            + "\\b"));

    /** Tool result size (chars) above which we expect the data to be
     *  reflected in the reply. */
    private static final int TOOL_DATA_THRESHOLD = 500;

    /** Reply size (chars) below which we suspect the data wasn't relayed. */
    private static final int REPLY_BRIEF_THRESHOLD = 200;

    private static final int MAX_VALIDATION_CORRECTIONS = 2;

    private static final String INTENT_CORRECTION_TEMPLATE =
            "VALIDATION CHECK: your previous response said you would do "
                    + "something ('%s') but emitted no tool call. Either "
                    + "emit the tool call now, or rephrase as a direct "
                    + "answer / question without promising future action. "
                    + "Do not repeat the same intent-without-action sentence.";

    private static final String DATA_RELAY_CORRECTION_TEMPLATE =
            "VALIDATION CHECK: this turn's tools returned %d characters of "
                    + "data, but your reply has only %d. The caller cannot see "
                    + "tool results — only your reply text. Re-issue the "
                    + "reply with the actual data pasted in: file lists, file "
                    + "contents, command output, etc., as appropriate. If the "
                    + "tool returned irrelevant data, say so plainly.";

    private static final String SETTINGS_REF_TYPE = "tenant";
    /** Provider-specific API-key setting key, e.g. {@code ai.provider.gemini.apiKey}. */
    private static final String SETTING_PROVIDER_API_KEY_FMT = "ai.provider.%s.apiKey";

    private final ThinkProcessService thinkProcessService;
    private final ObjectMapper objectMapper;
    private final StreamingProperties streamingProperties;
    private final ModelCatalog modelCatalog;
    private final ZaphodProperties zaphodProperties;
    private final MemoryService memoryService;
    private final MemoryCompactionService memoryCompactionService;
    private final AiModelResolver aiModelResolver;

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

            AiChatConfig config = resolveAiConfig(
                    process, ctx.settingService(), aiModelResolver);
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

            int maxIters = paramInt(process, "maxIterations", MAX_TOOL_ITERATIONS);
            boolean validation = paramBool(process, "validation", false);
            if (validation) {
                log.info("Zaphod.turn id='{}' validation=on maxIters={}",
                        process.getId(), maxIters);
            }
            String finalText = runToolLoop(
                    aiChat, toolSpecs, tools, messages, ctx, process,
                    maxIters, validation);

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
            ThinkProcessDocument process,
            int maxIters,
            boolean validation) {
        StringBuilder finalText = new StringBuilder();
        int corrections = 0;
        int toolDataChars = 0;
        for (int iter = 0; iter < maxIters; iter++) {
            ChatRequest.Builder req = ChatRequest.builder().messages(messages);
            if (!toolSpecs.isEmpty()) {
                req.toolSpecifications(toolSpecs);
            }

            StreamResult streamed = streamOneIteration(aiChat, req.build(), ctx, process);
            AiMessage reply = streamed.message;

            if (!reply.hasToolExecutionRequests()) {
                String text = reply.text();
                if (validation && corrections < MAX_VALIDATION_CORRECTIONS) {
                    String intent = matchIntent(text);
                    if (intent != null) {
                        log.info(
                                "Zaphod id='{}' validation: intent-without-action (\"{}\"), correcting ({}/{})",
                                process.getId(), intent,
                                corrections + 1, MAX_VALIDATION_CORRECTIONS);
                        messages.add(reply);
                        messages.add(SystemMessage.from(
                                String.format(INTENT_CORRECTION_TEMPLATE, intent)));
                        corrections++;
                        continue;
                    }
                    int replyLen = text == null ? 0 : text.length();
                    if (toolDataChars >= TOOL_DATA_THRESHOLD
                            && replyLen <= REPLY_BRIEF_THRESHOLD) {
                        log.info(
                                "Zaphod id='{}' validation: data-relay-gap (toolData={}, reply={}), correcting ({}/{})",
                                process.getId(), toolDataChars, replyLen,
                                corrections + 1, MAX_VALIDATION_CORRECTIONS);
                        messages.add(reply);
                        messages.add(SystemMessage.from(
                                String.format(DATA_RELAY_CORRECTION_TEMPLATE,
                                        toolDataChars, replyLen)));
                        corrections++;
                        continue;
                    }
                }
                if (text != null) {
                    finalText.append(text);
                }
                if (validation && corrections > 0) {
                    log.info("Zaphod id='{}' validation: completed after {} correction(s)",
                            process.getId(), corrections);
                }
                return finalText.toString();
            }
            messages.add(reply);
            for (ToolExecutionRequest call : reply.toolExecutionRequests()) {
                String result = invokeOne(tools, call, process.getId());
                if (result != null) toolDataChars += result.length();
                messages.add(ToolExecutionResultMessage.from(call, result));
            }
        }
        throw new AiChatException(
                "Zaphod exceeded " + maxIters
                        + " tool iterations — aborting turn to avoid runaway.");
    }

    /**
     * Returns the matched intent fragment, or {@code null} when the
     * text doesn't look like a future-tense announcement.
     */
    private static @Nullable String matchIntent(@Nullable String text) {
        if (text == null || text.isBlank()) return null;
        for (Pattern p : INTENT_PATTERNS) {
            var m = p.matcher(text);
            if (m.find()) {
                int start = m.start();
                int end = Math.min(text.length(), start + 60);
                return text.substring(start, end).trim();
            }
        }
        return null;
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
        messages.add(SystemMessage.from(SystemPrompts.compose(process, SYSTEM_PROMPT)));
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
        AiModelResolver.Resolved resolved = modelResolver.resolveOrDefault(spec, tenantId);

        String apiKeySetting = String.format(
                SETTING_PROVIDER_API_KEY_FMT, resolved.provider());
        String apiKey = settings.getDecryptedPassword(
                tenantId, SETTINGS_REF_TYPE, tenantId, apiKeySetting);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "No API key configured for provider '" + resolved.provider()
                            + "' (tenant='" + tenantId
                            + "', setting='" + apiKeySetting + "')");
        }
        return new AiChatConfig(resolved.provider(), resolved.modelName(), apiKey);
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
