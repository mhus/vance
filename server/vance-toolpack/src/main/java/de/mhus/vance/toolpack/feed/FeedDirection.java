package de.mhus.vance.toolpack.feed;

/**
 * Direction a page is fetched in, relative to the cursor.
 *
 * <p>Both directions ride on the same cursor — see
 * {@link FeedFetch#cursor()}.
 */
public enum FeedDirection {

    /** Backwards in time: the endless scroll. Cursor is an upper bound. */
    OLDER,

    /** Forwards in time: pull-to-refresh. Cursor is a lower bound. */
    NEWER
}
