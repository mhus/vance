package de.mhus.vance.brain.zarniwoop;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.mhus.vance.brain.project.ProjectEnginesStopRequested;
import de.mhus.vance.brain.sourceconfig.SourceConfig;
import de.mhus.vance.brain.sourceconfig.SourceConfigCache;
import de.mhus.vance.brain.sourceconfig.SourceConfigLoader;
import de.mhus.vance.brain.sourceconfig.SourceConfigPaths;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.core.SecretResolver;
import de.mhus.vance.toolpack.research.ProviderInstanceConfig;
import de.mhus.vance.toolpack.research.SearchProtocol;
import de.mhus.vance.toolpack.research.SearchProviderInstance;
import de.mhus.vance.toolpack.research.SearchScope;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Assembles {@link SearchProviderInstance}s for a project from the documents
 * under {@link SourceConfigPaths#RESEARCH}, caches them per project, and tears
 * them down when the project is suspended.
 *
 * <p>The cache key is {@code (tenantId, projectId)}; a five-minute Caffeine TTL
 * is the backstop for a changed document, with the routed change event
 * shortening it where it reaches. Project-suspend evicts immediately via
 * {@link ProjectEnginesStopRequested} and calls
 * {@link SearchProviderInstance#dispose()} on every instance.
 *
 * <p>Unknown protocols, missing required fields and explicitly
 * disabled endpoints are dropped silently with a warn log — the
 * service must keep running for the other instances and an operator
 * sees the problem in {@code research_providers}.
 */
@Service
@Slf4j
public class SearchProviderFactory implements SourceConfigCache {

    /** Default factory-cache TTL when the setting is unset / invalid. */
    static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    private final SourceConfigLoader configLoader;
    private final SecretResolver secretResolver;
    private final Map<String, SearchProtocol> protocolsById;
    private final Cache<ScopeKey, Providers> cache;

    public SearchProviderFactory(
            SourceConfigLoader configLoader, SecretResolver secretResolver,
            List<SearchProtocol> protocols) {
        this.configLoader = configLoader;
        this.secretResolver = secretResolver;
        Map<String, SearchProtocol> byId = new LinkedHashMap<>();
        for (SearchProtocol p : protocols) {
            SearchProtocol prev = byId.put(p.id(), p);
            if (prev != null) {
                log.warn("SearchProtocol id collision on '{}': '{}' replaces '{}' — "
                        + "later bean wins, but this is a misconfiguration",
                        p.id(), p.getClass().getName(), prev.getClass().getName());
            }
        }
        this.protocolsById = Map.copyOf(byId);
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(DEFAULT_TTL)
                .<ScopeKey, Providers>removalListener(
                        (key, value, cause) -> disposeAll(value))
                // The removal listener is the ONE dispose path — for expiry it is
                // the only one there can be, so an explicit dispose beside a manual
                // remove would run it twice. Same-thread so that an explicit evict
                // has finished disposing by the time it returns; the default pool
                // would make that asynchronous for no benefit here.
                .executor(Runnable::run)
                .build();
        log.info("SearchProviderFactory initialised with {} protocol(s): {}",
                protocolsById.size(), protocolsById.keySet());
    }

    /**
     * Return the assembled instances for {@code scope}'s project,
     * building (and caching) them on first use. Throws when the scope
     * has no project — research lives on the project lifecycle and
     * has no fallback.
     */
    public List<SearchProviderInstance> assemble(SearchScope scope) {
        return providers(scope).instances();
    }

    /**
     * The configuration document behind an endpoint, or null when this project
     * has none by that name.
     */
    public @Nullable SourceConfig config(SearchScope scope, String instanceId) {
        return providers(scope).configs().get(instanceId);
    }

    @Override
    public String configPathPrefix() {
        return SourceConfigPaths.RESEARCH;
    }

    private Providers providers(SearchScope scope) {
        if (scope == null) {
            throw new ZarniwoopException("scope is required");
        }
        if (StringUtils.isBlank(scope.projectId())) {
            throw new ZarniwoopException("research tools require a project scope");
        }
        ScopeKey key = new ScopeKey(scope.tenantId(), scope.projectId());
        return cache.get(key, k -> build(scope));
    }

    /**
     * Drop the cached instances of this project so the next {@link #assemble}
     * reads the configuration documents again.
     *
     * <p>Exists because the five-minute TTL is indistinguishable from a
     * misconfiguration: an operator who has just written an endpoint and sees an
     * empty provider list cannot tell whether they got a key wrong or are
     * simply early. The insights tab already offered a "Reload" button that
     * re-issued the same request into the same cache — a button that promises a
     * re-read and cannot deliver one is worse than none.
     */
    public void evict(SearchScope scope) {
        if (scope == null) {
            return;
        }
        evict(scope.tenantId(), scope.projectId());
    }

    @Override
    public void evict(String tenantId, String projectId) {
        if (StringUtils.isBlank(projectId)) {
            return;
        }
        Providers evicted = cache.asMap().remove(new ScopeKey(tenantId, projectId));
        if (evicted != null) {
            log.debug("Zarniwoop: evicted {} provider instance(s) for '{}/{}' (explicit refresh)",
                    evicted.instances().size(), tenantId, projectId);
        }
    }

    @Override
    public void evictTenant(String tenantId) {
        if (StringUtils.isBlank(tenantId)) {
            return;
        }
        int dropped = 0;
        for (ScopeKey key : List.copyOf(cache.asMap().keySet())) {
            if (key.tenantId().equals(tenantId) && cache.asMap().remove(key) != null) {
                dropped++;
            }
        }
        if (dropped > 0) {
            log.debug("Zarniwoop: evicted {} project scope(s) of tenant '{}' "
                    + "(tenant-wide configuration changed)", dropped, tenantId);
        }
    }

    /** Evict the instances for the suspended project and dispose them. */
    @EventListener
    public void onProjectStop(ProjectEnginesStopRequested event) {
        if (event == null || StringUtils.isBlank(event.tenantId())
                || StringUtils.isBlank(event.projectName())) {
            return;
        }
        ScopeKey key = new ScopeKey(event.tenantId(), event.projectName());
        Providers evicted = cache.asMap().remove(key);
        if (evicted != null) {
            log.debug("Zarniwoop: evicted {} provider instance(s) for '{}/{}' (project stop)",
                    evicted.instances().size(), event.tenantId(), event.projectName());
        }
    }

    // ── internals ────────────────────────────────────────────────────

    private Providers build(SearchScope scope) {
        List<SourceConfig> configs = configLoader.load(
                scope.tenantId(), scope.projectId(), SourceConfigPaths.RESEARCH);
        if (configs.isEmpty()) {
            return Providers.empty();
        }

        List<SearchProviderInstance> instances = new ArrayList<>(configs.size());
        Map<String, SourceConfig> byId = new LinkedHashMap<>(configs.size());
        for (SourceConfig config : configs) {
            // Endpoints with enabled=false are still instantiated —
            // ZarniwoopGateService consults the same configuration at dispatch
            // time, but keeping the instance lets the UI re-enable it
            // temporarily via a manual override.
            byId.put(config.name(), config);

            if (StringUtils.isBlank(config.protocol())) {
                log.warn("Zarniwoop: endpoint '{}' has no protocol set, skipping",
                        config.documentPath());
                continue;
            }
            SearchProtocol protocol = protocolsById.get(config.protocol());
            if (protocol == null) {
                log.warn("Zarniwoop: endpoint '{}' references unknown protocol '{}', skipping. "
                                + "Known protocols: {}",
                        config.documentPath(), config.protocol(), protocolsById.keySet());
                continue;
            }

            try {
                // Scope goes along because this cache is keyed on it anyway. A
                // protocol that has to call out before any request arrives —
                // one fetching a remote capability declaration — has nowhere
                // else to learn where it lives. Project scope only, for the
                // same reason the documents above are read that way.
                ProviderInstanceConfig cfg = new ProviderInstanceConfig(
                        config.name(), config.protocol(),
                        config.baseUrl() == null ? "" : config.baseUrl(),
                        config.credentialLocation(),
                        () -> resolveCredential(scope, config),
                        config.extras(),
                        scope.tenantId(), scope.projectId());
                instances.add(protocol.instantiate(cfg));
            } catch (RuntimeException e) {
                log.warn("Zarniwoop: protocol '{}' refused to instantiate endpoint '{}': {}",
                        config.protocol(), config.documentPath(), e.toString());
            }
        }
        log.debug("Zarniwoop: assembled {} instance(s) for '{}/{}'",
                instances.size(), scope.tenantId(), scope.projectId());
        return new Providers(List.copyOf(instances), Map.copyOf(byId));
    }

    /**
     * The endpoint credential, with {@code {{secret:…}}} references resolved
     * and a {@code {noop}} literal handed back verbatim.
     *
     * <p>Through {@code resolveForConnector} rather than {@code resolve}: a
     * search provider is a connector, not a dynamic element, so it may read a
     * {@code PASSWORD}-typed setting or a vault entry (spec §10).
     *
     * <p>The invocation context carries no user and no process on purpose: the
     * instance is cached per {@code (tenant, project)} and shared across every
     * caller, so a user- or process-scoped reference would serve the first
     * caller's secret to everyone behind them.
     */
    private @Nullable String resolveCredential(SearchScope scope, SourceConfig config) {
        String raw = config.apiKey();
        if (raw == null) {
            return null;
        }
        return secretResolver.resolveForConnector(raw, new ToolInvocationContext(
                scope.tenantId(), scope.projectId(), null, null, null));
    }

    private static void disposeAll(@Nullable Providers providers) {
        if (providers == null) return;
        for (SearchProviderInstance instance : providers.instances()) {
            try {
                instance.dispose();
            } catch (RuntimeException ex) {
                log.warn("Zarniwoop: dispose of '{}' raised: {}", instance.id(), ex.toString());
            }
        }
    }

    /** What one project's cache entry holds: the live instances and their configuration. */
    private record Providers(
            List<SearchProviderInstance> instances, Map<String, SourceConfig> configs) {
        static Providers empty() {
            return new Providers(List.of(), Map.of());
        }
    }

    /** Cache key: project-scoped, never tenant-only. */
    record ScopeKey(String tenantId, String projectId) { }
}
