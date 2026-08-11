package de.mhus.vance.foot.tools.file;

import de.mhus.vance.api.tools.FileWalkDefaults;
import java.nio.file.Path;

/**
 * Directory-noise filter for the recursive {@code client_file_*} walks.
 *
 * <p>Thin adapter over {@link FileWalkDefaults}: this side walks absolute
 * filesystem paths, the shared judgement works on paths relative to the
 * search root. The skip list itself lives in {@code vance-api} so the WORK
 * backends behind the same {@code file_*} wrapper filter identically — a
 * {@code file_grep} that skips {@code node_modules} on one target and walks
 * into it on the other is the wrapper failing at its one job.
 */
final class WalkFilters {

    private WalkFilters() {}

    /**
     * Whether {@code file} is generated content that a source search should
     * step over. Checks every segment below {@code root}, so a nested
     * {@code packages/x/node_modules/…} is caught as well as a top-level one.
     */
    static boolean isGenerated(Path root, Path file) {
        Path fileName = file.getFileName();
        if (fileName != null && FileWalkDefaults.isGenerated(fileName)) {
            return true;
        }
        Path rel;
        try {
            rel = root.relativize(file);
        } catch (IllegalArgumentException e) {
            // Different roots (e.g. a symlink escape) — nothing to judge.
            return false;
        }
        return FileWalkDefaults.isGenerated(rel);
    }
}
