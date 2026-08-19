package de.mhus.vance.toolpack.feed;

/**
 * The back channel's closed vocabulary.
 *
 * <p>The admission criterion is one sentence: <b>a signal describes the
 * item, not the reader.</b> {@code WRONG_CATEGORY} is a checkable statement
 * about an entry and useful to any aggregator; "like", "hide" or "read
 * later" describe the reader and stay out. The criterion is
 * self-enforcing — it also answers the requests nobody has made yet,
 * without a case-by-case argument.
 *
 * <p>Anything this set does not model is a deep link into the source's own
 * UI ({@link FeedItem#controlUrl()}), not a new verb and not a generic
 * {@code action(verb, payload)}. A source-declared verb catalogue with its
 * own payload schema would be an RPC tunnel with extra ceremony, and it
 * would have the UI render forms from a foreign definition with foreign
 * labels. Because the set is closed, labels and i18n are ours.
 */
public enum FeedSignal {

    /**
     * This entry is wrong: misfiled, mislabelled, broken, duplicated.
     * Carries a {@link FeedReportReason}.
     */
    REPORT,

    /**
     * Produce something for this entry and keep it — a translation, the
     * full text. Carries a {@link FeedRequestKind}.
     *
     * <p>Fire-and-forget like every signal: the result does not come back
     * in the response but on the next read of the item. A signal with a
     * return value would be the way in for synchronous RPC semantics.
     */
    REQUEST
}
