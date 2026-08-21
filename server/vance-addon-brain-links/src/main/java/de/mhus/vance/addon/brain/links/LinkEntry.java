package de.mhus.vance.addon.brain.links;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One link in a links manifest ({@code links.entries[]}).
 *
 * <p><b>What is stored and what is not</b> is the whole design of this
 * record. {@link #title} is stored: it is written once when the link is
 * added (from the page's own {@code og:title}, or by the person) so the
 * list stays readable even when the site is gone. {@link #teaser} and
 * {@link #image} are stored <em>only when somebody typed them</em> — left
 * empty they are resolved live from the brain's link-preview proxy, which
 * already caches OG metadata per URL for the whole tenant. A second copy
 * of that cache in every manifest would go stale in a place nobody
 * refreshes.
 *
 * <p>So: an empty {@code teaser} does not mean "no teaser", it means
 * "whatever the page says today". A non-empty one is an override and
 * always wins.
 *
 * @param url     the link, normalised by {@link LinkUrls#normalise}. The
 *                entry's identity.
 * @param title   display title. Snapshot at add time or typed override.
 * @param teaser  own teaser text; empty ⇒ live from the link preview.
 * @param image   own picture URL; empty ⇒ live from the link preview.
 * @param group   grouping label; blank ⇒ the lead ("ungrouped") group.
 *                Purely organisational — not a scope, no permissions.
 * @param tags    free labels, for filtering inside the app.
 * @param note    the reader's own remark. Deliberately separate from
 *                {@code teaser}: a teaser describes the page, a note
 *                describes why <em>this</em> list has it.
 * @param addedAt when it was added, for "newest first" reading.
 */
public record LinkEntry(
        String url,
        @Nullable String title,
        @Nullable String teaser,
        @Nullable String image,
        @Nullable String group,
        List<String> tags,
        @Nullable String note,
        @Nullable Instant addedAt) {

    public LinkEntry {
        if (tags == null) tags = List.of();
    }

    /**
     * Read one entry from its untyped manifest form. Returns {@code null}
     * when there is no usable URL — skipping one broken row beats refusing
     * the manifest that the person would fix it in.
     */
    public static @Nullable LinkEntry fromMap(Map<?, ?> map) {
        String url = asString(map.get("url"));
        if (url == null) return null;
        String normalised;
        try {
            normalised = LinkUrls.normalise(url);
        } catch (RuntimeException e) {
            // A hand-edited manifest can hold anything. A row we refuse to
            // render as a link is dropped, not escalated.
            return null;
        }
        return new LinkEntry(
                normalised,
                asString(map.get("title")),
                asString(map.get("teaser")),
                asString(map.get("image")),
                asString(map.get("group")),
                tags(map.get("tags")),
                asString(map.get("note")),
                instant(map.get("addedAt")));
    }

    /** Serialise back to the manifest form — only fields that carry a value. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("url", url);
        putIfSet(m, "title", title);
        putIfSet(m, "teaser", teaser);
        putIfSet(m, "image", image);
        putIfSet(m, "group", group);
        if (!tags.isEmpty()) m.put("tags", List.copyOf(tags));
        putIfSet(m, "note", note);
        if (addedAt != null) m.put("addedAt", addedAt.toString());
        return m;
    }

    /** The label a card shows when no title was ever stored. */
    public String displayTitle() {
        return title != null && !title.isBlank() ? title : LinkUrls.hostLabel(url);
    }

    private static void putIfSet(Map<String, Object> m, String key, @Nullable String value) {
        if (value != null && !value.isBlank()) m.put(key, value);
    }

    private static List<String> tags(@Nullable Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            String s = asString(o);
            if (s != null && !out.contains(s)) out.add(s);
        }
        return List.copyOf(out);
    }

    private static @Nullable Instant instant(@Nullable Object raw) {
        if (raw instanceof Instant i) return i;
        if (raw instanceof java.util.Date d) return d.toInstant();
        String s = asString(raw);
        if (s == null) return null;
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            // An unreadable timestamp costs a sort key, not the entry.
            return null;
        }
    }

    private static @Nullable String asString(@Nullable Object v) {
        if (v instanceof String s && !s.isBlank()) return s.trim();
        return null;
    }
}
