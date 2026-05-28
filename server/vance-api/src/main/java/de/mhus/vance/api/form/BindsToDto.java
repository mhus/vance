package de.mhus.vance.api.form;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Direct-mapping descriptor on a {@link FormFieldDto}. Specifies the
 * setting that the field reads/writes 1:1 (see
 * specification/setting-forms.md §4).
 *
 * <p>{@link #scope} is one of {@code project} / {@code user} /
 * {@code tenant}. When {@code null}, the enclosing form's
 * {@code defaultScope} is used (and that, in turn, falls back to
 * {@code project} when not set).
 *
 * <p>{@link #settingType} lets a field force a specific persisted
 * type when the field-type alone is ambiguous — e.g. {@code integer}
 * fields default to {@code INT} but can request {@code LONG} for
 * values that may exceed 32-bit. {@code null} = derive from the
 * field type.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("form")
public class BindsToDto {

    /** Setting key (dot notation, see settings-system §5). */
    private String key;

    /** {@code project} | {@code user} | {@code tenant}. {@code null} = inherit. */
    private @Nullable String scope;

    /** Persisted type override. {@code null} = derive from field type. */
    private @Nullable String settingType;
}
