package de.mhus.vance.brain.oauth;

import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.LookupResult;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.settings.SettingService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

/**
 * Cascade-aware OAuth-provider-config loader. Reads one YAML per
 * provider through {@link DocumentService#lookupCascade} /
 * {@link DocumentService#listByPrefixCascade}, then pulls the
 * matching {@code clientSecret} from the tenant
 * {@code PASSWORD}-setting {@code oauth.<providerId>.client_secret}.
 *
 * <p>Document path: {@code oauth/<providerId>.yaml}. Configs live in
 * the {@code _tenant} system project; the YAML never contains the
 * secret in plaintext — only the {@code clientId} is in YAML. Bundled
 * templates ship under {@code vance-defaults/oauth/} but with
 * placeholder {@code clientId} so a tenant has to opt-in by overriding.
 *
 * <p>Validation is intentionally cheap: YAML must parse, {@code type}
 * and {@code clientId} must be present, type-specific required fields
 * (e.g. {@code discoveryUrl} for {@code oidc}) must be present.
 * The {@code clientSecret} <i>lookup</i> happens in
 * {@link #load(String, String)} / {@link #loadAll(String)} so the
 * returned config is immediately consumable by an
 * {@link OAuthProvider}; missing-secret → the resulting config's
 * {@code clientSecret} is empty, which provider beans treat as a
 * fail-fast at flow time with a clear "configure client_secret" message.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OAuthProviderLoader {

    /** Document path prefix for OAuth provider configs. */
    public static final String OAUTH_PATH_PREFIX = "_vance/oauth/";

    /** Document path suffix; the provider name itself does not carry it. */
    public static final String OAUTH_PATH_SUFFIX = ".yaml";

    /** Tenant-setting key pattern for the per-provider client secret. */
    public static final String CLIENT_SECRET_KEY_PREFIX = "oauth.";

    /** Tenant-setting key suffix for the per-provider client secret. */
    public static final String CLIENT_SECRET_KEY_SUFFIX = ".client_secret";

    private final DocumentService documentService;
    private final SettingService settingService;

    /** Resolve a single provider config by name (cascade: _tenant → resource). */
    public Optional<OAuthProviderConfig> load(String tenantId, String providerId) {
        if (providerId == null || providerId.isBlank()) return Optional.empty();
        String norm = normalizedName(providerId);
        Optional<LookupResult> hit = documentService.lookupCascade(
                tenantId, HomeBootstrapService.TENANT_PROJECT_NAME, pathFor(norm));
        if (hit.isEmpty()) return Optional.empty();
        try {
            OAuthProviderConfig cfg = parse(norm, hit.get().content());
            return Optional.of(withResolvedSecret(tenantId, cfg));
        } catch (RuntimeException e) {
            throw new OAuthProviderParseException(
                    "Failed to parse OAuth provider '" + norm + "' from "
                            + hit.get().source() + " at path '" + hit.get().path()
                            + "': " + e.getMessage(), e);
        }
    }

    /**
     * Every configured provider for {@code tenantId}, cascade-merged.
     * Provider entries in {@code _tenant} override bundled defaults by
     * {@code providerId}. Parse errors are logged and skipped — a
     * single broken doc must not block the tenant's other integrations.
     */
    public List<OAuthProviderConfig> loadAll(String tenantId) {
        Map<String, LookupResult> hits = documentService.listByPrefixCascade(
                tenantId, HomeBootstrapService.TENANT_PROJECT_NAME, OAUTH_PATH_PREFIX);
        List<OAuthProviderConfig> out = new ArrayList<>(hits.size());
        for (Map.Entry<String, LookupResult> e : hits.entrySet()) {
            String path = e.getKey();
            String name = nameFromPath(path);
            if (name == null) continue;
            try {
                OAuthProviderConfig cfg = parse(name, e.getValue().content());
                out.add(withResolvedSecret(tenantId, cfg));
            } catch (RuntimeException ex) {
                log.warn("OAuthProviderLoader: skipping malformed provider path='{}' source={}: {}",
                        path, e.getValue().source(), ex.getMessage());
            }
        }
        return out;
    }

    /**
     * Validate a YAML body without persisting and without resolving the
     * client secret — used by the admin REST controller before writing
     * a provider document, so malformed input never reaches the
     * document layer.
     *
     * @throws OAuthProviderParseException with a field-level error message
     */
    public OAuthProviderConfig validateYaml(String providerId, String yaml) {
        String norm = normalizedName(providerId);
        try {
            return parse(norm, yaml);
        } catch (RuntimeException ex) {
            throw new OAuthProviderParseException(
                    "OAuth provider YAML invalid: " + ex.getMessage(), ex);
        }
    }

    /** Compose the document path for {@code providerId}. */
    public static String pathFor(String providerId) {
        return OAUTH_PATH_PREFIX + normalizedName(providerId) + OAUTH_PATH_SUFFIX;
    }

    /** Conventional tenant-setting key for the provider's client secret. */
    public static String clientSecretKey(String providerId) {
        return CLIENT_SECRET_KEY_PREFIX + normalizedName(providerId) + CLIENT_SECRET_KEY_SUFFIX;
    }

    /** Normalised, lowercase provider id. */
    public static String normalizedName(String providerId) {
        return providerId.trim().toLowerCase(Locale.ROOT);
    }

    private static @Nullable String nameFromPath(String path) {
        if (!path.startsWith(OAUTH_PATH_PREFIX)) return null;
        if (!path.endsWith(OAUTH_PATH_SUFFIX)) return null;
        String stem = path.substring(
                OAUTH_PATH_PREFIX.length(),
                path.length() - OAUTH_PATH_SUFFIX.length());
        return stem.isBlank() ? null : stem;
    }

    private OAuthProviderConfig withResolvedSecret(String tenantId, OAuthProviderConfig cfg) {
        String secret = settingService.getDecryptedPassword(
                tenantId,
                SettingService.SCOPE_PROJECT,
                HomeBootstrapService.TENANT_PROJECT_NAME,
                clientSecretKey(cfg.providerId()));
        return new OAuthProviderConfig(
                cfg.providerId(),
                cfg.typeId(),
                cfg.discoveryUrl(),
                cfg.authorizeUrl(),
                cfg.tokenUrl(),
                cfg.clientId(),
                secret == null ? "" : secret,
                cfg.scopes(),
                cfg.extra());
    }

    @SuppressWarnings("unchecked")
    private static OAuthProviderConfig parse(String providerId, String yamlBody) {
        Yaml yaml = new Yaml();
        Object parsed = yaml.load(yamlBody);
        if (parsed == null) {
            throw new IllegalStateException("OAuth provider YAML is empty");
        }
        if (!(parsed instanceof Map<?, ?> rawMap)) {
            throw new IllegalStateException("OAuth provider YAML must have a top-level map");
        }
        Map<String, Object> spec = (Map<String, Object>) rawMap;

        String type = stringOrThrow(spec.get("type"), "type");
        String clientId = stringOrThrow(spec.get("clientId"), "clientId");
        String discoveryUrl = stringOrNull(spec.get("discoveryUrl"));
        String authorizeUrl = stringOrNull(spec.get("authorizeUrl"));
        String tokenUrl = stringOrNull(spec.get("tokenUrl"));
        List<String> scopes = stringList(spec.get("scopes"), "scopes");
        Map<String, Object> extra = mapOrEmpty(spec.get("extra"), "extra");

        // Reject embedded clientSecret outright — secrets belong in tenant
        // settings, never in the YAML body. Surfacing a clear error early
        // prevents the "I wrote it in the file and it doesn't work" trap.
        if (spec.containsKey("clientSecret") || spec.containsKey("client_secret")) {
            throw new IllegalStateException(
                    "'clientSecret' must not appear in the YAML — store it as the tenant "
                            + "PASSWORD setting '" + clientSecretKey(providerId) + "' instead");
        }

        // Type-specific required fields.
        switch (type) {
            case "oidc" -> {
                if (discoveryUrl == null) {
                    throw new IllegalStateException(
                            "'discoveryUrl' is required for type 'oidc'");
                }
            }
            case "generic-oauth2", "slack", "atlassian", "github" -> {
                if (authorizeUrl == null || tokenUrl == null) {
                    throw new IllegalStateException(
                            "'authorizeUrl' and 'tokenUrl' are required for type '" + type + "'");
                }
            }
            case "google" -> {
                // Google goes through OIDC discovery — accept either form.
                if (discoveryUrl == null && (authorizeUrl == null || tokenUrl == null)) {
                    throw new IllegalStateException(
                            "'discoveryUrl' OR both 'authorizeUrl'+'tokenUrl' required for type 'google'");
                }
            }
            default -> {
                // Unknown type — lazy-fail at registry materialization time
                // (when the bean lookup misses) with a richer error. No
                // need to duplicate the typeId catalog here.
            }
        }

        return new OAuthProviderConfig(
                providerId,
                type,
                discoveryUrl,
                authorizeUrl,
                tokenUrl,
                clientId,
                /*clientSecret*/ "",
                scopes,
                extra);
    }

    private static String stringOrThrow(@Nullable Object raw, String fieldName) {
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new IllegalStateException(
                    "missing required field '" + fieldName + "' (must be a non-empty string)");
        }
        return s;
    }

    private static @Nullable String stringOrNull(@Nullable Object raw) {
        return raw instanceof String s && !s.isBlank() ? s : null;
    }

    private static List<String> stringList(@Nullable Object raw, String fieldName) {
        if (raw == null) return new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            throw new IllegalStateException("'" + fieldName + "' must be a list");
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String s) || s.isBlank()) {
                throw new IllegalStateException(
                        "'" + fieldName + "' contains a non-string or blank entry");
            }
            out.add(s);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOrEmpty(@Nullable Object raw, String fieldName) {
        if (raw == null) return new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> m)) {
            throw new IllegalStateException("'" + fieldName + "' must be a map");
        }
        Map<String, Object> out = new LinkedHashMap<>(m.size());
        for (Map.Entry<?, ?> e : m.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    /** Surfacing-friendly wrapper for parse failures. */
    public static class OAuthProviderParseException extends RuntimeException {
        public OAuthProviderParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
