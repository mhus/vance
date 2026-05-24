package de.mhus.vance.brain.wizard;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One declarative follow-up suggestion attached to a wizard. The brain
 * appends each follow-up as a markdown link to the rendered prompt
 * (in the resolved language), so the engine can offer it to the user
 * after the main task succeeds.
 *
 * <p>Wire form: {@code [<label>](vance:/wizards/<wizard>?kind=wizard&<prefill>)}.
 * The Web-UI's {@code MarkdownView} intercepts the click, switches the
 * chat side panel to the wizards tab, and opens the named wizard with
 * the prefill values seeded into the form.
 *
 * @param wizard    target wizard name (matches a YAML basename in the cascade)
 * @param label     localized link label, {@code Map<lang, text>}
 * @param prefill   pre-rendered form values to seed (Pebble-templated against
 *                  the source wizard's values)
 * @param condition optional Pebble condition; when present and falsy, the
 *                  suggestion is omitted from the rendered prompt
 */
public record WizardFollowUp(
        String wizard,
        Map<String, String> label,
        Map<String, String> prefill,
        @Nullable String condition) {
}
