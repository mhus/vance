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
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Zaphod — two heads, no brain.
 *
 * <p>Minimal chat engine. Keeps a conversation log in
 * {@link ChatMessageService}, replays it as LLM history on every turn,
 * streams the answer back. No tools, no orchestration, no task-tree.
 *
 * <p><b>Still missing</b> for a real end-to-end chat experience:
 * <ul>
 *   <li>No streaming sink to the client — partial tokens are trace-logged
 *       because the context does not yet offer an {@code EventPublisher}.
 *       Persisted history is complete; live partials just don't surface.
 *   <li>No pending-message queue on the process — callers hand a
 *       {@link SteerMessage} directly into {@link #steer}. Once the queue
 *       and lane scheduler land, {@link #steer} will drain the queue
 *       instead.
 * </ul>
 *
 * <p><b>Status writes:</b> Zaphod flips {@link ThinkProcessStatus} on
 * lifecycle boundaries itself until the lane scheduler takes over.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class Zaphod implements ThinkEngine {

    public static final String NAME = "zaphod";
    public static final String VERSION = "0.1.0";

    public static final String GREETING = "Zaphod here. Ask me anything.";

    private static final String SYSTEM_PROMPT =
            "You are a minimal assistant in a Vance test session. "
                    + "Keep answers short and helpful.";

    private static final String SETTINGS_REF_TYPE = "tenant";
    private static final String SETTING_AI_PROVIDER = "ai.default.provider";
    private static final String SETTING_AI_MODEL = "ai.default.model";
    private static final String SETTING_ANTHROPIC_API_KEY = "ai.provider.anthropic.apiKey";

    private static final String DEFAULT_PROVIDER = "anthropic";
    private static final String DEFAULT_MODEL = "claude-sonnet-4-5";

    private final ThinkProcessService thinkProcessService;

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
        return "Minimal walking-skeleton chat engine. No tools, no orchestration.";
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
        runTurn(process, ctx, userInput.content(), token -> log.trace("token='{}'", token));
    }

    @Override
    public void stop(ThinkProcessDocument process, ThinkEngineContext ctx) {
        log.info("Zaphod.stop id='{}'", process.getId());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.STOPPED);
    }

    // ──────────────────── One turn ────────────────────

    /**
     * Persists the user message, replays the full history into the LLM,
     * streams the assistant reply, and persists it. Returns the full
     * response text.
     */
    private String runTurn(
            ThinkProcessDocument process,
            ThinkEngineContext ctx,
            String userInput,
            Consumer<String> tokenConsumer) {

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
            AiChatOptions options = AiChatOptions.builder().build();
            AiChat aiChat = ctx.aiModelService().createChat(config, options);

            List<ChatMessageDocument> history = chatLog.history(
                    process.getTenantId(), process.getSessionId(), process.getId());
            ChatRequest request = buildRequest(history);

            String response = streamCompletion(aiChat, request, tokenConsumer);

            chatLog.append(ChatMessageDocument.builder()
                    .tenantId(process.getTenantId())
                    .sessionId(process.getSessionId())
                    .thinkProcessId(process.getId())
                    .role(ChatRole.ASSISTANT)
                    .content(response)
                    .build());

            String preview = response.length() > 120 ? response.substring(0, 120) + "…" : response;
            log.info("Zaphod.steer id='{}' -> '{}'", process.getId(), preview);
            return response;
        } finally {
            thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.READY);
        }
    }

    private static ChatRequest buildRequest(List<ChatMessageDocument> history) {
        List<ChatMessage> messages = new ArrayList<>(history.size() + 1);
        messages.add(SystemMessage.from(SYSTEM_PROMPT));
        for (ChatMessageDocument msg : history) {
            messages.add(toLangchain(msg));
        }
        return ChatRequest.builder().messages(messages).build();
    }

    private static ChatMessage toLangchain(ChatMessageDocument msg) {
        return switch (msg.getRole()) {
            case USER -> UserMessage.from(msg.getContent());
            case ASSISTANT -> AiMessage.from(msg.getContent());
            case SYSTEM -> SystemMessage.from(msg.getContent());
        };
    }

    private static String streamCompletion(
            AiChat aiChat, ChatRequest request, Consumer<String> tokenConsumer) {
        StringBuilder accumulator = new StringBuilder();
        CompletableFuture<String> result = new CompletableFuture<>();
        aiChat.streamingChatModel().chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {
                if (partial == null || partial.isEmpty()) {
                    return;
                }
                accumulator.append(partial);
                try {
                    tokenConsumer.accept(partial);
                } catch (RuntimeException e) {
                    log.warn("tokenConsumer threw: {}", e.toString());
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse complete) {
                result.complete(accumulator.toString());
            }

            @Override
            public void onError(Throwable error) {
                result.completeExceptionally(error);
            }
        });
        try {
            return result.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new AiChatException("Zaphod streaming failed: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiChatException("Zaphod streaming interrupted", e);
        }
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
        String apiKey = settings.getDecryptedPassword(
                tenantId, SETTINGS_REF_TYPE, tenantId,
                SETTING_ANTHROPIC_API_KEY);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "No Anthropic API key configured for tenant '" + tenantId
                            + "' at setting '" + SETTING_ANTHROPIC_API_KEY + "'");
        }
        return new AiChatConfig(provider, model, apiKey);
    }
}
