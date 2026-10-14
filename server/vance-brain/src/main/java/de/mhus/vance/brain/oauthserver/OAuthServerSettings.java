package de.mhus.vance.brain.oauthserver;

import java.net.URI;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Configuration of the OAuth authorization server, and the one place that
 * turns {@code vance.web.public-base-url} into issuer and resource URLs.
 *
 * <p><b>The issuer comes from configuration only, never from request
 * headers.</b> A client compares the issuer in the metadata with the URL it
 * fetched the metadata from, and the token audience with the resource it
 * asked for — a value derived from a {@code Host} or {@code X-Forwarded-*}
 * header would be whatever the last hop said. Without a usable base URL the
 * server is {@linkplain #enabled() disabled}: its endpoints answer 503 rather
 * than publish an issuer that breaks every token check.
 */
@Component
@Slf4j
public class OAuthServerSettings {

    private final @Nullable String baseUrl;
    private final boolean enabled;
    private final Duration accessTokenTtl;
    private final Duration refreshTokenTtl;
    private final Duration grantMaxLifetime;
    private final boolean registrationEnabled;
    private final boolean clientMetadataDocumentsEnabled;

    public OAuthServerSettings(
            @Value("${vance.web.public-base-url:}") String publicBaseUrl,
            @Value("${vance.oauth-server.enabled:true}") boolean enabledFlag,
            @Value("${vance.oauth-server.access-token-ttl-seconds:3600}") long accessTokenTtlSeconds,
            @Value("${vance.oauth-server.refresh-token-ttl-days:30}") long refreshTokenTtlDays,
            @Value("${vance.oauth-server.grant-max-lifetime-days:365}") long grantMaxLifetimeDays,
            @Value("${vance.oauth-server.registration-enabled:true}") boolean registrationEnabled,
            @Value("${vance.oauth-server.client-metadata-documents-enabled:true}")
                    boolean clientMetadataDocumentsEnabled) {
        this.baseUrl = normalizeBaseUrl(publicBaseUrl);
        this.enabled = enabledFlag && this.baseUrl != null;
        this.accessTokenTtl = Duration.ofSeconds(Math.max(60, accessTokenTtlSeconds));
        this.refreshTokenTtl = Duration.ofDays(Math.max(1, refreshTokenTtlDays));
        this.grantMaxLifetime = Duration.ofDays(Math.max(1, grantMaxLifetimeDays));
        this.registrationEnabled = registrationEnabled;
        this.clientMetadataDocumentsEnabled = clientMetadataDocumentsEnabled;
        if (enabledFlag && this.baseUrl == null) {
            log.error(
                    "OAuth server disabled: vance.web.public-base-url '{}' is not an absolute http(s) URL",
                    publicBaseUrl);
        }
    }

    /** Whether the authorization server is usable at all. */
    public boolean enabled() {
        return enabled;
    }

    /** Public base URL without trailing slash; only call when {@link #enabled()}. */
    public String baseUrl() {
        if (baseUrl == null) {
            throw new IllegalStateException("OAuth server is not enabled");
        }
        return baseUrl;
    }

    /** {@code {base}/brain/{tenant}} — the issuer identifier of one tenant. */
    public String issuer(String tenant) {
        return baseUrl() + "/brain/" + tenant;
    }

    public Duration accessTokenTtl() {
        return accessTokenTtl;
    }

    public Duration refreshTokenTtl() {
        return refreshTokenTtl;
    }

    public Duration grantMaxLifetime() {
        return grantMaxLifetime;
    }

    public boolean registrationEnabled() {
        return registrationEnabled;
    }

    public boolean clientMetadataDocumentsEnabled() {
        return clientMetadataDocumentsEnabled;
    }

    /**
     * Absolute {@code http(s)} URL without trailing slash and without query
     * or fragment, or {@code null} when the value cannot serve as a base.
     */
    static @Nullable String normalizeBaseUrl(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        try {
            URI uri = URI.create(trimmed);
            String scheme = uri.getScheme();
            if (scheme == null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || uri.getHost() == null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) {
                return null;
            }
        } catch (IllegalArgumentException e) {
            return null;
        }
        return trimmed;
    }
}
