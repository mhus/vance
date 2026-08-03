package de.mhus.vance.brain.thinkengine.action;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.data.message.AiMessage;
import org.junit.jupiter.api.Test;

/**
 * {@link ReasoningExtractor#extract} — only genuine
 * reasoning becomes "thoughts"; a plain answer never does.
 */
class ReasoningExtractorTest {

    @Test
    void thinkingField_isPreferred() {
        AiMessage reply = AiMessage.builder()
                .text("the answer")
                .thinking("separate-channel reasoning")
                .build();
        assertThat(ReasoningExtractor.extract(reply))
                .isEqualTo("separate-channel reasoning");
    }

    @Test
    void inlineThinkBlock_innerIsExtracted_tagsDropped() {
        AiMessage reply = AiMessage.from("<think>step by step</think>final answer");
        assertThat(ReasoningExtractor.extract(reply))
                .isEqualTo("step by step");
    }

    @Test
    void plainAnswer_withoutReasoning_yieldsEmpty() {
        // The bug: this used to be captured as "thoughts", duplicating
        // the answer in the thinking channel.
        AiMessage reply = AiMessage.from(
                "Nein, das hilft leider nicht. Frei abrufbar heißt nicht frei verwertbar.");
        assertThat(ReasoningExtractor.extract(reply)).isEmpty();
    }

    @Test
    void strayCloseTag_withoutOpen_isNotCaptured() {
        // A confused model echoing a correction and a lone </think>.
        AiMessage reply = AiMessage.from("Call the tool now.\n\nCall the tool now.</think>");
        assertThat(ReasoningExtractor.extract(reply)).isEmpty();
    }

    @Test
    void multipleThinkBlocks_areJoined() {
        AiMessage reply = AiMessage.from("<think>a</think>mid<think>b</think>end");
        assertThat(ReasoningExtractor.extract(reply)).isEqualTo("a\nb");
    }

    @Test
    void emptyText_yieldsEmpty() {
        assertThat(ReasoningExtractor.extract(AiMessage.from(""))).isEmpty();
    }
}
