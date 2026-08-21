package de.mhus.vance.addon.brain.links;

import de.mhus.vance.shared.document.kind.ApplicationDocument;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedSet;
import org.jspecify.annotations.Nullable;

/**
 * The {@code config.links} block of an {@code app: links} manifest.
 *
 * <p>Reading is lenient the way every hand-editable document has to be: a
 * missing block, a malformed row, a group named twice — each costs the
 * thing that was wrong, never the app you would open to fix it.
 *
 * <p>{@link #groups} is the <em>declared order</em> of group headings, and
 * it is a separate list rather than being derived from the entries for one
 * reason: an empty group has to be able to exist. Create "To read" before
 * there is anything in it, and it survives the round trip. Groups that
 * only appear on entries are appended by {@link #orderedGroups()} — so a
 * hand-written manifest never has to declare anything.
 *
 * @param groups          declared group order.
 * @param entries         the links, in display order.
 * @param indexOutputPath relative path of the generated index
 *                        (default {@code _index.md}).
 */
public record LinksConfig(
        List<String> groups,
        List<LinkEntry> entries,
        String indexOutputPath) {

    /** Block key inside {@code config}, and the app discriminator. */
    public static final String BLOCK = "links";
    public static final String DEFAULT_INDEX = "_index.md";

    public LinksConfig {
        groups = groups == null ? List.of() : List.copyOf(groups);
        entries = entries == null ? List.of() : List.copyOf(entries);
        if (indexOutputPath == null || indexOutputPath.isBlank()) {
            indexOutputPath = DEFAULT_INDEX;
        }
    }

    public static LinksConfig empty() {
        return new LinksConfig(List.of(), List.of(), DEFAULT_INDEX);
    }

    /** Read the block out of an application manifest. */
    public static LinksConfig from(@Nullable ApplicationDocument doc) {
        if (doc == null || doc.config() == null) return empty();
        if (!(doc.config().get(BLOCK) instanceof Map<?, ?> block)) return empty();

        List<String> groups = new ArrayList<>();
        if (block.get("groups") instanceof List<?> list) {
            for (Object o : list) {
                String s = asString(o);
                if (s != null && !groups.contains(s)) groups.add(s);
            }
        }

        List<LinkEntry> entries = new ArrayList<>();
        if (block.get("entries") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    LinkEntry e = LinkEntry.fromMap(m);
                    if (e != null) entries.add(e);
                } else if (o instanceof String s && !s.isBlank()) {
                    // Short form: a bare URL. The form a person types when
                    // editing the YAML by hand.
                    LinkEntry e = LinkEntry.fromMap(Map.of("url", s));
                    if (e != null) entries.add(e);
                }
            }
        }

        String indexOutput = DEFAULT_INDEX;
        if (block.get("index") instanceof Map<?, ?> index) {
            String out = asString(index.get("outputPath"));
            if (out != null) indexOutput = out;
        }

        return new LinksConfig(groups, entries, indexOutput);
    }

    /** The YAML form written back into {@code config.links}. */
    public Map<String, Object> toBlock() {
        Map<String, Object> block = new LinkedHashMap<>();
        if (!groups.isEmpty()) block.put("groups", List.copyOf(groups));
        List<Map<String, Object>> rows = new ArrayList<>(entries.size());
        for (LinkEntry e : entries) rows.add(e.toMap());
        block.put("entries", rows);
        Map<String, Object> index = new LinkedHashMap<>();
        index.put("outputPath", indexOutputPath);
        block.put("index", index);
        return block;
    }

    /**
     * Every group that has a heading, declared ones first in their declared
     * order, then any that only exist on an entry. The ungrouped lead group
     * is not in here — it has no name and always comes first.
     */
    public List<String> orderedGroups() {
        SequencedSet<String> ordered = new LinkedHashSet<>(groups);
        for (LinkEntry e : entries) {
            String g = e.group();
            if (g != null && !g.isBlank()) ordered.add(g);
        }
        return List.copyOf(ordered);
    }

    /** Entries of one group; {@code null} or blank selects the lead group. */
    public List<LinkEntry> entriesOf(@Nullable String group) {
        String want = group == null || group.isBlank() ? "" : group;
        List<LinkEntry> out = new ArrayList<>();
        for (LinkEntry e : entries) {
            String g = e.group() == null || e.group().isBlank() ? "" : e.group();
            if (g.equals(want)) out.add(e);
        }
        return List.copyOf(out);
    }

    private static @Nullable String asString(@Nullable Object v) {
        if (v instanceof String s && !s.isBlank()) return s.trim();
        return null;
    }
}
