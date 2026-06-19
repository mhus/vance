package de.mhus.vance.brain.lunkwill;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code vance.lunkwill.*} — tunables for the Lunkwill focused-worker
 * engine. All values are safety-net thresholds. Lunkwill itself is
 * endless-by-design — these only catch runaway loops and stuck models.
 */
@Data
@ConfigurationProperties(prefix = "vance.lunkwill")
public class LunkwillProperties {

    /**
     * Wallclock budget per process, in minutes. When a Lunkwill
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
}
