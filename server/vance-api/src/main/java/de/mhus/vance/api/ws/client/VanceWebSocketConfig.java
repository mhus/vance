package de.mhus.vance.api.ws.client;

import java.net.URI;
import java.time.Duration;
import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * Immutable connection parameters for {@link VanceWebSocketClient}.
 */
@Value
@Builder
public class VanceWebSocketConfig {

    /** Target URL, e.g. {@code ws://brain.local:8080/ws} or {@code wss://...}. */
    URI uri;

    /**
     * Signed JWT carried as {@code Authorization: Bearer ...} on the handshake.
     */
    String jwtToken;

    /**
     * Connection profile — sent via {@code X-Vance-Profile}. Use the canonical
     * constants from {@code de.mhus.vance.api.ws.Profiles} where applicable
     * ({@code Profiles.FOOT}, {@code Profiles.WEB}, …).
     */
    String profile;

    /** SemVer of the client build — sent via {@code X-Vance-Client-Version}. */
    String clientVersion;

    /**
     * Optional human-readable client identifier — sent via
     * {@code X-Vance-Client-Name}. Logged on the brain side.
     */
    @Nullable
    String clientName;

    /** Handshake/connect timeout; default 10s if not set. */
    @Builder.Default
    Duration connectTimeout = Duration.ofSeconds(10);
}
