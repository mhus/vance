package de.mhus.vance.api.inbox;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * One label/value pair of an {@link EffectDescription}. Values are plain
 * strings without markup — they are rendered as text, not interpreted.
 *
 * <p>Top-level rather than nested inside {@link EffectDescription}
 * because the TypeScript generator emits one interface per class and
 * cannot resolve a nested record.
 */
@GenerateTypeScript("inbox")
public record EffectFact(String label, String value) {
}
