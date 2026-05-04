package de.mhus.vance.shared.document.kind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory model of a {@code kind: tree} document — hierarchical
 * items, recursive children. Same codec family as {@link ListDocument},
 * different per-item shape.
 *
 * @param kind  always {@code "tree"} (or {@code "mindmap"} when used
 *              by {@link MindmapCodec}); kept generic for symmetry.
 * @param items the top-level items.
 * @param extra unknown top-level fields, re-emitted on save.
 *
 * <p>Spec: {@code specification/doc-kind-tree.md}.
 */
public record TreeDocument(String kind, List<TreeItem> items, Map<String, Object> extra) {

    public TreeDocument {
        if (kind == null || kind.isBlank()) kind = "tree";
        if (items == null) items = new ArrayList<>();
        if (extra == null) extra = new LinkedHashMap<>();
    }

    public static TreeDocument empty() {
        return new TreeDocument("tree", new ArrayList<>(), new LinkedHashMap<>());
    }
}
