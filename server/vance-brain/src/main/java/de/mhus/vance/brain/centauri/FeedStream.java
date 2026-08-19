package de.mhus.vance.brain.centauri;

/**
 * The smallest thing that has a cursor: one source instance, one selector.
 *
 * <p>{@code stream = instance × selector} is the modelling that carries both
 * examples. A source with a server-side taxonomy yields one stream per
 * category; a source with user-typed hashtags yields one per tag. Same
 * structure, different origin — which is why the configuration UI can treat
 * them alike while the source still decides where selectors come from.
 */
public record FeedStream(String sourceId, String selector) {

    public FeedStream {
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId is required");
        }
        selector = selector == null ? "" : selector.trim();
    }

    /**
     * Stable key used in the cursor bundle and as the second-level
     * tie-break of the merge. The separator cannot occur in a source id
     * (settings keys are dot-segmented) so the mapping is unambiguous.
     */
    public String key() {
        return sourceId + '|' + selector;
    }

    public static FeedStream parseKey(String key) {
        int cut = key.indexOf('|');
        if (cut < 0) {
            return new FeedStream(key, "");
        }
        return new FeedStream(key.substring(0, cut), key.substring(cut + 1));
    }
}
