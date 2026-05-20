package de.mhus.vance.brain.kit;

import org.jspecify.annotations.Nullable;

/**
 * One entry in the {@code choices:} list of a
 * {@link TemplateInputType#SELECT} or {@link TemplateInputType#MULTI_SELECT}
 * input.
 *
 * <p>For backward compatibility with v1 templates whose {@code choices:}
 * was a flat YAML string array ({@code [a, b, c]}), the YAML parser will
 * coerce each string into {@code new TemplateChoice(string, null, false)}.
 * Multi-select templates must use the richer map form
 * ({@code [{value: jira, label: Jira, default: true}, …]}) because
 * per-choice defaults are necessary for the form pre-fill.
 *
 * @param value             technical key used in {@code {{var:…}}} substitution
 *                          and {@code derived.perChoice}; must be unique within
 *                          the input's choices
 * @param label             human-readable label for the Web-UI; falls back to
 *                          {@code value} when omitted
 * @param defaultSelected   whether this choice is pre-selected (multi-select only;
 *                          ignored for single-select where the input's
 *                          {@code defaultValue} carries the pre-selection)
 */
public record TemplateChoice(
        String value,
        @Nullable String label,
        boolean defaultSelected) {

    public TemplateChoice {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("choice: 'value' is required");
        }
    }

    /** Returns {@link #label} when set, else {@link #value}. */
    public String labelOrValue() {
        return label != null && !label.isBlank() ? label : value;
    }
}
