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
 * <p>Four endpoints:
 * <ul>
 *   <li>The user-facing chat WebSocket at
 *       {@link VanceBrainProperties.Paths#getChat()} (canonical:
 *       {@code /brain/{tenant}/ws/chat}), fronted by
 *       {@link VanceHandshakeInterceptor} for JWT auth.</li>
 *   <li>The user-facing multi-channel live WebSocket at
 *       {@link VanceBrainProperties.Paths#getLive()} (canonical:
 *       {@code /brain/{tenant}/ws/live}), also fronted by
 *       {@link VanceHandshakeInterceptor} for JWT auth. v1 only carries the
 *       {@code session} channel and is additive — no production client hits
 *       it until Schritt 3/4 of the Live-WS migration.</li>
 *   <li>The pod-to-pod chat tunnel at
 *       {@link VanceBrainProperties.Paths#getInternalChat()} (canonical:
 *       {@code /internal/{tenant}/ws/chat}), fronted by
 *       {@link InternalChatHandshakeInterceptor} for shared-secret +
 *       identity-forward auth. Same {@link VanceWebSocketHandler} pipeline
 *       as the external chat endpoint — the home-pod sees a forwarded
 *       user-connection.</li>
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
            registry.addHandler(handler, paths.getChat())
                    .addInterceptors(interceptor)
                    .setAllowedOrigins("*");
            registry.addHandler(liveHandler, paths.getLive())
                    .addInterceptors(interceptor)
                    .setAllowedOrigins("*");
            // Pod-to-pod chat tunnel: same VanceWebSocketHandler pipeline,
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
     * Base64 + JSON envelope) and large session-list / agent-doc payloads
     * fit. 1 MiB matches the spec's per-chunk hard limit.
     */
    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(1024 * 1024);
        container.setMaxBinaryMessageBufferSize(1024 * 1024);
        return container;
    }
}
