package de.mhus.vance.brain.ai.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.RawContentBlockDeltaEvent;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.TextDelta;
import de.mhus.vance.brain.ai.AiChatException;
import de.mhus.vance.brain.ai.AiChatOptions;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
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
                forwardTextDelta(event, handler);
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
     * Pull every text-delta out of the event stream and feed it to the
     * caller's handler. Other event kinds (message_start, message_stop,
     * content_block_start/stop, tool-use input deltas) are accumulated
     * into the final {@link Message} but not surfaced as partials —
     * langchain4j's streaming contract is "text tokens only".
     */
    private static void forwardTextDelta(
            RawMessageStreamEvent event, StreamingChatResponseHandler handler) {
        Optional<RawContentBlockDeltaEvent> delta = event.contentBlockDelta();
        if (delta.isEmpty()) {
            return;
        }
        Optional<TextDelta> text = delta.get().delta().text();
        if (text.isEmpty()) {
            return;
        }
        String token = text.get().text();
        if (token == null || token.isEmpty()) {
            return;
        }
        try {
            handler.onPartialResponse(token);
        } catch (RuntimeException e) {
            log.warn("StreamingChatResponseHandler.onPartialResponse threw: {}",
                    e.toString());
        }
    }
}
