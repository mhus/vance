package de.mhus.vance.api.kit;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Resolved view of a tool-template — returned by
 * {@code GET /brain/{tenant}/admin/tool-templates/{name}}. Backed by
 * the actual {@code template.yaml} parsed from the kit's git source.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("kit")
public class ToolTemplateDescriptorDto {

    /** Catalog name (matches the catalog entry). */
    private String name;

    /** Display title. Falls back to {@link #name}. */
    private String title;

    /** Long description. */
    private @Nullable String description;

    /** Web-UI icon hint. */
    private @Nullable String icon;

    /** Input schema for form rendering / agent prompting. */
    @Builder.Default
    private List<ToolTemplateInputDto> inputs = new ArrayList<>();

    /** Post-install hook (e.g. "go to Connected Accounts and click Connect"). */
    private @Nullable ToolTemplatePostInstallDto postInstall;
}
