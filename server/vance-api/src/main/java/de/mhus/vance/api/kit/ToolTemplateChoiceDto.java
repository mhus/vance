package de.mhus.vance.api.kit;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One option in a {@link ToolTemplateInputDto#getChoices()} list. Used by
 * both {@code SELECT} (Web-UI picks {@link #value} as the selected
 * scalar) and {@code MULTI_SELECT} (Web-UI uses {@link #defaultSelected}
 * to seed the checkbox state; submits an array of {@link #value}s).
 *
 * <p>YAML schema:
 * <pre>
 *   choices:
 *     - value: jira
 *       label: Jira
 *       default: true
 *     - value: confluence
 *       label: Confluence
 * </pre>
 *
 * <p>v1 short-form ({@code choices: [jira, confluence]}) is still
 * accepted by the brain parser and gets coerced to
 * {@code {value: <s>, label: null, default: false}}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("kit")
public class ToolTemplateChoiceDto {

    /** Technical key used in substitution and {@code derived.perChoice}. */
    private String value;

    /** Human-readable label. Falls back to {@link #value} when null. */
    private @Nullable String label;

    /**
     * Pre-selected by default. For {@code SELECT} this is informational
     * — the input's {@code defaultValue} carries the actual default.
     */
    private boolean defaultSelected;
}
