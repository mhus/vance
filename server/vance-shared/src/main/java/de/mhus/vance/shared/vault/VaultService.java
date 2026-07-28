package de.mhus.vance.shared.vault;

import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.settings.SettingService;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Facade for external secret-manager access. Resolves the applicable
 * {@link VaultBinding} from the {@code vault.*} settings along the scope
 * cascade (user → project → tenant), selects the matching {@link VaultProvider}
 * by {@code vault.type}, and delegates the actual read/write.
 *
 * <p>Deliberately the <em>only</em> owner of vault access: the
 * {@code {{secret:vault:...}}} resolver, compose secret injection and the
 * write tools all go through here, so scope resolution and provider selection
 * live in one place. There is exactly one binding per scope layer (no named
 * instances in v1) — {@code vault.type} being blank means "no vault bound".
 *
 * <p>Only one bean per {@code type} may be registered; a second provider
 * claiming an already-taken {@code type} is a wiring error caught at first use.
 */
@Service
@Slf4j
public class VaultService {

    /** Prefix for all vault binding settings. */
    static final String PREFIX = "vault.";
    /** Provider discriminator setting. */
    static final String KEY_TYPE = PREFIX + "type";
    /** Universal endpoint setting. */
    static final String KEY_BASE_URL = PREFIX + "baseUrl";
    /** Decrypted credential setting (PASSWORD). */
    static final String KEY_CLIENT_SECRET = PREFIX + "clientSecret";

    /**
     * Non-secret provider parameters copied into {@link VaultBinding#config()},
     * keyed by their {@code vault.<suffix>}. Currently Infisical-shaped; adding
     * a provider that needs another key extends this list.
     */
    static final List<String> CONFIG_SUFFIXES =
            List.of("project", "environment", "path", "clientId");

    /** Outcome-tagged counters for the read / write paths. */
    static final String METRIC_READS = "vance.vault.reads";
    static final String METRIC_WRITES = "vance.vault.writes";

    private final SettingService settingService;
    private final List<VaultProvider> providers;
    private final MetricService metricService;

    public VaultService(
            SettingService settingService, List<VaultProvider> providers, MetricService metricService) {
        this.settingService = settingService;
        this.providers = providers;
        this.metricService = metricService;
    }

    /**
     * Reads {@code key} from the vault bound at {@code scope}.
     *
     * @return the secret value, or {@code null} if the vault has no such key
     * @throws VaultException if no vault is bound at the scope, no provider is
     *         registered for the configured type, a required binding setting is
     *         missing, or the provider fails to reach the vault. Any lower-level
     *         failure (settings-store error, provider RuntimeException) is wrapped
     *         as VaultException, so this method never leaks another exception type
     */
    public @Nullable String readSecret(VaultScope scope, String key) {
        requireKey(key);
        Bound bound = bind(scope, METRIC_READS);
        log.trace("Vault read: type='{}' key='{}' scope(tenant='{}',project='{}',user='{}')",
                bound.binding().type(), key, scope.tenantId(), scope.projectId(), scope.userId());
        String value;
        try {
            value = bound.provider().readSecret(bound.binding(), key);
        } catch (VaultException e) {
            count(METRIC_READS, "error");
            throw e;
        } catch (RuntimeException e) {
            count(METRIC_READS, "error");
            throw new VaultException(
                    "Vault provider '" + bound.binding().type() + "' read failed: " + e.getMessage(), e);
        }
        count(METRIC_READS, value == null ? "not_found" : "success");
        return value;
    }

    /**
     * Store {@code value} under {@code key} in the vault bound at {@code scope}.
     * Create-or-update semantics are the provider's. Throws {@link VaultException}
     * on any failure (no vault bound, no provider, read-only identity, transport).
     */
    public void writeSecret(VaultScope scope, String key, String value) {
        requireKey(key);
        if (value == null) {
            throw new VaultException("Secret value must not be null");
        }
        Bound bound = bind(scope, METRIC_WRITES);
        log.trace("Vault write: type='{}' key='{}' scope(tenant='{}',project='{}',user='{}')",
                bound.binding().type(), key, scope.tenantId(), scope.projectId(), scope.userId());
        try {
            bound.provider().writeSecret(bound.binding(), key, value);
        } catch (VaultException e) {
            count(METRIC_WRITES, "error");
            throw e;
        } catch (RuntimeException e) {
            count(METRIC_WRITES, "error");
            throw new VaultException(
                    "Vault provider '" + bound.binding().type() + "' write failed: " + e.getMessage(), e);
        }
        count(METRIC_WRITES, "success");
    }

    /**
     * Generate a cryptographically random secret and store it under {@code key}.
     * The value is <b>never returned</b> — this provisions a fresh credential the
     * caller (and the LLM) never sees; use it afterwards via the secret reference.
     */
    public void generateSecret(VaultScope scope, String key, SecretFormat format, int length) {
        writeSecret(scope, key, generateValue(format, length));
    }

    private void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new VaultException("Secret key must not be blank");
        }
    }

    /**
     * Resolve the binding and select the provider for {@code scope}, emitting the
     * structural outcome (not_configured / no_provider / error) under {@code metric}.
     * Shared by read and write so the resolve+select+metric boilerplate lives once.
     */
    private Bound bind(VaultScope scope, String metric) {
        VaultBinding binding;
        try {
            binding = resolveBinding(scope);
        } catch (VaultException e) {
            count(metric, "error");
            throw e;
        } catch (RuntimeException e) {
            // Wrap infra failures (e.g. a settings-store read error) so the facade
            // only ever throws VaultException — callers fail closed uniformly.
            count(metric, "error");
            throw new VaultException("Vault binding resolution failed: " + e.getMessage(), e);
        }
        if (binding == null) {
            count(metric, "not_configured");
            throw new VaultException(
                    "No vault bound at scope (tenant='" + scope.tenantId()
                            + "', project='" + scope.projectId()
                            + "', user='" + scope.userId()
                            + "'): set '" + KEY_TYPE + "' to bind one");
        }
        VaultProvider provider;
        try {
            provider = selectProvider(binding.type());
        } catch (RuntimeException e) {
            count(metric, "no_provider");
            throw e;
        }
        return new Bound(binding, provider);
    }

    private record Bound(VaultBinding binding, VaultProvider provider) {}

    private void count(String metric, String outcome) {
        metricService.counter(metric, "outcome", outcome).increment();
    }

    // ──────────────────── secret generation ────────────────────

    /** Format of a generated secret. {@code UUID} ignores the requested length. */
    public enum SecretFormat {
        ALPHANUMERIC, HEX, UUID
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALNUM =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    static String generateValue(SecretFormat format, int length) {
        return switch (format) {
            case UUID -> UUID.randomUUID().toString();
            case HEX -> randomString(length, HEX);
            case ALPHANUMERIC -> randomString(length, ALNUM);
        };
    }

    private static String randomString(int length, char[] alphabet) {
        if (length <= 0) {
            throw new VaultException("generated secret length must be positive");
        }
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(alphabet[RANDOM.nextInt(alphabet.length)]);
        }
        return sb.toString();
    }

    /**
     * Whether a vault is bound at {@code scope} — i.e. {@code vault.type} is set
     * somewhere along the cascade. Cheap check for callers that want to skip the
     * whole vault path when nothing is configured.
     */
    public boolean isConfigured(VaultScope scope) {
        return findBindingLayer(scope) != null;
    }

    /**
     * Assembles the vault binding for {@code scope}, or {@code null} when no
     * {@code vault.type} is set anywhere along the cascade. Package-private: the
     * write path (Phase 5) reuses it.
     *
     * <p><b>Atomic per layer.</b> The cascade picks the innermost scope layer
     * (user → project → tenant) that actually carries a {@code vault.type}, then
     * reads <em>every</em> binding key from that <em>same</em> layer. Resolving
     * each key independently through the cascade would let a partially-configured
     * user layer (e.g. only {@code vault.clientSecret}) borrow the remaining keys
     * from the project layer, silently assembling a mismatched
     * client-id/client-secret pair that fails auth. A binding wins or loses as a
     * whole.
     */
    @Nullable VaultBinding resolveBinding(VaultScope scope) {
        String layer = findBindingLayer(scope);
        if (layer == null) {
            return null;
        }
        String tenant = scope.tenantId();
        String type = trimToNull(
                settingService.getStringValue(tenant, SettingService.SCOPE_PROJECT, layer, KEY_TYPE));
        // findBindingLayer already confirmed vault.type is present on this layer.
        String baseUrl = trimToNull(
                settingService.getStringValue(tenant, SettingService.SCOPE_PROJECT, layer, KEY_BASE_URL));
        if (baseUrl == null) {
            throw new VaultException(
                    "Vault '" + type + "' is missing required setting '" + KEY_BASE_URL + "'");
        }
        Map<String, String> config = new LinkedHashMap<>();
        for (String suffix : CONFIG_SUFFIXES) {
            String v = trimToNull(settingService.getStringValue(
                    tenant, SettingService.SCOPE_PROJECT, layer, PREFIX + suffix));
            if (v != null) {
                config.put(suffix, v);
            }
        }
        String secret =
                settingService.getDecryptedPassword(tenant, SettingService.SCOPE_PROJECT, layer, KEY_CLIENT_SECRET);
        return new VaultBinding(type, baseUrl, config, secret);
    }

    /**
     * The reference-id of the innermost cascade layer carrying {@code vault.type},
     * or {@code null} if none does. Layers are project-scoped settings on the
     * user home project ({@code _user_<id>}), the addressed project, and the
     * tenant default project ({@code _tenant}) — matching the read order of
     * {@link SettingService#getStringValueUserProjectCascade}.
     */
    private @Nullable String findBindingLayer(VaultScope scope) {
        for (String ref : candidateLayers(scope)) {
            String type = settingService.getStringValue(
                    scope.tenantId(), SettingService.SCOPE_PROJECT, ref, KEY_TYPE);
            if (type != null && !type.isBlank()) {
                return ref;
            }
        }
        return null;
    }

    private static List<String> candidateLayers(VaultScope scope) {
        List<String> refs = new ArrayList<>(3);
        if (scope.userId() != null && !scope.userId().isBlank()) {
            refs.add(HomeBootstrapService.HUB_PROJECT_NAME_PREFIX + scope.userId());
        }
        if (scope.projectId() != null && !scope.projectId().isBlank()
                && !HomeBootstrapService.TENANT_PROJECT_NAME.equals(scope.projectId())) {
            refs.add(scope.projectId());
        }
        refs.add(HomeBootstrapService.TENANT_PROJECT_NAME);
        return refs;
    }

    private static @Nullable String trimToNull(@Nullable String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** Package-private: shared by read and (Phase 5) write. */
    VaultProvider selectProvider(String type) {
        VaultProvider match = null;
        for (VaultProvider p : providers) {
            if (type.equals(p.type())) {
                if (match != null) {
                    throw new VaultException(
                            "Multiple vault providers registered for type '" + type + "'");
                }
                match = p;
            }
        }
        if (match == null) {
            throw new VaultException(
                    "No vault provider registered for type '" + type + "' (available: "
                            + providers.stream().map(VaultProvider::type).sorted().toList() + ")");
        }
        return match;
    }
}
