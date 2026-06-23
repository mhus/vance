package de.mhus.vance.foot.tools.file;

import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import org.jspecify.annotations.Nullable;

/**
 * Path-glob helpers for the {@code client_file_*} tools.
 *
 * <p>Java NIO's stock {@code PathMatcher("glob:**\/X")} has a sharp
 * edge: it matches {@code "dir/X"} but <em>not</em> {@code "X"} at the
 * root of the walk, because {@code **} insists on at least the trailing
 * path separator to consume. Every other find-style tool (ripgrep, fd,
 * fnmatch in fish) treats {@code **\/X} as "X anywhere, including the
 * root". {@link #buildGlobMatcher(String)} matches that intuition by
 * OR-ing in the root-level variant whenever the pattern starts with
 * {@code **\/}.
 *
 * <p>Used by {@link ClientFileFindTool} and {@link ClientFileGrepTool}.
 * A parallel copy lives under {@code de.mhus.vance.brain.tools.workspace}
 * because Foot cannot depend on {@code vance-shared} / {@code vance-brain}.
 */
final class GlobMatchers {

    private GlobMatchers() {}

    /**
     * Returns a {@link PathMatcher} for the given glob, with the
     * {@code **\/}-prefix root-match fix applied. {@code null} input
     * returns {@code null} so callers can keep their "no glob = no
     * filter" branch.
     */
    static @Nullable PathMatcher buildGlobMatcher(@Nullable String pathGlob) {
        if (pathGlob == null) return null;
        PathMatcher full = FileSystems.getDefault().getPathMatcher("glob:" + pathGlob);
        if (!pathGlob.startsWith("**/")) return full;
        PathMatcher rootVariant = FileSystems.getDefault().getPathMatcher(
                "glob:" + pathGlob.substring(3));
        return p -> full.matches(p) || rootVariant.matches(p);
    }
}
