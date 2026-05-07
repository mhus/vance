package de.mhus.vance.api.servertools;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
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

/**
 * Write payload for creating or updating a server tool. The {@code name}
 * is part of the URL; everything else lives in the body. Server-side
 * validation ensures {@code type} matches a registered
 * {@code ToolFactory}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("server-tools")
public class ServerToolWriteRequest {

    @NotBlank
    private String type;

    @NotBlank
    private String description;

    @Builder.Default
    private Map<String, Object> parameters = new LinkedHashMap<>();

    @Builder.Default
    private List<String> labels = new ArrayList<>();

    private boolean enabled;

    private boolean primary;

    /**
     * Sub-tools to deactivate within this pack. Local names only (without
     * the {@code <pack>__} prefix). Empty / null → all sub-tools active.
     * Singleton packs (e.g. {@code doc_lookup}) ignore this.
     */
    @Builder.Default
    private Set<String> disabledSubTools = new LinkedHashSet<>();

    /**
     * Pack-level default for {@code Tool.deferred()}. Multi-tool packs
     * with many sub-tools should set this to {@code true} so sub-tools
     * surface via the discovery block instead of the tool manifest.
     * Singleton packs ignore this — the factory's classification wins.
     */
    private boolean defaultDeferred;
}
