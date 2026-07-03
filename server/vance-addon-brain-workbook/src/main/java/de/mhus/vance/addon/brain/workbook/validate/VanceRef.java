package de.mhus.vance.addon.brain.workbook.validate;

import org.jspecify.annotations.Nullable;

/**
 * A parsed {@code vance:} document reference from a fence attribute
 * ({@code config} / {@code uri} / {@code script} / {@code saveScript}).
 * Resolves the project-relative {@code path} and the optional {@code ?kind=}
 * hint. Resolution mirrors the runtime services: a leading {@code /} (after
 * the scheme) is project-absolute; a bare name is relative to the referencing
 * document's folder.
 */
public record VanceRef(String path, @Nullable String kind) {

    /**
     * Parse {@code raw} against {@code baseDocPath} (the page holding the
     * fence). Returns {@code null} when {@code raw} is blank.
     */
    public static @Nullable VanceRef parse(@Nullable String raw, String baseDocPath) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.strip();
        if (s.startsWith("vance:")) s = s.substring("vance:".length());

        String kind = null;
        int q = s.indexOf('?');
        if (q >= 0) {
            String query = s.substring(q + 1);
            s = s.substring(0, q);
            for (String part : query.split("&")) {
                int eq = part.indexOf('=');
                if (eq > 0 && part.substring(0, eq).equals("kind")) {
                    kind = part.substring(eq + 1).strip();
                }
            }
        }
        return new VanceRef(resolveRelative(baseDocPath, s.strip()), kind);
    }

    /**
     * Resolve {@code rel} against the folder of {@code basePath}. A
     * leading-slash {@code rel} is project-absolute.
     */
    static String resolveRelative(String basePath, String rel) {
        if (rel.startsWith("/")) return rel.substring(1);
        int slash = basePath.lastIndexOf('/');
        String parent = slash >= 0 ? basePath.substring(0, slash) : "";
        return parent.isEmpty() ? rel : parent + "/" + rel;
    }
}
