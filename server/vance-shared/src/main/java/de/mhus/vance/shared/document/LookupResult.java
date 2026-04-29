package de.mhus.vance.shared.document;

import org.jspecify.annotations.Nullable;

/**
 * Result of a cascade document lookup — carries the resolved content
 * plus the source that owned it. Callers that need the original
 * {@link DocumentDocument} (e.g. to read metadata or stream the
 * underlying storage blob) get it via {@link #document()}; resource-
 * sourced results have no document and fill the field with {@code null}.
 *
 * @param path     normalized path the lookup matched (without leading slash)
 * @param content  document content as UTF-8 string
 * @param source   which cascade layer the value came from
 * @param document the owning {@link DocumentDocument}; {@code null} for
 *                 {@link Source#RESOURCE} results
 */
public record LookupResult(
        String path,
        String content,
        Source source,
        @Nullable DocumentDocument document) {

    /** Cascade layer that produced a {@link LookupResult}. */
    public enum Source {
        /** The user's project. Innermost; overrides {@link #VANCE} and {@link #RESOURCE}. */
        PROJECT,
        /** The tenant-wide {@code _vance} system project. Overrides {@link #RESOURCE}. */
        VANCE,
        /** A classpath resource under {@code vance-defaults/}. Outermost / fallback. */
        RESOURCE
    }
}
