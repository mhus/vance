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
