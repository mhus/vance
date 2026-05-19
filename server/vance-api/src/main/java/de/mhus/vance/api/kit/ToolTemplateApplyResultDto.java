package de.mhus.vance.api.kit;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Result of a tool-template apply. Wraps the underlying
 * {@link KitOperationResultDto} (so callers can show "X documents
 * added, Y settings updated") plus the template-level metadata
 * (template name + post-install hook) the Web-UI / agent uses to
 * drive the next step.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("kit")
public class ToolTemplateApplyResultDto {

    /** The template that was applied. */
    private String templateName;

    /** The installer's per-document / per-setting outcome. */
    private KitOperationResultDto installer;

    /** Follow-up hook for the caller to surface ({@code null} = nothing to do). */
    private @Nullable ToolTemplatePostInstallDto postInstall;
}
