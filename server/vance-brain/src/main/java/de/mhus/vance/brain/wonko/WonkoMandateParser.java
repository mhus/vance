package de.mhus.vance.brain.wonko;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Reads the {@code mandate} param (plan §6.1) into a {@link WonkoMandate}.
 * Accepts the recipe/YAML form with lowercase levels:
 *
 * <pre>
 * mandate:
 *   default: confirm
 *   categories:
 *     naming: decide                       # short form
 *     data-model: { level: ask, locked: true }
 *     cosmetics: { level: confirm, ceiling: decide }
 *   notes: "prototype — performance does not matter"
 * </pre>
 *
 * A plain string is kept as {@link WonkoMandate#getNotes() notes}; unknown
 * levels fall back to the default instead of failing the run.
 */
final class WonkoMandateParser {

    private WonkoMandateParser() {}

    static WonkoMandate parse(@Nullable Object raw) {
        WonkoMandate mandate = new WonkoMandate();
        if (raw instanceof String s && !s.isBlank()) {
            mandate.setNotes(s.trim());
            return mandate;
        }
        if (!(raw instanceof Map<?, ?> map)) {
            return mandate;
        }
        MandateLevel def = MandateLevel.parse(map.get("default"));
        if (def != null) {
            mandate.setDefaultLevel(def);
        }
        if (map.get("notes") instanceof String notes && !notes.isBlank()) {
            mandate.setNotes(notes.trim());
        }
        if (map.get("categories") instanceof Map<?, ?> categories) {
            for (Map.Entry<?, ?> e : categories.entrySet()) {
                String name = WonkoKeys.slug(String.valueOf(e.getKey()));
                if (!name.isEmpty()) {
                    mandate.getCategories().put(name, category(e.getValue(), mandate.getDefaultLevel()));
                }
            }
        }
        return mandate;
    }

    /**
     * Lays the caller's mandate over the one remembered in the register: the
     * caller's default, notes and categories win (they are the authority for
     * this run), categories only the register knows — among them the ones
     * Wonko created in earlier runs — stay.
     */
    static void overlay(WonkoMandate remembered, WonkoMandate caller, boolean callerGaveDefault) {
        if (callerGaveDefault) {
            remembered.setDefaultLevel(caller.getDefaultLevel());
        }
        if (caller.getNotes() != null) {
            remembered.setNotes(caller.getNotes());
        }
        remembered.getCategories().putAll(caller.getCategories());
    }

    static boolean declaresDefault(@Nullable Object raw) {
        return raw instanceof Map<?, ?> map && MandateLevel.parse(map.get("default")) != null;
    }

    private static WonkoCategory category(@Nullable Object raw, MandateLevel fallback) {
        MandateLevel shortForm = MandateLevel.parse(raw);
        if (shortForm != null) {
            return WonkoCategory.builder().level(shortForm).build();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            return WonkoCategory.builder().level(fallback).build();
        }
        MandateLevel level = MandateLevel.parse(map.get("level"));
        return WonkoCategory.builder()
                .level(level == null ? fallback : level)
                .ceiling(MandateLevel.parse(map.get("ceiling")))
                .locked(Boolean.TRUE.equals(map.get("locked")))
                .description(map.get("description") instanceof String d && !d.isBlank() ? d.trim() : null)
                .build();
    }
}
