package de.mhus.vance.api.kit;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body for {@code POST /brain/{tenant}/admin/tool-templates/{name}/apply}.
 *
 * <p>{@code inputs} maps every required (and any optional) template
 * input by name to the user-supplied value. PASSWORD inputs must be
 * present here in plaintext — the server encrypts before persistence.
 * Don't log this request body.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("kit")
public class ToolTemplateApplyRequestDto {

    /** Target project to apply into. */
    @NotBlank
    private String projectId;

    /** Field-name → value. PASSWORD inputs in plaintext. */
    @Builder.Default
    private Map<String, String> inputs = new LinkedHashMap<>();

    /** Optional auth token if the kit's git source needs one. */
    private @Nullable String token;
}
