package de.mhus.vance.shared.servertool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Parsed server-tool configuration — the runtime view of a
 * {@code server-tools/<name>.yaml} document. Used by {@code
 * ServerToolLoader} and the registry, and adapted to the
 * historical {@link ServerToolDocument} parameter shape via
 * {@link #toTransientDocument(String, String)} so {@code ToolFactory}
 * implementations don't need to change in this refactor.
 *
 * <p>{@code source} records which cascade tier produced the entry
 * (project, _vance, or classpath resource). {@code documentId} carries
 * the owning {@code DocumentDocument} id — useful as a stable cache
 * key for pack-materialization and for the admin REST surface to round-
 * trip without re-reading.
 */
public record ServerToolConfig(
        String name,
        String type,
        String description,
        Map<String, Object> parameters,
        List<String> labels,
        boolean enabled,
        boolean primary,
        Set<String> disabledSubTools,
        boolean defaultDeferred,
        String promptHint,
        Source source,
        @Nullable String documentId,
        @Nullable String createdBy,
        String yaml) {

    public ServerToolConfig {
        if (promptHint == null) promptHint = "";
    }

    /** Cascade tier that produced this config. */
    public enum Source {
        /** Innermost — overrides {@link #VANCE} and {@link #RESOURCE}. */
        PROJECT,
        /** Tenant-wide system project; overrides {@link #RESOURCE}. */
        VANCE,
        /** Classpath default shipped under {@code vance-defaults/server-tools/}. */
        RESOURCE
    }

    /**
     * Build a transient {@link ServerToolDocument} carrying this config —
     * the historical parameter shape consumed by {@code ToolFactory#create}.
     * The returned document has no Mongo {@code id}; {@code documentId}
     * (the owning {@code DocumentDocument} id) is stamped into
     * {@link ServerToolDocument#getId()} so callers can use it as a stable
     * pack-cache key.
     */
    public ServerToolDocument toTransientDocument(String tenantId, String projectId) {
        ServerToolDocument doc = new ServerToolDocument();
        doc.setId(documentId);
        doc.setTenantId(tenantId);
        doc.setProjectId(projectId);
        doc.setName(name);
        doc.setType(type);
        doc.setDescription(description);
        doc.setParameters(new LinkedHashMap<>(parameters));
        doc.setLabels(new ArrayList<>(labels));
        doc.setEnabled(enabled);
        doc.setPrimary(primary);
        doc.setDisabledSubTools(new LinkedHashSet<>(disabledSubTools));
        doc.setDefaultDeferred(defaultDeferred);
        doc.setPromptHint(promptHint);
        doc.setCreatedBy(createdBy);
        return doc;
    }
}
