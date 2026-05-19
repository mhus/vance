package de.mhus.vance.shared.servertool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Carrier shape for a server-tool's runtime configuration. No longer
 * persisted — server-tool config lives as a {@code DocumentDocument}
 * under {@code server-tools/<name>.yaml} and is parsed into a
 * {@link ServerToolConfig}. The {@code Document} suffix is kept for
 * compatibility with {@link de.mhus.vance.brain.tools.types.ToolFactory#create}
 * callers; semantically this class is just a parameter object.
 *
 * <p>{@code id} stores the underlying {@code DocumentDocument} id so
 * factories with doc-keyed external state (e.g. the MCP connection
 * pool) can use it as a stable cache key.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServerToolDocument {

    private @Nullable String id;

    private String tenantId = "";

    /** Owning project ({@code ProjectDocument.name}); {@code _vance} for system defaults. */
    private String projectId = "";

    /** Stable tool name — the identifier the LLM uses to call it. Snake_case. */
    private String name = "";

    /** Tool-type identifier, resolved against the {@code ToolFactory} registry. */
    private String type = "";

    /** Short human-readable purpose, shown to the LLM. */
    private String description = "";

    /** Type-specific configuration. Shape defined by the factory's schema. */
    @Builder.Default
    private Map<String, Object> parameters = new LinkedHashMap<>();

    /** Selector tags. {@code null} means "no labels" (treated as empty). */
    @Builder.Default
    private @Nullable List<String> labels = new ArrayList<>();

    /** {@code false} hides the tool from the cascade — used to disable a system tool per-project. */
    private boolean enabled = true;

    /** {@code true} advertises the tool to the LLM up-front; {@code false} requires {@code find_tools}. */
    private boolean primary = false;

    /**
     * Sub-tools that are deactivated within this pack. Only meaningful
     * for multi-tool packs (REST API, MCP, plugin bundles). Each entry
     * matches the sub-tool's <i>local</i> name (the part after the
     * {@code <pack>__} prefix). Empty / null → all sub-tools active.
     */
    @Builder.Default
    private @Nullable Set<String> disabledSubTools = new LinkedHashSet<>();

    /**
     * Pack-level default for {@link de.mhus.vance.toolpack.Tool#deferred()}.
     */
    private boolean defaultDeferred = false;

    /**
     * Free-form prompt fragment that engines inject into the system
     * message <b>only when this pack is reachable in the current turn</b>
     * (i.e. it shows up in {@code dispatcher.resolveAll}). Use this to
     * explain pack-specific calling conventions, sub-tool naming, or
     * "do-not-set-this-arg" rules that are not obvious from the
     * sub-tool descriptions — e.g. "cloudId is auto-injected, call
     * {@code find_tools(query='jira')} to enumerate".
     *
     * <p>Empty (the default) means "no extra hint". Multi-line markdown
     * is fine — the engine just concatenates active hints under a
     * "Tool usage notes" section.
     */
    @Builder.Default
    private String promptHint = "";

    /** Username of the creator ({@code UserDocument.name}); {@code null} for bootstrap-created defaults. */
    private @Nullable String createdBy;
}
