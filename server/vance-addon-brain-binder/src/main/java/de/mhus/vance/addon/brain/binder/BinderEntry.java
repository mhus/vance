package de.mhus.vance.addon.brain.binder;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One anchored entry in a binder manifest — the raw, manifest-level
 * shape ({@code binder.entries[]}), before resolution against the
 * document store.
 *
 * @param ref     the target document, canonically a {@code vance:/<path>}
 *                URI. Required.
 * @param section optional grouping label for the sidebar. Blank = the
 *                lead ("without section") group. Purely organisational —
 *                NOT a scope, no permissions/cascade.
 * @param title   optional display-title override. When absent, the
 *                resolved document's own title is shown.
 */
public record BinderEntry(
        String ref,
        @Nullable String section,
        @Nullable String title) {

    /** Read one entry from its untyped manifest map form. */
    public static @Nullable BinderEntry fromMap(Map<?, ?> map) {
        Object refRaw = map.get("ref");
        if (!(refRaw instanceof String ref) || ref.isBlank()) return null;
        String section = asString(map.get("section"));
        String title = asString(map.get("title"));
        return new BinderEntry(ref.trim(), section, title);
    }

    /** Serialise back to the manifest map form (only non-null fields). */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ref", ref);
        if (section != null && !section.isBlank()) m.put("section", section);
        if (title != null && !title.isBlank()) m.put("title", title);
        return m;
    }

    private static @Nullable String asString(@Nullable Object v) {
        if (v instanceof String s && !s.isBlank()) return s.trim();
        return null;
    }
}
