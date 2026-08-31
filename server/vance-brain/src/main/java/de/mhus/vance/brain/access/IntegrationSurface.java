package de.mhus.vance.brain.access;

import java.util.Locale;
import org.springframework.util.AntPathMatcher;

/**
 * One method + path shape an {@link IntegrationScopeProfile} opens.
 *
 * <p>The method is part of the surface and not an afterthought, because in this
 * tree several verbs routinely share one path — {@code /addon/links/entry} is
 * {@code POST} to add, {@code PATCH} to edit and {@code DELETE} to remove. A
 * path-only surface would hand a capture integration the delete button.
 *
 * <p>{@code pathPattern} is Ant-style ({@code *} within a segment, {@code **}
 * across segments) and relative to the tenant root. Matching is exact-ish on
 * purpose: a trailing-slash variant of the same route matches, a longer path
 * does not unless the pattern says {@code **}.
 */
public record IntegrationSurface(String method, String pathPattern) {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    /** Wildcard method, for a surface where every verb is intended. */
    public static final String ANY_METHOD = "*";

    public IntegrationSurface {
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("method is required (use \"*\" for any)");
        }
        if (pathPattern == null || pathPattern.isBlank()) {
            throw new IllegalArgumentException("pathPattern is required");
        }
        method = method.trim().toUpperCase(Locale.ROOT);
        pathPattern = pathPattern.trim();
        if (!pathPattern.startsWith("/")) {
            // A pattern that silently never matches is the worst failure here —
            // it looks configured and denies everything.
            throw new IllegalArgumentException(
                    "pathPattern must start with '/' and be relative to the tenant root "
                            + "(e.g. \"/addon/links/entry\") — got '" + pathPattern + "'");
        }
    }

    /** Convenience for the common case. */
    public static IntegrationSurface of(String method, String pathPattern) {
        return new IntegrationSurface(method, pathPattern);
    }

    /**
     * Whether this surface covers {@code requestMethod} on {@code tenantPath}
     * (the request URI with {@code /brain/{tenant}} already stripped).
     */
    public boolean matches(String requestMethod, String tenantPath) {
        if (!ANY_METHOD.equals(method) && !method.equalsIgnoreCase(requestMethod)) {
            return false;
        }
        String path = tenantPath;
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return MATCHER.match(pathPattern, path);
    }
}
