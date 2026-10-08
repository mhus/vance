package de.mhus.vance.brain.thinkengine.loop;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * The per-turn limits of one tool loop, resolved from {@link
 * EngineLoopProperties} and the process's recipe params.
 *
 * @param maxIterations opt-in round cap; {@code 0} = no cap (the default)
 * @param wallclockMinutes per-turn wallclock net; {@code 0} trips at once
 * @param idleStuckThreshold identical batches in a row that count as stuck; {@code 0} = off
 * @param pollThrottleStepMs base wait between status-only batches; {@code 0} = no throttle
 * @param pollThrottleMaxMs upper bound of that wait
 */
@Slf4j
public record LoopLimits(
        int maxIterations,
        int wallclockMinutes,
        int idleStuckThreshold,
        int pollThrottleStepMs,
        int pollThrottleMaxMs) {

    /** Recipe param for the opt-in round cap. */
    public static final String PARAM_MAX_ITERATIONS = "maxIterations";

    /** Recipe param narrowing (or widening) the per-turn wallclock net. */
    public static final String PARAM_MAX_WALLCLOCK_MINUTES = "maxWallclockMinutes";

    public static LoopLimits resolve(ThinkProcessDocument process, EngineLoopProperties properties) {
        return new LoopLimits(
                nonNegativeParam(process, PARAM_MAX_ITERATIONS, 0),
                nonNegativeParam(process, PARAM_MAX_WALLCLOCK_MINUTES, properties.getMaxWallclockMinutes()),
                properties.getIdleStuckThreshold(),
                properties.getPollThrottleStepMs(),
                properties.getPollThrottleMaxMs());
    }

    /**
     * Reads a non-negative integer recipe param. A negative or non-numeric
     * value is a typo, not an intention — it falls back to the default with
     * a warning rather than silently disabling (or tripping) a net.
     */
    private static int nonNegativeParam(ThinkProcessDocument process, String key, int fallback) {
        Map<String, Object> params = process.getEngineParams();
        @Nullable Object raw = params == null ? null : params.get(key);
        if (raw == null) return fallback;
        Integer parsed = null;
        if (raw instanceof Number n) {
            parsed = n.intValue();
        } else if (raw instanceof String str) {
            try {
                parsed = Integer.valueOf(str.strip());
            } catch (NumberFormatException ignored) {
                // handled below
            }
        }
        if (parsed == null || parsed < 0) {
            log.warn("process id='{}' ignoring {}='{}' — not a non-negative number", process.getId(), key, raw);
            return fallback;
        }
        return parsed;
    }
}
