package de.mhus.vance.api.tools;

import java.nio.file.Path;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Shared walk defaults for the {@code file_*} tool family.
 *
 * <p>The family exists three times over: a generic wrapper ({@code file_grep})
 * that dispatches to a CLIENT backend on the user's machine
 * ({@code client_file_grep}, in {@code vance-foot}) or a WORK backend on a
 * server workspace ({@code work_file_grep}, in {@code vance-brain}). One tool
 * name, so one behaviour — a depth cap, result cap or noise filter that
 * differs per target makes the wrapper a lie.
 *
 * <p>This class is the single source for those numbers and for the
 * generated-content filter. It lives in {@code vance-api} because that is the
 * only module both sides may depend on: {@code vance-foot} is restricted to
 * {@code vance-api} by design, so a helper in {@code vance-shared} could not
 * be reached from the CLIENT implementations. Pure Java, no Spring, no
 * Jackson — it fits the module's contract-only rule.
 */
public final class FileWalkDefaults {

    private FileWalkDefaults() {}

    /** Recursion depth cap when the caller doesn't set {@code maxDepth}. */
    public static final int DEFAULT_MAX_DEPTH = 12;

    /**
     * Result-row cap when the caller doesn't set {@code limit}.
     *
     * <p>The <em>ceiling</em> deliberately stays per-tool and is not defined
     * here: grep caps at 1 000 match rows, find at 2 000 file rows, and those
     * are different units. What has to agree is CLIENT vs WORK for the same
     * tool name — see {@code WorkTargetToolSymmetryTest}.
     */
    public static final int DEFAULT_LIMIT = 200;

    /**
     * Directory names skipped anywhere in the tree. Deliberately restricted
     * to directories whose contents are checked-out dependencies, build
     * output, or tool state — never hand-written sources. {@code vendor/} is
     * left out on purpose: in Go and PHP projects it is routinely committed
     * and read.
     *
     * <p>Without this filter a grep across a JS monorepo scans dependency
     * trees and build output alongside the sources — measured on the Vance
     * workbench: 5 815 files scanned and the 200-hit cap consumed by
     * {@code node_modules/}, {@code dist/} and a {@code tsconfig.tsbuildinfo},
     * so the answer the LLM needed never made it into the response. Skipping
     * generated content is what every comparable tool (ripgrep, fd, ag) does
     * by default.
     *
     * <p>The filter is a default, not a rule: every tool that applies it also
     * exposes {@code includeGenerated=true} for the cases where searching
     * inside a dependency is the actual intent.
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
     * Whether a path <em>relative to the search root</em> points at generated
     * content a source search should step over. Every segment is checked, so
     * a nested {@code packages/x/node_modules/…} is caught as well as a
     * top-level one.
     *
     * <p>Takes a relative path on purpose: the CLIENT side walks absolute
     * filesystem paths, the WORK side carries paths relative to a RootDir.
     * Relativizing is the caller's job; judging is this method's.
     */
    public static boolean isGenerated(Path relativePath) {
        Path fileName = relativePath.getFileName();
        if (fileName != null && SKIPPED_FILES.contains(fileName.toString())) {
            return true;
        }
        for (Path segment : relativePath) {
            if (SKIPPED_DIRS.contains(segment.toString())) return true;
        }
        return false;
    }

    /**
     * Depth of a path relative to the search root: a file directly in the
     * root is depth 1. Mirrors what {@code Files.walk(root, maxDepth)} counts
     * on the CLIENT side, so {@code maxDepth=1} means "flat directory" on
     * both targets.
     */
    public static int depthOf(Path relativePath) {
        return relativePath.getNameCount();
    }

    /** Clamps a caller-supplied row cap into {@code [1, maxLimit]}. */
    public static int clampLimit(@Nullable Integer raw, int maxLimit) {
        if (raw == null) return Math.min(DEFAULT_LIMIT, maxLimit);
        return Math.min(maxLimit, Math.max(1, raw));
    }

    /** Clamps a caller-supplied depth cap; non-positive means "use the default". */
    public static int clampDepth(@Nullable Integer raw) {
        if (raw == null || raw <= 0) return DEFAULT_MAX_DEPTH;
        return raw;
    }
}
