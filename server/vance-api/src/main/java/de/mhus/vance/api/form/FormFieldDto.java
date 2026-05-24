package de.mhus.vance.api.form;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Universal form-field descriptor — the wire contract for one input
 * element in a wizard or tool-template form. Carries every metadata
 * the Web-UI {@code FormFields.vue} renderer needs to draw the field
 * and validate submissions.
 *
 * <p>{@link #type} is one of:
 * <ul>
 *   <li>{@code string} — single-line text input</li>
 *   <li>{@code textarea} — multi-line text (see {@link #rows})</li>
 *   <li>{@code password} — masked input</li>
 *   <li>{@code integer} — whole number (see {@link #integerMin}/{@link #integerMax})</li>
 *   <li>{@code boolean} — checkbox</li>
 *   <li>{@code select} — single-choice dropdown (see {@link #choices})</li>
 *   <li>{@code multi_select} — multi-choice checkbox list (see {@link #choices})</li>
 *   <li>{@code repeat} — array of nested fields (see {@link #min}/{@link #max}/{@link #item})</li>
 * </ul>
 *
 * <p>Localized fields ({@link #label}, {@link #help}) are
 * {@code Map<lang, text>}. The brain renders the prompt-template
 * server-side; resolution against the tenant's default language
 * happens before the DTO leaves the wire when the consumer is the
 * Web-UI (so the UI sees pre-resolved strings).
 *
 * <p>{@code @JsonInclude(NON_NULL)} keeps the JSON terse — fields
 * irrelevant to the field-type are simply omitted.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("form")
public class FormFieldDto {

    /** Variable name — referenced from the {@code promptTemplate} as {@code {{ name }}}. */
    private String name;

    /** Field kind. See class Javadoc for the catalog. */
    private String type;

    /** Localized form label, {@code Map<lang, text>}. */
    private Map<String, String> label;

    /** Localized help text shown below the field. */
    private @Nullable Map<String, String> help;

    /** Whether the input must be supplied. */
    private boolean required;

    /**
     * Pre-fill value. Encoded as a string for primitive types
     * (matches the {@code modelValue} convention on the Web-UI side):
     * boolean → {@code "true"}/{@code "false"}, integer → numeric
     * string, select → the chosen {@code value}, multi_select →
     * JSON-array string. {@code null} when no default is set.
     */
    private @Nullable String defaultValue;

    /** Allowed values for {@code select} / {@code multi_select}. Empty for other types. */
    @Builder.Default
    private List<FormChoiceDto> choices = new ArrayList<>();

    /** UI-hint for {@code textarea}: number of visible rows. {@code null} = renderer default. */
    private @Nullable Integer rows;

    /** Lower bound for {@code integer} values (inclusive). {@code null} = unbounded. */
    private @Nullable Integer integerMin;

    /** Upper bound for {@code integer} values (inclusive). {@code null} = unbounded. */
    private @Nullable Integer integerMax;

    /** Minimum entries for {@code repeat}. {@code null} = unbounded (incl. zero). */
    private @Nullable Integer min;

    /** Maximum entries for {@code repeat}. {@code null} = unbounded. */
    private @Nullable Integer max;

    /** Nested-field schema for {@code repeat}. {@code null} for non-repeat types. */
    private @Nullable List<FormFieldDto> item;
}
