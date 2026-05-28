package de.mhus.vance.brain.settingform;

import de.mhus.vance.api.form.FormFieldDto;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Parsed in-memory representation of one Setting Form YAML. Carries
 * the fields (with Pebble {@code showIf}/{@code writeIf} expressions
 * still embedded — backend-only) plus the computed-settings list.
 *
 * <p>Localized maps ({@code title}, {@code description}) stay as
 * {@code Map<lang, text>}; resolution happens at the controller
 * boundary via
 * {@link de.mhus.vance.shared.form.LocalizedTexts#resolve(Map, String)}.
 *
 * <p>{@link #defaultScope} is one of {@code project}, {@code user},
 * {@code tenant}; the loader enforces this. Per-binding overrides on
 * individual fields or in the computed-settings entries take
 * precedence; see {@link SettingFormPlanBuilder}.
 */
public record ResolvedSettingForm(
        String name,
        Map<String, String> title,
        Map<String, String> description,
        @Nullable String icon,
        @Nullable String category,
        String defaultScope,
        List<FormFieldDto> fields,
        List<ResolvedComputedSetting> computedSettings,
        boolean clearable,
        List<String> availableIn,
        SettingFormSource source) {
}
