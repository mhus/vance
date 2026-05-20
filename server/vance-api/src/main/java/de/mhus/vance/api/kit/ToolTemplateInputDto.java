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
 * One input field from a tool-template — mirrored 1:1 from the brain's
 * {@code TemplateInput} record. Surfaced by the {@code describe}
 * endpoint so the Web-UI wizard can render a form and the chat agent
 * can prompt the user field-by-field.
 *
 * <p>{@link #target} is intentionally a string — the Web-UI only cares
 * whether the value is a runtime-known setting (for "we'll store this
 * encrypted" copy) or document-inline (for the "shown in the kit
 * docs" copy). The full target metadata stays server-side.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("kit")
public class ToolTemplateInputDto {

    /** Variable name — referenced as {@code &#123;&#123;var:<name>&#125;&#125;}. */
    private String name;

    /** Field kind: {@code string | password | boolean | integer | select}. */
    private String type;

    /** Form label. */
    private String label;

    /** Optional help text shown below / next to the field. */
    private @Nullable String help;

    /** Whether the input must be supplied. */
    private boolean required;

    /** Pre-fill value when the caller doesn't pass one. */
    private @Nullable String defaultValue;

    /**
     * Allowed values for {@code SELECT} and {@code MULTI_SELECT}. Empty
     * for other types. Each entry carries {@code value}, optional
     * {@code label}, and a {@code default} flag (used by multi-select to
     * seed the checkbox state).
     */
    @Builder.Default
    private List<ToolTemplateChoiceDto> choices = new ArrayList<>();

    /**
     * Target persistence kind: {@code document-inline} or {@code setting}.
     * Determines whether the value ends up in a kit document or in
     * SettingService.
     */
    private String target;
}
