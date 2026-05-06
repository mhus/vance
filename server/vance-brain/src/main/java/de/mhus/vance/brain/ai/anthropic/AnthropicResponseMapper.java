package de.mhus.vance.brain.ai.anthropic;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.Usage;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

/**
 * Maps an Anthropic {@link Message} (response from
 * {@code client.messages().create(...)}) to a langchain4j
 * {@link ChatResponse}. The reverse direction of
 * {@link AnthropicRequestMapper}.
 *
 * <p>Stays close to the SDK's typed surface. Cache-token counters
 * ({@code cache_creation_input_tokens}, {@code cache_read_input_tokens})
 * are exposed via the SDK's {@code Usage} accessors when present, with
 * a JSON-property fallback for older SDK shapes.
 */
final class AnthropicResponseMapper {

    /** Used to serialise tool-use input back to a JSON string —
     *  langchain4j stores tool-call arguments as text. */
    private static final ObjectMapper JSON = new ObjectMapper();

    private AnthropicResponseMapper() {}

    static ChatResponse toChatResponse(Message message) {
        AiMessage ai = toAiMessage(message);
        TokenUsage usage = toTokenUsage(message);
        FinishReason finish = toFinishReason(message);
        ChatResponse.Builder b = ChatResponse.builder().aiMessage(ai);
        if (usage != null) {
            b.tokenUsage(usage);
        }
        if (finish != null) {
            b.finishReason(finish);
        }
        return b.build();
    }

    private static AiMessage toAiMessage(Message message) {
        StringBuilder text = new StringBuilder();
        List<ToolExecutionRequest> toolReqs = new ArrayList<>();
        for (ContentBlock block : message.content()) {
            Optional<TextBlock> textBlock = block.text();
            if (textBlock.isPresent()) {
                String t = textBlock.get().text();
                if (t != null && !t.isEmpty()) {
                    if (text.length() > 0) {
                        text.append('\n');
                    }
                    text.append(t);
                }
                continue;
            }
            Optional<ToolUseBlock> toolBlock = block.toolUse();
            if (toolBlock.isPresent()) {
                toolReqs.add(toToolExecutionRequest(toolBlock.get()));
            }
        }
        if (toolReqs.isEmpty()) {
            return AiMessage.from(text.toString());
        }
        if (text.length() == 0) {
            return AiMessage.from(toolReqs);
        }
        return AiMessage.from(text.toString(), toolReqs);
    }

    private static ToolExecutionRequest toToolExecutionRequest(ToolUseBlock block) {
        String args = serialiseInput(block);
        return ToolExecutionRequest.builder()
                .id(block.id())
                .name(block.name())
                .arguments(args)
                .build();
    }

    private static String serialiseInput(ToolUseBlock block) {
        try {
            JsonValue raw = block._input();
            // JsonValue extends JsonField as raw type — asObject erases.
            Object obj = raw.asObject().orElse(null);
            if (obj != null) {
                return JSON.writeValueAsString(obj);
            }
            return raw.toString();
        } catch (RuntimeException e) {
            return "{}";
        }
    }

    /**
     * Pulls token counts from the response, including cache creation
     * and cache read counters when present. Returns an
     * {@link AnthropicTokenUsage} so cache-aware downstream consumers
     * (LlmTraceRecorder, Insights) can pick up the extra fields via
     * {@code instanceof}.
     *
     * <p>Cache counters are typed accessors on the SDK's {@link Usage}
     * (since 2.x); a missing field produces an empty Optional which
     * collapses to {@code 0} here.
     */
    private static @Nullable TokenUsage toTokenUsage(Message message) {
        Usage usage = message.usage();
        if (usage == null) {
            return null;
        }
        long input = usage.inputTokens();
        long output = usage.outputTokens();
        long cacheCreate = usage.cacheCreationInputTokens().orElse(0L);
        long cacheRead = usage.cacheReadInputTokens().orElse(0L);
        return new AnthropicTokenUsage(
                (int) input, (int) output, cacheCreate, cacheRead);
    }

    private static @Nullable FinishReason toFinishReason(Message message) {
        StopReason reason = message.stopReason().orElse(null);
        if (reason == null) {
            return null;
        }
        // Map by the SDK's known constants — fall back to OTHER for
        // anything we don't recognise so the caller still sees a value.
        if (reason.equals(StopReason.END_TURN)) return FinishReason.STOP;
        if (reason.equals(StopReason.STOP_SEQUENCE)) return FinishReason.STOP;
        if (reason.equals(StopReason.MAX_TOKENS)) return FinishReason.LENGTH;
        if (reason.equals(StopReason.TOOL_USE)) return FinishReason.TOOL_EXECUTION;
        return FinishReason.OTHER;
    }

}
