package de.mhus.vance.toolpack.feed;

/**
 * What became of a signal — as much as can honestly be said.
 *
 * <p>Note what is missing: any statement about effect. How a source weighs
 * or de-duplicates reports is its own business, so the UI says "reported",
 * never "category changed".
 *
 * <p>Transport failures are not an outcome; they throw
 * {@link FeedException} so the dispatcher can route them to the failure
 * tracker and set a cooldown, exactly as a failed fetch does.
 */
public enum FeedSignalOutcome {

    /** The source took it. Nothing is promised beyond that. */
    ACCEPTED,

    /** This source does not accept this signal. Known before sending. */
    UNSUPPORTED,

    /** The source refused it — unknown item, malformed reason, rate limit. */
    REJECTED
}
