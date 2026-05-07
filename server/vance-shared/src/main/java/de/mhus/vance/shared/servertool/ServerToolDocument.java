package de.mhus.vance.shared.servertool;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Persistent server-tool record. Scoped to a tenant + project; addressed
 * by {@code name} inside the project. The {@code _vance} system project
 * carries tenant-wide defaults; user projects can shadow them.
 *
 * <p>{@code type} identifies which {@code ToolFactory} bean expands the
 * document into a runnable {@code Tool}. {@code parameters} is the
 * type-specific configuration; its shape is defined by the factory's
 * {@code parametersSchema()}.
 *
 * <p>{@code primary} maps to the runtime {@code Tool#primary()} flag and
 * is <b>not</b> inherited from a shadowed cascade layer — every document
 * states it explicitly.
 *
 * <p>{@code labels} is the second selector axis next to {@code name};
 * recipes can reference tools via {@code @<label>}. Labels are
 * <b>replaced</b> on cascade overrides (no merging across layers).
 */
@Document(collection = "server_tools")
@CompoundIndexes({
        @CompoundIndex(
                name = "tenant_project_name_idx",
                def = "{ 'tenantId': 1, 'projectId': 1, 'name': 1 }",
                unique = true),
        @CompoundIndex(
                name = "tenant_project_enabled_idx",
                def = "{ 'tenantId': 1, 'projectId': 1, 'enabled': 1 }")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServerToolDocument {

    @Id
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
     *
     * <p>Used to deactivate single endpoints of a JIRA pack (or
     * filesystem-MCP read-but-not-write) without duplicating the whole
     * pack at the project layer. See
     * {@code planning/server-tool-providers.md} §3.3.
     */
    @Builder.Default
    private @Nullable Set<String> disabledSubTools = new LinkedHashSet<>();

    /**
     * Pack-level default for {@link de.mhus.vance.toolpack.Tool#deferred()}.
     * Pack factories with many sub-tools (e.g. a 50-endpoint REST pack)
     * should default to {@code true} so the LLM doesn't get flooded with
     * schemas — sub-tools surface only via the discovery block until
     * activated by {@code describe_tool}. Per-sub-tool override is up to
     * the factory (e.g. {@code parameters.deferredOverrides[name] = false}).
     *
     * <p>Singleton-pack types (doc_lookup) ignore this — they always
     * produce one tool whose {@code Tool.deferred()} comes from the
     * factory's classification.
     */
    private boolean defaultDeferred = false;

    @Version
    private @Nullable Long version;

    @CreatedDate
    private @Nullable Instant createdAt;

    @LastModifiedDate
    private @Nullable Instant updatedAt;

    /** Username of the creator ({@code UserDocument.name}); {@code null} for bootstrap-created defaults. */
    private @Nullable String createdBy;
}
