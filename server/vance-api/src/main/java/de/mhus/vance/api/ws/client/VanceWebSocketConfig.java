package de.mhus.vance.api.ws.client;

import de.mhus.vance.api.ws.ClientType;
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

    /** Signed JWT carried as {@code Authorization: Bearer ...} on the handshake. */
    String jwtToken;

    /** Type of client — wire form sent via {@code X-Vance-Client-Type}. */
    ClientType clientType;

    /** SemVer of the client build — wire form sent via {@code X-Vance-Client-Version}. */
    String clientVersion;

    /** Optional session-resume id; null means „create new session". */
    @Nullable String sessionId;

    /** Handshake/connect timeout; default 10s if not set. */
    @Builder.Default
    Duration connectTimeout = Duration.ofSeconds(10);
}
