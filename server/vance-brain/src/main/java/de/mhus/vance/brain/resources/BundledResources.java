package de.mhus.vance.brain.resources;

import java.util.List;

/**
 * The files of one resource area and where they came from: {@link #CLASSPATH}
 * or {@code central v<N>}.
 */
public record BundledResources(List<BundledResourceFile> files, String origin) {

    public static final String CLASSPATH = "classpath";

    private static final String CENTRAL_PREFIX = "central v";

    public static BundledResources central(List<BundledResourceFile> files, long version) {
        return new BundledResources(files, CENTRAL_PREFIX + version);
    }

    public boolean isCentral() {
        return origin.startsWith(CENTRAL_PREFIX);
    }
}
