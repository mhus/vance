package de.mhus.vance.brain.jaglan;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.mhus.vance.brain.project.ProjectEnginesStopRequested;
import de.mhus.vance.brain.sourceconfig.SourceConfig;
import de.mhus.vance.brain.sourceconfig.SourceConfigCache;
import de.mhus.vance.brain.sourceconfig.SourceConfigLoader;
import de.mhus.vance.brain.sourceconfig.SourceConfigPaths;
import de.mhus.vance.shared.document.jaglan.JaglanPaths;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.core.SecretResolver;
import de.mhus.vance.toolpack.jaglan.JaglanInstance;
import de.mhus.vance.toolpack.jaglan.JaglanInstanceConfig;
import de.mhus.vance.toolpack.jaglan.JaglanProtocol;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Assembles {@link JaglanInstance}s for a project from the documents under
 * {@link SourceConfigPaths#MOUNTS}, caches them per project and tears them down
 * when the project is suspended. Same shape as {@code FeedSourceFactory}.
 *
 * <p>Cache key is {@code (tenantId, projectId)}. Configuration is read at
 * project level to match: the document cascade is project → {@code _tenant},
 * so a {@code _tenant} mount applies to every project of the tenant and a
 * project overrides it by writing a document of the same name.
 *
 * <p>A broken mount is <b>dropped, not fatal</b> — unknown protocol, missing
 * field, refused configuration, or a name that is not a legal path segment.
 * One misconfigured mount must not take a project's other mounts down with
 * it, and a dropped mount is better than a folder in the tree that can never
 * answer.
 */
@Service
@Slf4j
public class JaglanSourceFactory implements SourceConfigCache {

    static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final SourceConfigLoader configLoader;
    private final SecretResolver secretResolver;
    private final Map<String, JaglanProtocol> protocolsById;
    private final Cache<ScopeKey, List<JaglanInstance>> cache;

    public JaglanSourceFactory(
            SourceConfigLoader configLoader, SecretResolver secretResolver,
            List<JaglanProtocol> protocols) {
        this.configLoader = configLoader;
        this.secretResolver = secretResolver;
        Map<String, JaglanProtocol> byId = new LinkedHashMap<>();
        for (JaglanProtocol p : protocols) {
            JaglanProtocol prev = byId.put(p.id(), p);
            if (prev != null) {
                log.warn("JaglanProtocol id collision on '{}': '{}' replaces '{}' — "
                                + "later bean wins, but this is a misconfiguration",
                        p.id(), p.getClass().getName(), prev.getClass().getName());
            }
        }
        this.protocolsById = Map.copyOf(byId);
        this.cache = Caffeine.newBuilder()
                .maximumSize(256)
                .expireAfterWrite(CACHE_TTL)
                .<ScopeKey, List<JaglanInstance>>removalListener((key, value, cause) -> disposeAll(value))
                .build();
        log.info("Jaglan: {} protocol(s) registered: {}", protocolsById.size(), protocolsById.keySet());
    }

    /** The mounts of a project, assembled on first use and cached. */
    public List<JaglanInstance> assemble(String tenantId, String projectId) {
        if (StringUtils.isBlank(tenantId) || StringUtils.isBlank(projectId)) {
            return List.of();
        }
        return cache.get(new ScopeKey(tenantId, projectId), this::build);
    }

    /** One mount by name, or {@code null} when it is not configured. */
    public @Nullable JaglanInstance find(String tenantId, String projectId, String mount) {
        for (JaglanInstance instance : assemble(tenantId, projectId)) {
            if (instance.mount().equals(mount)) return instance;
        }
        return null;
    }

    @Override
    public String configPathPrefix() {
        return SourceConfigPaths.MOUNTS;
    }

    /**
     * Drop the cached mounts so the next {@link #assemble} reads the documents
     * again.
     *
     * <p>Exists for the same reason Centauri's does: a five-minute TTL is
     * indistinguishable from a misconfiguration. An operator who has just
     * written a mount document and sees no {@code _ext} folder cannot tell
     * whether the file is wrong or they are simply early.
     */
    @Override
    public void evict(String tenantId, String projectId) {
        List<JaglanInstance> evicted = cache.asMap().remove(new ScopeKey(tenantId, projectId));
        if (evicted != null) {
            log.debug("Jaglan: evicted {} mount(s) for '{}/{}' (explicit refresh)",
                    evicted.size(), tenantId, projectId);
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
            log.debug("Jaglan: evicted {} project scope(s) of tenant '{}' "
                    + "(tenant-wide configuration changed)", dropped, tenantId);
        }
    }

    @EventListener
    public void onProjectStop(ProjectEnginesStopRequested event) {
        if (event == null || StringUtils.isBlank(event.tenantId())
                || StringUtils.isBlank(event.projectName())) {
            return;
        }
        List<JaglanInstance> evicted =
                cache.asMap().remove(new ScopeKey(event.tenantId(), event.projectName()));
        if (evicted != null) {
            log.debug("Jaglan: evicted {} mount(s) for '{}/{}' (project stop)",
                    evicted.size(), event.tenantId(), event.projectName());
        }
    }

    // ── internals ────────────────────────────────────────────────────

    private List<JaglanInstance> build(ScopeKey scope) {
        List<SourceConfig> configs = configLoader.load(
                scope.tenantId(), scope.projectId(), SourceConfigPaths.MOUNTS);
        if (configs.isEmpty()) {
            return List.of();
        }

        List<JaglanInstance> result = new ArrayList<>(configs.size());
        for (SourceConfig config : configs) {
            String mount = config.name();
            if (!JaglanPaths.isValidMountName(mount)) {
                // The name becomes a path segment and part of every derived
                // document id, so a bad one has to be refused here, at the
                // place a human can fix it — not turn into a broken path on
                // every access.
                log.warn("Jaglan: mount name '{}' is not a legal path segment, skipping", mount);
                continue;
            }
            if (!config.enabled()) {
                // Unlike a feed source, a disabled mount is dropped rather than
                // kept: what it would produce is a folder in the document tree,
                // and a folder that exists but answers nothing is worse than no
                // folder at all.
                continue;
            }
            if (StringUtils.isBlank(config.protocol())) {
                log.warn("Jaglan: mount '{}' has no protocol set, skipping", config.documentPath());
                continue;
            }
            JaglanProtocol protocol = protocolsById.get(config.protocol());
            if (protocol == null) {
                log.warn("Jaglan: mount '{}' references unknown protocol '{}', skipping. "
                                + "Known protocols: {}",
                        config.documentPath(), config.protocol(), protocolsById.keySet());
                continue;
            }

            try {
                JaglanInstanceConfig cfg = new JaglanInstanceConfig(
                        mount, config.protocol(),
                        config.baseUrl() == null ? "" : config.baseUrl(),
                        config.credentialLocation(),
                        // Resolved on every call so a rotated secret takes
                        // effect without waiting for the instance cache.
                        () -> resolveCredential(scope, config),
                        scope.tenantId(), scope.projectId(), config.extras());
                result.add(protocol.instantiate(cfg));
            } catch (RuntimeException e) {
                log.warn("Jaglan: protocol '{}' refused to instantiate mount '{}': {}",
                        config.protocol(), config.documentPath(), e.toString());
            }
        }
        log.debug("Jaglan: assembled {} mount(s) for '{}/{}'",
                result.size(), scope.tenantId(), scope.projectId());
        return List.copyOf(result);
    }

    /**
     * The mount credential, with {@code {{secret:…}}} references resolved and a
     * {@code {noop}} literal handed back verbatim.
     *
     * <p>Through {@code resolveForConnector}: a mount is a connector, so it may
     * read a {@code PASSWORD}-typed setting or a vault entry. No user and no
     * process in the context — the instance is cached per
     * {@code (tenant, project)} and shared by everyone reading through it.
     */
    private @Nullable String resolveCredential(ScopeKey scope, SourceConfig config) {
        String raw = config.apiKey();
        if (raw == null) {
            return null;
        }
        return secretResolver.resolveForConnector(raw, new ToolInvocationContext(
                scope.tenantId(), scope.projectId(), null, null, null));
    }

    private static void disposeAll(@Nullable List<JaglanInstance> instances) {
        if (instances == null) return;
        for (JaglanInstance instance : instances) {
            try {
                instance.dispose();
            } catch (RuntimeException e) {
                log.warn("Jaglan: dispose of mount '{}' failed: {}",
                        instance.mount(), e.toString());
            }
        }
    }

    record ScopeKey(String tenantId, String projectId) {}
}
