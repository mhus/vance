package de.mhus.vance.toolpack.feed;

/**
 * Why an entry is being reported. Closed set — each value is a statement
 * about the entry that any aggregator can act on.
 */
public enum FeedReportReason {

    /** Filed under a category it does not belong to. */
    WRONG_CATEGORY,

    /** Tagged with a language that is not the language of the text. */
    WRONG_LANGUAGE,

    /** The target URL does not resolve, or resolves to something else. */
    BROKEN_LINK,

    /** The same story is already in the stream under another entry. */
    DUPLICATE,

    /** Advertising or content-farm material dressed as an article. */
    SPAM
}
