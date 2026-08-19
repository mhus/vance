package de.mhus.vance.toolpack.feed;

/**
 * What the source is asked to produce and keep.
 *
 * <p>Note what is <i>not</i> here: "give me a translation now". Vancetope
 * can translate a body itself through the light LLM service, and that path
 * needs no back channel at all. This enum is for the other case — the
 * source produces the variant once, for everyone, instead of every reader
 * paying for it separately.
 */
public enum FeedRequestKind {

    /** Produce and store a translation of this entry. */
    TRANSLATION,

    /** Fetch and store the full article behind a teaser-only entry. */
    FULL_TEXT
}
