package de.mhus.vance.toolpack.feed;

/**
 * One {@link FeedItem#extras()} key a source says is worth showing, and what
 * to call it.
 *
 * <p>The alternative was a list in the reader, and it does not survive the
 * second source: a card looking for {@code originPlace} finds nothing at a
 * Mastodon instance and misses its {@code boosts}. The source owns its
 * vocabulary, so the source names it.
 *
 * <p>Declared in {@link FeedCapabilities}, not on the item — a label on every
 * entry is the same label twenty times a page. List order is display order;
 * empty means show none, the rule {@code signalsAccepted} already follows.
 * The values in {@code extras} stay free and untyped, and nothing filters on
 * them.
 */
public record FeedExtraField(String key, String label) {

    public FeedExtraField {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("extra field key is required");
        }
        key = key.trim();
        label = label == null || label.isBlank() ? key : label.trim();
    }

    public static FeedExtraField of(String key, String label) {
        return new FeedExtraField(key, label);
    }
}
