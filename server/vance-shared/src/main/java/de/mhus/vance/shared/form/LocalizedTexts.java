package de.mhus.vance.shared.form;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Helpers for the {@code Map<lang, text>} convention used in
 * {@link de.mhus.vance.api.form.FormFieldDto#getLabel()},
 * {@link de.mhus.vance.api.wizard.WizardDto#getTitle()}, etc.
 *
 * <p>Resolution rule: prefer the requested language, then English as a
 * universal fallback, then any other entry, then a blank string. We
 * intentionally do <em>not</em> walk a locale-tree ({@code de-CH → de}) —
 * the wizard authors carry the burden of using two-letter codes
 * consistently, and the resolver stays a one-line lookup.
 */
public final class LocalizedTexts {

    private LocalizedTexts() {}

    /**
     * Resolves a {@code Map<lang, text>} against {@code preferred}.
     * Returns the empty string when {@code map} is {@code null} or has
     * no usable entry.
     */
    public static String resolve(@Nullable Map<String, String> map, @Nullable String preferred) {
        if (map == null || map.isEmpty()) return "";
        if (preferred != null && !preferred.isBlank()) {
            String hit = map.get(preferred);
            if (hit != null && !hit.isBlank()) return hit;
        }
        String en = map.get("en");
        if (en != null && !en.isBlank()) return en;
        for (String v : map.values()) {
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }
}
