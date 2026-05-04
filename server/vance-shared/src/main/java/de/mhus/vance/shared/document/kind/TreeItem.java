package de.mhus.vance.shared.document.kind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One node in a {@code kind: tree} document. Recursive — every
 * {@code TreeItem} carries its own list of children.
 *
 * <p>{@code text} is mandatory; {@code children} may be empty (leaf
 * node); {@code extra} carries unknown per-item fields, round-trip
 * stable in JSON/YAML and dropped in markdown.
 *
 * <p>Spec: {@code specification/doc-kind-tree.md} §2.
 */
public record TreeItem(String text, List<TreeItem> children, Map<String, Object> extra) {

    public TreeItem {
        Objects.requireNonNull(text, "text");
        if (children == null) children = new ArrayList<>();
        if (extra == null) extra = new LinkedHashMap<>();
    }

    /** Convenience: a leaf item with the given text. */
    public static TreeItem leaf(String text) {
        return new TreeItem(text, new ArrayList<>(), new LinkedHashMap<>());
    }
}
