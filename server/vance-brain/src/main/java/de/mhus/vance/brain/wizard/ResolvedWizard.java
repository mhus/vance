package de.mhus.vance.brain.wizard;

import de.mhus.vance.api.form.FormFieldDto;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Parsed in-memory representation of one wizard YAML. Carries the
 * Pebble templates (kept brain-side only) plus all metadata needed
 * to render the summary / full DTOs.
 *
 * <p>Localized fields stay as {@code Map<lang, text>} maps; resolution
 * against the caller's preferred language happens at the controller
 * boundary via
 * {@link de.mhus.vance.shared.form.LocalizedTexts#resolve(Map, String)}.
 */
public record ResolvedWizard(
        String name,
        Map<String, String> title,
        Map<String, String> description,
        @Nullable String icon,
        @Nullable String category,
        List<FormFieldDto> fields,
        String promptTemplate,
        @Nullable String validatorPrompt,
        List<WizardFollowUp> followUps,
        List<String> availableIn,
        WizardSource source) {
}
