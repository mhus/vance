package de.mhus.vance.brain.magrathea;

import java.util.UUID;

/**
 * 8-hex-char run identifiers — short enough for log lines, wide enough
 * to be unique across a project. Matches the convention Slartibartfast
 * uses for its run buckets.
 */
public final class MagratheaRunIdGenerator {

    private MagratheaRunIdGenerator() {}

    /** Returns the first 8 hex chars of a fresh UUIDv4. */
    public static String fresh() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
