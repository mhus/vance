package de.mhus.vance.brain.servertool;

import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.types.ToolFactory;
import de.mhus.vance.brain.tools.types.ToolFactoryRegistry;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.servertool.ServerToolDocument;
import de.mhus.vance.shared.servertool.ServerToolRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
     */
    public Optional<Tool> lookup(String tenantId, String projectId, String name) {
        if (!HomeBootstrapService.VANCE_PROJECT_NAME.equals(projectId)) {
            Optional<ServerToolDocument> projectDoc =
                    repository.findByTenantIdAndProjectIdAndName(tenantId, projectId, name);
            if (projectDoc.isPresent()) {
                return projectDoc.get().isEnabled()
                        ? Optional.of(materialize(projectDoc.get()))
                        : Optional.empty();
            }
        }
        Optional<ServerToolDocument> vanceDoc = repository.findByTenantIdAndProjectIdAndName(
                tenantId, HomeBootstrapService.VANCE_PROJECT_NAME, name);
        if (vanceDoc.isPresent()) {
            return vanceDoc.get().isEnabled()
                    ? Optional.of(materialize(vanceDoc.get()))
                    : Optional.empty();
        }
        return builtInProvider.find(name);
    }

    /**
     * All tools visible in the cascade, merged outer-to-inner. Inner
     * layers replace outer ones by {@code name}; disabled documents
     * remove the entry from the result.
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
                acc.remove(doc.getName());
                continue;
            }
            acc.put(doc.getName(), materialize(doc));
        }
    }

    private Tool materialize(ServerToolDocument doc) {
        ToolFactory factory = factoryRegistry.find(doc.getType()).orElseThrow(
                () -> new IllegalStateException(
                        "Unknown tool type '" + doc.getType()
                                + "' on server_tool '" + doc.getName()
                                + "' (tenant=" + doc.getTenantId()
                                + ", project=" + doc.getProjectId() + ")"));
        return factory.create(doc);
    }

    // ──────────────────── CRUD (project layer) ────────────────────

    public ServerToolDocument create(
            String tenantId, String projectId, ServerToolDocument doc) {
        validateType(doc);
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
        validateType(incoming);
        return repository.save(incoming);
    }

    public void delete(String tenantId, String projectId, String name) {
        repository.findByTenantIdAndProjectIdAndName(tenantId, projectId, name)
                .ifPresent(repository::delete);
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

    private void validateType(ServerToolDocument doc) {
        if (factoryRegistry.find(doc.getType()).isEmpty()) {
            throw new IllegalArgumentException(
                    "Unknown tool type '" + doc.getType()
                            + "' — available: "
                            + factoryRegistry.list().stream().map(ToolFactory::typeId).toList());
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
