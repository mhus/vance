package de.mhus.vance.shared.prak;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Working-buffer strength of a span / chat message.
 *
 * <p>Derived Java-side from extracted items + static patterns (see
 * {@code planning/memory-evaluation-pipeline.md} §4b). The LLM does
 * <em>not</em> emit this — strength is a function of (item importance,
 * evidence coverage, hot-path markers, trivial patterns).
 *
 * <p>Persisted as a tag on {@code ChatMessageDocument} (e.g.
 * {@code STRENGTH:strong}), consistent with the tag convention from
 * {@code process-history-search.md}.
 */
public enum SpanStrength {

    /** Default — compaction first. 5-turn TTL in the working buffer. */
    WEAK,

    /** Default for substantive messages. 20-turn TTL. */
    NORMAL,

    /** Item-evidence with importance ≥ 4, or hot-path marker. 50-turn TTL. */
    STRONG,

    /** Compaction-immune. Set explicitly by user or recipe policy — never derived. */
    PINNED;

    /**
     * Prefix used when persisting strength as a tag on
     * {@code ChatMessageDocument.tags}. Together with the lower-cased
     * enum name (e.g. {@code STRENGTH:strong}) it forms the full tag.
     */
    public static final String TAG_PREFIX = "STRENGTH:";

    /**
     * Tag form for persistence — e.g. {@code "STRENGTH:strong"}. The
     * default {@link #NORMAL} is typically <em>not</em> persisted
     * (absence of any {@code STRENGTH:*} tag is the default), but the
     * tag form is still available for callers that need to write it
     * explicitly.
     */
    public String tag() {
        return TAG_PREFIX + name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parse a {@code STRENGTH:*} tag back into the enum, or {@code null}
     * if the input is not a recognised strength tag.
     */
    public static @Nullable SpanStrength fromTag(@Nullable String tag) {
        if (tag == null || !tag.startsWith(TAG_PREFIX)) return null;
        try {
            return valueOf(tag.substring(TAG_PREFIX.length()).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
