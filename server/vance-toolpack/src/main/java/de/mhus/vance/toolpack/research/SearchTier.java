package de.mhus.vance.toolpack.research;

/**
 * The "depth of control" a search request asks for.
 *
 * <ul>
 *   <li>{@link #NORMAL} — short tool schema (query, modality, num). The
 *       default for the LLM-facing {@code research_search} tool.</li>
 *   <li>{@link #EXPERT} — exposes the filter surface
 *       (site, filetype, dateRange, instance-pin). Used by
 *       {@code research_search_expert} which is held back as a deferred
 *       tool so the LLM only sees the extra fields when it asked for
 *       them via {@code describe_tool}.</li>
 * </ul>
 */
public enum SearchTier {
    NORMAL,
    EXPERT
}
