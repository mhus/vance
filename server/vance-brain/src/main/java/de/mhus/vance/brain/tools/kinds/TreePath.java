package de.mhus.vance.brain.tools.kinds;

import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.shared.document.kind.TreeDocument;
import de.mhus.vance.shared.document.kind.TreeItem;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Helpers for navigating a {@link TreeDocument} by an integer path.
 * Paths are comma-separated 0-based indices ({@code "0,2,1"} means
 * "third grandchild of the third child of the first item").
 */
public final class TreePath {

    private TreePath() {}

    /** Parse a comma-separated index path. Empty string → empty path
     *  (= the root list). */
    public static int[] parse(String pathStr) {
        if (pathStr == null || pathStr.isBlank()) return new int[0];
        String[] parts = pathStr.split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                throw new ToolException("Invalid path segment: '" + parts[i] + "'");
            }
            if (out[i] < 0) throw new ToolException("Negative path segment: " + out[i]);
        }
        return out;
    }

    public static String format(int[] path) {
        if (path.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < path.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(path[i]);
        }
        return sb.toString();
    }

    /** Walk to the parent list of the item at {@code path}. Empty
     *  path → root list. Throws when an intermediate index is out
     *  of range. */
    public static List<TreeItem> parentList(TreeDocument doc, int[] path) {
        List<TreeItem> current = doc.items();
        for (int i = 0; i < path.length - 1; i++) {
            int idx = path[i];
            if (idx < 0 || idx >= current.size()) {
                throw new ToolException("path[" + i + "]=" + idx + " out of range at depth " + i);
            }
            current = current.get(idx).children();
        }
        return current;
    }

    /** Resolve the item at the given path. Throws on out-of-range. */
    public static TreeItem at(TreeDocument doc, int[] path) {
        if (path.length == 0) {
            throw new ToolException("Empty path doesn't refer to an item");
        }
        List<TreeItem> parent = parentList(doc, path);
        int idx = path[path.length - 1];
        if (idx < 0 || idx >= parent.size()) {
            throw new ToolException("Final index " + idx + " out of range "
                    + "[0," + (parent.size() - 1) + "]");
        }
        return parent.get(idx);
    }

    /** Search the whole tree for items whose text contains
     *  {@code query} (case-insensitive). Returns the matching paths. */
    public static List<int[]> findByText(TreeDocument doc, String query) {
        List<int[]> matches = new ArrayList<>();
        String q = query.toLowerCase();
        walk(doc.items(), new ArrayList<>(), (path, item) -> {
            if (item.text().toLowerCase().contains(q)) {
                int[] arr = new int[path.size()];
                for (int i = 0; i < arr.length; i++) arr[i] = path.get(i);
                matches.add(arr);
            }
        });
        return matches;
    }

    public interface Visitor {
        void visit(List<Integer> path, TreeItem item);
    }

    public static void walk(List<TreeItem> items, List<Integer> prefix, Visitor visitor) {
        for (int i = 0; i < items.size(); i++) {
            prefix.add(i);
            TreeItem item = items.get(i);
            visitor.visit(prefix, item);
            walk(item.children(), prefix, visitor);
            prefix.remove(prefix.size() - 1);
        }
    }

    /** Replace a path's leaf with {@code mutator(item)} and return
     *  a new {@link TreeDocument} where that one item is swapped. */
    public static TreeDocument replaceAt(TreeDocument doc, int[] path, java.util.function.Function<TreeItem, TreeItem> mutator) {
        if (path.length == 0) throw new ToolException("Empty path");
        List<TreeItem> newItems = mutate(doc.items(), path, 0, mutator);
        return new TreeDocument(doc.kind(), newItems, doc.extra());
    }

    private static List<TreeItem> mutate(List<TreeItem> source, int[] path, int depth,
                                          java.util.function.Function<TreeItem, TreeItem> mutator) {
        List<TreeItem> out = new ArrayList<>(source);
        int idx = path[depth];
        if (idx < 0 || idx >= out.size()) {
            throw new ToolException("path[" + depth + "]=" + idx + " out of range");
        }
        TreeItem item = out.get(idx);
        if (depth == path.length - 1) {
            out.set(idx, mutator.apply(item));
        } else {
            List<TreeItem> mutatedChildren = mutate(item.children(), path, depth + 1, mutator);
            out.set(idx, new TreeItem(item.text(), mutatedChildren, item.extra()));
        }
        return out;
    }

    /** Same as {@link #replaceAt} but the mutator returns
     *  {@code null} to remove the leaf instead of replacing it. */
    public static TreeDocument removeAt(TreeDocument doc, int[] path) {
        if (path.length == 0) throw new ToolException("Empty path");
        List<TreeItem> newItems = remove(doc.items(), path, 0);
        return new TreeDocument(doc.kind(), newItems, doc.extra());
    }

    private static List<TreeItem> remove(List<TreeItem> source, int[] path, int depth) {
        List<TreeItem> out = new ArrayList<>(source);
        int idx = path[depth];
        if (idx < 0 || idx >= out.size()) {
            throw new ToolException("path[" + depth + "]=" + idx + " out of range");
        }
        if (depth == path.length - 1) {
            out.remove(idx);
        } else {
            TreeItem item = out.get(idx);
            List<TreeItem> mutatedChildren = remove(item.children(), path, depth + 1);
            out.set(idx, new TreeItem(item.text(), mutatedChildren, item.extra()));
        }
        return out;
    }

    /** Insert {@code newItem} into the children list of the item at
     *  {@code parentPath}. {@code position} = -1 appends. */
    public static TreeDocument insertChild(TreeDocument doc, int[] parentPath, int position, TreeItem newItem) {
        if (parentPath.length == 0) {
            // Insert into root list
            List<TreeItem> rootCopy = new ArrayList<>(doc.items());
            int idx = position < 0 ? rootCopy.size() : position;
            rootCopy.add(Math.min(idx, rootCopy.size()), newItem);
            return new TreeDocument(doc.kind(), rootCopy, doc.extra());
        }
        return replaceAt(doc, parentPath, (parent) -> {
            List<TreeItem> kids = new ArrayList<>(parent.children());
            int idx = position < 0 ? kids.size() : position;
            kids.add(Math.min(idx, kids.size()), newItem);
            return new TreeItem(parent.text(), kids, parent.extra());
        });
    }

    public static @Nullable TreeItem itemAt(TreeDocument doc, int[] path) {
        try {
            return at(doc, path);
        } catch (ToolException e) {
            return null;
        }
    }
}
