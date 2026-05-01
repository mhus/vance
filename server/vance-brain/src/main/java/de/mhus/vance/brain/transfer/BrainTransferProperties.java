package de.mhus.vance.brain.transfer;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code vance.transfer.*} — brain-side tunables for the file-transfer
 * subsystem. See {@code specification/file-transfer.md} §7 for context.
 */
@Data
@ConfigurationProperties(prefix = "vance.transfer")
public class BrainTransferProperties {

    /**
     * POSIX mode mask AND-ed against {@code attrs.mode} from the
     * sender. Brain workspace runs as the pod user and never executes
     * transferred files directly, so the default trims execute bits.
     */
    private int brainModeMask = 0644;

    /** Hard upper bound on inbound uploads (Foot → Brain). */
    private long maxUploadSize = 100L * 1024 * 1024;

    /** Hard upper bound on outbound downloads (Brain → Foot). */
    private long maxDownloadSize = 100L * 1024 * 1024;

    /** Phase timeout in milliseconds. */
    private long phaseTimeoutMs = 30_000;

    /** Sweeper tick interval in milliseconds. */
    private long sweeperIntervalMs = 5_000;
}
