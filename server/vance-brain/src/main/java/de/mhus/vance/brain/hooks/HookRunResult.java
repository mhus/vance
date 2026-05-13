package de.mhus.vance.brain.hooks;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Outcome of a single hook run. Drives the terminal event-log row the
 * dispatcher writes.
 */
public record HookRunResult(
        Outcome outcome,
        Duration duration,
        int actionCount,
        @Nullable String errorPhase,
        @Nullable String errorMessage) {

    public enum Outcome {
        COMPLETED,
        FAILED,
        SKIPPED
    }

    public static HookRunResult completed(Duration duration, int actionCount) {
        return new HookRunResult(Outcome.COMPLETED, duration, actionCount, null, null);
    }

    public static HookRunResult failed(
            Duration duration, String phase, String message) {
        return new HookRunResult(Outcome.FAILED, duration, 0, phase, message);
    }

    public static HookRunResult skipped(Duration duration, String reason) {
        return new HookRunResult(Outcome.SKIPPED, duration, 0, "skipped", reason);
    }

    public Map<String, Object> toPayload() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("durationMs", duration == null ? 0L : duration.toMillis());
        if (actionCount > 0) {
            p.put("actionCount", actionCount);
        }
        if (errorPhase != null) {
            p.put("phase", errorPhase);
        }
        if (errorMessage != null) {
            p.put("error", errorMessage);
        }
        return p;
    }
}
