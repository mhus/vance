package de.mhus.vance.brain.zarniwoop;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The research-pressure handler injects an escalating "wrap up" nudge as
 * the count of research-tool results in the live conversation crosses the
 * soft / strong thresholds — a gradient, not a cliff, and only research
 * tools count.
 */
class ResearchSearchPressureHandlerTest {

    private final ResearchSearchPressureHandler handler = new ResearchSearchPressureHandler();
    private final ThinkProcessDocument process =
            ThinkProcessDocument.builder().id("proc-1").build();

    @Test
    void belowSoftThreshold_returnsSameListUntouched() {
        List<ChatMessage> messages = researchResults(5);

        List<ChatMessage> out = handler.apply(messages, null, process);

        assertThat(out).isSameAs(messages);
    }

    @Test
    void atSoftThreshold_injectsGentleNudge() {
        List<ChatMessage> messages = researchResults(6);

        List<ChatMessage> out = handler.apply(messages, null, process);

        assertThat(out).hasSize(messages.size() + 1);
        assertThat(lastSystemText(out)).contains("Prioritise only the most important");
    }

    @Test
    void atStrongThreshold_injectsStrongNudge() {
        List<ChatMessage> messages = researchResults(12);

        List<ChatMessage> out = handler.apply(messages, null, process);

        assertThat(lastSystemText(out)).contains("Synthesise your answer NOW");
    }

    @Test
    void onlyResearchToolsCount_otherToolResultsIgnored() {
        List<ChatMessage> messages = new ArrayList<>(researchResults(3));
        for (int i = 0; i < 20; i++) {
            messages.add(ToolExecutionResultMessage.from(
                    "c-other-" + i, "file_read", "{}"));
        }

        List<ChatMessage> out = handler.apply(messages, null, process);

        // 3 research + 20 file_read = below the 6 soft threshold → no nudge.
        assertThat(out).isSameAs(messages);
    }

    private static List<ChatMessage> researchResults(int n) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from("research philosophical works on E=mc2"));
        for (int i = 0; i < n; i++) {
            messages.add(ToolExecutionResultMessage.from(
                    "call-" + i, "research_search", "{\"results\":[]}"));
        }
        return messages;
    }

    private static String lastSystemText(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof SystemMessage sm) {
                return sm.text();
            }
        }
        return "";
    }
}
