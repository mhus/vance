package de.mhus.vance.toolpack.feed;

/**
 * How a source's selectors come into existence — the difference between
 * a server-side taxonomy and an open, user-typed one.
 *
 * <p>Calling both "category" would leave the configuration UI with no
 * dropdown for the first kind or an empty one for the second, so the
 * source states which it is.
 */
public enum FeedSelectorMode {

    /**
     * A finite, server-side taxonomy. {@link FeedSourceInstance#listSelectors()}
     * is authoritative and the UI offers a multi-select.
     */
    ENUMERABLE,

    /**
     * Open-ended, user-typed selectors (hashtags, accounts). The UI offers
     * a free-text field per {@link FeedSelectorKind} and validates through
     * {@link FeedSourceInstance#validateSelector(String)}.
     */
    FREEFORM,

    /**
     * Exactly one stream, no selector at all — a plain single-endpoint
     * source. {@code listSelectors()} returns empty and the selector
     * string is ignored.
     */
    NONE
}
