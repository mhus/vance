package de.mhus.vance.shared.starred;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The parsed content of a user's starred control file: an ordered list of
 * {@link StarredItem} plus every top-level key the codec did not recognise.
 *
 * <p>Order is file order, and file order is meaningful — it decides both the
 * tile sequence on the landing page and the tie-break when two entries claim
 * the same {@code type}.
 */
public record StarredDocument(List<StarredItem> items, Map<String, Object> extra) {

    public StarredDocument {
        items = items == null ? List.of() : List.copyOf(items);
        extra = extra == null ? Map.of() : Map.copyOf(extra);
    }

    public static StarredDocument empty() {
        return new StarredDocument(List.of(), Map.of());
    }

    public StarredDocument withItems(List<StarredItem> replacement) {
        return new StarredDocument(replacement, extra);
    }

    /**
     * First entry at {@code (project, path)}. First wins: a duplicate is a
     * defect the kind handler reports, but a lookup must still be
     * deterministic rather than throwing on a file a human broke.
     */
    public Optional<StarredItem> find(String project, String path) {
        return items.stream().filter(i -> i.matches(project, path)).findFirst();
    }

    /** Entries at the given visibility threshold or above, in file order. */
    public List<StarredItem> resolvable() {
        return items.stream().filter(i -> i.visibility().resolvable()).toList();
    }

    /** Entries the landing page shows, in file order. */
    public List<StarredItem> displayed() {
        return items.stream().filter(i -> i.visibility().displayed()).toList();
    }

    /**
     * Replace the entry at {@code (project, path)} in place, or append when it
     * is not there yet. In-place keeps the user's ordering across a re-star.
     */
    public StarredDocument upsert(StarredItem item) {
        List<StarredItem> out = new ArrayList<>(items.size() + 1);
        boolean replaced = false;
        for (StarredItem existing : items) {
            if (!replaced && existing.matches(item.project(), item.path())) {
                out.add(item);
                replaced = true;
            } else {
                out.add(existing);
            }
        }
        if (!replaced) out.add(item);
        return withItems(out);
    }

    /** Drop every entry at {@code (project, path)} — duplicates included. */
    public StarredDocument remove(String project, String path) {
        return withItems(items.stream().filter(i -> !i.matches(project, path)).toList());
    }

    /** Mutable copy of {@link #extra}, for callers that need to add a key. */
    public Map<String, Object> extraCopy() {
        return new LinkedHashMap<>(extra);
    }
}
