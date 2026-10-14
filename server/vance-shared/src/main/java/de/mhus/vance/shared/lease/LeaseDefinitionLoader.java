package de.mhus.vance.shared.lease;

import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.tenant.TenantService;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

/**
 * Resolves a {@link LeaseDefinition} by name from the four config layers,
 * merging <b>field-wise</b> — not whole-document-first-wins, because the
 * quota levels belong to different layers (§4 of
 * {@code planning/lease-system.md}):
 *
 * <ol>
 *   <li><b>classpath</b> — {@code vance-defaults/_vance/config/lease/<name>.yaml}
 *       (add-on defaults; the tex add-on ships one)</li>
 *   <li><b>system</b> — tenant {@code _vance}, project {@code _tenant}
 *       (operator overrides; the only place a {@code levels.global} cap counts)</li>
 *   <li><b>tenant</b> — the holder's tenant, project {@code _tenant}</li>
 *   <li><b>project</b> — the holder's tenant + project</li>
 * </ol>
 *
 * <p>Field ownership — the most specific <em>allowed</em> layer wins:
 * {@code levels.global} only from classpath/system (a tenant raising the
 * ceiling of all tenants is a privilege the model denies),
 * {@code resource} only up to the tenant layer (a project must not land
 * on a private counter by accident), everything else from any layer.
 *
 * <p>Merged definitions are cached per {@code (tenant, project, name)} and
 * invalidated through the document-change-event chain — the brain-side
 * listener drops cache entries on writes under {@code _vance/config/lease/}
 * (local and cross-pod, see {@code LeaseDefinitionCacheListener}).
 */
@Service
@Slf4j
public class LeaseDefinitionLoader {

    /** Document path inside each project scope. */
    public static final String PATH_PREFIX = "_vance/config/lease/";

    public static final String PATH_SUFFIX = ".yaml";

    private final DocumentService documentService;

    /** Merged definitions per {@code tenant|project|name}. */
    private final ConcurrentHashMap<String, LeaseDefinition> cache = new ConcurrentHashMap<>();

    public LeaseDefinitionLoader(DocumentService documentService) {
        this.documentService = documentService;
    }

    /**
     * The merged definition for {@code name} at the given scope, or empty
     * when no layer defines it.
     */
    public Optional<LeaseDefinition> load(String tenantId, @Nullable String projectId, String name) {
        String cacheKey = cacheKey(tenantId, projectId, name);
        LeaseDefinition hit = cache.get(cacheKey);
        if (hit != null) return Optional.of(hit);

        LeaseDefinition merged = merge(name, readLayers(tenantId, projectId, name));
        if (merged == null) return Optional.empty();
        cache.put(cacheKey, merged);
        return Optional.of(merged);
    }

    /**
     * Drops cached entries for the changed path. A change in the system or
     * tenant layer affects every project below it, so the invalidation is
     * deliberately coarse: the named lease is dropped for the writing
     * tenant/project, and system-layer writes drop the name everywhere.
     */
    public void invalidate(String tenantId, @Nullable String projectId, String name) {
        if (TenantService.SYSTEM_TENANT.equals(tenantId)) {
            cache.keySet().removeIf(k -> k.endsWith("|" + name));
            return;
        }
        cache.remove(cacheKey(tenantId, projectId, name));
        // The tenant layer feeds every project of the tenant.
        if (HomeBootstrapService.TENANT_PROJECT_NAME.equals(projectId)) {
            String prefix = tenantId + "|";
            cache.keySet().removeIf(k -> k.startsWith(prefix) && k.endsWith("|" + name));
        }
    }

    /** Drops everything — shutdown / tests. */
    public void invalidateAll() {
        cache.clear();
    }

    // ──────────────── layer reads ────────────────

    private record Layer(Map<String, Object> yaml, String source) {}

    private @Nullable LeaseDefinition merge(String name, java.util.List<Layer> layers) {
        // Layers come in outermost-first; a field takes the value of the
        // innermost *allowed* layer that sets it. Ownership table (§4):
        //   resource  → classpath/system/tenant   global → classpath/system
        //   tenant    → classpath/system/tenant   project/user/ttl/wait → all
        @Nullable String resource = null;
        @Nullable Long maxGlobal = null;
        @Nullable Long maxTenant = null;
        @Nullable Long maxProject = null;
        @Nullable Long maxUser = null;
        @Nullable Long ttl = null;
        @Nullable Long wait = null;
        for (Layer layer : layers) {
            LeaseDefinition parsed = LeaseDefinition.fromLayer(name, layer.yaml());
            if (isProjectLayer(layer.source())) {
                // Project layer owns only project/user/ttl/wait — no resource,
                // no global, no tenant cap.
                maxProject = first(parsed.maxProject(), maxProject);
                maxUser = first(parsed.maxUser(), maxUser);
            } else {
                resource = first(parsed.resource(), resource);
                if ("classpath".equals(layer.source()) || "system".equals(layer.source())) {
                    maxGlobal = first(parsed.maxGlobal(), maxGlobal);
                }
                if ("classpath".equals(layer.source())
                        || "system".equals(layer.source())
                        || "tenant".equals(layer.source())) {
                    maxTenant = first(parsed.maxTenant(), maxTenant);
                }
                maxProject = first(parsed.maxProject(), maxProject);
                maxUser = first(parsed.maxUser(), maxUser);
            }
            ttl = first(parsed.ttlSeconds(), ttl);
            wait = first(parsed.waitMs(), wait);
        }
        return layers.isEmpty()
                ? null
                : new LeaseDefinition(name, resource, maxGlobal, maxTenant, maxProject, maxUser, ttl, wait);
    }

    private java.util.List<Layer> readLayers(String tenantId, @Nullable String projectId, String name) {
        String path = PATH_PREFIX + name + PATH_SUFFIX;
        java.util.List<Layer> layers = new java.util.ArrayList<>(4);

        // 1. classpath (outermost — add-on defaults)
        documentService
                .readBundledResource(path)
                .ifPresent(content -> layers.add(new Layer(parseYaml(name, "classpath", content), "classpath")));

        // 2. system tenant _vance / _tenant — the only place levels.global counts
        findByPath(TenantService.SYSTEM_TENANT, HomeBootstrapService.TENANT_PROJECT_NAME, path)
                .ifPresent(content -> layers.add(new Layer(parseYaml(name, "system", content), "system")));

        // 3. holder's tenant / _tenant
        if (!TenantService.SYSTEM_TENANT.equals(tenantId)) {
            findByPath(tenantId, HomeBootstrapService.TENANT_PROJECT_NAME, path)
                    .ifPresent(content -> layers.add(new Layer(parseYaml(name, "tenant", content), "tenant")));
        }

        // 4. holder's project
        if (projectId != null && !projectId.isBlank() && !HomeBootstrapService.TENANT_PROJECT_NAME.equals(projectId)) {
            findByPath(tenantId, projectId, path)
                    .ifPresent(content -> layers.add(new Layer(parseYaml(name, "project", content), "project")));
        }
        return layers;
    }

    private Optional<String> findByPath(String tenantId, String projectId, String path) {
        try {
            return documentService.findByPath(tenantId, projectId, path).map(documentService::readContent);
        } catch (RuntimeException e) {
            log.warn("lease definition: reading '{}/{}' failed: {}", tenantId, path, e.toString());
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseYaml(String name, String source, String content) {
        try {
            Object parsed = new Yaml().load(content);
            if (parsed == null) return Map.of();
            if (parsed instanceof Map<?, ?> m) return (Map<String, Object>) m;
            log.warn("lease definition '{}': {} layer is not a YAML mapping — ignored", name, source);
            return Map.of();
        } catch (RuntimeException e) {
            log.warn("lease definition '{}': malformed {} layer ignored: {}", name, source, e.toString());
            return Map.of();
        }
    }

    private static boolean isProjectLayer(String source) {
        return "project".equals(source);
    }

    private static <T> T first(@Nullable T inner, @Nullable T outer) {
        return inner != null ? inner : outer;
    }

    private static String cacheKey(String tenantId, @Nullable String projectId, String name) {
        return tenantId + "|" + (projectId == null ? "" : projectId) + "|" + name;
    }
}
