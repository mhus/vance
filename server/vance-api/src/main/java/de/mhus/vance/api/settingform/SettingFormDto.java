package de.mhus.vance.api.settingform;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import de.mhus.vance.api.form.FormFieldDto;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Full Setting Form definition returned by
 * {@code GET /brain/{tenant}/setting-forms/{name}}. Carries the form
 * fields (with live cascade values populated for each direct-mapped
 * field) plus the computed-settings summary so the UI can show
 * "this form may also touch keys X, Y, Z".
 *
 * <p>Localized {@code title} / {@code description} arrive
 * pre-resolved against the caller's preferred language — the UI sees
 * plain strings.
 *
 * <p>Pebble templates ({@code value}, {@code showIf}, {@code writeIf})
 * are <b>not</b> serialised on this DTO. They stay backend-only; the
 * UI submits raw form values via {@code POST /apply} and gets back
 * the executed plan.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("settingform")
public class SettingFormDto {

    private String name;

    private String title;

    private String description;

    private @Nullable String icon;

    private @Nullable String category;

    /** {@code project} (default) | {@code user} | {@code tenant}. */
    private String defaultScope;

    /**
     * Form fields with bindings. For direct-mapped fields the brain
     * fills {@code currentValue}/{@code currentSource} from the live
     * cascade before responding — PASSWORD values are returned as
     * {@code "***"} or {@code "[set]"}, never plaintext.
     */
    @Builder.Default
    private List<FormFieldDto> fields = new ArrayList<>();

    /**
     * Computed-setting outputs (metadata only — the Pebble templates
     * are not exposed). The UI uses this to show users which extra
     * keys will be touched on apply.
     */
    @Builder.Default
    private List<ComputedSettingDto> settings = new ArrayList<>();

    /** {@code PROJECT} | {@code USER} | {@code VANCE} | {@code RESOURCE}. */
    private String source;

    /** Whether the form supports the Reset action (see spec §6.3). */
    private boolean clearable;
}
