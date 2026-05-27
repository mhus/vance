package de.mhus.vance.brain.memory;

import de.mhus.vance.brain.ai.ModelInfo;
import de.mhus.vance.brain.prak.PrakProperties;
import dev.langchain4j.data.message.ChatMessage;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Decides whether — and how aggressively — a process needs to compact
 * its history before the next LLM call. Three-tier trigger
 * ({@link CompactionMode#SOFT} / {@link CompactionMode#HARD} /
 * {@link CompactionMode#EMERGENCY}) based on the ratio of estimated
 * prompt tokens to the model's context window.
 *
 * <p>Used by every engine that wants its prompt to fit. Previously
 * only Ford had this check inline; extracting it lets Arthur, Eddie,
 * Marvin, … all share the same logic and pick up the strength-aware
 * compaction path automatically.
 *
 * <p>Token estimation uses the cheap {@code chars/4} heuristic from
 * Ford — accurate enough for trigger decisions; the precise per-
 * provider tokenizer is a future refinement.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CompactionTriggerService {

    /** Avg chars per token for the cheap estimator. */
    static final int CHARS_PER_TOKEN = 4;

    private final PrakProperties prakProperties;

    /**
     * Pick a compaction mode from estimated tokens + model context
     * window. Returns {@link CompactionMode#NONE} when nothing needs
     * to happen yet.
     */
    public CompactionMode evaluate(int estimatedTokens, int contextWindowTokens) {
        if (contextWindowTokens <= 0) return CompactionMode.NONE;
        double ratio = (double) estimatedTokens / contextWindowTokens;
        if (ratio >= prakProperties.getCompactionEmergencyThreshold()) {
            return CompactionMode.EMERGENCY;
        }
        if (ratio >= prakProperties.getCompactionHardThreshold()) {
            return CompactionMode.HARD;
        }
        if (ratio >= prakProperties.getCompactionSoftThreshold()) {
            return CompactionMode.SOFT;
        }
        return CompactionMode.NONE;
    }

    /**
     * Convenience: estimate tokens + evaluate in one step.
     */
    public CompactionMode evaluate(List<ChatMessage> messages, ModelInfo modelInfo) {
        int est = estimateTokens(messages);
        return evaluate(est, modelInfo.contextWindowTokens());
    }

    /**
     * Cheap, provider-agnostic token estimator. {@code chars / 4} is
     * a known approximation for English-ish text — off by ~25% in
     * either direction, which is plenty for trigger decisions.
     */
    public int estimateTokens(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return 0;
        int totalChars = 0;
        for (ChatMessage m : messages) {
            String text = textOf(m);
            if (text != null) totalChars += text.length();
        }
        return totalChars / CHARS_PER_TOKEN;
    }

    private static String textOf(ChatMessage m) {
        // langchain4j ChatMessage has a text() method on most subtypes;
        // for the cheap estimator we don't need exact tokens — just total
        // string length. Use toString() as a robust fallback.
        try {
            // langchain4j ChatMessage has no common text() in newer API;
            // toString() includes role + content and is consistent.
            return m == null ? null : m.toString();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
