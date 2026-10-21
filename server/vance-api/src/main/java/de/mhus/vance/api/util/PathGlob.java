package de.mhus.vance.api.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Ant-style glob matcher, used by both the brain and the {@code vance-liver}
 * backup tool ({@code planning/user-backup.md} §4.3) so the deny-list the
 * server applies and the deny-list the client writes cannot drift.
 *
 * <p><b>Syntax</b> (relative to a project root):
 *
 * <ul>
 *   <li>{@code *} — any number of characters within a single path segment
 *       (no {@code /});</li>
 *   <li>{@code **} — any number of segments, including zero;</li>
 *   <li>{@code ?} — exactly one character within a single segment;</li>
 *   <li>anything else — literal.</li>
 * </ul>
 *
 * <p><b>Empty pattern</b> is rejected — an empty glob would silently match
 * nothing, or, depending on how a caller treats a miss, silently match
 * everything. Both are wrong; the constructor fails fast instead. Use
 * {@link #ANY} for the unconditional match.
 *
 * <p><b>Path semantics</b>: input paths are interpreted with {@code /} as the
 * segment separator, regardless of platform. The empty path is matched only
 * by {@code "**"}. A leading {@code /} on the input is ignored — patterns are
 * always project-relative.
 *
 * <p><b>Match anchoring</b>: a pattern without a leading {@code **} is
 * anchored to the first segment. {@code foo/*.md} matches {@code foo/x.md}
 * but not {@code bar/foo/x.md}; {@code **}{@code /foo/*.md} matches both.
 * Same rule the Spring {@code AntPathMatcher} uses, which is what the rest
 * of the brain's surface matching (see {@code IntegrationSurface}) relies on.
 *
 * <p><b>Pure</b>: the matcher has no Spring / Mongo dependency. {@code vance-api}
 * forbids both (per {@code CLAUDE.md}), and {@code vance-liver} reuses the
 * JAR without dragging the rest of the brain along.
 */
public final class PathGlob {

    /** Sentinel pattern matching every path (and only the empty path? no — {@code **} matches every path). */
    public static final String ANY = "**";

    private final String pattern;
    private final String[] segments;

    private PathGlob(String pattern, String[] segments) {
        this.pattern = pattern;
        this.segments = segments;
    }

    /**
     * Compile {@code pattern}. Throws {@link IllegalArgumentException} for an
     * empty pattern or one that contains {@code /} directly adjacent to a
     * wildcard in a way that would always misbehave — a {@code **} followed
     * by a trailing slash is a copy-paste hazard that matches the same set
     * as {@code **} alone.
     */
    public static PathGlob compile(@Nullable String pattern) {
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("glob pattern must not be empty");
        }
        String trimmed = pattern.trim();
        if (trimmed.endsWith("/") || trimmed.contains("//")) {
            throw new IllegalArgumentException(
                    "glob pattern must not end with '/' or contain '//' segments: '" + pattern + "'");
        }
        String[] segments = trimmed.split("/", -1);
        // split with limit -1 keeps trailing empties — drop them, they cannot
        // match anything meaningful here.
        if (segments.length > 0 && segments[segments.length - 1].isEmpty()) {
            String[] shortened = new String[segments.length - 1];
            System.arraycopy(segments, 0, shortened, 0, shortened.length);
            segments = shortened;
        }
        return new PathGlob(trimmed, segments);
    }

    /** The pattern string the glob was compiled from, normalized (trimmed). */
    public String pattern() {
        return pattern;
    }

    /**
     * Whether {@code path} matches this glob. {@code null} or empty input
     * matches nothing except the explicit {@code "**"} pattern.
     */
    public boolean matches(@Nullable String path) {
        if (path == null) return false;
        // Strip a single leading '/' — patterns are project-relative and
        // some callers will pass the leading slash from a request URI.
        String p = path;
        if (p.startsWith("/")) p = p.substring(1);
        if (p.isEmpty()) {
            return segments.length == 1 && "**".equals(segments[0]);
        }
        String[] input = p.split("/", -1);
        if (input.length > 0 && input[input.length - 1].isEmpty()) {
            String[] shortened = new String[input.length - 1];
            System.arraycopy(input, 0, shortened, 0, shortened.length);
            input = shortened;
        }
        return matchSegments(segments, 0, input, 0);
    }

    /** Whether any pattern in {@code globs} matches {@code path}. Empty list never matches. */
    public static boolean anyMatch(List<PathGlob> globs, @Nullable String path) {
        for (PathGlob g : globs) {
            if (g.matches(path)) return true;
        }
        return false;
    }

    /** Compile a list of patterns, rejecting the list on any single failure so a typo never half-applies. */
    public static List<PathGlob> compileAll(List<String> patterns) {
        Objects.requireNonNull(patterns, "patterns");
        List<PathGlob> out = new ArrayList<>(patterns.size());
        for (String p : patterns) {
            out.add(compile(p));
        }
        return List.copyOf(out);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (!(o instanceof PathGlob other)) return false;
        return pattern.equals(other.pattern);
    }

    @Override
    public int hashCode() {
        return pattern.hashCode();
    }

    @Override
    public String toString() {
        return "PathGlob[" + pattern + "]";
    }

    // ── matching ─────────────────────────────────────────────────────

    /**
     * Recursive match. Two indices advance independently: each call consumes
     * one or more segments from {@code pattern} and one from {@code input}.
     * {@code **} in the pattern branches both ways (zero or more segments).
     *
     * <p>Anchor rule: a non-{@code **} first segment anchors the pattern to
     * the start of the input. Implemented by giving {@code **} a special
     * case at index 0 (it can match zero segments).
     */
    private static boolean matchSegments(String[] pat, int pi, String[] in, int ii) {
        int pIdx = pi;
        int iIdx = ii;
        while (pIdx < pat.length) {
            String p = pat[pIdx];
            if ("**".equals(p)) {
                // **/foo matches foo and a/b/foo. Greedy + backtracking:
                // try ** matching zero segments first; fall through to one
                // more if the rest fails.
                if (pIdx == pat.length - 1) {
                    return true; // trailing ** matches everything (incl. empty)
                }
                for (int tryLen = 0; iIdx + tryLen <= in.length; tryLen++) {
                    if (matchSegments(pat, pIdx + 1, in, iIdx + tryLen)) {
                        return true;
                    }
                }
                return false;
            }
            if (iIdx >= in.length) return false;
            if (!segmentMatches(p, in[iIdx])) return false;
            pIdx++;
            iIdx++;
        }
        return iIdx == in.length;
    }

    /** Match one pattern segment against one input segment. */
    private static boolean segmentMatches(String patternSeg, String inputSeg) {
        int pi = 0;
        int ii = 0;
        while (pi < patternSeg.length() && ii < inputSeg.length()) {
            char pc = patternSeg.charAt(pi);
            if (pc == '*') {
                // Greedy star: consume the rest if possible; backtrack by
                // advancing ii one character at a time.
                if (pi == patternSeg.length() - 1) {
                    return true;
                }
                for (int skip = ii; skip <= inputSeg.length(); skip++) {
                    if (segmentMatchesImpl(patternSeg, pi + 1, inputSeg, skip)) {
                        return true;
                    }
                }
                return false;
            }
            if (pc == '?') {
                ii++;
                pi++;
                continue;
            }
            if (pc != inputSeg.charAt(ii)) return false;
            pi++;
            ii++;
        }
        // Trailing * in pattern, rest of input consumed
        while (pi < patternSeg.length() && patternSeg.charAt(pi) == '*') {
            pi++;
        }
        return pi == patternSeg.length() && ii == inputSeg.length();
    }

    /** Tail-recursive step after the leading * has been consumed. */
    private static boolean segmentMatchesImpl(String patternSeg, int pi, String inputSeg, int ii) {
        int pIdx = pi;
        int iIdx = ii;
        while (pIdx < patternSeg.length() && iIdx < inputSeg.length()) {
            char pc = patternSeg.charAt(pIdx);
            if (pc == '*') {
                for (int skip = iIdx; skip <= inputSeg.length(); skip++) {
                    if (segmentMatchesImpl(patternSeg, pIdx + 1, inputSeg, skip)) {
                        return true;
                    }
                }
                return false;
            }
            if (pc == '?') {
                pIdx++;
                iIdx++;
                continue;
            }
            if (pc != inputSeg.charAt(iIdx)) return false;
            pIdx++;
            iIdx++;
        }
        while (pIdx < patternSeg.length() && patternSeg.charAt(pIdx) == '*') {
            pIdx++;
        }
        return pIdx == patternSeg.length() && iIdx == inputSeg.length();
    }
}
