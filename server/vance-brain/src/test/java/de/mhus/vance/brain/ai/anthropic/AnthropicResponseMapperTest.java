package de.mhus.vance.brain.ai.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit-tests for {@link AnthropicResponseMapper}. The Anthropic SDK
 * classes are Kotlin-{@code final}; Mockito's inline mock-maker
 * (default since 5.x) handles them.
 *
 * <p>Mocks are always built into local variables before being passed
 * to {@code when(...).thenReturn(...)} — nesting helper-calls inside
 * a stubbing trips Mockito's "UnfinishedStubbingException".
 */
class AnthropicResponseMapperTest {

    @Test
    void textOnlyResponse_mapsToAiMessageWithText() {
        ContentBlock text = textContentBlock("Hello world");
        Usage usage = usageWith(10, 5, 0L, 0L);
        Message message = messageWith(List.of(text), usage, StopReason.END_TURN);

        ChatResponse response = AnthropicResponseMapper.toChatResponse(message);

        AiMessage ai = response.aiMessage();
        assertThat(ai.text()).isEqualTo("Hello world");
        assertThat(ai.hasToolExecutionRequests()).isFalse();
        assertThat(response.finishReason()).isEqualTo(FinishReason.STOP);
    }

    @Test
    void toolUseResponse_mapsToToolExecutionRequest_withRoundTrippedJson() {
        Map<String, Object> input = Map.of("location", "Berlin", "unit", "C");
        ContentBlock toolBlock = toolUseContentBlock(
                "call_42", "get_weather", JsonValue.from(input));
        Usage usage = usageWith(20, 8, 0L, 0L);
        Message message = messageWith(List.of(toolBlock), usage, StopReason.TOOL_USE);

        ChatResponse response = AnthropicResponseMapper.toChatResponse(message);

        AiMessage ai = response.aiMessage();
        assertThat(ai.hasToolExecutionRequests()).isTrue();
        List<ToolExecutionRequest> reqs = ai.toolExecutionRequests();
        assertThat(reqs).hasSize(1);
        ToolExecutionRequest req = reqs.get(0);
        assertThat(req.id()).isEqualTo("call_42");
        assertThat(req.name()).isEqualTo("get_weather");
        // Arguments are a JSON string — keys are present, values match.
        // We don't assert key order to stay robust against Jackson-config
        // changes; the contract is "valid JSON parseable back to input".
        assertThat(req.arguments()).contains("\"location\":\"Berlin\"");
        assertThat(req.arguments()).contains("\"unit\":\"C\"");
        assertThat(response.finishReason()).isEqualTo(FinishReason.TOOL_EXECUTION);
    }

    @Test
    void cacheCounters_areExposedAsAnthropicTokenUsage() {
        ContentBlock text = textContentBlock("ok");
        Usage usage = usageWith(100, 20, 4096L, 8192L);
        Message message = messageWith(List.of(text), usage, StopReason.END_TURN);

        ChatResponse response = AnthropicResponseMapper.toChatResponse(message);

        TokenUsage tokenUsage = response.tokenUsage();
        assertThat(tokenUsage).isInstanceOf(AnthropicTokenUsage.class);
        AnthropicTokenUsage anthropicUsage = (AnthropicTokenUsage) tokenUsage;
        assertThat(anthropicUsage.inputTokenCount()).isEqualTo(100);
        assertThat(anthropicUsage.outputTokenCount()).isEqualTo(20);
        assertThat(anthropicUsage.getCacheCreationInputTokens()).isEqualTo(4096L);
        assertThat(anthropicUsage.getCacheReadInputTokens()).isEqualTo(8192L);
    }

    @Test
    void cacheCounters_defaultToZero_whenSdkOmitsThem() {
        // Anthropic only emits the cache fields when caching is active —
        // calls without cache_control markers come back without them.
        Usage usage = mock(Usage.class);
        when(usage.inputTokens()).thenReturn(50L);
        when(usage.outputTokens()).thenReturn(10L);
        when(usage.cacheCreationInputTokens()).thenReturn(Optional.empty());
        when(usage.cacheReadInputTokens()).thenReturn(Optional.empty());
        ContentBlock text = textContentBlock("ok");
        Message message = messageWith(List.of(text), usage, StopReason.END_TURN);

        ChatResponse response = AnthropicResponseMapper.toChatResponse(message);

        AnthropicTokenUsage anthropicUsage = (AnthropicTokenUsage) response.tokenUsage();
        assertThat(anthropicUsage.getCacheCreationInputTokens()).isZero();
        assertThat(anthropicUsage.getCacheReadInputTokens()).isZero();
    }

    @Test
    void stopReason_mapsAllKnownValues() {
        assertThat(finishReasonFor(StopReason.END_TURN)).isEqualTo(FinishReason.STOP);
        assertThat(finishReasonFor(StopReason.STOP_SEQUENCE)).isEqualTo(FinishReason.STOP);
        assertThat(finishReasonFor(StopReason.MAX_TOKENS)).isEqualTo(FinishReason.LENGTH);
        assertThat(finishReasonFor(StopReason.TOOL_USE)).isEqualTo(FinishReason.TOOL_EXECUTION);
        // PAUSE_TURN / REFUSAL aren't in our explicit table — they fall
        // through to OTHER so the caller still sees a finish reason.
        assertThat(finishReasonFor(StopReason.PAUSE_TURN)).isEqualTo(FinishReason.OTHER);
        assertThat(finishReasonFor(StopReason.REFUSAL)).isEqualTo(FinishReason.OTHER);
    }

    @Test
    void mixedTextAndToolUse_yieldsBothInAiMessage() {
        // Anthropic occasionally emits a leading text block alongside the
        // tool_use block when the model "explains" the call before making
        // it. AiMessage.from(text, tools) preserves both.
        ContentBlock text = textContentBlock("Looking that up for you.");
        ContentBlock tool = toolUseContentBlock(
                "call_x", "search", JsonValue.from(Map.of("q", "vance")));
        Usage usage = usageWith(30, 12, 0L, 0L);
        Message message = messageWith(List.of(text, tool), usage, StopReason.TOOL_USE);

        ChatResponse response = AnthropicResponseMapper.toChatResponse(message);

        AiMessage ai = response.aiMessage();
        assertThat(ai.text()).isEqualTo("Looking that up for you.");
        assertThat(ai.hasToolExecutionRequests()).isTrue();
        assertThat(ai.toolExecutionRequests()).hasSize(1);
    }

    // ──────────────────── helpers ────────────────────

    private static FinishReason finishReasonFor(StopReason reason) {
        ContentBlock text = textContentBlock("ok");
        Usage usage = usageWith(1, 1, 0L, 0L);
        Message message = messageWith(List.of(text), usage, reason);
        return AnthropicResponseMapper.toChatResponse(message).finishReason();
    }

    private static Message messageWith(
            List<ContentBlock> content, Usage usage, StopReason stopReason) {
        Message message = mock(Message.class);
        when(message.content()).thenReturn(content);
        when(message.usage()).thenReturn(usage);
        when(message.stopReason()).thenReturn(Optional.of(stopReason));
        return message;
    }

    private static ContentBlock textContentBlock(String text) {
        TextBlock textBlock = mock(TextBlock.class);
        when(textBlock.text()).thenReturn(text);
        ContentBlock block = mock(ContentBlock.class);
        when(block.text()).thenReturn(Optional.of(textBlock));
        when(block.toolUse()).thenReturn(Optional.empty());
        return block;
    }

    private static ContentBlock toolUseContentBlock(String id, String name, JsonValue input) {
        ToolUseBlock toolBlock = mock(ToolUseBlock.class);
        when(toolBlock.id()).thenReturn(id);
        when(toolBlock.name()).thenReturn(name);
        when(toolBlock._input()).thenReturn(input);
        ContentBlock block = mock(ContentBlock.class);
        when(block.text()).thenReturn(Optional.empty());
        when(block.toolUse()).thenReturn(Optional.of(toolBlock));
        return block;
    }

    private static Usage usageWith(long input, long output, long cacheCreate, long cacheRead) {
        Usage usage = mock(Usage.class);
        when(usage.inputTokens()).thenReturn(input);
        when(usage.outputTokens()).thenReturn(output);
        when(usage.cacheCreationInputTokens()).thenReturn(
                cacheCreate > 0 ? Optional.of(cacheCreate) : Optional.empty());
        when(usage.cacheReadInputTokens()).thenReturn(
                cacheRead > 0 ? Optional.of(cacheRead) : Optional.empty());
        return usage;
    }
}
