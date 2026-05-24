package de.mhus.vance.api.form;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One option in a {@link FormFieldDto#getChoices()} list. Used by
 * {@code SELECT} (single-pick scalar) and {@code MULTI_SELECT}
 * (checkbox group, seeded by {@link #defaultSelected}).
 *
 * <p>YAML schema:
 * <pre>
 *   choices:
 *     - value: formal
 *       label: { de: "Formell", en: "Formal" }
 *       default: true
 *     - value: casual
 *       label: { de: "Locker", en: "Casual" }
 * </pre>
 *
 * <p>Short-form ({@code choices: [formal, casual]}) is coerced
 * by the brain-side parser to {@code {value: <s>, label: null,
 * default: false}}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("form")
public class FormChoiceDto {

    /** Technical key — what gets submitted and substituted. */
    private String value;

    /** Localized label, language-code → text. Falls back to {@link #value} when null/empty. */
    private @Nullable Map<String, String> label;

    /**
     * Pre-selected by default. For {@code SELECT} this is informational
     * — the input's {@code defaultValue} carries the actual default.
     */
    private boolean defaultSelected;
}
