package de.mhus.vance.toolpack.feed;

import org.jspecify.annotations.Nullable;

/**
 * One selectable stream offered by a {@link FeedSelectorMode#ENUMERABLE}
 * source.
 *
 * <p>{@code value} is what travels back in {@link FeedFetch#selector()} —
 * opaque to Centauri. {@code label} is display text and {@code language}
 * an optional hint, so the configuration UI can group a source's taxonomy
 * without a second round-trip.
 */
public record FeedSelector(
        String value,
        String label,
        FeedSelectorKind kind,
        @Nullable String language) {

    public FeedSelector {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("selector value is required");
        }
        if (label == null || label.isBlank()) {
            label = value;
        }
        if (kind == null) {
            throw new IllegalArgumentException("selector kind is required for " + value);
        }
    }

    public static FeedSelector of(String value, String label, FeedSelectorKind kind) {
        return new FeedSelector(value, label, kind, null);
    }
}
