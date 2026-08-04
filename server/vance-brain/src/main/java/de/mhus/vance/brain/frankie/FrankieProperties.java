package de.mhus.vance.brain.frankie;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code vance.frankie.*} — tunables for the Frankie focused-worker
 * engine. All values are safety-net thresholds. Frankie itself is
 * endless-by-design — these only catch runaway loops and stuck models.
 */
@Data
@ConfigurationProperties(prefix = "vance.frankie")
public class FrankieProperties {

    /**
     * Wallclock budget per process, in minutes. When a Frankie
     * process has been running longer than this, the loop blocks on
     * the next iteration. Counts wall time including suspends —
     * prevents Suspend-Resume gaming.
     */
    private int maxWallclockMinutes = 60;

    /**
     * Idle-stuck threshold — number of consecutive identical
     * tool-call batch hashes before the engine treats the loop as
     * stuck. Hash = tool-name + JSON hash of args.
     */
    private int idleStuckThreshold = 5;

    /**
     * Poll-throttle step in ms. After a tool batch that only polled a
     * running background job (exec_status), the engine pauses before the
     * next LLM round so it doesn't hammer the status tool in a tight loop
     * (each poll is a full model call — tokens + latency). The wait grows
     * progressively: {@code step} on the first consecutive poll, {@code
     * 2·step} on the second, … capped at {@link #pollThrottleMaxMs}. It
     * resets to zero as soon as the model does real (non-polling) work
     * again — so a job that finishes fast is noticed quickly, while a
     * long build backs off. Transparent to the model. {@code 0} disables
     * the throttle. The sleep is chunked so a halt/ESC still lands within
     * ~1s.
     */
    private int pollThrottleStepMs = 5000;

    /** Upper bound for the progressive poll-throttle (see {@link #pollThrottleStepMs}). */
    private int pollThrottleMaxMs = 30000;
}
