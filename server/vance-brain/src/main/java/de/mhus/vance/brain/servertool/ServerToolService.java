package de.mhus.vance.brain.servertool;

import de.mhus.vance.brain.tools.types.ToolFactory;
import de.mhus.vance.brain.tools.types.ToolFactoryRegistry;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.servertool.ServerToolConfig;
import de.mhus.vance.shared.servertool.ServerToolDocument;
import de.mhus.vance.shared.servertool.ServerToolLoader;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Lookup + CRUD entry point for server tools. Reads delegate to
 * {@link ServerToolRegistry}, which holds the cascade-merged in-memory
 * snapshot built from {@link ServerToolLoader} (YAML documents under
 * {@code server-tools/<name>.yaml}). Writes persist through
 * {@link DocumentService} and immediately {@link ServerToolRegistry#refreshOne
 * refresh} the registry so subsequent lookups see the new state.
 *
 * <p>Built-in {@code Tool} beans are layered <i>below</i> the registry:
 * a configured cascade entry (whether enabled or disabled) shadows a
 * built-in of the same name. The "disabled" state in the innermost
 * cascade layer is the documented way to suppress a built-in for a
 * specific tenant or project.
 *
 * <p>{@code _vance} is the tenant-wide system project. The registry
 * lazy-bootstraps any scope it hasn't seen yet, so callers don't have
 * to choreograph activation — the lifecycle listener still preloads
 * regular projects on engine start for snappy first lookups.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ServerToolService {

    private final ServerToolRegistry registry;
    private final ServerToolLoader loader;
    private final ToolFactoryRegistry factoryRegistry;
    private final DocumentService documentService;

    /** Set by the source aggregator after construction (avoids cycles). */
    private volatile BuiltInProvider builtInProvider = BuiltInProvider.EMPTY;

    public void setBuiltInProvider(BuiltInProvider provider) {
        this.builtInProvider = provider;
    }

    // ──────────────────── Cascade lookup ────────────────────

    /**
     * Resolve a tool by name. Registry takes precedence over built-ins:
     * if any cascade layer carries the pack-name, the registry decides
     * (returning the materialised sub-tool, or empty when the entry is
     * disabled). Built-ins are consulted only when no document tier
     * carries the name.
     */
    public Optional<Tool> lookup(String tenantId, String projectId, String name) {
        return lookup(tenantId, projectId, name, /*ctx*/ null);
    }

    public Optional<Tool> lookup(
            String tenantId, String projectId, String name,
            @Nullable ToolInvocationContext ctx) {
        ensureBootstrapped(tenantId, projectId);
        Optional<ServerToolConfig> cfg = registry.findConfig(tenantId, projectId, name);
        if (cfg.isPresent()) {
            return registry.lookup(tenantId, projectId, name, ctx);
        }
        return builtInProvider.find(name);
    }

    /**
     * All tools visible to the project — built-ins shadowed by every
     * configured pack-name, then the registry's materialised tools
     * (which already filters disabled packs / sub-tools) layered on top.
     *
     * <p>{@code ctx} carries the calling user/session for factories that
     * bootstrap user-scoped connections (MCP-server with OAuth). Pass
     * {@code null} on admin paths that have no user yet.
     */
    public List<Tool> listAll(String tenantId, String projectId) {
        return listAll(tenantId, projectId, /*ctx*/ null);
    }

    public List<Tool> listAll(
            String tenantId, String projectId,
            @Nullable ToolInvocationContext ctx) {
        ensureBootstrapped(tenantId, projectId);
        Map<String, Tool> acc = new LinkedHashMap<>();
        for (Tool t : builtInProvider.list()) {
            acc.put(t.name(), t);
        }
        for (ServerToolConfig cfg : registry.listConfigs(tenantId, projectId)) {
            String prefix = cfg.name() + ToolFactory.PACK_SEPARATOR;
            acc.keySet().removeIf(n -> n.equals(cfg.name()) || n.startsWith(prefix));
        }
        for (Tool t : registry.listAll(tenantId, projectId, ctx)) {
            acc.put(t.name(), t);
        }
        return new ArrayList<>(acc.values());
    }

    /** Tools tagged with {@code label}; expands {@code @<label>} recipe selectors. */
    public List<Tool> findByLabel(String tenantId, String projectId, String label) {
        return findByLabel(tenantId, projectId, label, /*ctx*/ null);
    }

    public List<Tool> findByLabel(
            String tenantId, String projectId, String label,
            @Nullable ToolInvocationContext ctx) {
        return listAll(tenantId, projectId, ctx).stream()
                .filter(t -> t.labels().contains(label))
                .toList();
    }

    private void ensureBootstrapped(String tenantId, String projectId) {
        try {
            registry.ensureBootstrapped(tenantId, projectId);
        } catch (RuntimeException ex) {
            log.warn("ServerToolService: lazy bootstrap failed '{}/{}': {}",
                    tenantId, projectId, ex.toString());
        }
    }

    // ──────────────────── CRUD (project layer) ────────────────────

    /**
     * Create a new server tool in {@code projectId}. The new entry must
     * not collide with an existing one (use {@link #update} to modify).
     */
    public ServerToolConfig create(
            String tenantId, String projectId, ServerToolConfig incoming) {
        String norm = ServerToolLoader.normalizedName(incoming.name());
        if (documentService.findByPath(tenantId, projectId, ServerToolLoader.pathFor(norm))
                .isPresent()) {
            throw new IllegalStateException(
                    "Server tool already exists: tenant=" + tenantId
                            + " project=" + projectId + " name=" + norm);
        }
        return writeAndRefresh(tenantId, projectId, norm, incoming, /*createdBy*/ incoming.createdBy());
    }

    /** Replace an existing server tool by name. */
    public ServerToolConfig update(
            String tenantId, String projectId, String name, ServerToolConfig incoming) {
        String norm = ServerToolLoader.normalizedName(name);
        DocumentDocument existing = documentService.findByPath(
                        tenantId, projectId, ServerToolLoader.pathFor(norm))
                .orElseThrow(() -> new IllegalStateException(
                        "Server tool not found: tenant=" + tenantId
                                + " project=" + projectId + " name=" + norm));
        return writeAndRefresh(tenantId, projectId, norm, incoming, existing.getCreatedBy());
    }

    /** Remove a server tool from {@code projectId}. No-op when absent. */
    public void delete(String tenantId, String projectId, String name) {
        String norm = ServerToolLoader.normalizedName(name);
        documentService.findByPath(tenantId, projectId, ServerToolLoader.pathFor(norm))
                .ifPresent(doc -> {
                    // Refresh is driven by the DocumentChangedEvent that
                    // documentService.delete publishes — picked up by
                    // ServerToolDocumentListener which calls
                    // registry.refreshOne. Keeping ensureBootstrapped so
                    // the listener's refresh actually has a scope to update.
                    ensureBootstrapped(tenantId, projectId);
                    documentService.delete(doc.getId(), de.mhus.vance.shared.permission.WriteActor.SYSTEM);
                });
    }

    /** Cascade-resolved config for the admin REST surface (round-trip view). */
    public Optional<ServerToolConfig> findConfig(
            String tenantId, String projectId, String name) {
        ensureBootstrapped(tenantId, projectId);
        return registry.findConfig(tenantId, projectId, name);
    }

    /** All cascade-resolved configs in the project's view (incl. disabled). */
    public List<ServerToolConfig> listConfigs(String tenantId, String projectId) {
        ensureBootstrapped(tenantId, projectId);
        return registry.listConfigs(tenantId, projectId);
    }

    private ServerToolConfig writeAndRefresh(
            String tenantId, String projectId, String normName,
            ServerToolConfig incoming, String createdBy) {
        if (factoryRegistry.find(incoming.type()).isEmpty()) {
            throw new IllegalArgumentException(
                    "Unknown tool type '" + incoming.type() + "' — available: "
                            + factoryRegistry.list().stream().map(ToolFactory::typeId).toList());
        }
        String yaml = incoming.yaml();
        if (yaml == null || yaml.isBlank()) {
            yaml = ServerToolConfigYamlWriter.write(incoming);
        }
        // Sanity-check the YAML once more so we never persist something the
        // loader can't read back.
        loader.validateYaml(normName, yaml);
        // Bootstrap before the write so the listener-driven refresh has a
        // scope to update — without this, the first write to a fresh scope
        // would publish the event into an empty registry. The actual
        // refresh runs synchronously via the DocumentChangedEvent →
        // RoutedDocumentChangedEvent → ServerToolDocumentListener chain
        // that documentService.upsertText kicks off.
        ensureBootstrapped(tenantId, projectId);
        documentService.upsertText(
                tenantId, projectId,
                ServerToolLoader.pathFor(normName),
                /*title*/ "Server tool: " + normName,
                /*tags*/ null,
                yaml,
                createdBy,
                de.mhus.vance.shared.permission.WriteActor.SYSTEM);
        return registry.findConfig(tenantId, projectId, normName).orElseThrow(
                () -> new IllegalStateException(
                        "Server tool vanished immediately after write: " + normName));
    }

    // ──────────────────── Legacy shims (ServerToolDocument carriers) ────────────────────
    //
    // These keep the historical callers (Kit code, ServerToolBootstrapService,
    // IntellijMcpRegisterHandler, ServerToolAdminController) working through
    // the transition. They convert {@code ServerToolDocument} ↔ {@code
    // ServerToolConfig} and delegate to the canonical methods above so all
    // writes funnel through {@code DocumentService}. Removed in Schritt E
    // once every caller is on {@code ServerToolConfig}.

    /** Legacy carrier-shape lookup. Returns a transient (non-persisted) document. */
    public Optional<ServerToolDocument> findDocument(
            String tenantId, String projectId, String name) {
        return findConfig(tenantId, projectId, name)
                .map(cfg -> cfg.toTransientDocument(tenantId, projectId));
    }

    /** Legacy carrier-shape list. Each element is a transient document. */
    public List<ServerToolDocument> listDocuments(String tenantId, String projectId) {
        List<ServerToolConfig> configs = listConfigs(tenantId, projectId);
        List<ServerToolDocument> out = new ArrayList<>(configs.size());
        for (ServerToolConfig cfg : configs) {
            out.add(cfg.toTransientDocument(tenantId, projectId));
        }
        return out;
    }

    /** Legacy carrier-shape create — converts and delegates to {@link #create(String, String, ServerToolConfig)}. */
    public ServerToolDocument create(
            String tenantId, String projectId, ServerToolDocument doc) {
        ServerToolConfig stored = create(tenantId, projectId, configOf(doc));
        return stored.toTransientDocument(tenantId, projectId);
    }

    /** Legacy carrier-shape update — converts and delegates to {@link #update(String, String, String, ServerToolConfig)}. */
    public ServerToolDocument update(
            String tenantId, String projectId, String name, ServerToolDocument incoming) {
        ServerToolConfig stored = update(tenantId, projectId, name, configOf(incoming));
        return stored.toTransientDocument(tenantId, projectId);
    }

    private static ServerToolConfig configOf(ServerToolDocument doc) {
        return new ServerToolConfig(
                doc.getName(),
                doc.getType(),
                doc.getDescription(),
                doc.getParameters() == null
                        ? new java.util.LinkedHashMap<>()
                        : new java.util.LinkedHashMap<>(doc.getParameters()),
                doc.getLabels() == null
                        ? new java.util.ArrayList<>()
                        : new java.util.ArrayList<>(doc.getLabels()),
                doc.isEnabled(),
                doc.isPrimary(),
                doc.getDisabledSubTools() == null
                        ? new java.util.LinkedHashSet<>()
                        : new java.util.LinkedHashSet<>(doc.getDisabledSubTools()),
                doc.isDefaultDeferred(),
                doc.getPromptHint() == null ? "" : doc.getPromptHint(),
                ServerToolConfig.Source.PROJECT,
                /*documentId*/ null,
                doc.getCreatedBy(),
                /*yaml*/ "");
    }

    // ──────────────────── Type registry passthrough ────────────────────

    public List<ToolFactory> listTypes() {
        return factoryRegistry.list();
    }

    public Optional<ToolFactory> findType(String typeId) {
        return factoryRegistry.find(typeId);
    }

    /**
     * Built-in bean accessor injected by the source aggregator. Kept as
     * an interface so {@code ServerToolService} stays free of a direct
     * dependency on {@code BuiltInToolSource} (which depends on
     * {@code Tool} bean discovery and would create a cycle).
     */
    public interface BuiltInProvider {

        BuiltInProvider EMPTY = new BuiltInProvider() {
            @Override
            public List<Tool> list() {
                return List.of();
            }

            @Override
            public Optional<Tool> find(String name) {
                return Optional.empty();
            }
        };

        List<Tool> list();

        Optional<Tool> find(String name);
    }
}
