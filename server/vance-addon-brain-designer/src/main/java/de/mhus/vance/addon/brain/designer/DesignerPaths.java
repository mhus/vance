package de.mhus.vance.addon.brain.designer;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Pure path arithmetic for the designer app — no I/O, fully unit-tested.
 *
 * <p>Everything the content route resolves goes through here, so the
 * traversal rules live in exactly one place:
 *
 * <ul>
 *   <li>A design is a <em>single</em> path segment directly under the app
 *       folder. Names starting with {@code _} are reserved (manifest,
 *       generated artefacts) and never designs.</li>
 *   <li>A file inside a design is addressed by a relative path whose
 *       segments are plain names — no {@code .}, no {@code ..}, no empty
 *       segments, no backslashes, no leading or trailing slash. The empty
 *       inner path maps to the design's entry file.</li>
 * </ul>
 */
public final class DesignerPaths {

    /** Manifest filename that makes a folder a designer app. */
    public static final String APP_MANIFEST = "_app.yaml";

    /** File inside a design folder that makes the folder a design. */
    public static final String ENTRY_FILE = "index.html";

    /** Optional per-design metadata file (title, description). */
    public static final String DESIGN_META_FILE = "design.yaml";

    /** Generated design catalogue written by {@code refresh()}. */
    public static final String INDEX_OUTPUT_PATH = "_index.md";

    private DesignerPaths() {}

    /**
     * Strips leading/trailing slashes from a folder path. Blank is an
     * error — an app always has a non-empty folder.
     */
    public static String normaliseFolder(String folder) {
        String f = folder == null ? "" : folder.trim();
        while (f.startsWith("/")) f = f.substring(1);
        while (f.endsWith("/")) f = f.substring(0, f.length() - 1);
        if (f.isEmpty()) {
            throw new IllegalArgumentException("folder must not be empty");
        }
        return f;
    }

    /** The app folder's manifest path: {@code <folder>/_app.yaml}. */
    public static String manifestPath(String appFolder) {
        return normaliseFolder(appFolder) + "/" + APP_MANIFEST;
    }

    /**
     * Whether a path segment (design candidate) is addressable as a design.
     * Reserved names — the manifest and generated artefacts — start with
     * {@code _}.
     */
    public static boolean isDesignName(String segment) {
        return !segment.isEmpty() && segment.charAt(0) != '_';
    }

    /**
     * The design-name segment of a document path relative to the app
     * folder, or empty when the document does not live inside a design
     * subfolder (loose file directly under the app folder, manifest,
     * generated artefact, or a path outside the folder entirely).
     */
    public static Optional<String> designNameOf(String appFolder, String path) {
        String prefix = normaliseFolder(appFolder) + "/";
        if (!path.startsWith(prefix)) {
            return Optional.empty();
        }
        String rest = path.substring(prefix.length());
        int slash = rest.indexOf('/');
        // A loose file directly under the app folder has no design segment.
        if (slash < 0) {
            return Optional.empty();
        }
        String name = rest.substring(0, slash);
        return isDesignName(name) ? Optional.of(name) : Optional.empty();
    }

    /**
     * Resolves a requested inner path inside one design to the full
     * document path, or empty when the request is invalid. The empty
     * inner path (or one ending in {@code /}) maps to the entry file,
     * so an iframe can load the design by its folder URL alone and
     * relative sub-resource URLs resolve naturally.
     *
     * <p>Invalid means: traversal ({@code ..}, {@code .}), empty or
     * {@code .}/dot-dot segments, backslashes, or a design name that is
     * not a single plain segment. The method composes the result purely
     * from validated segments — the caller never has to re-check that
     * the resolved path stayed inside the design folder.
     */
    public static Optional<String> resolveFile(String appFolder, String design, String innerPath) {
        if (!isPlainSegment(design) || !isDesignName(design)) {
            return Optional.empty();
        }
        String inner = normaliseInner(innerPath);
        if (inner == null) {
            return Optional.empty();
        }
        String base = normaliseFolder(appFolder) + "/" + design + "/";
        if (inner.isEmpty()) {
            return Optional.of(base + ENTRY_FILE);
        }
        return Optional.of(base + inner);
    }

    /**
     * Normalises the requested inner path: strips a leading/trailing
     * slash and maps the resulting empty path to empty ("directory URL —
     * use the entry file", handled by the caller). Returns {@code null}
     * when any segment is not a plain name — the caller turns that into
     * "no such file".
     */
    private static @Nullable String normaliseInner(String innerPath) {
        String p = innerPath == null ? "" : innerPath.trim();
        while (p.startsWith("/")) p = p.substring(1);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (p.isEmpty()) {
            return "";
        }
        for (String segment : p.split("/", -1)) {
            if (!isPlainSegment(segment)) {
                return null;
            }
        }
        return p;
    }

    /**
     * A plain path segment: non-empty, no dot or dot-dot, no backslash.
     * A segment may contain percent-decoded spaces or unicode — document
     * paths already allow those.
     */
    private static boolean isPlainSegment(String segment) {
        if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
            return false;
        }
        return segment.indexOf('\\') < 0 && segment.indexOf('/') < 0;
    }
}
