package de.mhus.vance.addon.brain.gtd;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The GTD buckets (Things-style). A bucket is <b>derived</b> from an action's
 * {@code when}/{@code deadline} + today — see {@link GtdBucketResolver} — it is
 * not a stored folder.
 *
 * <p>Two exceptions, and they are the same exception twice: {@link #INBOX} and
 * {@link #TRASH} <b>are</b> folders. Both hold actions that are outside the
 * work list — one because nobody has processed them yet, the other because
 * somebody put them away — and for both, "which folder" is the whole state.
 * Deriving them from {@code when} would need a second attribute that means
 * "ignore the first one".
 */
public enum GtdBucket {
    INBOX, TODAY, UPCOMING, ANYTIME, SOMEDAY, TRASH;

    /** Buckets that are a folder rather than a function of {@code when}. */
    public boolean isFolderBucket() {
        return this == INBOX || this == TRASH;
    }

    /** Lowercase wire name (`inbox`, `today`, …). */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parse a wire name back to a bucket; {@code null} when unknown/blank. */
    public static @Nullable GtdBucket fromWire(@Nullable String s) {
        if (s == null || s.isBlank()) return null;
        for (GtdBucket b : values()) {
            if (b.wireName().equals(s.trim().toLowerCase(Locale.ROOT))) return b;
        }
        return null;
    }
}
