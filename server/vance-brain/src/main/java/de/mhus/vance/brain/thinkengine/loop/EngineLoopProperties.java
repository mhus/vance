package de.mhus.vance.brain.thinkengine.loop;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code vance.engine-loop.*} — defaults of the tool-loop safety nets shared
 * by Ford, Wowbagger and the session-mode identities. Recipes narrow or
 * widen the wallclock via {@code params.maxWallclockMinutes} and opt into a
 * round cap via {@code params.maxIterations}.
 */
@Data
@ConfigurationProperties(prefix = "vance.engine-loop")
public class EngineLoopProperties {

    /**
     * Per-turn wallclock net, in minutes — the generous last resort behind
     * the stuck-detecting nets, not a routine limit.
     */
    private int maxWallclockMinutes = 60;

    /**
     * Idle-stuck net: a turn stops when the same tool batch (names +
     * arguments) repeats this many times in a row. {@code 0} disables it.
     */
    private int idleStuckThreshold = 5;

    /** Base wait between consecutive status-only poll batches; grows per poll. */
    private int pollThrottleStepMs = 5000;

    /** Upper bound of the poll-throttle wait. */
    private int pollThrottleMaxMs = 30000;
}
