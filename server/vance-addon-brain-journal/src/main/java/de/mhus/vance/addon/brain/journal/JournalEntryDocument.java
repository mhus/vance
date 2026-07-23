package de.mhus.vance.addon.brain.journal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * In-memory model of a {@code kind: journal-entry} document — one day's
 * diary page.
 *
 * <p>The body is free-form Markdown prose (edited with the shared block
 * editor in body-only mode). Structural data lives in the typed fields
 * and lands in the Markdown front-matter (or YAML/JSON top-level keys):
 *
 * @param kind  always {@code "journal-entry"}.
 * @param date  ISO date ({@code yyyy-MM-dd}) — the entry's calendar day
 *              and the canonical identity. Mirrors the filename stem.
 * @param title short headline; defaults to the long-formatted date in the
 *              UI when empty.
 * @param mood  optional mood token; the five presets
 *              ({@code great/good/neutral/low/bad}) render with an icon,
 *              free-form values pass through.
 * @param tags  free classification tags (also mirrored onto the native
 *              document tag set for search).
 * @param body  Markdown prose. Empty when the day has no writing yet.
 * @param extra unknown front-matter / top-level keys, passthrough.
 */
public record JournalEntryDocument(
        String kind,
        String date,
        String title,
        @Nullable String mood,
        List<String> tags,
        String body,
        Map<String, Object> extra) {

    public static final String KIND = "journal-entry";

    public JournalEntryDocument {
        if (kind == null || kind.isBlank()) kind = KIND;
        if (date == null) date = "";
        if (title == null) title = "";
        if (tags == null) tags = new ArrayList<>();
        if (body == null) body = "";
        if (extra == null) extra = new LinkedHashMap<>();
    }

    public static JournalEntryDocument empty() {
        return new JournalEntryDocument(KIND, "", "", null,
                new ArrayList<>(), "", new LinkedHashMap<>());
    }
}
