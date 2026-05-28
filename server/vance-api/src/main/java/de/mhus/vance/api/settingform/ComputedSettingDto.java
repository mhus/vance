package de.mhus.vance.api.settingform;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One entry in the {@code settings:} section of a Setting Form. Each
 * computed setting carries a target key + scope and a Pebble template
 * that renders the value from the form-field context.
 *
 * <p>Pebble templates are <b>not</b> serialised on the wire: only
 * {@link #key} / {@link #scope} / {@link #settingType} reach the
 * Web-UI (so the UI can show "this form would also write key X").
 * The template itself stays brain-side.
 *
 * <p>{@link #writeIf} also stays backend-only; the wire just flags
 * whether one is present, via {@link #conditional}.
 *
 * <p>See {@code specification/setting-forms.md §5.2}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("settingform")
public class ComputedSettingDto {

    /** Target setting key (dot notation). */
    private String key;

    /** {@code project} | {@code user} | {@code tenant}. {@code null} = inherit from form's {@code defaultScope}. */
    private @Nullable String scope;

    /**
     * Persisted {@link de.mhus.vance.api.settings.SettingType} name —
     * {@code STRING} / {@code INT} / {@code LONG} / {@code DOUBLE} /
     * {@code BOOLEAN} / {@code PASSWORD}. Default {@code STRING}.
     */
    private @Nullable String settingType;

    /**
     * Whether this entry carries a {@code writeIf} guard — the brain
     * exposes the presence flag (not the expression itself) so the UI
     * can hint "may be cleared depending on form values".
     */
    private boolean conditional;
}
