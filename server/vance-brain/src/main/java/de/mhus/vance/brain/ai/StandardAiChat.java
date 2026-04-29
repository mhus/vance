package de.mhus.vance.brain.ai;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * Provider-agnostic {@link AiChat} wrapper. Holds a matched sync/streaming
 * model pair produced by any {@link AiModelProvider} implementation.
 *
 * <p>Everything in here talks to langchain4j's generic
 * {@link ChatModel} / {@link StreamingChatModel} interfaces, so this class
 * works unchanged for Anthropic, Gemini, OpenAI, etc. Provider-specific
 * wiring (api-key format, model-builder quirks) stays in the provider bean.
 */
@Slf4j
public class StandardAiChat implements AiChat {

    private final String name;
    private final ChatModel sync;
    private final StreamingChatModel streaming;
    private final AiChatOptions options;

    public StandardAiChat(String name, ChatModel sync, StreamingChatModel streaming, AiChatOptions options) {
        this.name = name;
        // Wrap with trace-logging proxies up-front so every engine
        // (Arthur/Ford/Marvin/...) and every caller (engines,
        // memory-compaction, future tools) gets the same input/output
        // trace under logger {@code de.mhus.vance.brain.ai.trace}.
        // The wrappers are zero-cost when trace is disabled.
        this.sync = sync == null ? null : new LoggingChatModel(name, sync);
        this.streaming = wrapStreaming(name, streaming, options);
        this.options = options;
    }

    /**
     * Stack the streaming model: trace-logging inside, retry / chain-fallback
     * outside. The resilient layer hides transient provider failures (rate
     * limits, demand spikes, 5xx) from every engine — they see one
     * {@link StreamingChatModel} that just works, or a clean
     * {@link AiChatException} when everything is genuinely down.
     *
     * <p>When {@code options.userNotifier} is set, the resilient layer also
     * pushes human-readable retry / chain-advance messages so the engine
     * call-site can surface them in the user-progress side-channel.
     */
    private static @Nullable StreamingChatModel wrapStreaming(
            String name, @Nullable StreamingChatModel raw, AiChatOptions options) {
        if (raw == null) {
            return null;
        }
        StreamingChatModel logged = new LoggingStreamingChatModel(name, raw);
        return new ResilientStreamingChatModel(
                List.of(new ChainEntry(logged, name, RetryPolicy.DEFAULT)),
                options.getUserNotifier());
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String ask(String question) {
        if (question == null || question.isBlank()) {
            throw new AiChatException("question is blank");
        }
        try {
            ChatResponse response = sync.chat(buildRequest(question));
            return response.aiMessage().text();
        } catch (RuntimeException e) {
            throw new AiChatException(
                    "Sync call failed for '" + name + "': " + e.getMessage(), e);
        }
    }

    @Override
    public String askStream(String question, Consumer<String> tokenConsumer) {
        if (question == null || question.isBlank()) {
            throw new AiChatException("question is blank");
        }
        if (tokenConsumer == null) {
            throw new AiChatException("tokenConsumer is null");
        }

        ChatRequest request = buildRequest(question);
        StringBuilder accumulator = new StringBuilder();
        CompletableFuture<String> result = new CompletableFuture<>();

        StreamingChatResponseHandler handler = new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {
                if (partial == null || partial.isEmpty()) {
                    return;
                }
                accumulator.append(partial);
                try {
                    tokenConsumer.accept(partial);
                } catch (RuntimeException e) {
                    log.warn("tokenConsumer threw for chat '{}': {}", name, e.toString());
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
        };

        try {
            streaming.chat(request, handler);
            return result.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new AiChatException(
                    "Stream failed for '" + name + "': " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiChatException("Stream interrupted for '" + name + "'", e);
        } catch (RuntimeException e) {
            throw new AiChatException(
                    "Stream failed for '" + name + "': " + e.getMessage(), e);
        }
    }

    @Override
    public ChatModel chatModel() {
        return sync;
    }

    @Override
    public StreamingChatModel streamingChatModel() {
        return streaming;
    }

    @Override
    public boolean isAvailable() {
        return sync != null && streaming != null;
    }

    private ChatRequest buildRequest(String question) {
        List<ChatMessage> messages = new ArrayList<>();
        String system = options.getSystemMessage();
        if (system != null && !system.isBlank()) {
            messages.add(SystemMessage.from(system));
        }
        messages.add(UserMessage.from(question));
        return ChatRequest.builder().messages(messages).build();
    }
}
