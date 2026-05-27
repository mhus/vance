package de.mhus.vance.brain.prak;

import de.mhus.vance.shared.prak.ItemCountExpectation;
import java.util.Set;

/**
 * Inputs the sanitizer needs that are external to the analyzer output
 * itself.
 *
 * <p>{@link #existingTurnIds()} is the set of chat-turn ids the
 * analyzer was given as input; evidence pointing outside this set is
 * treated as halluzination. {@link #substantialMessageCount()} feeds
 * the coverage check. {@link #expectedRange()} comes from the
 * cheap-path pre-filter.
 */
public record SanitizeContext(
        Set<String> existingTurnIds,
        int substantialMessageCount,
        ItemCountExpectation expectedRange) {
}
