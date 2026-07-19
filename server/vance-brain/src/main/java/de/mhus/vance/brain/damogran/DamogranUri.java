package de.mhus.vance.brain.damogran;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

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

    /** Directory of a document path (parent), or {@code ""} at project root. */
    static String parentDir(String docPath) {
        int slash = docPath.lastIndexOf('/');
        return slash > 0 ? docPath.substring(0, slash) : "";
    }

    /** A resolved {@code vance:} document reference. {@code project} null = current. */
    record VanceRef(@Nullable String project, String path) {}

    /**
     * Resolves a {@code vance:} document URI into a {@link VanceRef}. Three forms:
     * <ul>
     *   <li>{@code vance:hello.tex} — same project, relative to the compose
     *       document's directory ({@code baseDir}). Legacy tex-compose behaviour:
     *       in {@code documents/tex1} it resolves to {@code documents/tex1/hello.tex}.</li>
     *   <li>{@code vance:/docs/x} — same project, root-absolute (leading slash).</li>
     *   <li>{@code vance://other-project/docs/x} — cross-project (authority =
     *       project name), root-relative in that project.</li>
     * </ul>
     * A blank {@code baseDir} makes the relative form root-relative.
     */
    static VanceRef resolveVance(@Nullable String baseDir, String uri) {
        String rest = uri.substring("vance:".length());

        if (rest.startsWith("//")) {
            String authorityAndPath = rest.substring(2);
            int slash = authorityAndPath.indexOf('/');
            if (slash <= 0) {
                throw new DamogranException("vance:// URI needs project and path: " + uri);
            }
            String project = authorityAndPath.substring(0, slash);
            String path = authorityAndPath.substring(slash + 1);
            while (path.startsWith("/")) {
                path = path.substring(1);
            }
            if (project.isBlank() || path.isBlank()) {
                throw new DamogranException("invalid vance:// URI: " + uri);
            }
            return new VanceRef(project, path);
        }

        boolean absolute = rest.startsWith("/");
        while (rest.startsWith("/")) {
            rest = rest.substring(1);
        }
        if (rest.isBlank()) {
            throw new DamogranException("empty document path in URI: " + uri);
        }
        String path = (absolute || baseDir == null || baseDir.isBlank()) ? rest : baseDir + "/" + rest;
        return new VanceRef(null, path);
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
