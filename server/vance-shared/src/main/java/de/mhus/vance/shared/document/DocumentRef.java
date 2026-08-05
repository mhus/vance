package de.mhus.vance.shared.document;

import org.jspecify.annotations.Nullable;

/**
 * A document reference resolved to its addressable parts by
 * {@link DocumentRefResolver}: the target project and a canonical path
 * (no leading/trailing slash, forward-slash separated, {@code .}/{@code ..}
 * collapsed). {@link #query} carries the raw query string of a
 * {@code vance:} URI ({@code kind=…} etc.) when present, so consumers that
 * need it (embeds, links) keep it without re-parsing; it is {@code null}
 * for a bare path.
 *
 * @param projectId target project (the {@code name} of the project — the
 *                  authority of a {@code vance://<project>/…} ref, or the
 *                  current project for a same-project ref)
 * @param path      canonical document path within {@link #projectId}
 * @param query     raw query string without the leading {@code ?}, or null
 */
public record DocumentRef(String projectId, String path, @Nullable String query) {

    /** Same-project resolved ref without a query. */
    public static DocumentRef of(String projectId, String path) {
        return new DocumentRef(projectId, path, null);
    }
}
