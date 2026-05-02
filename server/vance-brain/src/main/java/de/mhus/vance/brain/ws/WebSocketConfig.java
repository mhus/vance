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
 * <p>Two endpoints:
 * <ul>
 *   <li>The user-facing WebSocket at {@link VanceBrainProperties#getPath()}
 *       (typically {@code /brain/{tenant}/ws}) — fronted by
 *       {@link VanceHandshakeInterceptor} for JWT auth.</li>
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
            VanceHandshakeInterceptor interceptor,
            EngineWsServerHandler engineHandler,
            EngineWsHandshakeInterceptor engineInterceptor,
            VanceBrainProperties properties) {
        return registry -> {
            registry.addHandler(handler, properties.getPath())
                    .addInterceptors(interceptor)
                    // Browser WebSocket sends an Origin header that Spring matches
                    // against the registered allowed list; the default refuses any
                    // cross-origin upgrade. We accept any origin because auth is
                    // JWT-only — the upgrade itself is gated by BrainAccessFilter
                    // (token + tenant cross-check) rather than the page's origin.
                    .setAllowedOrigins("*");
            registry.addHandler(engineHandler, "/internal/engine-bind")
                    .addInterceptors(engineInterceptor)
                    // Origin is meaningless for cluster-internal pod-to-pod traffic;
                    // the InternalAccessFilter + handshake-interceptor gate is the
                    // real auth. K8s NetworkPolicy keeps this off the ingress.
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
