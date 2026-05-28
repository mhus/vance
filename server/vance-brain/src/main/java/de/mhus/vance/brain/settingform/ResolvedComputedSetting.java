package de.mhus.vance.brain.settingform;

import de.mhus.vance.api.settings.SettingType;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Parsed in-memory representation of one entry in a Setting Form's
 * {@code settings:} section. Carries the Pebble templates (kept
 * brain-side) plus the target descriptor.
 *
 * <p>{@link #scope} is {@code null} when the YAML entry omitted it —
 * the form's {@code defaultScope} kicks in at plan-build time.
 *
 * <p>{@link #writeIfExpression} is a Pebble expression body (the
 * brain wraps it as {@code {% if ... %}1{% endif %}} when evaluating).
 * {@code null} = always write.
 */
public record ResolvedComputedSetting(
        String key,
        @Nullable String scope,
        SettingType settingType,
        String valueTemplate,
        @Nullable String writeIfExpression,
        @Nullable Map<String, String> description) {
}
