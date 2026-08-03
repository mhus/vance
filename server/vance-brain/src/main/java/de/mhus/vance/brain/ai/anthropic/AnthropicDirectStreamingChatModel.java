package de.mhus.vance.brain.ai.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.RawContentBlockDeltaEvent;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.TextDelta;
import com.anthropic.models.messages.ThinkingDelta;
import de.mhus.vance.brain.ai.AiChatException;
import de.mhus.vance.brain.ai.AiChatOptions;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * Streaming counterpart of {@link AnthropicDirectChatModel}. Uses the
 * SDK's {@link StreamResponse} to read SSE events, forwards every text
 * delta to the langchain4j {@link StreamingChatResponseHandler}, and at
 * end-of-stream hands the assembled {@link Message} (built by
 * {@link MessageAccumulator}) to
 * {@link AnthropicResponseMapper#toChatResponse(Message)} for the final
 * {@link ChatResponse}.
 *
 * <p>Tool-use deltas don't appear as text — those land in the assembled
 * message as {@code tool_use} content blocks and are surfaced through
 * the response mapper. The handler's
 * {@link StreamingChatResponseHandler#onPartialResponse} contract only
 * promises text fragments, so silent tool-call assembly is correct.
 */
@Slf4j
public class AnthropicDirectStreamingChatModel implements StreamingChatModel {

    private final AnthropicClient client;
    private final String modelName;
    private final int maxTokens;
    private final AiChatOptions options;
    private final AnthropicDirectChatModel paramsBuilder;

    public AnthropicDirectStreamingChatModel(
            AnthropicClient client, String modelName, int maxTokens, AiChatOptions options) {
        this.client = client;
        this.modelName = modelName;
        this.maxTokens = maxTokens;
        this.options = options;
        // Reuse the sync model's param-building logic. It's a tiny
        // object — sharing it avoids drift between sync and streaming
        // request shapes (cache markers, beta headers, etc.).
        this.paramsBuilder = new AnthropicDirectChatModel(
                client, modelName, maxTokens, options);
    }

    @Override
    public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
        MessageCreateParams params = paramsBuilder.buildParams(request);
        MessageAccumulator accumulator = MessageAccumulator.create();
        try (StreamResponse<RawMessageStreamEvent> stream =
                     client.messages().createStreaming(params)) {
            stream.stream().forEach(event -> {
                accumulator.accumulate(event);
                forwardDelta(event, handler);
            });
        } catch (RuntimeException e) {
            log.warn("AnthropicDirectStreamingChatModel error: {}", e.toString());
            handler.onError(e);
            return;
        }
        try {
            Message message = accumulator.message();
            ChatResponse response = AnthropicResponseMapper.toChatResponse(message);
            handler.onCompleteResponse(response);
        } catch (RuntimeException e) {
            handler.onError(new AiChatException(
                    "Failed to assemble Anthropic streaming response", e));
        }
    }

    /**
     * Pull text- and thinking-deltas out of the event stream and feed
     * them to the caller's handler: text via {@code onPartialResponse},
     * extended-thinking via {@code onPartialThinking}. Other event kinds
     * (message_start, message_stop, content_block_start/stop, tool-use
     * input deltas, signature deltas) are accumulated into the final
     * {@link Message} but not surfaced as partials.
     */
    private static void forwardDelta(
            RawMessageStreamEvent event, StreamingChatResponseHandler handler) {
        Optional<RawContentBlockDeltaEvent> deltaEvent = event.contentBlockDelta();
        if (deltaEvent.isEmpty()) {
            return;
        }
        var delta = deltaEvent.get().delta();
        Optional<TextDelta> text = delta.text();
        if (text.isPresent()) {
            String token = text.get().text();
            if (token != null && !token.isEmpty()) {
                try {
                    handler.onPartialResponse(token);
                } catch (RuntimeException e) {
                    log.warn("StreamingChatResponseHandler.onPartialResponse threw: {}",
                            e.toString());
                }
            }
            return;
        }
        Optional<ThinkingDelta> thinking = delta.thinking();
        if (thinking.isPresent()) {
            String token = thinking.get().thinking();
            if (token != null && !token.isEmpty()) {
                try {
                    handler.onPartialThinking(new PartialThinking(token));
                } catch (RuntimeException e) {
                    log.warn("StreamingChatResponseHandler.onPartialThinking threw: {}",
                            e.toString());
                }
            }
        }
    }
}
