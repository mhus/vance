package de.mhus.vance.brain.ws;

import de.mhus.vance.api.ws.LiveEnvelope;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime configuration exposed to the WebSocket layer.
 *
 * <p>Bound from the {@code vance.ws.*} namespace in {@code application.yml}.
 * Defaults are picked so the server runs out of the box in dev.
 *
 * <p>The {@link Paths} substructure carries the WebSocket endpoint paths:
 * the user-facing multi-channel endpoint and the pod-to-pod chat tunnel.
 * See {@code planning/live-ws.md} for the protocol details.
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

    /**
     * Seconds between server-initiated WebSocket PING control frames on the
     * external endpoint. Browsers answer these transparently (they cannot send
     * pings from JS), which both keeps a quiet connection alive through an idle
     * proxy/middlebox and gives the server active liveness detection. {@code 0}
     * disables the sweep. Keep this below the smallest idle timeout on the path
     * (e.g. Caddy's write/idle timeout).
     */
    private int serverPingIntervalSeconds = 20;

    /**
     * Number of consecutive server pings a connection may miss (no PONG) before
     * it is considered stale and closed — which releases its session bind so a
     * reconnecting client resumes cleanly instead of hitting "bound elsewhere".
     */
    private int serverPingMaxMissed = 2;

    /**
     * Max milliseconds a single outbound frame may take to flush before the
     * connection is treated as stale and closed. Backs the
     * {@code ConcurrentWebSocketSessionDecorator} that fronts every external
     * session so a dead/slow client cannot block a server thread (ping sweep,
     * chat-streaming callback, notification push).
     */
    private int sendTimeLimitMs = 15_000;

    /**
     * Max bytes that may sit buffered for a single connection before it is
     * closed. Must comfortably exceed the largest single frame (see
     * {@code WebSocketConfig} max text buffer) so a legitimate large tool
     * result is not mistaken for a backed-up client.
     */
    private int sendBufferSizeBytes = 32 * 1024 * 1024;

    /** Feature flags advertised to clients. */
    private List<String> capabilities = List.of();

    /** WebSocket endpoint paths. See {@link Paths}. */
    private Paths paths = new Paths();

    /**
     * Endpoint paths grouped so the migration phases can re-shape them
     * without churning the rest of the config surface. See
     * {@code planning/live-ws.md} §10 for the full migration sequence.
     */
    @Data
    public static class Paths {

        /**
         * User-facing multi-channel endpoint. Carries the {@code session}
         * channel (chat-frames inside a {@link LiveEnvelope}) and is the
         * forward-looking home of {@code documents}/{@code notify}/{@code
         * progress}/{@code control} channels once those are defined.
         */
        private String external = "/brain/*/ws";

        /**
         * Pod-to-pod chat tunnel endpoint — the home-pod's receiver-side of
         * the cross-pod proxy flow. Off-ingress (gated by
         * {@code InternalAccessFilter} + K8s NetworkPolicy), shared-secret
         * authenticated, identity forwarded by the face-pod in dedicated
         * headers. See {@link InternalChatHandshakeInterceptor} and
         * {@code planning/live-ws.md} §8.
         */
        private String internalChat = "/internal/*/ws/chat";
    }
}
