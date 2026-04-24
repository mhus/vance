package de.mhus.vance.brain.events;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code vance.streaming.*} — flush policy for progressive chat
 * chunks sent to the client.
 */
@Data
@ConfigurationProperties(prefix = "vance.streaming")
public class StreamingProperties {

    /** Flush once the buffer holds at least this many characters. */
    private int chunkCharThreshold = 40;

    /** Force-flush the buffer after this many milliseconds regardless of size. */
    private long chunkFlushMs = 200;
}
