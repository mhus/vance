package de.mhus.vance.api.wizard;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import de.mhus.vance.api.form.FormFieldDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Full wizard definition returned by {@code GET /brain/{tenant}/wizards/{name}}
 * for Web-UI rendering. Carries the form fields plus localized title /
 * description.
 *
 * <p>The {@code promptTemplate} and {@code validatorPrompt} fields are
 * intentionally <em>not</em> on this DTO — those are Pebble templates
 * that stay backend-only. The Web-UI submits form values to
 * {@code POST /render}, the brain renders, and returns the prompt
 * text via {@link WizardRenderResponseDto}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("wizard")
public class WizardDto {

    private String name;

    /** Localized title, {@code Map<lang, text>}. */
    private Map<String, String> title;

    /** Localized one-line description, {@code Map<lang, text>}. */
    private Map<String, String> description;

    /** Heroicon name (see web-ui spec §7). */
    private @Nullable String icon;

    /** Grouping for the wizard-tab sort (e.g. {@code strategie}, {@code setup}). */
    private @Nullable String category;

    /** Form fields the user fills in before render. */
    @Builder.Default
    private List<FormFieldDto> fields = new ArrayList<>();

    /** {@code PROJECT} | {@code USER} | {@code VANCE} | {@code RESOURCE}. */
    private String source;

    /** Whether this wizard ships an LLM-validator prompt (v2 feature). */
    private boolean hasValidator;
}
