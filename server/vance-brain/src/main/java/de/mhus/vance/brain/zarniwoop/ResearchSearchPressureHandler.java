package de.mhus.vance.brain.zarniwoop;

import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.brain.thinkengine.TurnContextHandler;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Soft budget on research over-searching, implemented as a
 * {@link TurnContextHandler} so the engines stay research-agnostic. The
 * research tools return a {@code gaps} list that a thorough model dutifully
 * chases search-by-search; without a brake it can run many searches in a
 * single natural-stop turn. This handler injects an escalating "wrap up"
 * note whose weight grows with the number of research searches already
 * executed this turn — a gradient, not a cliff: the model keeps a sensible
 * result and decides itself when to synthesise. The engine's iteration cap
 * remains only as a hard runaway backstop.
 *
 * <p>Counts are derived directly from the live conversation (research-tool
 * result messages), so there is no separate counter and the window is
 * naturally the current turn.
 */
@Component
@Slf4j
public class ResearchSearchPressureHandler implements TurnContextHandler {

    /** Wire names of the research search tools that count toward the budget. */
    private static final Set<String> RESEARCH_TOOLS = Set.of(
            "research_search",
            "research_investigate",
            "research_rich",
            "research_search_expert");

    /** First (gentle) nudge at this many searches; strong nudge at the second. */
    private static final int SOFT_THRESHOLD = 6;
    private static final int STRONG_THRESHOLD = 12;

    @Override
    public List<ChatMessage> apply(
            List<ChatMessage> messages, ThinkEngineContext ctx, ThinkProcessDocument process) {
        int count = countResearchResults(messages);
        String note = pressureNote(count);
        if (note == null) {
            return messages;
        }
        log.trace("ResearchSearchPressure id='{}' count={} injecting nudge",
                process.getId(), count);
        // Ephemeral request-only augmentation — never mutate the canonical
        // conversation, so the nudge does not accumulate across iterations.
        List<ChatMessage> augmented = new ArrayList<>(messages);
        augmented.add(SystemMessage.from(note));
        return augmented;
    }

    private static int countResearchResults(List<ChatMessage> messages) {
        int n = 0;
        for (ChatMessage m : messages) {
            if (m instanceof ToolExecutionResultMessage tr
                    && tr.toolName() != null
                    && RESEARCH_TOOLS.contains(tr.toolName())) {
                n++;
            }
        }
        return n;
    }

    private static String pressureNote(int count) {
        if (count >= STRONG_THRESHOLD) {
            return "Research budget: you have run " + count + " searches this turn — "
                    + "each further search is costly and adds little. Synthesise your "
                    + "answer NOW from what you have gathered. Only search again if a "
                    + "single specific fact is truly essential and still missing.";
        }
        if (count >= SOFT_THRESHOLD) {
            return "Research budget: you have run " + count + " searches this turn. "
                    + "Prioritise only the most important remaining gaps; for minor gaps "
                    + "what you already have is enough. Move toward writing your answer.";
        }
        return null;
    }
}
