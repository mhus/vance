package de.mhus.vance.shared.prak;

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
    PINNED
}
