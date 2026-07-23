package de.mhus.vance.addon.brain.gtd;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The five GTD buckets (Things-style). A bucket is <b>derived</b> from an
 * action's {@code when}/{@code deadline} + today — see {@link GtdBucketResolver}
 * — it is not a stored folder.
 */
public enum GtdBucket {
    INBOX, TODAY, UPCOMING, ANYTIME, SOMEDAY;

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
