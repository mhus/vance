package de.mhus.vance.brain.memory;

import de.mhus.vance.brain.prak.PrakProperties;
import de.mhus.vance.brain.prak.TrivialPatterns;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.prak.SpanStrength;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Picks the subset of the active history that should be compacted in
 * a given {@link CompactionMode}. Implements §6.1 of
 * {@code planning/memory-evaluation-pipeline.md} with the
 * "optimistic fallback" for unrated messages — TrivialPatterns
 * (ack / self-narration) is the stand-in for an explicit
 * {@code STRENGTH:weak} tag.
 *
 * <p>Always preserves the anchor (last K messages, per mode) verbatim,
 * regardless of strength. The split between "stay" and "compact" is:
 *
 * <table>
 *   <tr><th>Strength / Mode</th><th>SOFT</th><th>HARD</th><th>EMERGENCY</th></tr>
 *   <tr><td>{@code PINNED}</td><td>stay</td><td>stay</td><td>stay</td></tr>
 *   <tr><td>{@code STRONG}</td><td>stay</td><td>stay</td><td>compact</td></tr>
 *   <tr><td>{@code NORMAL}</td><td>stay</td><td>compact</td><td>compact</td></tr>
 *   <tr><td>{@code WEAK}</td><td>compact</td><td>compact</td><td>compact</td></tr>
 *   <tr><td>unrated + trivial</td><td>compact</td><td>compact</td><td>compact</td></tr>
 *   <tr><td>unrated + substantive</td><td>stay</td><td>compact</td><td>compact</td></tr>
 * </table>
 */
@Component
@RequiredArgsConstructor
public class StrengthAwareSelector {

    private final PrakProperties prakProperties;

    /**
     * Returns the prefix of {@code active} (chronologically oldest
     * first) that should be folded into the compaction summary. The
     * anchor — last K verbatim — is excluded.
     *
     * <p>Output is a stable sub-list view: same {@link ChatMessageDocument}
     * references, in their original order. Empty when nothing
     * qualifies (history under anchor, or every older message is
     * STRONG/PINNED).
     */
    public List<ChatMessageDocument> selectForCompaction(
            List<ChatMessageDocument> active, CompactionMode mode) {
        if (active == null || active.isEmpty()) return List.of();
        int anchor = anchorForMode(mode);
        int total = active.size();
        if (total <= anchor) return List.of();

        List<ChatMessageDocument> out = new ArrayList<>();
        for (int i = 0; i < total - anchor; i++) {
            ChatMessageDocument m = active.get(i);
            if (shouldCompact(m, mode)) {
                out.add(m);
            }
        }
        return out;
    }

    private int anchorForMode(CompactionMode mode) {
        return switch (mode) {
            case EMERGENCY -> Math.max(1, prakProperties.getEmergencyAnchor());
            case HARD      -> Math.max(1, prakProperties.getHardAnchor());
            case SOFT      -> Math.max(1, prakProperties.getSoftAnchor());
            case NONE      -> Integer.MAX_VALUE;
        };
    }

    private static boolean shouldCompact(ChatMessageDocument m, CompactionMode mode) {
        @Nullable SpanStrength s = readStrength(m);

        // PINNED is sacrosanct in every mode.
        if (s == SpanStrength.PINNED) return false;
        // EMERGENCY: everything except PINNED.
        if (mode == CompactionMode.EMERGENCY) return true;
        // STRONG survives SOFT + HARD.
        if (s == SpanStrength.STRONG) return false;
        // WEAK is always compacted (mode in SOFT/HARD).
        if (s == SpanStrength.WEAK) return true;
        // NORMAL: compacted in HARD, stays in SOFT.
        if (s == SpanStrength.NORMAL) return mode == CompactionMode.HARD;
        // Unrated: optimistic-fallback heuristic.
        String content = m.getContent() == null ? "" : m.getContent();
        boolean trivial = TrivialPatterns.isAck(content) || TrivialPatterns.isSelfNarration(content);
        if (trivial) return true;          // unrated-trivial = treat as WEAK
        return mode == CompactionMode.HARD; // unrated-substantive: HARD compacts, SOFT keeps
    }

    private static @Nullable SpanStrength readStrength(ChatMessageDocument m) {
        Set<String> tags = m.getTags();
        if (tags == null || tags.isEmpty()) return null;
        for (String tag : tags) {
            SpanStrength s = SpanStrength.fromTag(tag);
            if (s != null) return s;
        }
        return null;
    }
}
