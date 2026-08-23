package de.mhus.vance.brain.centauri;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.mhus.vance.brain.project.ProjectEnginesStopRequested;
import de.mhus.vance.brain.sourceconfig.SourceConfig;
import de.mhus.vance.brain.sourceconfig.SourceConfigCache;
import de.mhus.vance.brain.sourceconfig.SourceConfigLoader;
import de.mhus.vance.brain.sourceconfig.SourceConfigPaths;
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
 * Assembles {@link FeedSourceInstance}s for a project from the documents under
 * {@link SourceConfigPaths#FEEDS}, caches them per project and tears them down
 * when the project is suspended.
 *
 * <p>The cache key is {@code (tenantId, projectId)} — <b>not</b> the reader.
 * The reader pseudonym is a parameter of each call, not a property of the
 * instance: keying per user would multiply this cache, the cooldown
 * bookkeeping and the connection pools by the number of readers and buy
 * nothing, since the credential and the endpoint are the same for all of
 * them.
 *
 * <p>Unknown protocols, missing fields and refused configurations are
 * dropped with a warning rather than failing the assembly: one broken
 * endpoint must not take the other sources of a feed down with it.
 *
 * <p>The parsed configurations are kept beside the instances because two other
 * collaborators need them: the gate asks whether an endpoint is enabled, and
 * the actor resolver whether the reader pseudonym may travel to it. Both used
 * to read a setting per call; both now read the object this factory already
 * holds.
 */
@Service
@Slf4j
public class FeedSourceFactory implements SourceConfigCache {

    static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    /**
     * Whether the reader pseudonym travels to this source. Default {@code true}
     * because the feature would otherwise be dead by default and never
     * exercised; the switch exists all the same, since not wanting one's
     * readers profiled by a foreign source is a legitimate position.
     */
    static final String FIELD_SEND_ACTOR = "sendActor";

    private final SourceConfigLoader configLoader;
    private final SecretResolver secretResolver;
    private final Map<String, FeedProtocol> protocolsById;
    private final Cache<ScopeKey, Sources> cache;

    public FeedSourceFactory(
            SourceConfigLoader configLoader, SecretResolver secretResolver,
            List<FeedProtocol> protocols) {
        this.configLoader = configLoader;
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
                .<ScopeKey, Sources>removalListener(
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

    @Override
    public String configPathPrefix() {
        return SourceConfigPaths.FEEDS;
    }

    /** All configured sources of {@code scope}'s project, built on first use. */
    public List<FeedSourceInstance> assemble(FeedScope scope) {
        return sources(scope).instances();
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
     * The configuration document behind an endpoint, or null when this project
     * has none by that name.
     */
    public @Nullable SourceConfig config(FeedScope scope, String sourceId) {
        return sources(scope).configs().get(sourceId);
    }

    /**
     * Drop the cached sources of this project so the next {@link #assemble} reads
     * the documents again.
     *
     * <p>Exists because the five-minute TTL is indistinguishable from a
     * misconfiguration: an operator who has just written a feed source and sees
     * an empty source list cannot tell whether they got the file wrong or are
     * simply early. A caller that can force the re-read turns that wait into a
     * button. The change listener covers the common case but not every pod —
     * see {@code SourceConfigDocumentListener}.
     */
    public void evict(FeedScope scope) {
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
        Sources evicted = cache.asMap().remove(new ScopeKey(tenantId, projectId));
        if (evicted != null) {
            log.debug("Centauri: evicted {} source instance(s) for '{}/{}' (explicit refresh)",
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
            log.debug("Centauri: evicted {} project scope(s) of tenant '{}' "
                    + "(tenant-wide configuration changed)", dropped, tenantId);
        }
    }

    @EventListener
    public void onProjectStop(ProjectEnginesStopRequested event) {
        if (event == null || StringUtils.isBlank(event.tenantId())
                || StringUtils.isBlank(event.projectName())) {
            return;
        }
        ScopeKey key = new ScopeKey(event.tenantId(), event.projectName());
        Sources evicted = cache.asMap().remove(key);
        if (evicted != null) {
            log.debug("Centauri: evicted {} source instance(s) for '{}/{}' (project stop)",
                    evicted.instances().size(), event.tenantId(), event.projectName());
        }
    }

    // ── internals ────────────────────────────────────────────────────

    private Sources sources(FeedScope scope) {
        if (scope == null) {
            throw new CentauriException("scope is required");
        }
        if (StringUtils.isBlank(scope.projectId())) {
            throw new CentauriException("feed sources require a project scope");
        }
        ScopeKey key = new ScopeKey(scope.tenantId(), scope.projectId());
        return cache.get(key, k -> build(scope));
    }

    private Sources build(FeedScope scope) {
        List<SourceConfig> configs = configLoader.load(
                scope.tenantId(), scope.projectId(), SourceConfigPaths.FEEDS);
        if (configs.isEmpty()) {
            return Sources.empty();
        }

        List<FeedSourceInstance> instances = new ArrayList<>(configs.size());
        Map<String, SourceConfig> byId = new LinkedHashMap<>(configs.size());
        for (SourceConfig config : configs) {
            // Endpoints with enabled=false are still instantiated — the gate
            // consults the same configuration at dispatch time, and keeping the
            // instance lets the configuration UI show what exists.
            byId.put(config.name(), config);

            if (StringUtils.isBlank(config.protocol())) {
                log.warn("Centauri: endpoint '{}' has no protocol set, skipping",
                        config.documentPath());
                continue;
            }
            FeedProtocol protocol = protocolsById.get(config.protocol());
            if (protocol == null) {
                log.warn("Centauri: endpoint '{}' references unknown protocol '{}', skipping. "
                                + "Known protocols: {}",
                        config.documentPath(), config.protocol(), protocolsById.keySet());
                continue;
            }

            try {
                FeedInstanceConfig cfg = new FeedInstanceConfig(
                        config.name(), config.protocol(),
                        config.baseUrl() == null ? "" : config.baseUrl(),
                        config.credentialLocation(),
                        // Closes over this project's scope — the instance is cached
                        // per (tenant, project) anyway — and resolves on every call,
                        // so a rotated secret takes effect without waiting for the TTL.
                        () -> resolveCredential(scope, config),
                        protocolExtras(config));
                instances.add(protocol.instantiate(cfg));
            } catch (RuntimeException e) {
                log.warn("Centauri: protocol '{}' refused to instantiate endpoint '{}': {}",
                        config.protocol(), config.documentPath(), e.toString());
            }
        }
        log.debug("Centauri: assembled {} source instance(s) for '{}/{}'",
                instances.size(), scope.tenantId(), scope.projectId());
        return new Sources(List.copyOf(instances), Map.copyOf(byId));
    }

    /**
     * What the protocol gets to see. {@code sendActor} is held back: it governs
     * what Centauri sends <em>on behalf of</em> the reader, and the point of
     * deriving the pseudonym centrally is that no protocol implementation ever
     * has a say in it.
     */
    private static Map<String, Object> protocolExtras(SourceConfig config) {
        Map<String, Object> extras = new LinkedHashMap<>(config.extras());
        extras.remove(FIELD_SEND_ACTOR);
        return extras;
    }

    /**
     * The endpoint credential, with {@code {{secret:…}}} references resolved
     * and a {@code {noop}} literal handed back verbatim.
     *
     * <p>Through {@code resolveForConnector} rather than {@code resolve}: a
     * feed protocol is a connector, not a dynamic element, so it may read a
     * {@code PASSWORD}-typed setting or a vault entry (spec §10).
     *
     * <p>The invocation context carries no user and no process on purpose: the
     * instance is cached per {@code (tenant, project)} and shared across every
     * reader, so a user- or process-scoped reference would serve the first
     * caller's secret to everyone behind them.
     */
    private @Nullable String resolveCredential(FeedScope scope, SourceConfig config) {
        String raw = config.apiKey();
        if (raw == null) {
            return null;
        }
        return secretResolver.resolveForConnector(raw, new ToolInvocationContext(
                scope.tenantId(), scope.projectId(), null, null, null));
    }

    private static void disposeAll(@Nullable Sources sources) {
        if (sources == null) {
            return;
        }
        for (FeedSourceInstance instance : sources.instances()) {
            try {
                instance.dispose();
            } catch (RuntimeException ex) {
                log.warn("Centauri: dispose of '{}' raised: {}", instance.id(), ex.toString());
            }
        }
    }

    /** What one project's cache entry holds: the live instances and their configuration. */
    private record Sources(List<FeedSourceInstance> instances, Map<String, SourceConfig> configs) {
        static Sources empty() {
            return new Sources(List.of(), Map.of());
        }
    }

    /** Cache key: project-scoped, never tenant-only and never per reader. */
    record ScopeKey(String tenantId, String projectId) { }
}
