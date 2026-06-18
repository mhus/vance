package de.mhus.vance.shared.redis;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import lombok.Data;
import org.jspecify.annotations.Nullable;
import org.springframework.aot.hint.annotation.Reflective;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection properties for the cross-pod messaging Redis instance.
 *
 * <p>Field-level config under {@code vance.redis.*}, with a convenience
 * URL alternative ({@code vance.redis.url} or the {@code VANCE_REDIS_URL}
 * env var). The URL form trumps the individual fields and is parsed
 * {@code redis[s]://[:password@]host[:port][/database]}.
 *
 * <p>Defaults match a local dev Redis on {@code localhost:6379}, no
 * password. Production deployments override via env or
 * {@code application.yml}.
 */
@Data
@ConfigurationProperties(prefix = "vance.redis")
@Reflective
public class VanceRedisProperties {

    /**
     * Master switch for Redis-backed cross-pod features. Default
     * {@code false} — single-pod / local-dev setups don't need Redis,
     * and most AI integration tests run without it. Set to {@code true}
     * in deployments that span multiple pods so live-features
     * (document-presence, future {@code documents.changed} push, cross-
     * session NOTIFY) can talk between pods.
     */
    private boolean enabled = false;

    private String host = "localhost";
    private int port = 6379;
    private int database = 0;

    /** Optional. Empty / null means no AUTH. */
    private @Nullable String password;

    private boolean ssl = false;

    /**
     * Convenience: a full Redis URL that overrides {@link #host},
     * {@link #port}, {@link #password}, {@link #database}, {@link #ssl}.
     * Useful for ops handing one env-var to the deployment.
     */
    @Value("${vance.redis.url:}")
    private String url;

    @PostConstruct
    public void parseUrl() {
        if (url == null || url.isBlank()) return;
        try {
            URI uri = URI.create(url);
            if (uri.getHost() != null) host = uri.getHost();
            if (uri.getPort() > 0) port = uri.getPort();
            if (uri.getUserInfo() != null) {
                String[] parts = uri.getUserInfo().split(":", 2);
                if (parts.length == 2) password = parts[1];
            }
            String path = uri.getPath();
            if (path != null && path.length() > 1) {
                try {
                    database = Integer.parseInt(path.substring(1));
                } catch (NumberFormatException ignored) {
                    // leave database at default
                }
            }
            ssl = uri.getScheme() != null && uri.getScheme().equalsIgnoreCase("rediss");
        } catch (RuntimeException ignored) {
            // Bad URL — fall back to the individual fields. The connection
            // attempt will surface the real error at startup.
        }
    }
}
