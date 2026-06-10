package de.mhus.vance.toolpack.research;

/**
 * The kind of result a search returns. Carried in {@link SearchRequest}
 * and {@link SearchResult}; matched against
 * {@link SearchProviderInstance#modalities()} when the dispatcher picks
 * an instance.
 *
 * <p>The enum is intentionally fixed in v1: tool schemas exposed to the
 * LLM list these values explicitly, so silently appending values from
 * add-ons would skew schema validation. New modalities go through a
 * pull-request — see {@code planning/zarniwoop-service.md} §2.
 */
public enum SearchModality {
    WEB,
    IMAGE,
    VIDEO,
    PDF,
    NEWS,
    ACADEMIC,
    BOOK,
    MAP,
    CODE,
    INTERNAL_DOC,
    RAG
}
