package de.mhus.vance.brain.thinkengine.action;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StructuredActionEngine#unstreamedTerminalMessage}:
 * the decision whether a terminal action's message still needs to be
 * streamed to the client because the model emitted no prose this
 * iteration (observed with cortecs glm-5.2, which packs the whole answer
 * into the structured action instead of streaming it).
 */
class StructuredActionEngineTest {

    private static EngineAction answer(String message) {
        return new EngineAction("ANSWER", "done", Map.of("message", message));
    }

    @Test
    void whenProseContainsMessage_returnsNull() {
        // Normal case: the model streamed the answer as prose already —
        // re-emitting it would double the reply.
        String out = StructuredActionEngine.unstreamedTerminalMessage(
                "Here is the answer.", answer("Here is the answer."));
        assertThat(out).isNull();
    }

    @Test
    void whenProseIsOnlyAPreamble_returnsActionMessage() {
        // glm-5.2 case: the model streamed a short preamble as content
        // but packed the real answer into the action message. The prose
        // does not contain the answer, so it must still be streamed —
        // otherwise the client shows only the preamble.
        String out = StructuredActionEngine.unstreamedTerminalMessage(
                "Here is the summary:",
                answer("## Result\n\nThe full answer body."));
        assertThat(out).isEqualTo("## Result\n\nThe full answer body.");
    }

    @Test
    void whenNoProseStreamed_returnsActionMessage() {
        // glm-5.2 case: nothing streamed, whole answer in the action.
        String out = StructuredActionEngine.unstreamedTerminalMessage(
                null, answer("Behoben."));
        assertThat(out).isEqualTo("Behoben.");
    }

    @Test
    void whenReplyTextBlank_returnsActionMessage() {
        String out = StructuredActionEngine.unstreamedTerminalMessage(
                "   ", answer("Behoben."));
        assertThat(out).isEqualTo("Behoben.");
    }

    @Test
    void whenNoProseAndNoMessage_returnsNull() {
        EngineAction noMessage = new EngineAction("ANSWER", "done", Map.of());
        assertThat(StructuredActionEngine.unstreamedTerminalMessage(null, noMessage))
                .isNull();
    }

    @Test
    void whenNoProseAndBlankMessage_returnsNull() {
        assertThat(StructuredActionEngine.unstreamedTerminalMessage("", answer("  ")))
                .isNull();
    }
}
