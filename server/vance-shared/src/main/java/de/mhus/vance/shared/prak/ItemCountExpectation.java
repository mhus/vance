package de.mhus.vance.shared.prak;

/**
 * Inclusive bracket on the number of items the analyzer is expected to
 * extract from a given span, derived heuristically by the cheap-path
 * pre-filter.
 *
 * <p>Used downstream by the sanitizer to compute the hard cap
 * ({@code max * hardCapMultiplier}, floored at the absolute minimum).
 * See {@code planning/memory-evaluation-pipeline.md} §4c.1.
 */
public record ItemCountExpectation(int min, int max) {

    public ItemCountExpectation {
        if (min < 0 || max < min) {
            throw new IllegalArgumentException(
                    "Invalid expectation range: min=" + min + " max=" + max);
        }
    }

    public static final ItemCountExpectation ACK_ONLY = new ItemCountExpectation(0, 2);
    public static final ItemCountExpectation NORMAL = new ItemCountExpectation(1, 5);
    public static final ItemCountExpectation MARKER_RICH = new ItemCountExpectation(3, 10);
}
