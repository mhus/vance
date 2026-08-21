package de.mhus.vance.brain.jaglan;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.mhus.vance.brain.project.ProjectEnginesStopRequested;
import de.mhus.vance.shared.document.jaglan.JaglanPaths;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.toolpack.jaglan.JaglanInstance;
import de.mhus.vance.toolpack.jaglan.JaglanInstanceConfig;
import de.mhus.vance.toolpack.jaglan.JaglanProtocol;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Assembles {@link JaglanInstance}s for a project from
 * {@code jaglan.mount.<name>.*}, caches them per project and tears them down
 * when the project is suspended. Same shape as {@code FeedSourceFactory}.
 *
 * <p>Cache key is {@code (tenantId, projectId)}. Settings are resolved at
 * project level ({@code processId = null}) to match: reading the process
 * cascade while caching per project would leak the first caller's
 * process-scoped overrides to every other process until the TTL expires.
 *
 * <p>A broken mount is <b>dropped, not fatal</b> — unknown protocol, missing
 * field, refused configuration, or a name that is not a legal path segment.
 * One misconfigured mount must not take a project's other mounts down with
 * it, and a dropped mount is better than a folder in the tree that can never
 * answer.
 */
@Service
@Slf4j
public class JaglanSourceFactory {

    static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final SettingService settings;
    private final Map<String, JaglanProtocol> protocolsById;
    private final Cache<ScopeKey, List<JaglanInstance>> cache;

    public JaglanSourceFactory(SettingService settings, List<JaglanProtocol> protocols) {
        this.settings = settings;
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

    /**
     * Drop the cached mounts so the next {@link #assemble} reads the settings
     * again.
     *
     * <p>Exists for the same reason Centauri's does: a five-minute TTL is
     * indistinguishable from a misconfiguration. An operator who has just
     * written {@code jaglan.mount.*} and sees no {@code _ext} folder cannot
     * tell whether the keys are wrong or they are simply early.
     */
    public void evict(String tenantId, String projectId) {
        List<JaglanInstance> evicted = cache.asMap().remove(new ScopeKey(tenantId, projectId));
        if (evicted != null) {
            log.debug("Jaglan: evicted {} mount(s) for '{}/{}' (explicit refresh)",
                    evicted.size(), tenantId, projectId);
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
        Map<String, String> raw = settings.findByPrefixCascade(
                scope.tenantId(), scope.projectId(), /* processId */ null,
                JaglanSettings.PREFIX_MOUNT);
        if (raw.isEmpty()) {
            return List.of();
        }

        Map<String, Map<String, String>> byMount = groupByMount(raw);
        List<JaglanInstance> result = new ArrayList<>(byMount.size());
        for (Map.Entry<String, Map<String, String>> entry : byMount.entrySet()) {
            String mount = entry.getKey();
            Map<String, String> fields = entry.getValue();

            if (!JaglanPaths.isValidMountName(mount)) {
                // The name becomes a path segment and part of every derived
                // document id, so a bad one has to be refused here, at the
                // place a human can fix it — not turn into a broken path on
                // every access.
                log.warn("Jaglan: mount name '{}' is not a legal path segment, skipping", mount);
                continue;
            }
            if ("false".equalsIgnoreCase(fields.get(JaglanSettings.bare(
                    JaglanSettings.SUFFIX_ENABLED)))) {
                continue;
            }
            String protocolId = fields.get(JaglanSettings.bare(JaglanSettings.SUFFIX_PROTOCOL));
            if (StringUtils.isBlank(protocolId)) {
                log.warn("Jaglan: mount '{}' has no protocol set, skipping", mount);
                continue;
            }
            JaglanProtocol protocol = protocolsById.get(protocolId);
            if (protocol == null) {
                log.warn("Jaglan: mount '{}' references unknown protocol '{}', skipping. "
                        + "Known protocols: {}", mount, protocolId, protocolsById.keySet());
                continue;
            }

            String baseUrl = fields.get(JaglanSettings.bare(JaglanSettings.SUFFIX_BASE_URL));
            Map<String, Object> extras = new LinkedHashMap<>();
            for (Map.Entry<String, String> f : fields.entrySet()) {
                if (JaglanSettings.isCommonField(f.getKey())) continue;
                extras.put(f.getKey(), f.getValue());
            }

            try {
                String credentialKey = JaglanSettings.mountApiKey(mount);
                JaglanInstanceConfig cfg = new JaglanInstanceConfig(
                        mount, protocolId,
                        baseUrl == null ? "" : baseUrl,
                        credentialKey,
                        // Read on every call so a rotated key takes effect
                        // without waiting for the instance cache to expire.
                        () -> settings.getDecryptedPasswordCascade(
                                scope.tenantId(), scope.projectId(), null, credentialKey),
                        scope.tenantId(), scope.projectId(), extras);
                result.add(protocol.instantiate(cfg));
            } catch (RuntimeException e) {
                log.warn("Jaglan: protocol '{}' refused to instantiate mount '{}': {}",
                        protocolId, mount, e.toString());
            }
        }
        log.debug("Jaglan: assembled {} mount(s) for '{}/{}'",
                result.size(), scope.tenantId(), scope.projectId());
        return List.copyOf(result);
    }

    /** Group {@code jaglan.mount.<name>.<suffix>} keys per mount. */
    static Map<String, Map<String, String>> groupByMount(Map<String, String> raw) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : raw.entrySet()) {
            String key = e.getKey();
            if (!key.startsWith(JaglanSettings.PREFIX_MOUNT)) continue;
            String rest = key.substring(JaglanSettings.PREFIX_MOUNT.length());
            int dot = rest.indexOf('.');
            if (dot <= 0 || dot == rest.length() - 1) continue;
            out.computeIfAbsent(rest.substring(0, dot), k -> new LinkedHashMap<>())
                    .put(rest.substring(dot + 1), e.getValue());
        }
        return out;
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
