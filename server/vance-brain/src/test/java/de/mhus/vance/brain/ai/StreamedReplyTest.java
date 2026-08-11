package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link StreamedReply} carries the finish reason + output cap past the
 * point where engines used to reduce a completion to its {@code AiMessage},
 * so an empty reply can be diagnosed instead of guessed at.
 */
class StreamedReplyTest {

    private static final String COLLAPSE = "_transient glitch, try again._";

    private static ChatRequest request(@org.jspecify.annotations.Nullable Integer maxTokens) {
        ChatRequest.Builder b = ChatRequest.builder().messages(UserMessage.from("hi"));
        if (maxTokens != null) {
            b.maxOutputTokens(maxTokens);
        }
        return b.build();
    }

    private static StreamedReply capture(
            AiMessage message,
            @org.jspecify.annotations.Nullable FinishReason finish,
            @org.jspecify.annotations.Nullable Integer maxTokens) {
        ChatResponse.Builder b = ChatResponse.builder().aiMessage(message);
        if (finish != null) {
            b.finishReason(finish);
        }
        return StreamedReply.of(b.build(), request(maxTokens));
    }

    @Test
    void of_readsTheOutputCapOffTheRequest() {
        StreamedReply reply = capture(AiMessage.from(""), FinishReason.LENGTH, 8192);

        assertThat(reply.maxOutputTokens()).isEqualTo(8192);
        assertThat(reply.finishReason()).isEqualTo(FinishReason.LENGTH);
    }

    @Test
    void isEmpty_isFalseWhenOnlyToolCallsArePresent() {
        ToolExecutionRequest call = ToolExecutionRequest.builder()
                .id("c1").name("file_read").arguments("{}").build();
        StreamedReply reply = capture(AiMessage.from("", List.of(call)), FinishReason.TOOL_EXECUTION, 8192);

        // A tool call with no prose is the normal agentic turn, not a stall.
        assertThat(reply.isEmpty()).isFalse();
    }

    @Test
    void isEmpty_isTrueForBlankTextWithoutToolCalls() {
        assertThat(capture(AiMessage.from("   "), FinishReason.STOP, 8192).isEmpty()).isTrue();
    }

    @Test
    void atOutputCap_onlyForLengthFinishReason() {
        assertThat(capture(AiMessage.from(""), FinishReason.LENGTH, 8192).atOutputCap()).isTrue();
        assertThat(capture(AiMessage.from(""), FinishReason.STOP, 8192).atOutputCap()).isFalse();
        assertThat(capture(AiMessage.from(""), null, 8192).atOutputCap()).isFalse();
    }

    @Test
    void emptyReplyMessage_keepsCallerWordingWhenNotTruncated() {
        StreamedReply reply = capture(AiMessage.from(""), FinishReason.STOP, 8192);

        assertThat(reply.emptyReplyMessage(COLLAPSE, "parked.")).isEqualTo(COLLAPSE);
    }

    @Test
    void emptyReplyMessage_namesTheCapAndTheKnobWhenTruncated() {
        StreamedReply reply = capture(AiMessage.from(""), FinishReason.LENGTH, 8192);

        String msg = reply.emptyReplyMessage(COLLAPSE, "The worker stays BLOCKED.");

        assertThat(msg)
                .contains("output-token limit of 8192")
                .contains("maxTokens")
                .contains("The worker stays BLOCKED.")
                .doesNotContain("transient");
        // Stays a single italic run so the client renders it as one note.
        assertThat(msg).startsWith("_").endsWith("_");
    }

    @Test
    void emptyReplyMessage_omitsTheNumberWhenTheRequestCarriedNoCap() {
        StreamedReply reply = capture(AiMessage.from(""), FinishReason.LENGTH, null);

        assertThat(reply.emptyReplyMessage(COLLAPSE, null))
                .contains("reached its output-token limit before")
                .doesNotContain("limit of");
    }
}
