package de.mhus.vance.brain.servertool;

import de.mhus.vance.brain.tools.types.ToolFactory;
import de.mhus.vance.brain.tools.types.ToolFactoryRegistry;
import de.mhus.vance.shared.servertool.ServerToolConfig;
import de.mhus.vance.shared.servertool.ServerToolDocument;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.servertool.ServerToolLoader;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * In-memory registry of cascade-resolved server-tool configurations,
 * per {@code (tenantId, projectId)} scope. Bootstrapped at project
 * activation, refreshed by the admin controller after write/delete.
 *
 * <p>The registry talks only to {@link ServerToolLoader} (cascade-merged
 * configs across project → _vance → classpath) and to
 * {@link ToolFactoryRegistry} (factory lookup). Built-in bean tools are
 * intentionally not handled here — the consuming {@code ServerToolService}
 * decides when to fall back to them.
 *
 * <p>Pack materialisation is <b>lazy</b>: a resolved entry holds the
 * parsed {@link ServerToolConfig}; the {@link de.mhus.vance.toolpack.Tool}
 * list is built on first access and then captured on the entry instance.
 * A refresh replaces the entry, so the materialised state goes away with
 * it (GC frees the old tool list). MCP/REST connection pools tied to the
 * underlying document id are <i>not</i> released here — that's
 * orchestrated by the consuming service when it wires the lookup path
 * (Schritt C).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ServerToolRegistry {

    private final ServerToolLoader loader;
    private final ToolFactoryRegistry factoryRegistry;

    /** Project scope cache. Key: {@code tenantId|projectId}. */
    private final Map<String, ProjectScope> scopes = new ConcurrentHashMap<>();

    // ──────────────────── Lifecycle ────────────────────

    /**
     * Load every cascade-resolved tool for this project into the
     * registry. Idempotent — a re-bootstrap replaces the prior scope
     * atomically. Returns the number of entries that loaded successfully
     * (malformed YAML is logged + skipped by the loader, not counted).
     */
    public synchronized int bootstrapProject(String tenantId, String projectId) {
        ProjectScope prior = scopes.remove(scopeKey(tenantId, projectId));
        if (prior != null) releaseAll(prior);
        List<ServerToolConfig> entries = loader.listAll(tenantId, projectId);
        Map<String, ResolvedTool> byName = new LinkedHashMap<>();
        for (ServerToolConfig cfg : entries) {
            byName.put(cfg.name(), new ResolvedTool(cfg, tenantId, projectId));
        }
        scopes.put(scopeKey(tenantId, projectId), new ProjectScope(byName));
        log.info("ServerToolRegistry bootstrap '{}/{}' loaded {} entries",
                tenantId, projectId, byName.size());
        return byName.size();
    }

    /** Drop the scope. Subsequent lookups return empty until {@link #bootstrapProject}. */
    public synchronized void unloadProject(String tenantId, String projectId) {
        ProjectScope prior = scopes.remove(scopeKey(tenantId, projectId));
        if (prior != null) releaseAll(prior);
    }

    private void releaseAll(ProjectScope scope) {
        for (ResolvedTool entry : scope.entries.values()) {
            invalidateFactory(entry.config);
        }
    }

    /** Notify the type-specific factory that the document is being replaced/removed. */
    private void invalidateFactory(ServerToolConfig cfg) {
        if (cfg.documentId() == null) return;
        factoryRegistry.find(cfg.type()).ifPresent(f -> {
            try {
                f.invalidate(cfg.documentId());
            } catch (RuntimeException ex) {
                log.warn("ServerToolRegistry: factory '{}' invalidate failed for '{}': {}",
                        cfg.type(), cfg.name(), ex.toString());
            }
        });
    }

    /** Full project re-bootstrap — equivalent to {@link #bootstrapProject}. */
    public int refreshProject(String tenantId, String projectId) {
        return bootstrapProject(tenantId, projectId);
    }

    /**
     * Reload exactly one tool by name in the project's cascade. Removes
     * the entry from the scope if the cascade no longer carries it.
     *
     * @return {@code true} when the entry now resolves to a live config;
     *         {@code false} when it was removed (deleted or parse-broken)
     */
    public synchronized boolean refreshOne(String tenantId, String projectId, String name) {
        ProjectScope scope = scopes.get(scopeKey(tenantId, projectId));
        // If the scope isn't loaded, the refresh is irrelevant — the
        // project will load fresh on next bootstrap.
        if (scope == null) {
            // Even when the originating scope isn't loaded, a change to a
            // CASCADE-PARENT (i.e. _tenant) must invalidate every loaded
            // child so their cached snapshot of the parent stays correct.
            invalidateCascadeChildrenIfParent(tenantId, projectId, name);
            return false;
        }
        String norm = ServerToolLoader.normalizedName(name);
        ResolvedTool prior = scope.entries.get(norm);
        Optional<ServerToolConfig> reloaded;
        try {
            reloaded = loader.load(tenantId, projectId, norm);
        } catch (ServerToolLoader.ServerToolParseException ex) {
            log.warn("ServerToolRegistry refreshOne parse failed '{}/{}/{}': {}",
                    tenantId, projectId, norm, ex.getMessage());
            scope.entries.remove(norm);
            if (prior != null) invalidateFactory(prior.config);
            invalidateCascadeChildrenIfParent(tenantId, projectId, name);
            return false;
        }
        if (prior != null) invalidateFactory(prior.config);
        if (reloaded.isEmpty()) {
            scope.entries.remove(norm);
            invalidateCascadeChildrenIfParent(tenantId, projectId, name);
            return false;
        }
        scope.entries.put(norm, new ResolvedTool(reloaded.get(), tenantId, projectId));
        invalidateCascadeChildrenIfParent(tenantId, projectId, name);
        return true;
    }

    /**
     * When the changed tool lives in the tenant-default project
     * ({@code _tenant}), every other loaded scope for this tenant has a
     * stale cascade-view and must reload. We drop the cached entries —
     * the next access bootstraps them again. Tools in a regular project
     * don't cascade upwards, so no peer scope is affected.
     *
     * <p>This is the side of the refresh that {@link ServerToolService}
     * cannot do alone: it only knows about the doc's own project, not
     * who's reading it.
     */
    private void invalidateCascadeChildrenIfParent(
            String tenantId, String projectId, String name) {
        if (!HomeBootstrapService.TENANT_PROJECT_NAME.equals(projectId)) return;
        String tenantKey = tenantId + "|";
        List<String> stale = new ArrayList<>();
        for (String key : scopes.keySet()) {
            if (!key.startsWith(tenantKey)) continue;
            if (key.equals(scopeKey(tenantId, projectId))) continue; // skip self
            stale.add(key);
        }
        for (String key : stale) {
            ProjectScope removed = scopes.remove(key);
            if (removed != null) releaseAll(removed);
        }
        if (!stale.isEmpty()) {
            log.info("ServerToolRegistry: invalidated {} child scope(s) after _tenant '{}' change",
                    stale.size(), name);
        }
    }

    // ──────────────────── Read API ────────────────────

    /**
     * Cascade-resolved config for {@code name}. {@code name} may be a
     * sub-tool ({@code <pack>__<sub>}); the lookup matches the pack
     * portion against the stored config names.
     *
     * <p>Returns the config even if {@code enabled=false} so the caller
     * can decide between "fall through to built-ins" (config absent)
     * versus "tool is explicitly disabled" (config present but disabled).
     */
    public Optional<ServerToolConfig> findConfig(
            String tenantId, String projectId, String name) {
        ProjectScope scope = scopes.get(scopeKey(tenantId, projectId));
        if (scope == null) return Optional.empty();
        ResolvedTool entry = scope.entries.get(packPrefix(name));
        return entry == null ? Optional.empty() : Optional.of(entry.config);
    }

    /**
     * Resolve {@code name} to a runnable tool. Returns empty when no
     * cascade layer carries the pack, when the config is disabled, or
     * when the requested sub-tool is disabled inside an enabled pack.
     */
    public Optional<de.mhus.vance.toolpack.Tool> lookup(
            String tenantId, String projectId, String name) {
        return lookup(tenantId, projectId, name, /*ctx*/ null);
    }

    public Optional<de.mhus.vance.toolpack.Tool> lookup(
            String tenantId, String projectId, String name,
            @Nullable ToolInvocationContext ctx) {
        ProjectScope scope = scopes.get(scopeKey(tenantId, projectId));
        if (scope == null) return Optional.empty();
        ResolvedTool entry = scope.entries.get(packPrefix(name));
        if (entry == null) return Optional.empty();
        if (!entry.config.enabled()) return Optional.empty();
        return entry.pickSubTool(name, factoryRegistry, ctx);
    }

    /**
     * Every enabled tool in this project's cascade, with disabled
     * sub-tools filtered out per pack. Order is the order the loader
     * emitted the entries — usable for stable display in admin UIs.
     */
    public List<de.mhus.vance.toolpack.Tool> listAll(String tenantId, String projectId) {
        return listAll(tenantId, projectId, /*ctx*/ null);
    }

    public List<de.mhus.vance.toolpack.Tool> listAll(
            String tenantId, String projectId,
            @Nullable ToolInvocationContext ctx) {
        ProjectScope scope = scopes.get(scopeKey(tenantId, projectId));
        if (scope == null) return List.of();
        List<de.mhus.vance.toolpack.Tool> out = new ArrayList<>();
        for (ResolvedTool entry : scope.entries.values()) {
            if (!entry.config.enabled()) continue;
            out.addAll(entry.materializeFiltered(factoryRegistry, ctx));
        }
        return out;
    }

    /** Tools in this project's cascade carrying {@code label}. */
    public List<de.mhus.vance.toolpack.Tool> findByLabel(
            String tenantId, String projectId, String label) {
        return findByLabel(tenantId, projectId, label, /*ctx*/ null);
    }

    public List<de.mhus.vance.toolpack.Tool> findByLabel(
            String tenantId, String projectId, String label,
            @Nullable ToolInvocationContext ctx) {
        return listAll(tenantId, projectId, ctx).stream()
                .filter(t -> t.labels().contains(label))
                .toList();
    }

    /** Cascade-resolved configs for this project (raw, including disabled). */
    public List<ServerToolConfig> listConfigs(String tenantId, String projectId) {
        ProjectScope scope = scopes.get(scopeKey(tenantId, projectId));
        if (scope == null) return List.of();
        List<ServerToolConfig> out = new ArrayList<>(scope.entries.size());
        for (ResolvedTool entry : scope.entries.values()) {
            out.add(entry.config);
        }
        return out;
    }

    // ──────────────────── Internals ────────────────────

    private static String scopeKey(String tenantId, String projectId) {
        return tenantId + "|" + projectId;
    }

    private static String packPrefix(String name) {
        int sep = name.indexOf(ToolFactory.PACK_SEPARATOR);
        return sep < 0 ? name : name.substring(0, sep);
    }

    /** Mutable per-project container — accessed under the registry lock. */
    private static final class ProjectScope {
        final Map<String, ResolvedTool> entries;

        ProjectScope(Map<String, ResolvedTool> entries) {
            this.entries = new LinkedHashMap<>(entries);
        }
    }

    /**
     * Cascade-resolved tool entry. Materialisation is <b>NOT</b> cached
     * on the entry — every call re-runs {@code factory.create(doc, ctx)}
     * so that user-scoped state (OAuth tokens, MCP session ids) reflects
     * the current invocation context, not the first one that materialised.
     * Heavy work (live MCP {@code tools/list}, REST OpenAPI parsing) is
     * cached one level down — in the factory's own connection pool — so
     * the per-request cost is a Map-keyed lookup.
     */
    static final class ResolvedTool {
        final ServerToolConfig config;
        final String tenantId;
        final String projectId;

        ResolvedTool(ServerToolConfig config, String tenantId, String projectId) {
            this.config = config;
            this.tenantId = tenantId;
            this.projectId = projectId;
        }

        /**
         * Materialised tools without {@code disabledSubTools} filtering.
         * {@code ctx} threads the calling user/session down into pack
         * factories that bootstrap user-scoped connections (MCP-server
         * with OAuth, REST-API with per-user tokens). Re-runs every call
         * — see class javadoc for the rationale.
         */
        List<de.mhus.vance.toolpack.Tool> materializeRaw(
                ToolFactoryRegistry registry,
                @Nullable ToolInvocationContext ctx) {
            ToolFactory factory = registry.find(config.type()).orElseThrow(
                    () -> new IllegalStateException(
                            "Unknown tool type '" + config.type()
                                    + "' on server-tool '" + config.name()
                                    + "' (tenant=" + tenantId
                                    + ", project=" + projectId + ")"));
            ServerToolDocument transientDoc = config.toTransientDocument(tenantId, projectId);
            return List.copyOf(factory.create(transientDoc, ctx));
        }

        /** Convenience overload — no caller context. Kept for admin-side paths. */
        List<de.mhus.vance.toolpack.Tool> materializeRaw(ToolFactoryRegistry registry) {
            return materializeRaw(registry, /*ctx*/ null);
        }

        /** Materialised tools with disabled sub-tools removed. */
        List<de.mhus.vance.toolpack.Tool> materializeFiltered(
                ToolFactoryRegistry registry,
                @Nullable ToolInvocationContext ctx) {
            List<de.mhus.vance.toolpack.Tool> raw = materializeRaw(registry, ctx);
            if (config.disabledSubTools().isEmpty()) return raw;
            String prefix = config.name() + ToolFactory.PACK_SEPARATOR;
            return raw.stream().filter(t -> {
                String local = t.name().startsWith(prefix)
                        ? t.name().substring(prefix.length())
                        : t.name();
                return !config.disabledSubTools().contains(local);
            }).toList();
        }

        List<de.mhus.vance.toolpack.Tool> materializeFiltered(ToolFactoryRegistry registry) {
            return materializeFiltered(registry, /*ctx*/ null);
        }

        Optional<de.mhus.vance.toolpack.Tool> pickSubTool(
                String requestedName,
                ToolFactoryRegistry registry,
                @Nullable ToolInvocationContext ctx) {
            for (de.mhus.vance.toolpack.Tool t : materializeRaw(registry, ctx)) {
                if (!t.name().equals(requestedName)) continue;
                String prefix = config.name() + ToolFactory.PACK_SEPARATOR;
                String local = t.name().startsWith(prefix)
                        ? t.name().substring(prefix.length())
                        : t.name();
                if (config.disabledSubTools().contains(local)) {
                    return Optional.empty();
                }
                return Optional.of(t);
            }
            return Optional.empty();
        }

        Optional<de.mhus.vance.toolpack.Tool> pickSubTool(
                String requestedName, ToolFactoryRegistry registry) {
            return pickSubTool(requestedName, registry, /*ctx*/ null);
        }
    }
}
