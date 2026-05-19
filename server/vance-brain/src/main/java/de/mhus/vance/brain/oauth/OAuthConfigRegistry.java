package de.mhus.vance.brain.oauth;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Per-tenant in-memory registry of {@link ResolvedOAuthProvider}
 * instances. Bootstrapped lazily on first access per tenant; refreshed
 * by the admin controller after a provider-config edit.
 *
 * <p>Distinct from {@link OAuthProviderRegistry}, which is the
 * Spring-bean lookup for provider <i>types</i> (oidc / generic-oauth2 /
 * slack / …). This registry holds <i>instances</i> — the (tenant,
 * providerId) configurations a Vance deployment currently knows about.
 *
 * <p>Architecture mirrors {@code ServerToolRegistry}: lazy-bootstrap,
 * {@code refreshOne} / {@code refreshTenant} for explicit invalidation,
 * no Document-Change-Listener. Admin REST writes call refresh; lookups
 * fault in unknown tenants on demand.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OAuthConfigRegistry {

    private final OAuthProviderLoader loader;
    private final OAuthProviderRegistry providerRegistry;

    /** Per-tenant scope cache. Key: tenantId. */
    private final Map<String, TenantScope> scopes = new ConcurrentHashMap<>();

    // ──────────────────── Lifecycle ────────────────────

    /**
     * Load every configured provider for {@code tenantId} into the
     * registry, replacing any prior scope atomically. Idempotent.
     */
    public synchronized int bootstrapTenant(String tenantId) {
        List<OAuthProviderConfig> entries = loader.loadAll(tenantId);
        Map<String, ResolvedOAuthProvider> byProvider = new LinkedHashMap<>();
        for (OAuthProviderConfig cfg : entries) {
            ResolvedOAuthProvider resolved = resolveBeanFor(cfg);
            if (resolved != null) {
                byProvider.put(cfg.providerId(), resolved);
            }
        }
        scopes.put(tenantId, new TenantScope(byProvider));
        log.info("OAuthConfigRegistry bootstrap tenant='{}' loaded {} provider(s)",
                tenantId, byProvider.size());
        return byProvider.size();
    }

    /** Drop the tenant scope. Next lookup re-bootstraps. */
    public synchronized void unloadTenant(String tenantId) {
        scopes.remove(tenantId);
    }

    /** Full tenant re-bootstrap — equivalent to {@link #bootstrapTenant}. */
    public int refreshTenant(String tenantId) {
        return bootstrapTenant(tenantId);
    }

    /**
     * Reload exactly one provider by id. Removes the entry from the
     * scope if the cascade no longer carries it or its bean-type is
     * unknown.
     *
     * @return {@code true} when the entry now resolves to a live config
     */
    public synchronized boolean refreshOne(String tenantId, String providerId) {
        TenantScope scope = scopes.get(tenantId);
        if (scope == null) return false;
        String norm = OAuthProviderLoader.normalizedName(providerId);
        Optional<OAuthProviderConfig> reloaded;
        try {
            reloaded = loader.load(tenantId, norm);
        } catch (OAuthProviderLoader.OAuthProviderParseException ex) {
            log.warn("OAuthConfigRegistry refreshOne parse failed '{}/{}': {}",
                    tenantId, norm, ex.getMessage());
            scope.entries.remove(norm);
            return false;
        }
        if (reloaded.isEmpty()) {
            scope.entries.remove(norm);
            return false;
        }
        ResolvedOAuthProvider resolved = resolveBeanFor(reloaded.get());
        if (resolved == null) {
            scope.entries.remove(norm);
            return false;
        }
        scope.entries.put(norm, resolved);
        return true;
    }

    // ──────────────────── Read API ────────────────────

    /** Resolve {@code providerId} to a runnable provider+config pair. */
    public Optional<ResolvedOAuthProvider> resolve(String tenantId, String providerId) {
        ensureBootstrapped(tenantId);
        TenantScope scope = scopes.get(tenantId);
        if (scope == null) return Optional.empty();
        return Optional.ofNullable(
                scope.entries.get(OAuthProviderLoader.normalizedName(providerId)));
    }

    /** Every provider configured for this tenant. Order = loader emission order. */
    public List<ResolvedOAuthProvider> list(String tenantId) {
        ensureBootstrapped(tenantId);
        TenantScope scope = scopes.get(tenantId);
        return scope == null ? List.of() : new ArrayList<>(scope.entries.values());
    }

    // ──────────────────── Internals ────────────────────

    private void ensureBootstrapped(String tenantId) {
        if (scopes.containsKey(tenantId)) return;
        // Lazy + idempotent — bootstrapTenant is itself synchronized.
        try {
            bootstrapTenant(tenantId);
        } catch (RuntimeException ex) {
            log.warn("OAuthConfigRegistry: lazy bootstrap failed for tenant='{}': {}",
                    tenantId, ex.toString());
        }
    }

    private @org.jspecify.annotations.Nullable ResolvedOAuthProvider resolveBeanFor(
            OAuthProviderConfig cfg) {
        Optional<OAuthProvider> bean = providerRegistry.find(cfg.typeId());
        if (bean.isEmpty()) {
            log.warn("OAuthConfigRegistry: skipping provider '{}' — unknown typeId '{}', "
                    + "available: {}",
                    cfg.providerId(), cfg.typeId(),
                    providerRegistry.list().stream().map(OAuthProvider::typeId).toList());
            return null;
        }
        return new ResolvedOAuthProvider(cfg, bean.get());
    }

    /** Mutable per-tenant container — accessed under the registry-level lock. */
    private static final class TenantScope {
        final Map<String, ResolvedOAuthProvider> entries;

        TenantScope(Map<String, ResolvedOAuthProvider> entries) {
            this.entries = new LinkedHashMap<>(entries);
        }
    }
}
