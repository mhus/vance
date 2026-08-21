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
 *
 * <p><b>Both counts are nullable, meaning "unknown".</b> For folders derived
 * from document paths they are always known. They are not for a
 * {@linkplain de.mhus.vance.shared.document.jaglan.JaglanPaths#PREFIX mounted}
 * folder, whose contents live in a foreign source: counting them means either
 * listing the source (a folder tree must not do that) or trusting a
 * declaration the source may not offer. A wrong number in a file tree costs
 * the trust in the whole view — {@code 0} reads as "empty folder", and the
 * number of entries fetched so far reads as "3 documents" where 5000 live —
 * so the absent number is modelled instead of guessed.
 */
public record FolderInfo(
        String path,
        String name,
        @Nullable String parentPath,
        @Nullable Integer documentCount,
        @Nullable Integer subfolderCount) {
}
