package de.mhus.vance.api.kit;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One entry in a tenant's tool-templates catalog
 * ({@code _tenant/config/tool-templates.yaml}). Maps a catalog-local
 * {@code name} (the lookup key shown in the Web-UI wizard and the
 * chat-agent's {@code tool_template_list}) to a git source pointing at
 * a kit that ships a {@code template.yaml}.
 *
 * <p>Separate file from {@code project-kits.yaml} on purpose — project
 * kits initialise a whole project; tool templates are additive
 * configurations slotted into existing projects. See
 * {@code planning/tool-templates.md}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("kit")
public class ToolTemplateCatalogEntry {

    /** Lookup key — unique within the file. */
    private String name;

    /** Display title for UI and CLI. Falls back to {@link #name} when blank. */
    private @Nullable String title;

    /** One-line description, used as subtitle. */
    private @Nullable String description;

    /**
     * Free-form category for grouping in the Web-UI wizard ({@code communication},
     * {@code developer-tools}, …). No semantics beyond display order.
     */
    private @Nullable String category;

    /**
     * Where the kit lives in git. Same shape as
     * {@code project-kits.yaml} so the apply flow can hand this
     * straight to {@code KitResolver}.
     */
    private KitInheritDto source;
}
