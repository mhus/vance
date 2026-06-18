package de.mhus.vance.brain.ws;

import java.util.List;
import lombok.Data;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime configuration exposed to the WebSocket layer.
 *
 * <p>Bound from the {@code vance.ws.*} namespace in {@code application.yml}.
 * Defaults are picked so the server runs out of the box in dev.
 *
 * <p>The {@link Paths} substructure carries the WebSocket endpoint paths.
 * It exists as a phase-transition vehicle for the Live-WS refactor (see
 * {@code planning/live-ws.md}): during migration we carry both the legacy
 * chat path and the new multi-channel + internal paths here. The end-state
 * keeps only {@link Paths#getChat()}, eventually renamed back to the bare
 * {@code /brain/{tenant}/ws} once the {@code /ws/chat} suffix is obsolete.
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
     * Endpoint paths grouped so the migration phases can carry the legacy
     * chat path alongside the new endpoints without renaming config keys
     * mid-migration. See {@code planning/live-ws.md} §10 for the full
     * migration sequence.
     */
    @Data
    public static class Paths {

        /**
         * User-facing chat endpoint. Canonical path is {@code /brain/*\/ws/chat}
         * (since Schritt 1 of the Live-WS migration). End-state: this field
         * disappears in favour of {@link #live} (renamed to bare {@code /ws}).
         */
        private String chat = "/brain/*/ws/chat";

        /**
         * User-facing multi-channel live endpoint. {@code null} = handler not
         * registered (the v1 implementation doesn't ship this yet — see
         * Schritt 2 of the migration).
         */
        private @Nullable String live;

        /**
         * Pod-to-pod chat tunnel for cross-pod sessions. {@code null} =
         * handler not registered (the v1 implementation doesn't ship this
         * yet — see Schritt 2 of the migration).
         */
        private @Nullable String internalChat;
    }
}
