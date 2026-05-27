package de.mhus.vance.brain.prak;

/**
 * A single hot-path marker hit produced by {@link HotPathMarkerDetector}.
 *
 * <p>{@link #marker()} is the canonical lowercase form of the trigger
 * phrase ({@code "ab jetzt"}, {@code "merk dir"}). {@link #position()}
 * is the character offset into the source text — useful for ordering
 * multiple matches and for surfacing why a span was flagged.
 */
public record MarkerMatch(
        String marker,
        MarkerCategory category,
        int position) {
}
