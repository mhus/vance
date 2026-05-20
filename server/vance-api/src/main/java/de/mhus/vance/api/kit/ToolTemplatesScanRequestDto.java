package de.mhus.vance.api.kit;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Request body for the tool-templates catalog scan endpoint
 * ({@code POST /brain/{tenant}/admin/tool-templates/scan}): clone a git
 * repo, scan its {@code tools/} subdir for {@code template.yaml}
 * siblings, and return a fresh {@link ToolTemplateCatalogDto} without
 * persisting anything.
 *
 * <p>{@code gitUrl} is required; {@code ref} defaults to {@code main}
 * when blank. {@code token} carries an optional credential for private
 * repos — never persisted.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("kit")
public class ToolTemplatesScanRequestDto {

    private String gitUrl;

    private @Nullable String ref;

    private @Nullable String token;
}
