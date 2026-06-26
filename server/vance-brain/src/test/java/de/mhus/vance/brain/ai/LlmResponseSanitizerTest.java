package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pure-logic tests for {@link LlmResponseSanitizer}. No Spring
 * context, no engines — exercises the stripping convention against
 * verbatim samples taken from real reasoning-model output.
 */
class LlmResponseSanitizerTest {

    private final LlmResponseSanitizer sanitizer = new LlmResponseSanitizer();

    @Test
    void stripIsNoOpWhenModelDoesNotRequestIt() {
        String raw = "<think>internal</think>visible";
        ModelInfo model = model("ollama", "llama3", false);
        assertThat(sanitizer.strip(raw, model))
                .as("plain model passes content through unchanged")
                .isEqualTo(raw);
    }

    @Test
    void stripsThinkBlockForQwen3() {
        String raw = "<think>let me think about this\nstep one\nstep two</think>\n\nHere is the answer.";
        ModelInfo model = model("ollama", "qwen3:30b", true);
        assertThat(sanitizer.strip(raw, model))
                .isEqualTo("Here is the answer.");
    }

    @Test
    void stripsMultipleThinkBlocks() {
        String raw = "<think>first</think>part one<think>second</think>part two";
        assertThat(sanitizer.stripUnconditional(raw))
                .isEqualTo("part onepart two");
    }

    @Test
    void stripsHarmonyAnalysisChannel() {
        // Verbatim shape from a gpt-oss response: analysis channel before
        // the final channel, both separated by <|channel|> markers.
        String raw = "<|channel|>analysis<|message|>The user wants a chart.\n"
                + "I should call doc_create_kind.<|channel|>final<|message|>"
                + "Here's the chart you requested.";
        assertThat(sanitizer.stripUnconditional(raw))
                .isEqualTo("Here's the chart you requested.");
    }

    @Test
    void stripsHarmonyCommentaryChannel() {
        String raw = "<|channel|>commentary<|message|>side note for the developer"
                + "<|channel|>final<|message|>Visible reply.";
        assertThat(sanitizer.stripUnconditional(raw))
                .isEqualTo("Visible reply.");
    }

    @Test
    void leavesContentAlreadyCleanUntouched() {
        String clean = "Just the answer.\n\n```mermaid\nflowchart TD\n  A --> B\n```";
        assertThat(sanitizer.stripUnconditional(clean)).isEqualTo(clean);
    }

    @Test
    void collapsesExcessBlankLinesFromStrippedSurround() {
        // The reasoning block was on its own paragraph; after stripping
        // we'd be left with 3-4 blank lines in a row. Collapse to a
        // single blank-line gap so downstream parsing stays stable.
        String raw = "Intro line.\n\n<think>discussion</think>\n\nFollow-up.";
        assertThat(sanitizer.stripUnconditional(raw))
                .isEqualTo("Intro line.\n\nFollow-up.");
    }

    @Test
    void nullInputReturnsNull() {
        assertThat(sanitizer.strip(null, model("x", "y", true))).isNull();
    }

    @Test
    void nullModelReturnsContentUnchanged() {
        assertThat(sanitizer.strip("foo", null)).isEqualTo("foo");
    }

    @Test
    void stripsThinkInsideFencedMindmapBody() {
        // The qwen3 failure mode from the benchmark: the model writes
        // a ```mindmap fence with its thinking inside, no actual
        // hierarchy. After strip the fence body should be either
        // empty or contain whatever non-thinking content was left.
        String raw = "```mindmap\n<think>I should structure this as a tree</think>\n- Root\n```";
        String cleaned = sanitizer.stripUnconditional(raw);
        assertThat(cleaned).contains("- Root");
        assertThat(cleaned).doesNotContain("<think>");
        assertThat(cleaned).doesNotContain("</think>");
    }

    private static ModelInfo model(String provider, String name, boolean strip) {
        return new ModelInfo(provider, name, 8192, 4096, ModelSize.LARGE,
                Set.<ModelCapability>of(),
                ModelInfo.DEFAULT_TIMEOUT_SECONDS,
                ModelInfo.DEFAULT_ACTION_LOOP_CORRECTIONS,
                strip,
                /*messageParser*/ null,
                /*pricing*/ null);
    }
}
