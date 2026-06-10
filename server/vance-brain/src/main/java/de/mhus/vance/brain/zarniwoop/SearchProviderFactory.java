package de.mhus.vance.brain.zarniwoop;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.mhus.vance.brain.project.ProjectEnginesStopRequested;
import de.mhus.vance.shared.settings.SettingService;
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
 * Assembles {@link SearchProviderInstance}s for a project from the
 * {@code research.endpoint.<id>.*} settings, caches them per project,
 * and tears them down when the project is suspended.
 *
 * <p>The cache key is {@code (tenantId, projectId)}; a five-minute
 * Caffeine TTL handles operator setting changes (until a proper
 * {@code SettingChangedEvent} exists in vance-shared the TTL is the
 * fallback re-assembly trigger). Project-suspend evicts immediately
 * via {@link ProjectEnginesStopRequested} and calls
 * {@link SearchProviderInstance#dispose()} on every instance.
 *
 * <p>Unknown protocols, missing required fields and explicitly
 * disabled endpoints are dropped silently with a warn log — the
 * service must keep running for the other instances and an operator
 * sees the problem in {@code research_providers}.
 */
@Service
@Slf4j
public class SearchProviderFactory {

    /** Default factory-cache TTL when the setting is unset / invalid. */
    static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    private final SettingService settings;
    private final Map<String, SearchProtocol> protocolsById;
    private final Cache<ScopeKey, List<SearchProviderInstance>> cache;

    public SearchProviderFactory(SettingService settings, List<SearchProtocol> protocols) {
        this.settings = settings;
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
                .<ScopeKey, List<SearchProviderInstance>>removalListener(
                        (key, value, cause) -> disposeAll(value))
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
        if (scope == null) {
            throw new ZarniwoopException("scope is required");
        }
        if (StringUtils.isBlank(scope.projectId())) {
            throw new ZarniwoopException("research tools require a project scope");
        }
        ScopeKey key = new ScopeKey(scope.tenantId(), scope.projectId());
        return cache.get(key, k -> build(scope));
    }

    /** Evict the instances for the suspended project and dispose them. */
    @EventListener
    public void onProjectStop(ProjectEnginesStopRequested event) {
        if (event == null || StringUtils.isBlank(event.tenantId())
                || StringUtils.isBlank(event.projectName())) {
            return;
        }
        ScopeKey key = new ScopeKey(event.tenantId(), event.projectName());
        List<SearchProviderInstance> evicted = cache.asMap().remove(key);
        if (evicted != null) {
            log.debug("Zarniwoop: evicted {} provider instance(s) for '{}/{}' (project stop)",
                    evicted.size(), event.tenantId(), event.projectName());
            disposeAll(evicted);
        }
    }

    // ── internals ────────────────────────────────────────────────────

    private List<SearchProviderInstance> build(SearchScope scope) {
        Map<String, String> rawSettings = settings.findByPrefixCascade(
                scope.tenantId(), scope.projectId(), scope.processId(),
                ZarniwoopSettings.PREFIX_ENDPOINT);
        if (rawSettings.isEmpty()) {
            return List.of();
        }

        Map<String, Map<String, String>> byEndpointId = groupByEndpointId(rawSettings);
        List<SearchProviderInstance> result = new ArrayList<>(byEndpointId.size());
        for (Map.Entry<String, Map<String, String>> entry : byEndpointId.entrySet()) {
            String endpointId = entry.getKey();
            Map<String, String> fields = entry.getValue();

            String enabled = fields.get(ZarniwoopSettings.SUFFIX_ENABLED.substring(1));
            if ("false".equalsIgnoreCase(enabled)) {
                log.debug("Zarniwoop: endpoint '{}' is disabled, skipping", endpointId);
                continue;
            }

            String protocolId = fields.get(ZarniwoopSettings.SUFFIX_PROTOCOL.substring(1));
            if (StringUtils.isBlank(protocolId)) {
                log.warn("Zarniwoop: endpoint '{}' has no protocol set, skipping", endpointId);
                continue;
            }
            SearchProtocol protocol = protocolsById.get(protocolId);
            if (protocol == null) {
                log.warn("Zarniwoop: endpoint '{}' references unknown protocol '{}', skipping. "
                        + "Known protocols: {}", endpointId, protocolId, protocolsById.keySet());
                continue;
            }

            String baseUrl = fields.get(ZarniwoopSettings.SUFFIX_BASE_URL.substring(1));
            String credentialKey = ZarniwoopSettings.endpointApiKey(endpointId);

            Map<String, Object> extras = new LinkedHashMap<>();
            for (Map.Entry<String, String> f : fields.entrySet()) {
                String suffix = f.getKey();
                if (suffix.equals(ZarniwoopSettings.SUFFIX_PROTOCOL.substring(1))
                        || suffix.equals(ZarniwoopSettings.SUFFIX_BASE_URL.substring(1))
                        || suffix.equals(ZarniwoopSettings.SUFFIX_API_KEY.substring(1))
                        || suffix.equals(ZarniwoopSettings.SUFFIX_ENABLED.substring(1))) {
                    continue;
                }
                extras.put(suffix, f.getValue());
            }

            try {
                ProviderInstanceConfig cfg = new ProviderInstanceConfig(
                        endpointId, protocolId,
                        baseUrl == null ? "" : baseUrl,
                        credentialKey, extras);
                SearchProviderInstance instance = protocol.instantiate(cfg);
                result.add(instance);
            } catch (RuntimeException e) {
                log.warn("Zarniwoop: protocol '{}' refused to instantiate endpoint '{}': {}",
                        protocolId, endpointId, e.toString());
            }
        }
        log.debug("Zarniwoop: assembled {} instance(s) for '{}/{}'",
                result.size(), scope.tenantId(), scope.projectId());
        return List.copyOf(result);
    }

    /**
     * Group {@code research.endpoint.<id>.<suffix>} keys into a
     * per-endpoint map where the inner key is the suffix without the
     * leading dot. Keys that don't follow the pattern (e.g. someone
     * dropped {@code research.endpoint.serper-main} without a suffix)
     * are skipped silently.
     */
    static Map<String, Map<String, String>> groupByEndpointId(Map<String, String> rawSettings) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : rawSettings.entrySet()) {
            String key = e.getKey();
            if (!key.startsWith(ZarniwoopSettings.PREFIX_ENDPOINT)) continue;
            String rest = key.substring(ZarniwoopSettings.PREFIX_ENDPOINT.length());
            int dot = rest.indexOf('.');
            if (dot <= 0 || dot == rest.length() - 1) continue;
            String endpointId = rest.substring(0, dot);
            String suffix = rest.substring(dot + 1);
            out.computeIfAbsent(endpointId, k -> new LinkedHashMap<>())
                    .put(suffix, e.getValue());
        }
        return out;
    }

    private static void disposeAll(@Nullable List<SearchProviderInstance> instances) {
        if (instances == null) return;
        for (SearchProviderInstance instance : instances) {
            try {
                instance.dispose();
            } catch (RuntimeException ex) {
                log.warn("Zarniwoop: dispose of '{}' raised: {}", instance.id(), ex.toString());
            }
        }
    }

    /** Cache key: project-scoped, never tenant-only. */
    record ScopeKey(String tenantId, String projectId) { }
}
