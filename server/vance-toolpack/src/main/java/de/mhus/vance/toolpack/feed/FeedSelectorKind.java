package de.mhus.vance.toolpack.feed;

/**
 * The shape of a selector, so a {@link FeedSelectorMode#FREEFORM} source
 * can tell the UI what kind of free text it expects.
 *
 * <p>Deliberately a closed set: it drives which input affordance the
 * configuration form renders, and an open set would mean rendering
 * fields we cannot label.
 */
public enum FeedSelectorKind {

    /** A source-side taxonomy entry ({@code category:world}). */
    CATEGORY,

    /** A tag without its leading marker ({@code hashtag:opensource}). */
    HASHTAG,

    /** A single author or account ({@code account:@someone@example.social}). */
    ACCOUNT,

    /** A whole-instance firehose variant ({@code public:local}, {@code public:remote}). */
    PUBLIC
}
