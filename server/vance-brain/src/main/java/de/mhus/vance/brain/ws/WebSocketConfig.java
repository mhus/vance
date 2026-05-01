package de.mhus.vance.brain.ws;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * Spring wiring for the WebSocket endpoint.
 *
 * Registers {@link VanceWebSocketHandler} at {@link VanceBrainProperties#getPath()}
 * with the {@link VanceHandshakeInterceptor} in front of it.
 */
@Configuration
@EnableWebSocket
@EnableConfigurationProperties(VanceBrainProperties.class)
public class WebSocketConfig {

    @Bean
    public WebSocketConfigurer vanceWebSocketConfigurer(
            VanceWebSocketHandler handler,
            VanceHandshakeInterceptor interceptor,
            VanceBrainProperties properties) {
        return registry -> registry
                .addHandler(handler, properties.getPath())
                .addInterceptors(interceptor)
                // Browser WebSocket sends an Origin header that Spring matches
                // against the registered allowed list; the default refuses any
                // cross-origin upgrade. We accept any origin because auth is
                // JWT-only — the upgrade itself is gated by BrainAccessFilter
                // (token + tenant cross-check) rather than the page's origin.
                .setAllowedOrigins("*");
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
