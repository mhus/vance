package de.mhus.vance.brain.damogran;

import java.util.Locale;

/**
 * URI-scheme helpers for import/export dispatch. The scheme is the part before
 * the first {@code :} ({@code vance}, {@code http}, {@code https}, {@code git}).
 * A bare path (no scheme) is treated as workspace-local — scheme {@code ""}.
 */
final class DamogranUri {

    private DamogranUri() {}

    static String scheme(String uri) {
        int colon = uri.indexOf(':');
        return colon <= 0 ? "" : uri.substring(0, colon).toLowerCase(Locale.ROOT);
    }

    /** {@code vance:<path>} / {@code vance:/<path>} → {@code <path>}. */
    static String stripVance(String uri) {
        String path = uri.substring("vance:".length());
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.isBlank()) {
            throw new DamogranException("empty document path in URI: " + uri);
        }
        return path;
    }

    /** {@code git:<url>} → {@code <url>} (the inner URL keeps its own scheme). */
    static String stripGit(String uri) {
        String url = uri.substring("git:".length()).trim();
        if (url.isBlank()) {
            throw new DamogranException("empty git URL in URI: " + uri);
        }
        return url;
    }
}
