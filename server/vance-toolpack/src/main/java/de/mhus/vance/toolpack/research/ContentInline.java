package de.mhus.vance.toolpack.research;

/**
 * How a {@link ContentReference}'s payload travels to the LLM.
 *
 * <ul>
 *   <li>{@link #EMBED_TEXT} — short text bodies (snippets, Wikipedia
 *       extracts under ~30 KB) come back inside the tool-result JSON,
 *       no extra fetch needed.</li>
 *   <li>{@link #STASH_ON_DEMAND} — the instance can produce bytes
 *       (PDFs, large HTMLs) but only does so when the engine calls
 *       {@link SearchProviderInstance#loadContent}. The loaded bytes
 *       land in the project workspace temp-root via
 *       {@code ZarniwoopContentStore} and the engine attaches the file
 *       to the next LLM call.</li>
 * </ul>
 */
public enum ContentInline {
    EMBED_TEXT,
    STASH_ON_DEMAND
}
