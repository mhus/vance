package de.mhus.vance.brain.ws;

import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime configuration exposed to the WebSocket layer.
 *
 * Bound from the {@code vance.ws.*} namespace in {@code application.yml}.
 * Defaults are picked so the server runs out of the box in dev.
 */
@Data
@ConfigurationProperties(prefix = "vance.ws")
public class VanceBrainProperties {

    /** SemVer advertised to clients in the welcome message. */
    private String serverVersion = "0.1.0";

    /** Integer wire-protocol version. Bump on incompatible changes. */
    private int protocolVersion = 1;

    /** Seconds between expected client pings. */
    private int pingIntervalSeconds = 30;

    /** Feature flags advertised to clients. */
    private List<String> capabilities = List.of();

    /** HTTP path the WebSocket is exposed on. */
    private String path = "/brain/ws";
}
