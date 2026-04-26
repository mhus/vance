package de.mhus.vance.brain.arthur;

import de.mhus.vance.api.chat.ChatMessageChunkData;
import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiChatException;
import de.mhus.vance.brain.ai.AiChatOptions;
import de.mhus.vance.brain.events.ChunkBatcher;
import de.mhus.vance.brain.events.ClientEventPublisher;
import de.mhus.vance.brain.events.StreamingProperties;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Arthur — the reactive session-chat engine and reference
 * implementation of the orchestrator pattern. Listens, delegates,
 * synthesises. See {@code specification/arthur-engine.md}.
 *
 * <p><b>Reactive.</b> Arthur never reaches {@code DONE}. Each turn
 * starts when something hits the inbox (user input, sibling
 * {@code ProcessEvent}, async {@code ToolResult}) and ends with the
 * engine returning to {@code READY} or {@code BLOCKED}.
 *
 * <p><b>Drain-once-per-turn.</b> Arthur overrides
 * {@link #runTurn} so the entire inbox is folded into a single LLM
 * round-trip — five queued events are one LLM call, not five.
 *
 * <p><b>Tool pool (restricted).</b> Arthur sees only process control
 * + bundled docs. Filesystem, shell, web, MCP — those belong to
 * workers spawned via {@code process_create}. {@link #allowedTools}
 * publishes the set; the runtime enforces it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ArthurEngine implements ThinkEngine {

    public static final String NAME = "arthur";
    public static final String VERSION = "0.1.0";

    public static final String GREETING =
            "Hi, I'm Arthur. What are we working on?";

    private static final String SYSTEM_PROMPT_RESOURCE =
            "prompts/arthur-system-prompt.md";

    /** Loaded once at first call — the prompt file is bundled in the JAR. */
    private static volatile String cachedSystemPrompt;

    private static final Set<String> ALLOWED_TOOLS = Set.of(
            "process_create",
            "process_steer",
            "process_stop",
            "process_list",
            "process_status",
            "docs_list",
            "docs_read");

    private static final String SETTINGS_REF_TYPE = "tenant";
    private static final String SETTING_AI_PROVIDER = "ai.default.provider";
    private static final String SETTING_AI_MODEL = "ai.default.model";
    private static final String SETTING_PROVIDER_API_KEY_FMT = "ai.provider.%s.apiKey";

    private static final String DEFAULT_PROVIDER = "anthropic";
    private static final String DEFAULT_MODEL = "claude-sonnet-4-5";

    private final ThinkProcessService thinkProcessService;
    private final ObjectMapper objectMapper;
    private final StreamingProperties streamingProperties;
    private final ArthurProperties arthurProperties;

    // ──────────────────── Metadata ────────────────────

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "Arthur (Session Chat)";
    }

    @Override
    public String description() {
        return "Reactive session-chat agent. Talks to the user, "
                + "delegates deep work to worker processes, "
                + "synthesises their results back into the chat.";
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public Set<String> allowedTools() {
        return ALLOWED_TOOLS;
    }

    // ──────────────────── Lifecycle ────────────────────

    @Override
    public void start(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.info("Arthur.start tenant='{}' session='{}' id='{}'",
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
        log.debug("Arthur.resume id='{}'", process.getId());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.READY);
    }

    @Override
    public void suspend(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.debug("Arthur.suspend id='{}'", process.getId());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.SUSPENDED);
    }

    @Override
    public void stop(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.info("Arthur.stop id='{}'", process.getId());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.STOPPED);
    }

    /**
     * Single-message entry — used when the runtime delivers a
     * message via the per-message default. Arthur prefers
     * {@link #runTurn} (batch), but if something does call
     * {@code steer} directly we treat it as a one-element inbox.
     */
    @Override
    public void steer(ThinkProcessDocument process, ThinkEngineContext ctx, SteerMessage message) {
        runTurnFor(process, ctx, List.of(message));
    }

    /**
     * Drains the inbox and processes everything in one LLM
     * round-trip. Auto-Wakeup: keep re-draining until the queue
     * stays empty across a full pass — covers messages that arrive
     * during the LLM call.
     */
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

    // ──────────────────── One turn ────────────────────

    private void runTurnFor(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            List<SteerMessage> inbox) {

        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.RUNNING);
        try {
            ChatMessageService chatLog = ctx.chatMessageService();

            // Persist user-typed messages into the chat log (so future turns
            // see them in history). Other inbox kinds — ProcessEvent,
            // ToolResult, ExternalCommand — are turn-local: they steer this
            // turn but don't get written back as user-visible chat history.
            for (SteerMessage m : inbox) {
                if (m instanceof SteerMessage.UserChatInput uci) {
                    chatLog.append(ChatMessageDocument.builder()
                            .tenantId(process.getTenantId())
                            .sessionId(process.getSessionId())
                            .thinkProcessId(process.getId())
                            .role(ChatRole.USER)
                            .content(uci.content())
                            .build());
                }
            }

            AiChatConfig config = resolveAiConfig(process, ctx.settingService());
            AiChat aiChat = ctx.aiModelService().createChat(
                    config, AiChatOptions.builder().build());
            ContextToolsApi tools = ctx.tools();
            List<ToolSpecification> toolSpecs = tools.primaryAsLc4j();

            List<ChatMessage> messages = buildPromptMessages(process, chatLog, inbox);
            log.debug("Arthur.turn id='{}' inbox={} historyMsgs={}",
                    process.getId(), inbox.size(), messages.size());

            String finalText = runToolLoop(aiChat, toolSpecs, tools, messages, ctx, process);

            chatLog.append(ChatMessageDocument.builder()
                    .tenantId(process.getTenantId())
                    .sessionId(process.getSessionId())
                    .thinkProcessId(process.getId())
                    .role(ChatRole.ASSISTANT)
                    .content(finalText)
                    .build());

            String preview = finalText.length() > 120 ? finalText.substring(0, 120) + "…" : finalText;
            log.info("Arthur.turn id='{}' -> '{}'", process.getId(), preview);
        } finally {
            thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.READY);
        }
    }

    /**
     * Tool-call loop in streaming mode. Same shape as Zaphod's: each
     * iteration drives the streaming model, captures partials into
     * a chunk-batcher, and dispatches any tool-execution requests
     * the model returned. The loop exits when the model returns no
     * tool calls — that final reply text is the assistant's answer.
     */
    private String runToolLoop(
            AiChat aiChat,
            List<ToolSpecification> toolSpecs,
            ContextToolsApi tools,
            List<ChatMessage> messages,
            ThinkEngineContext ctx,
            ThinkProcessDocument process) {
        StringBuilder finalText = new StringBuilder();
        int maxIters = arthurProperties.getMaxToolIterations();
        for (int iter = 0; iter < maxIters; iter++) {
            ChatRequest.Builder req = ChatRequest.builder().messages(messages);
            if (!toolSpecs.isEmpty()) {
                req.toolSpecifications(toolSpecs);
            }
            AiMessage reply = streamOneIteration(aiChat, req.build(), ctx, process);

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
                "Arthur exceeded " + maxIters
                        + " tool iterations — aborting turn to avoid runaway.");
    }

    private AiMessage streamOneIteration(
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
                    log.warn("Arthur chunk-publish threw: {}", e.toString());
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
            return done.get().aiMessage();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new AiChatException("Arthur streaming failed: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiChatException("Arthur streaming interrupted", e);
        }
    }

    private String invokeOne(
            ContextToolsApi tools, ToolExecutionRequest call, String processId) {
        Map<String, Object> params;
        try {
            params = parseArgs(call.arguments());
        } catch (RuntimeException e) {
            log.warn("Arthur id='{}' tool='{}' bad arguments: {}",
                    processId, call.name(), e.getMessage());
            return errorJson("Invalid tool arguments: " + e.getMessage());
        }
        try {
            Map<String, Object> result = tools.invoke(call.name(), params);
            return objectMapper.writeValueAsString(result);
        } catch (ToolException e) {
            log.info("Arthur id='{}' tool='{}' returned error: {}",
                    processId, call.name(), e.getMessage());
            return errorJson(e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Arthur id='{}' tool='{}' unexpected failure: {}",
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

    // ──────────────────── Prompt building ────────────────────

    /**
     * Builds the LLM input: system prompt → chat history (assistant +
     * past user messages) → current-turn inbox rendered as user-role
     * messages with the {@code <process-event>} XML wrapper for
     * non-user inputs.
     */
    private List<ChatMessage> buildPromptMessages(
            ThinkProcessDocument process,
            ChatMessageService chatLog,
            List<SteerMessage> inbox) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt()));

        // Active history (ARCHIVED_CHAT compaction-aware once we wire
        // memoryService — for v1 just use full active history).
        List<ChatMessageDocument> history = chatLog.activeHistory(
                process.getTenantId(), process.getSessionId(), process.getId());

        // The inbox messages we just persisted (UserChatInput) are
        // already in `history`. Render the rest separately so the LLM
        // sees them clearly tagged.
        int userInputCount = 0;
        for (SteerMessage m : inbox) {
            if (m instanceof SteerMessage.UserChatInput) userInputCount++;
        }

        // History up to the just-appended user inputs is the "old"
        // conversation. The trailing N user messages are the current
        // turn — they're already represented as USER chat messages.
        for (ChatMessageDocument msg : history) {
            messages.add(toLangchain(msg));
        }

        // Now append the non-user inbox items (events, tool results,
        // external commands) as user-role messages with the XML
        // wrapper Arthur's prompt is trained on.
        for (SteerMessage m : inbox) {
            String wrapped = renderForLlm(m);
            if (wrapped != null) {
                messages.add(UserMessage.from(wrapped));
            }
        }

        return messages;
    }

    /**
     * Returns the LLM-facing rendering of one inbox message, or
     * {@code null} if the message has no separate rendering (the
     * UserChatInput case — already in chat history). Mirrors the
     * {@code <task-notification>} convention from Claude Code.
     */
    private static String renderForLlm(SteerMessage m) {
        return switch (m) {
            case SteerMessage.UserChatInput uci -> null; // already in chat history
            case SteerMessage.ProcessEvent pe -> {
                StringBuilder sb = new StringBuilder();
                sb.append("<process-event sourceProcessId=\"")
                        .append(escapeAttr(pe.sourceProcessId()))
                        .append("\" type=\"")
                        .append(pe.type().name().toLowerCase())
                        .append("\">");
                if (pe.humanSummary() != null) {
                    sb.append(escapeText(pe.humanSummary()));
                }
                sb.append("</process-event>");
                yield sb.toString();
            }
            case SteerMessage.ToolResult tr -> {
                StringBuilder sb = new StringBuilder();
                sb.append("<tool-result toolCallId=\"")
                        .append(escapeAttr(tr.toolCallId()))
                        .append("\" toolName=\"")
                        .append(escapeAttr(tr.toolName()))
                        .append("\" status=\"")
                        .append(tr.status().name().toLowerCase())
                        .append("\">");
                if (tr.error() != null) {
                    sb.append("error: ").append(escapeText(tr.error()));
                } else if (tr.result() != null) {
                    sb.append(escapeText(tr.result().toString()));
                }
                sb.append("</tool-result>");
                yield sb.toString();
            }
            case SteerMessage.ExternalCommand ec -> {
                StringBuilder sb = new StringBuilder();
                sb.append("<external-command name=\"")
                        .append(escapeAttr(ec.command()))
                        .append("\">")
                        .append(escapeText(String.valueOf(ec.params())))
                        .append("</external-command>");
                yield sb.toString();
            }
        };
    }

    private static String escapeAttr(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("\"", "&quot;");
    }

    private static String escapeText(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static ChatMessage toLangchain(ChatMessageDocument msg) {
        return switch (msg.getRole()) {
            case USER -> UserMessage.from(msg.getContent());
            case ASSISTANT -> AiMessage.from(msg.getContent());
            case SYSTEM -> SystemMessage.from(msg.getContent());
        };
    }

    /** Lazy-loads the bundled prompt file. Cached after the first hit. */
    private static String systemPrompt() {
        String cached = cachedSystemPrompt;
        if (cached != null) return cached;
        try {
            byte[] bytes = new ClassPathResource(SYSTEM_PROMPT_RESOURCE)
                    .getInputStream().readAllBytes();
            String loaded = new String(bytes, StandardCharsets.UTF_8);
            cachedSystemPrompt = loaded;
            return loaded;
        } catch (IOException ioe) {
            throw new UncheckedIOException(
                    "Arthur system-prompt resource missing: " + SYSTEM_PROMPT_RESOURCE, ioe);
        }
    }

    // ──────────────────── Config resolve (mirrors Zaphod) ────────────────────

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
