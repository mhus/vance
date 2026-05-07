package de.mhus.vance.brain.servertool;

import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.brain.tools.types.ToolFactory;
import de.mhus.vance.brain.tools.types.ToolFactoryRegistry;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.servertool.ServerToolDocument;
import de.mhus.vance.shared.servertool.ServerToolRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Cascade-aware lookup and CRUD for {@link ServerToolDocument}. Every
 * read goes through the project cascade
 * {@code project → _vance → built-in beans}; built-in beans are
 * supplied by the caller (the source aggregator) via
 * {@link #setBuiltInProvider(BuiltInProvider)} so the service has no
 * direct dependency on the bean source.
 *
 * <p>Write operations target the project layer directly — there is no
 * implicit promotion to {@code _vance}. Tenant-wide defaults are
 * managed by editing documents inside the {@code _vance} project.
 *
 * <p>{@code enabled=false} is treated as a positive cascade hit and
 * <b>stops</b> the lookup. That way a project can disable a system
 * tool by writing a stub document that points to the same name.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ServerToolService {

    private final ServerToolRepository repository;
    private final ToolFactoryRegistry factoryRegistry;

    /** Set by the source aggregator after construction (avoids cycles). */
    private volatile BuiltInProvider builtInProvider = BuiltInProvider.EMPTY;

    /**
     * Pack-materialization cache. Keyed by the document id; entry holds
     * the {@code updatedAt} timestamp it was built from plus the resolved
     * tool collection. A document update bumps {@code updatedAt}, so the
     * cache lookup re-builds. Bounded only by the number of distinct
     * server-tool documents — cleared on JVM restart.
     *
     * <p>Necessary because pack factories (REST API spec parsing, MCP
     * tools/list calls) are too expensive to redo on every cascade
     * read. Singleton factories (doc_lookup) tolerate the indirection
     * without measurable cost.
     */
    private final Map<String, PackCacheEntry> packCache = new ConcurrentHashMap<>();

    public void setBuiltInProvider(BuiltInProvider provider) {
        this.builtInProvider = provider;
    }

    // ──────────────────── Cascade lookup ────────────────────

    /**
     * Resolve a tool by name. Returns the first cascade hit:
     * <ol>
     *   <li>{@code project} (if not {@code _vance} itself)</li>
     *   <li>{@code _vance} system project</li>
     *   <li>built-in bean</li>
     * </ol>
     * A document with {@code enabled=false} stops the cascade and
     * returns empty.
     *
     * <p>Multi-tool packs: when {@code name} contains the {@code __}
     * pack-separator, the pack-doc is resolved by the prefix and the
     * matching sub-tool is returned (or empty if the sub-tool is in
     * the doc's {@code disabledSubTools}). Singleton-pack lookups
     * (name without {@code __}) match the doc's {@code name} directly
     * and pick the single tool the factory produces.
     */
    public Optional<Tool> lookup(String tenantId, String projectId, String name) {
        String packName = packPrefix(name);
        if (!HomeBootstrapService.VANCE_PROJECT_NAME.equals(projectId)) {
            Optional<ServerToolDocument> projectDoc =
                    repository.findByTenantIdAndProjectIdAndName(tenantId, projectId, packName);
            if (projectDoc.isPresent()) {
                return projectDoc.get().isEnabled()
                        ? pickFromPack(projectDoc.get(), name)
                        : Optional.empty();
            }
        }
        Optional<ServerToolDocument> vanceDoc = repository.findByTenantIdAndProjectIdAndName(
                tenantId, HomeBootstrapService.VANCE_PROJECT_NAME, packName);
        if (vanceDoc.isPresent()) {
            return vanceDoc.get().isEnabled()
                    ? pickFromPack(vanceDoc.get(), name)
                    : Optional.empty();
        }
        return builtInProvider.find(name);
    }

    /**
     * All tools visible in the cascade, merged outer-to-inner. Inner
     * layers replace outer ones by {@code name}; disabled documents
     * remove the entry from the result.
     *
     * <p>Multi-tool packs fan out at this layer: a pack-doc with name
     * {@code jira} contributes every sub-tool it materialises (e.g.
     * {@code jira__create_issue}, {@code jira__search_issues}).
     * Disabling the pack ({@code enabled=false}) removes the pack-name
     * itself <i>plus</i> every {@code <pack>__*} entry that an outer
     * cascade layer contributed.
     */
    public List<Tool> listAll(String tenantId, String projectId) {
        Map<String, Tool> merged = new LinkedHashMap<>();
        for (Tool t : builtInProvider.list()) {
            merged.put(t.name(), t);
        }
        applyLayer(merged, tenantId, HomeBootstrapService.VANCE_PROJECT_NAME);
        if (!HomeBootstrapService.VANCE_PROJECT_NAME.equals(projectId)) {
            applyLayer(merged, tenantId, projectId);
        }
        return new ArrayList<>(merged.values());
    }

    /**
     * All tools in the cascade carrying the given label. Used by the
     * recipe resolver to expand {@code @<label>} selectors.
     */
    public List<Tool> findByLabel(String tenantId, String projectId, String label) {
        List<Tool> out = new ArrayList<>();
        for (Tool t : listAll(tenantId, projectId)) {
            if (t.labels().contains(label)) out.add(t);
        }
        return out;
    }

    private void applyLayer(Map<String, Tool> acc, String tenantId, String projectId) {
        for (ServerToolDocument doc :
                repository.findByTenantIdAndProjectId(tenantId, projectId)) {
            if (!doc.isEnabled()) {
                // Pack-disable: drop the pack-name and any <pack>__* sub-tools
                // contributed by an outer layer.
                String prefix = doc.getName() + ToolFactory.PACK_SEPARATOR;
                acc.keySet().removeIf(n -> n.equals(doc.getName()) || n.startsWith(prefix));
                continue;
            }
            // Replace any contribution this name (or its sub-tools) had
            // from outer layers — Pack-Override semantics.
            String prefix = doc.getName() + ToolFactory.PACK_SEPARATOR;
            acc.keySet().removeIf(n -> n.equals(doc.getName()) || n.startsWith(prefix));
            Set<String> disabled = doc.getDisabledSubTools() == null
                    ? Set.of() : doc.getDisabledSubTools();
            for (Tool t : materialize(doc)) {
                String localName = stripPackPrefix(t.name(), doc.getName());
                if (disabled.contains(localName)) continue;
                acc.put(t.name(), t);
            }
        }
    }

    /**
     * Materializes a pack-doc through its {@link ToolFactory}, with
     * a per-document cache keyed by Mongo id and {@code updatedAt}. A
     * doc-edit bumps the timestamp and the next call rebuilds.
     *
     * <p>Documents without an id (in-memory only — typically tests)
     * skip the cache entirely. Production never sees a saved doc with
     * a null id, so this branch is just a safety valve.
     */
    private Collection<Tool> materialize(ServerToolDocument doc) {
        ToolFactory factory = factoryRegistry.find(doc.getType()).orElseThrow(
                () -> new IllegalStateException(
                        "Unknown tool type '" + doc.getType()
                                + "' on server_tool '" + doc.getName()
                                + "' (tenant=" + doc.getTenantId()
                                + ", project=" + doc.getProjectId() + ")"));
        if (doc.getId() == null) {
            return factory.create(doc);
        }
        Instant ts = doc.getUpdatedAt() == null ? Instant.EPOCH : doc.getUpdatedAt();
        PackCacheEntry hit = packCache.get(doc.getId());
        if (hit != null && hit.timestamp().equals(ts)) {
            return hit.tools();
        }
        Collection<Tool> built = factory.create(doc);
        packCache.put(doc.getId(), new PackCacheEntry(ts, List.copyOf(built)));
        return built;
    }

    /**
     * Picks the right sub-tool from a (possibly multi-tool) pack
     * document. Returns empty when the requested name doesn't appear
     * in the pack, or when the matched sub-tool is in the doc's
     * {@code disabledSubTools}.
     */
    private Optional<Tool> pickFromPack(ServerToolDocument doc, String requestedName) {
        String packName = doc.getName();
        Collection<Tool> tools = materialize(doc);
        Set<String> disabled = doc.getDisabledSubTools() == null
                ? Set.of() : doc.getDisabledSubTools();
        for (Tool t : tools) {
            if (!t.name().equals(requestedName)) continue;
            String localName = stripPackPrefix(t.name(), packName);
            if (disabled.contains(localName)) return Optional.empty();
            return Optional.of(t);
        }
        return Optional.empty();
    }

    /**
     * Extracts the pack name from a possibly-suffixed tool name.
     * {@code "jira__create_issue"} → {@code "jira"};
     * {@code "doc_lookup"} → {@code "doc_lookup"} (unchanged).
     */
    private static String packPrefix(String name) {
        int sep = name.indexOf(ToolFactory.PACK_SEPARATOR);
        return sep < 0 ? name : name.substring(0, sep);
    }

    /**
     * Extracts the local sub-tool name. For singleton packs (tool name
     * equals pack name) returns the full name unchanged so the
     * disabledSubTools logic still works on a one-element pack.
     */
    private static String stripPackPrefix(String fullName, String packName) {
        String prefix = packName + ToolFactory.PACK_SEPARATOR;
        if (fullName.startsWith(prefix)) {
            return fullName.substring(prefix.length());
        }
        return fullName;
    }

    /** Cache value: timestamp of materialisation + frozen tool list. */
    private record PackCacheEntry(Instant timestamp, List<Tool> tools) {
    }

    // ──────────────────── CRUD (project layer) ────────────────────

    public ServerToolDocument create(
            String tenantId, String projectId, ServerToolDocument doc) {
        validateDocument(doc);
        if (repository.existsByTenantIdAndProjectIdAndName(
                tenantId, projectId, doc.getName())) {
            throw new IllegalStateException(
                    "Server tool already exists: tenant=" + tenantId
                            + " project=" + projectId + " name=" + doc.getName());
        }
        doc.setTenantId(tenantId);
        doc.setProjectId(projectId);
        return repository.save(doc);
    }

    public ServerToolDocument update(
            String tenantId, String projectId, String name, ServerToolDocument incoming) {
        ServerToolDocument current = repository
                .findByTenantIdAndProjectIdAndName(tenantId, projectId, name)
                .orElseThrow(() -> new IllegalStateException(
                        "Server tool not found: tenant=" + tenantId
                                + " project=" + projectId + " name=" + name));
        incoming.setId(current.getId());
        incoming.setTenantId(tenantId);
        incoming.setProjectId(projectId);
        incoming.setName(name);
        incoming.setVersion(current.getVersion());
        incoming.setCreatedAt(current.getCreatedAt());
        incoming.setUpdatedAt(Instant.now());
        validateDocument(incoming);
        ServerToolDocument saved = repository.save(incoming);
        // Cache key is the doc-id; saving updates updatedAt → next
        // materialize() rebuild. Explicit invalidate not strictly
        // necessary, but cheap insurance for cases where the
        // pre-save current.getUpdatedAt() somehow leaks back.
        invalidatePackCache(saved);
        return saved;
    }

    public void delete(String tenantId, String projectId, String name) {
        repository.findByTenantIdAndProjectIdAndName(tenantId, projectId, name)
                .ifPresent(doc -> {
                    repository.delete(doc);
                    invalidatePackCache(doc);
                });
    }

    private void invalidatePackCache(ServerToolDocument doc) {
        if (doc.getId() != null) {
            packCache.remove(doc.getId());
        }
    }

    public Optional<ServerToolDocument> findDocument(
            String tenantId, String projectId, String name) {
        return repository.findByTenantIdAndProjectIdAndName(tenantId, projectId, name);
    }

    public List<ServerToolDocument> listDocuments(String tenantId, String projectId) {
        return repository.findByTenantIdAndProjectId(tenantId, projectId);
    }

    // ──────────────────── Type registry passthrough ────────────────────

    public List<ToolFactory> listTypes() {
        return factoryRegistry.list();
    }

    public Optional<ToolFactory> findType(String typeId) {
        return factoryRegistry.find(typeId);
    }

    /**
     * Validates type-id and (eagerly) factory.create(). The dry-run
     * surfaces missing required parameters at write time instead of
     * delaying the failure until the first cascade lookup. For
     * pack-factories with expensive setup (REST spec download, MCP
     * connection) the eager call also primes the pack cache.
     */
    private void validateDocument(ServerToolDocument doc) {
        ToolFactory factory = factoryRegistry.find(doc.getType()).orElseThrow(
                () -> new IllegalArgumentException(
                        "Unknown tool type '" + doc.getType()
                                + "' — available: "
                                + factoryRegistry.list().stream()
                                        .map(ToolFactory::typeId).toList()));
        try {
            // Dry-run materialise — surfaces missing required params /
            // bad spec URLs / unreachable MCP servers immediately.
            factory.create(doc);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "Server tool '" + doc.getName() + "' (type='" + doc.getType()
                            + "') failed factory validation: " + e.getMessage(), e);
        }
    }

    /**
     * Built-in bean accessor injected by the source aggregator. Kept
     * as an interface so {@code ServerToolService} stays free of a
     * direct dependency on {@code BuiltInToolSource} (which depends
     * on {@code Tool} bean discovery and would create a cycle).
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
