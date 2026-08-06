package de.mhus.vance.foot.tools.file;

import java.nio.file.Path;
import java.util.Set;

/**
 * Directory-noise filter for the recursive {@code client_file_*} walks.
 *
 * <p>Without it a grep across a JS monorepo scans dependency trees and build
 * output alongside the sources — measured on this workbench: 5 815 files
 * scanned and the 200-hit result cap consumed by {@code node_modules/},
 * {@code dist/} and a {@code tsconfig.tsbuildinfo}, so the answer the LLM
 * needed never made it into the response. Skipping generated content is what
 * every comparable tool (ripgrep, fd, ag) does by default.
 *
 * <p>The filter is a default, not a rule: every tool that applies it also
 * exposes {@code includeGenerated=true} for the cases where searching inside
 * a dependency is the actual intent.
 */
final class WalkFilters {

    private WalkFilters() {}

    /**
     * Directory names skipped anywhere in the tree. Deliberately restricted
     * to directories whose contents are checked-out dependencies, build
     * output, or tool state — never hand-written sources. {@code vendor/} is
     * left out on purpose: in Go and PHP projects it is routinely committed
     * and read.
     */
    private static final Set<String> SKIPPED_DIRS = Set.of(
            ".git", ".hg", ".svn",
            "node_modules", ".pnpm-store", "bower_components",
            "target", "build", "dist", "out",
            ".next", ".nuxt", ".svelte-kit", ".turbo", ".parcel-cache",
            ".gradle", ".m2",
            "__pycache__", ".venv", "venv", ".tox", ".mypy_cache", ".pytest_cache", ".ruff_cache",
            ".idea", ".vscode",
            ".cache", "coverage", ".nyc_output");

    /** File names skipped anywhere in the tree — build state, not content. */
    private static final Set<String> SKIPPED_FILES = Set.of(
            "tsconfig.tsbuildinfo", ".DS_Store");

    /**
     * Whether {@code file} is generated content that a source search should
     * step over. Checks every segment below {@code root}, so a nested
     * {@code packages/x/node_modules/…} is caught as well as a top-level one.
     */
    static boolean isGenerated(Path root, Path file) {
        if (SKIPPED_FILES.contains(file.getFileName().toString())) return true;
        Path rel;
        try {
            rel = root.relativize(file);
        } catch (IllegalArgumentException e) {
            // Different roots (e.g. a symlink escape) — nothing to judge.
            return false;
        }
        for (Path segment : rel) {
            if (SKIPPED_DIRS.contains(segment.toString())) return true;
        }
        return false;
    }
}
