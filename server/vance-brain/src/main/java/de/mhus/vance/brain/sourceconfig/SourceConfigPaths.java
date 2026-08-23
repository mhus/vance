package de.mhus.vance.brain.sourceconfig;

import org.jspecify.annotations.Nullable;

/**
 * Where the three subsystems keep their instance documents.
 *
 * <p>Under {@code _vance/config/} rather than flat under {@code _vance/}
 * because {@code feeds} is already taken: {@code app: feeds} is the reading
 * surface a user creates somewhere in the project, and what lives here is the
 * <em>source</em> such a feed reads from. {@code config/} says which of the two
 * a path means, and it is where the tenant's other operator-only configuration
 * already sits ({@code project-kits.yaml}, {@code kit-sources.yaml}).
 */
public final class SourceConfigPaths {

    private SourceConfigPaths() {
        /* constants only */
    }

    /** Centauri feed endpoints. */
    public static final String FEEDS = "_vance/config/feeds/";

    /** Zarniwoop search endpoints. */
    public static final String RESEARCH = "_vance/config/research/";

    /** Jaglan mounts. */
    public static final String MOUNTS = "_vance/config/mounts/";

    /** Canonical suffix; {@code .yml} is accepted on read but never written. */
    public static final String SUFFIX = ".yaml";

    private static final String ALT_SUFFIX = ".yml";

    /** The document path of one instance under {@code prefix}. */
    public static String pathFor(String prefix, String name) {
        return prefix + name + SUFFIX;
    }

    /**
     * The instance name a path carries, or {@code null} when the path is not a
     * YAML directly under {@code prefix}. Listings are one level deep already;
     * this also guards the change listeners, which see every path.
     */
    public static @Nullable String nameFromPath(String prefix, @Nullable String path) {
        if (path == null || !path.startsWith(prefix)) {
            return null;
        }
        String rest = path.substring(prefix.length());
        if (rest.indexOf('/') >= 0) {
            return null;
        }
        String stem = stripSuffix(rest);
        return stem == null || stem.isBlank() ? null : stem;
    }

    private static @Nullable String stripSuffix(String filename) {
        String lower = filename.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(SUFFIX)) {
            return filename.substring(0, filename.length() - SUFFIX.length());
        }
        if (lower.endsWith(ALT_SUFFIX)) {
            return filename.substring(0, filename.length() - ALT_SUFFIX.length());
        }
        return null;
    }
}
