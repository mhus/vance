package de.mhus.vance.shared.document.kind;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Optional priority dimension for a {@link ChecklistItem}, orthogonal
 * to {@link ChecklistStatus}. Three states: {@link #HIGH}, {@link #LOW},
 * or absent (normal — represented as {@code null} on the item).
 *
 * <p>Markdown form: trailing {@code #prio:high} / {@code #prio:low}
 * tag. JSON/YAML form: {@code "priority": "high"} / {@code "low"}.
 *
 * <p>Spec: {@code specification/doc-kind-checklist.md} §2.3.
 */
public enum ChecklistPriority {

    HIGH("high"),
    LOW("low");

    private final String wireName;

    ChecklistPriority(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    /** Resolve a wire-name into a priority. Unknown / null returns
     *  {@code null} (normal priority). */
    public static @Nullable ChecklistPriority fromWireName(@Nullable String wire) {
        if (wire == null) return null;
        return switch (wire.toLowerCase(Locale.ROOT)) {
            case "high" -> HIGH;
            case "low" -> LOW;
            default -> null;
        };
    }
}
