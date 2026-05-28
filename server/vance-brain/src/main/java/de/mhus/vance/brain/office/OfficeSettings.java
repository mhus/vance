package de.mhus.vance.brain.office;

import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.settings.SettingDocument;
import de.mhus.vance.shared.settings.SettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Convenience wrapper around {@link SettingService} for the
 * {@code office.*} setting group. Resolves per-project first,
 * falls back to the tenant-default {@code _vance} project so a
 * tenant-wide ONLYOFFICE setup doesn't have to be re-declared on
 * every project.
 *
 * <p>Setting keys (defined ad-hoc, no registry needed in Vance's
 * setting system):
 * <ul>
 *   <li>{@code office.provider} — {@code onlyoffice} (default),
 *       {@code collabora}, or {@code none} to disable</li>
 *   <li>{@code office.url} — base URL of the document server,
 *       e.g. {@code https://office.example.com}</li>
 *   <li>{@code office.jwtSecret} — PASSWORD-typed; the shared
 *       secret both Vance and the document server use to sign
 *       requests in either direction</li>
 *   <li>{@code office.jwtAlgorithm} — HS256 (default), HS384,
 *       HS512</li>
 * </ul>
 *
 * <p>Public Vance base-URL (for the callback URLs the document
 * server fetches) is read from the standard
 * {@code vance.web.publicBaseUrl} Spring property, not from the
 * setting cascade — it's deployment-shape, not per-tenant.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OfficeSettings {

    public static final String KEY_PROVIDER = "office.provider";
    public static final String KEY_URL = "office.url";
    public static final String KEY_JWT_SECRET = "office.jwtSecret";
    public static final String KEY_JWT_ALGORITHM = "office.jwtAlgorithm";
    public static final String KEY_CALLBACK_BASE_URL = "office.callbackBaseUrl";

    public static final String DEFAULT_PROVIDER = "none";
    public static final String DEFAULT_ALGORITHM = "HS256";

    /** Tenant-default-project used as the fallback scope when no
     *  project-level setting is configured. The name is the
     *  central {@link HomeBootstrapService#TENANT_PROJECT_NAME}
     *  constant — was {@code _vance} before the 2026 migration,
     *  is {@code _tenant} now. */
    private static final String TENANT_DEFAULT_PROJECT
            = HomeBootstrapService.TENANT_PROJECT_NAME;

    private final SettingService settingService;

    /**
     * Snapshot of the {@code office.*} settings resolved for one
     * scope. Immutable; build a new one per request rather than
     * caching since the operator might rotate the JWT secret.
     */
    public Snapshot resolve(String tenantId, @Nullable String projectId) {
        String provider = readString(tenantId, projectId, KEY_PROVIDER, DEFAULT_PROVIDER);
        String url = readString(tenantId, projectId, KEY_URL, "");
        String algorithm = readString(tenantId, projectId, KEY_JWT_ALGORITHM, DEFAULT_ALGORITHM);
        String callbackBaseUrl = readString(tenantId, projectId, KEY_CALLBACK_BASE_URL, "");
        String jwtSecret = readDecryptedPassword(tenantId, projectId, KEY_JWT_SECRET);
        return new Snapshot(provider, url, jwtSecret, algorithm, callbackBaseUrl);
    }

    private String readString(String tenantId, @Nullable String projectId,
                              String key, String defaultValue) {
        if (projectId != null && !projectId.isBlank()) {
            String v = settingService
                    .find(tenantId, "project", projectId, key)
                    .map(SettingDocument::getValue)
                    .orElse(null);
            if (v != null && !v.isBlank()) return v.trim();
        }
        String v = settingService
                .find(tenantId, "project", TENANT_DEFAULT_PROJECT, key)
                .map(SettingDocument::getValue)
                .orElse(null);
        return (v != null && !v.isBlank()) ? v.trim() : defaultValue;
    }

    private @Nullable String readDecryptedPassword(String tenantId,
                                                   @Nullable String projectId,
                                                   String key) {
        if (projectId != null && !projectId.isBlank()) {
            String v = settingService.getDecryptedPassword(
                    tenantId, "project", projectId, key);
            if (v != null && !v.isBlank()) return v;
        }
        String v = settingService.getDecryptedPassword(
                tenantId, "project", TENANT_DEFAULT_PROJECT, key);
        return (v != null && !v.isBlank()) ? v : null;
    }

    /**
     * Resolved office-config snapshot. Use {@link #isEnabled()} to
     * gate features in the UI / API before reading other fields.
     *
     * <p>{@code callbackBaseUrl} is the URL the document server uses
     * to reach the Vance brain for download + save callbacks. Empty
     * string means "fall back to the global
     * {@code vance.web.publicBaseUrl}" — useful per-tenant when
     * brain and office server live on different networks (e.g.
     * brain on macOS host, office in a docker container that
     * reaches the host via {@code host.docker.internal}).
     */
    public record Snapshot(
            String provider,
            String url,
            @Nullable String jwtSecret,
            String algorithm,
            String callbackBaseUrl) {

        /** {@code true} when a usable office integration is
         *  configured for this scope. */
        public boolean isEnabled() {
            return !"none".equalsIgnoreCase(provider)
                    && !url.isBlank()
                    && jwtSecret != null
                    && !jwtSecret.isBlank();
        }
    }
}
