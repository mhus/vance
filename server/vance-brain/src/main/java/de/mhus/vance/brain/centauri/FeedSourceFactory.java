package de.mhus.vance.brain.centauri;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.mhus.vance.brain.project.ProjectEnginesStopRequested;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.core.SecretResolver;
import de.mhus.vance.toolpack.feed.FeedInstanceConfig;
import de.mhus.vance.toolpack.feed.FeedProtocol;
import de.mhus.vance.toolpack.feed.FeedScope;
import de.mhus.vance.toolpack.feed.FeedSourceInstance;
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
 * Assembles {@link FeedSourceInstance}s for a project from the
 * {@code centauri.endpoint.<id>.*} settings, caches them per project and
 * tears them down when the project is suspended.
 *
 * <p>The cache key is {@code (tenantId, projectId)} — <b>not</b> the reader.
 * The reader pseudonym is a parameter of each call, not a property of the
 * instance: keying per user would multiply this cache, the cooldown
 * bookkeeping and the connection pools by the number of readers and buy
 * nothing, since the credential and the endpoint are the same for all of
 * them.
 *
 * <p>Endpoint settings are resolved at project level ({@code processId=null})
 * to match the project-scoped cache. Resolving the process cascade while
 * caching per project would leak the first caller's process-scoped overrides
 * to every other process until the TTL expires.
 *
 * <p>Unknown protocols, missing fields and refused configurations are
 * dropped with a warning rather than failing the assembly: one broken
 * endpoint must not take the other sources of a feed down with it.
 */
@Service
@Slf4j
public class FeedSourceFactory {

    static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    private final SettingService settings;
    private final SecretResolver secretResolver;
    private final Map<String, FeedProtocol> protocolsById;
    private final Cache<ScopeKey, List<FeedSourceInstance>> cache;

    public FeedSourceFactory(
            SettingService settings, SecretResolver secretResolver,
            List<FeedProtocol> protocols) {
        this.settings = settings;
        this.secretResolver = secretResolver;
        Map<String, FeedProtocol> byId = new LinkedHashMap<>();
        for (FeedProtocol p : protocols) {
            FeedProtocol prev = byId.put(p.id(), p);
            if (prev != null) {
                log.warn("FeedProtocol id collision on '{}': '{}' replaces '{}' — "
                                + "later bean wins, but this is a misconfiguration",
                        p.id(), p.getClass().getName(), prev.getClass().getName());
            }
        }
        this.protocolsById = Map.copyOf(byId);
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(DEFAULT_TTL)
                .<ScopeKey, List<FeedSourceInstance>>removalListener(
                        (key, value, cause) -> disposeAll(value))
                // The removal listener is the ONE dispose path — for expiry it is
                // the only one there can be, so an explicit dispose beside a manual
                // remove would run it twice. Same-thread so that an explicit evict
                // has finished disposing by the time it returns; the default pool
                // would make that asynchronous for no benefit here.
                .executor(Runnable::run)
                .build();
        log.info("Centauri: FeedSourceFactory initialised with {} protocol(s): {}",
                protocolsById.size(), protocolsById.keySet());
    }

    /** All configured sources of {@code scope}'s project, built on first use. */
    public List<FeedSourceInstance> assemble(FeedScope scope) {
        if (scope == null) {
            throw new CentauriException("scope is required");
        }
        if (StringUtils.isBlank(scope.projectId())) {
            throw new CentauriException("feed sources require a project scope");
        }
        ScopeKey key = new ScopeKey(scope.tenantId(), scope.projectId());
        return cache.get(key, k -> build(scope));
    }

    /** The one source with this endpoint id, or null when it is not configured. */
    public @Nullable FeedSourceInstance find(FeedScope scope, String sourceId) {
        for (FeedSourceInstance instance : assemble(scope)) {
            if (instance.id().equals(sourceId)) {
                return instance;
            }
        }
        return null;
    }

    /**
     * Drop the cached sources of this project so the next {@link #assemble} reads
     * the settings again.
     *
     * <p>Exists because the five-minute TTL is indistinguishable from a
     * misconfiguration: an operator who has just written
     * {@code centauri.endpoint.*} and sees an empty source list cannot tell
     * whether they got the keys wrong or are simply early. A caller that can
     * force the re-read turns that wait into a button.
     */
    public void evict(FeedScope scope) {
        if (scope == null || StringUtils.isBlank(scope.projectId())) {
            return;
        }
        List<FeedSourceInstance> evicted =
                cache.asMap().remove(new ScopeKey(scope.tenantId(), scope.projectId()));
        if (evicted != null) {
            log.debug("Centauri: evicted {} source instance(s) for '{}/{}' (explicit refresh)",
                    evicted.size(), scope.tenantId(), scope.projectId());
        }
    }

    @EventListener
    public void onProjectStop(ProjectEnginesStopRequested event) {
        if (event == null || StringUtils.isBlank(event.tenantId())
                || StringUtils.isBlank(event.projectName())) {
            return;
        }
        ScopeKey key = new ScopeKey(event.tenantId(), event.projectName());
        List<FeedSourceInstance> evicted = cache.asMap().remove(key);
        if (evicted != null) {
            log.debug("Centauri: evicted {} source instance(s) for '{}/{}' (project stop)",
                    evicted.size(), event.tenantId(), event.projectName());
        }
    }

    // ── internals ────────────────────────────────────────────────────

    private List<FeedSourceInstance> build(FeedScope scope) {
        Map<String, String> raw = settings.findByPrefixCascade(
                scope.tenantId(), scope.projectId(), /* processId */ null,
                CentauriSettings.PREFIX_ENDPOINT);
        if (raw.isEmpty()) {
            return List.of();
        }

        Map<String, Map<String, String>> byEndpointId = groupByEndpointId(raw);
        List<FeedSourceInstance> result = new ArrayList<>(byEndpointId.size());
        for (Map.Entry<String, Map<String, String>> entry : byEndpointId.entrySet()) {
            String endpointId = entry.getKey();
            Map<String, String> fields = entry.getValue();

            // Endpoints with .enabled=false are still instantiated — the gate
            // consults the same setting at dispatch time, and keeping the
            // instance lets the configuration UI show what exists.

            String protocolId = fields.get(suffix(CentauriSettings.SUFFIX_PROTOCOL));
            if (StringUtils.isBlank(protocolId)) {
                log.warn("Centauri: endpoint '{}' has no protocol set, skipping", endpointId);
                continue;
            }
            FeedProtocol protocol = protocolsById.get(protocolId);
            if (protocol == null) {
                log.warn("Centauri: endpoint '{}' references unknown protocol '{}', skipping. "
                        + "Known protocols: {}", endpointId, protocolId, protocolsById.keySet());
                continue;
            }

            String baseUrl = fields.get(suffix(CentauriSettings.SUFFIX_BASE_URL));
            Map<String, Object> extras = new LinkedHashMap<>();
            for (Map.Entry<String, String> f : fields.entrySet()) {
                if (isCommonField(f.getKey())) {
                    continue;
                }
                extras.put(f.getKey(), f.getValue());
            }

            try {
                String credentialKey = CentauriSettings.endpointApiKey(endpointId);
                FeedInstanceConfig cfg = new FeedInstanceConfig(
                        endpointId, protocolId,
                        baseUrl == null ? "" : baseUrl,
                        credentialKey,
                        // Closes over this project's scope — the instance is cached
                        // per (tenant, project) anyway — and reads on every call, so
                        // a rotated key takes effect without waiting for the TTL.
                        () -> resolveCredential(scope, credentialKey),
                        extras);
                result.add(protocol.instantiate(cfg));
            } catch (RuntimeException e) {
                log.warn("Centauri: protocol '{}' refused to instantiate endpoint '{}': {}",
                        protocolId, endpointId, e.toString());
            }
        }
        log.debug("Centauri: assembled {} source instance(s) for '{}/{}'",
                result.size(), scope.tenantId(), scope.projectId());
        return List.copyOf(result);
    }

    /**
     * The endpoint credential, with {@code {{secret:…}}} references resolved.
     *
     * <p>Through {@code resolveForConnector} rather than {@code resolve}: a
     * feed protocol is a connector, not a dynamic element, so it may read a
     * {@code PASSWORD}-typed setting or a vault entry (spec §10). Reading the
     * setting straight sent an unresolved {@code {{secret:vault:…}}} into the
     * {@code Authorization} header verbatim, which reaches the source as a
     * 401 with nothing to explain it.
     *
     * <p>The invocation context carries no user and no process on purpose: the
     * instance is cached per {@code (tenant, project)} and shared across every
     * reader, so a user- or process-scoped reference would serve the first
     * caller's secret to everyone behind them.
     */
    private @Nullable String resolveCredential(FeedScope scope, String credentialKey) {
        String raw = settings.getDecryptedPasswordCascade(
                scope.tenantId(), scope.projectId(), null, credentialKey);
        if (raw == null) {
            return null;
        }
        return secretResolver.resolveForConnector(raw, new ToolInvocationContext(
                scope.tenantId(), scope.projectId(), null, null, null));
    }

    /**
     * Group {@code centauri.endpoint.<id>.<suffix>} keys per endpoint, inner
     * key being the suffix without its leading dot. Keys without a suffix are
     * skipped silently.
     */
    static Map<String, Map<String, String>> groupByEndpointId(Map<String, String> raw) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : raw.entrySet()) {
            String key = e.getKey();
            if (!key.startsWith(CentauriSettings.PREFIX_ENDPOINT)) {
                continue;
            }
            String rest = key.substring(CentauriSettings.PREFIX_ENDPOINT.length());
            int dot = rest.indexOf('.');
            if (dot <= 0 || dot == rest.length() - 1) {
                continue;
            }
            out.computeIfAbsent(rest.substring(0, dot), k -> new LinkedHashMap<>())
                    .put(rest.substring(dot + 1), e.getValue());
        }
        return out;
    }

    private static boolean isCommonField(String fieldSuffix) {
        return fieldSuffix.equals(suffix(CentauriSettings.SUFFIX_PROTOCOL))
                || fieldSuffix.equals(suffix(CentauriSettings.SUFFIX_BASE_URL))
                || fieldSuffix.equals(suffix(CentauriSettings.SUFFIX_API_KEY))
                || fieldSuffix.equals(suffix(CentauriSettings.SUFFIX_ENABLED))
                || fieldSuffix.equals(suffix(CentauriSettings.SUFFIX_SEND_ACTOR))
                || fieldSuffix.equals(suffix(CentauriSettings.SUFFIX_ACTOR_SALT));
    }

    private static String suffix(String withLeadingDot) {
        return withLeadingDot.substring(1);
    }

    private static void disposeAll(@Nullable List<FeedSourceInstance> instances) {
        if (instances == null) {
            return;
        }
        for (FeedSourceInstance instance : instances) {
            try {
                instance.dispose();
            } catch (RuntimeException ex) {
                log.warn("Centauri: dispose of '{}' raised: {}", instance.id(), ex.toString());
            }
        }
    }

    /** Cache key: project-scoped, never tenant-only and never per reader. */
    record ScopeKey(String tenantId, String projectId) { }
}
