package de.mhus.vance.brain.prak;

import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.prak.SpanStrength;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Context-assembler-side filter: drops chat messages tagged below the
 * configured strength threshold from the LLM-replay history.
 *
 * <p>Sits between {@code ChatMessageService.activeHistory(...)} and the
 * engine's {@code AiMessage}-projection. Persisted history is never
 * mutated; only the in-memory list passed to the next LLM call shrinks.
 * See {@code planning/memory-evaluation-pipeline.md} §6.1
 * ("Anwendung im Context-Assembler").
 *
 * <p>Strength is read from {@code ChatMessageDocument.tags} via
 * {@link SpanStrength#fromTag(String)} — untagged messages default to
 * {@link SpanStrength#NORMAL} so the filter is safe on histories that
 * predate the strength-tag write path.
 *
 * <p>No-op when the filter is disabled via {@link
 * PrakProperties#isContextFilterEnabled()} or the threshold is
 * {@link SpanStrength#WEAK} (which would keep everything anyway).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HistoryStrengthFilter {

    private final PrakProperties props;

    /**
     * Apply the filter to {@code history}. Returns the input list as-is
     * when no filtering is needed (disabled, empty input, or threshold
     * keeps everything), otherwise a fresh {@link ArrayList} with the
     * dropped messages removed.
     */
    public List<ChatMessageDocument> filter(@Nullable List<ChatMessageDocument> history) {
        if (history == null || history.isEmpty()) {
            return history == null ? List.of() : history;
        }
        if (!props.isContextFilterEnabled()) {
            return history;
        }
        SpanStrength threshold = props.getContextFilterMinStrength();
        if (threshold == null || threshold == SpanStrength.WEAK) {
            // WEAK threshold means "keep everything ≥ WEAK" — full history.
            return history;
        }

        List<ChatMessageDocument> kept = new ArrayList<>(history.size());
        int dropped = 0;
        for (ChatMessageDocument m : history) {
            SpanStrength s = strengthOf(m);
            if (s.ordinal() >= threshold.ordinal()) {
                kept.add(m);
            } else {
                dropped++;
            }
        }
        if (dropped > 0) {
            log.debug("HistoryStrengthFilter dropped {} of {} messages below {}",
                    dropped, history.size(), threshold);
        }
        return kept;
    }

    /**
     * Strength of a single message — by tag if present, else
     * {@link SpanStrength#NORMAL} (the implicit default the deriver
     * also uses).
     */
    static SpanStrength strengthOf(ChatMessageDocument m) {
        Set<String> tags = m.getTags();
        if (tags == null || tags.isEmpty()) {
            return SpanStrength.NORMAL;
        }
        for (String tag : tags) {
            SpanStrength s = SpanStrength.fromTag(tag);
            if (s != null) {
                return s;
            }
        }
        return SpanStrength.NORMAL;
    }
}
