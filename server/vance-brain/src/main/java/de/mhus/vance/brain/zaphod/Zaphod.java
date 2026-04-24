package de.mhus.vance.brain.zaphod;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.ai.AiChat;
import de.mhus.vance.brain.ai.AiChatConfig;
import de.mhus.vance.brain.ai.AiChatException;
import de.mhus.vance.brain.ai.AiChatOptions;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Zaphod — two heads, no brain.
 *
 * <p>Minimal chat engine with tool support. Keeps a conversation log in
 * {@link ChatMessageService}, replays it as LLM history on every turn,
 * calls the model with primary tools advertised, loops over any
 * {@code toolExecutionRequests} the model emits, and persists the final
 * assistant text.
 *
 * <p><b>Persistence policy:</b> only the user's input and the model's
 * final text are written to the chat log. Intermediate tool calls and
 * results live only in the per-turn LC4J message list — they steer
 * <em>this</em> turn, not the next one. If the LLM needs to recall past
 * tool work, that belongs in memory, not in the chat log.
 *
 * <p><b>Streaming:</b> the tool-call loop uses the synchronous
 * {@link AiChat#chatModel()} because partial-token streaming with
 * interleaved tool-use blocks is provider-specific and fragile in
 * 1.0.0. Streaming of text-only responses can be reinstated once the
 * client surface has an event publisher; today's {@code tokenConsumer}
 * was trace-only anyway.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class Zaphod implements ThinkEngine {

    public static final String NAME = "zaphod";
    public static final String VERSION = "0.2.0";

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
        return "Minimal walking-skeleton chat engine with tool support.";
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

    /**
     * Persists the user message, runs the tool-call loop until the LLM
     * answers without further tool requests, and persists the final
     * assistant text.
     */
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

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(SystemMessage.from(SYSTEM_PROMPT));
            for (ChatMessageDocument msg : chatLog.history(
                    process.getTenantId(), process.getSessionId(), process.getId())) {
                messages.add(toLangchain(msg));
            }

            String finalText = runToolLoop(aiChat, toolSpecs, tools, messages, process.getId());

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
     * Tool-call loop. Sends the current message list to the model; if
     * the reply carries {@code toolExecutionRequests}, invokes each one
     * via the dispatcher, appends the assistant message and the result
     * messages to the list, and re-asks. Stops on a tool-free reply or
     * when {@link #MAX_TOOL_ITERATIONS} is hit.
     */
    private String runToolLoop(
            AiChat aiChat,
            List<ToolSpecification> toolSpecs,
            ContextToolsApi tools,
            List<ChatMessage> messages,
            String processId) {
        for (int iter = 0; iter < MAX_TOOL_ITERATIONS; iter++) {
            ChatRequest.Builder req = ChatRequest.builder().messages(messages);
            if (!toolSpecs.isEmpty()) {
                req.toolSpecifications(toolSpecs);
            }
            ChatResponse response;
            try {
                response = aiChat.chatModel().chat(req.build());
            } catch (RuntimeException e) {
                throw new AiChatException("Zaphod chat call failed: " + e.getMessage(), e);
            }
            AiMessage reply = response.aiMessage();
            if (!reply.hasToolExecutionRequests()) {
                String text = reply.text();
                return text == null ? "" : text;
            }
            messages.add(reply);
            for (ToolExecutionRequest call : reply.toolExecutionRequests()) {
                String result = invokeOne(tools, call, processId);
                messages.add(ToolExecutionResultMessage.from(call, result));
            }
        }
        throw new AiChatException(
                "Zaphod exceeded " + MAX_TOOL_ITERATIONS
                        + " tool iterations — aborting turn to avoid runaway.");
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

    private static ChatMessage toLangchain(ChatMessageDocument msg) {
        return switch (msg.getRole()) {
            case USER -> UserMessage.from(msg.getContent());
            case ASSISTANT -> AiMessage.from(msg.getContent());
            case SYSTEM -> SystemMessage.from(msg.getContent());
        };
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
