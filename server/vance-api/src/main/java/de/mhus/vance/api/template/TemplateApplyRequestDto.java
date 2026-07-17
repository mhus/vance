package de.mhus.vance.api.template;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotNull;
import java.util.HashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Body for {@code POST /brain/{tenant}/templates/{name}/apply}. Carries
 * the target {@link #folder}, the chosen {@link #name} (ignored for
 * {@code nameMode=fixed} templates) and the user-filled form values.
 *
 * <p>Value-type convention mirrors
 * {@link de.mhus.vance.api.wizard.WizardRenderRequestDto}: scalar fields
 * as {@code String}, multi-selects as {@code List<String>}, repeats as
 * {@code List<Map<String,Object>>}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("template")
public class TemplateApplyRequestDto {

    /** Target directory (project root = empty string). Trailing slash tolerated. */
    @NotNull
    private String folder;

    /**
     * Chosen filename stem for {@code nameMode=free} templates. The body
     * extension is appended when missing. Ignored for {@code fixed}
     * templates (the definition's {@code name.value} wins).
     */
    private @Nullable String name;

    @Builder.Default
    private Map<String, Object> values = new HashMap<>();

    private @Nullable String lang;
}
