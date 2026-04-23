package de.mhus.vance.shared.document;

import org.jspecify.annotations.Nullable;

/**
 * Virtual folder — never persisted, derived from document paths.
 *
 * <p>{@code path} is the full folder path (e.g. {@code "notes/thesis"}),
 * {@code name} is the last segment (e.g. {@code "thesis"}),
 * {@code parentPath} is the prefix above it (may be empty for top-level
 * folders), {@code documentCount} counts all documents whose path starts
 * inside this folder (direct and transitive),
 * {@code subfolderCount} counts direct subfolders.
 */
public record FolderInfo(
        String path,
        String name,
        @Nullable String parentPath,
        int documentCount,
        int subfolderCount) {
}
