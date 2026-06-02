package de.mhus.vance.api.servertools;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
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
 * Read view of a configured server tool. Carries the persisted fields
 * plus the owning project so the UI can render breadcrumbs / cascade
 * hints. Bundled bean tools are <b>not</b> represented here — they
 * live in code and are not editable through this endpoint.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("server-tools")
public class ServerToolDto {

    private String name;

    private String type;

    private String description;

    @Builder.Default
    private Map<String, Object> parameters = new LinkedHashMap<>();

    @Builder.Default
    private List<String> labels = new ArrayList<>();

    private boolean enabled;

    private boolean primary;

    /**
     * Sub-tools that are deactivated within this pack. Only meaningful
     * for multi-tool packs (REST API, MCP, plugin bundles). Each entry
     * matches the sub-tool's local name (without the {@code <pack>__}
     * prefix). Empty / null → all sub-tools active.
     */
    @Builder.Default
    private Set<String> disabledSubTools = new LinkedHashSet<>();

    /**
     * Pack-level default for {@code Tool.deferred()}. Multi-tool packs
     * with many sub-tools (e.g. a 50-endpoint REST pack) should default
     * to {@code true} so the LLM doesn't get flooded with schemas;
     * sub-tools surface only via the discovery block until activated by
     * {@code describe_tool}. Singleton packs (e.g. {@code doc_lookup})
     * ignore this — the factory's classification wins.
     */
    private boolean defaultDeferred;

    /**
     * Pack-level prompt fragment injected into the engine's system
     * message when this pack is reachable for the turn. Empty string
     * (the default) means "no extra hint".
     */
    private String promptHint = "";

    /** Owning project — {@code _tenant} for system-wide tools. */
    private String projectId;

    /**
     * Cascade tier that produced this entry — informs the admin UI
     * whether editing it would create a project-specific override.
     * One of:
     * <ul>
     *   <li>{@code PROJECT} — defined in the requesting project,</li>
     *   <li>{@code TENANT}  — cascaded from the tenant-default project ({@code _tenant}),</li>
     *   <li>{@code BUNDLED} — bundled classpath default ({@code vance-defaults/_vance/server-tools/}).</li>
     * </ul>
     */
    private @Nullable String source;

    /** Last update timestamp in millis since epoch; {@code null} for unsaved drafts. */
    private @Nullable Long updatedAtTimestamp;

    /** Creator user login, if recorded. */
    private @Nullable String createdBy;
}
