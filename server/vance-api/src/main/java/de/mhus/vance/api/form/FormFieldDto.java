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
@Builder(toBuilder = true)
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

    /**
     * Marker for dynamic choices populated by the brain at response
     * time. Mutually exclusive with {@link #choices} — when both are
     * declared, the loader rejects the form.
     *
     * <p>Known sources:
     * <ul>
     *   <li>{@code ai-models} — every {@code (provider, modelName)} pair
     *       visible in the cascade-merged {@code ai-models.yaml} as a
     *       {@code value: "<provider>:<modelName>"} choice. Used by the
     *       LLM-alias form to keep model lists in sync with the catalogue
     *       (see {@code setting_forms/llm-setup.yaml}).</li>
     * </ul>
     *
     * <p>Backend-only on inbound. On {@code GET /{name}} responses the
     * brain has resolved the marker into a populated {@link #choices}
     * list, so the UI never has to know about dynamic sources.
     */
    private @Nullable String choicesFrom;

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

    // ──────────────── Setting-Form extensions ────────────────
    // Optional fields used only by Setting Forms (see specification/setting-forms.md).
    // Wizards and kit-tool-templates leave them null; with @JsonInclude(NON_NULL)
    // they are omitted from the wire there.

    /**
     * Direct-mapping target: when set, the field's submitted value is
     * written 1:1 to the named setting key. {@code null} means the
     * field is UI-only (its value is still visible in Pebble contexts
     * for {@code showIf}/{@code writeIf}/{@code settings:} but no
     * setting is implicitly written from it).
     */
    private @Nullable BindsToDto bindsTo;

    /**
     * Pebble expression evaluated against the form values at apply
     * time. When falsy, the field is hidden in the UI and treated as
     * absent for required/bindsTo enforcement (its value remains in
     * the Pebble context though). {@code null} = always visible.
     */
    private @Nullable String showIf;

    /**
     * Pebble expression evaluated against the form values at apply
     * time. When falsy, the field's {@code bindsTo} target (and any
     * settings derived from this field) is <em>deleted</em> instead of
     * written — that is how Setting Forms do conditional reset.
     * {@code null} = always written.
     */
    private @Nullable String writeIf;

    /**
     * Live cascade view for direct-mapped fields, populated by the
     * brain when responding to {@code GET /setting-forms/{name}}. Tells
     * the UI what the current effective value of {@code bindsTo.key}
     * is and from which scope it comes. Always {@code null} on
     * inbound requests; ignored if the field has no {@code bindsTo}.
     *
     * <p>For PASSWORD-typed fields, {@code currentValue} is always
     * {@code "***"} or {@code "[set]"} — never plaintext.
     */
    private @Nullable String currentValue;

    /**
     * Cascade-source label paired with {@link #currentValue}: which
     * scope provided the live value. Examples: {@code "project"},
     * {@code "_tenant"}, {@code "_user_mike"}, or {@code null} when
     * the key is not set anywhere.
     */
    private @Nullable String currentSource;
}
