package de.mhus.vance.brain.ws;

import de.mhus.vance.brain.enginemessage.EngineWsHandshakeInterceptor;
import de.mhus.vance.brain.enginemessage.EngineWsServerHandler;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * Spring wiring for the WebSocket endpoints.
 *
 * <p>Three endpoints:
 * <ul>
 *   <li>The user-facing multi-channel WebSocket at
 *       {@link VanceBrainProperties.Paths#getExternal()} (canonical:
 *       {@code /brain/{tenant}/ws}), fronted by
 *       {@link VanceHandshakeInterceptor} for JWT auth. Carries the
 *       {@code session} channel (chat-frames wrapped in
 *       {@link de.mhus.vance.api.ws.LiveEnvelope}). Future channels
 *       ({@code documents}, {@code notify}, {@code progress},
 *       {@code control}) are reserved at the protocol level.</li>
 *   <li>The pod-to-pod chat tunnel at
 *       {@link VanceBrainProperties.Paths#getInternalChat()} (canonical:
 *       {@code /internal/{tenant}/ws/chat}), fronted by
 *       {@link InternalChatHandshakeInterceptor} for shared-secret +
 *       identity-forward auth. Carries raw {@code WebSocketEnvelope}s
 *       (no live wrapping) — the Face-Pod {@link LiveChatTunnel} peels
 *       and re-wraps. Same {@link VanceWebSocketHandler} pipeline as the
 *       legacy external chat endpoint used before the Live-WS migration.</li>
 *   <li>The pod-internal {@code /internal/engine-bind} WebSocket used by
 *       cross-pod {@code EngineMessage} routing — fronted by
 *       {@link EngineWsHandshakeInterceptor} for shared-secret auth, and
 *       additionally gated upstream by the {@code InternalAccessFilter}
 *       (path-prefix + constant-time token comparison).</li>
 * </ul>
 */
@Configuration
@EnableWebSocket
@EnableConfigurationProperties(VanceBrainProperties.class)
public class WebSocketConfig {

    @Bean
    public WebSocketConfigurer vanceWebSocketConfigurer(
            VanceWebSocketHandler handler,
            LiveWebSocketHandler liveHandler,
            VanceHandshakeInterceptor interceptor,
            InternalChatHandshakeInterceptor internalChatInterceptor,
            EngineWsServerHandler engineHandler,
            EngineWsHandshakeInterceptor engineInterceptor,
            VanceBrainProperties properties) {
        return registry -> {
            VanceBrainProperties.Paths paths = properties.getPaths();
            // Browser WebSocket sends an Origin header that Spring matches
            // against the registered allowed list; the default refuses any
            // cross-origin upgrade. We accept any origin because auth is
            // JWT-only — the upgrade itself is gated by BrainAccessFilter
            // (token + tenant cross-check) rather than the page's origin.
            registry.addHandler(liveHandler, paths.getExternal())
                    .addInterceptors(interceptor)
                    .setAllowedOrigins("*");
            // Pod-to-pod chat tunnel: VanceWebSocketHandler pipeline with
            // identity forwarded by the face-pod, shared-secret gated.
            // Origin is meaningless for cluster-internal traffic;
            // InternalAccessFilter + handshake-interceptor are the real auth,
            // K8s NetworkPolicy keeps the path off the external ingress.
            registry.addHandler(handler, paths.getInternalChat())
                    .addInterceptors(internalChatInterceptor)
                    .setAllowedOrigins("*");
            registry.addHandler(engineHandler, "/internal/engine-bind")
                    .addInterceptors(engineInterceptor)
                    .setAllowedOrigins("*");
        };
    }

    /**
     * Bumps WebSocket frame size limits past the JSR-356 default of 8 KiB
     * so {@code transfer-chunk} frames (default 64 KiB raw, ~88 KiB after
     * Base64 + JSON envelope), large session-list / agent-doc payloads
     * and bulk tool-result frames (a 200-hit {@code file_grep} with
     * context across a big codebase legitimately serialises to
     * ~1–4 MiB) fit. Tomcat closes the socket with code 1009 when an
     * inbound frame exceeds this — the corresponding close-reason is
     * {@code "The decoded text message was too big for the output buffer
     * and the endpoint does not support partial messages"}. Bumped from
     * 1 MiB to 8 MiB to leave headroom for large client-side tool
     * results without rebuilding the chunking protocol.
     */
    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(8 * 1024 * 1024);
        container.setMaxBinaryMessageBufferSize(8 * 1024 * 1024);
        return container;
    }
}
