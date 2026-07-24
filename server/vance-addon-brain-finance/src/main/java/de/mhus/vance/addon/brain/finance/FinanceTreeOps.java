package de.mhus.vance.addon.brain.finance;

import de.mhus.vance.addon.brain.finance.model.FinanceNode;
import de.mhus.vance.addon.brain.finance.model.FinanceTreeDocument;
import de.mhus.vance.addon.brain.finance.model.FinanceValue;
import de.mhus.vance.toolpack.ToolException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Pure, immutable transforms on a {@link FinanceTreeDocument} tree — no Spring,
 * no I/O. Each op returns a new document; node {@code name}s are the unique
 * business key, so they address nodes across the whole tree. Invalid ops
 * ({@code ToolException}) are surfaced to the caller unchanged.
 */
public final class FinanceTreeOps {

    private FinanceTreeOps() {
        // utility class
    }

    /** Find a node by name anywhere in the tree, or {@code null}. */
    public static @Nullable FinanceNode find(FinanceTreeDocument doc, String name) {
        return doc.root() == null ? null : find(doc.root(), name);
    }

    private static @Nullable FinanceNode find(FinanceNode node, String name) {
        if (node.name().equals(name)) return node;
        for (FinanceNode child : node.children()) {
            FinanceNode hit = find(child, name);
            if (hit != null) return hit;
        }
        return null;
    }

    /**
     * Add {@code child}. With {@code parentName == null} it becomes the root
     * (error if a root already exists); otherwise it is appended under the
     * named parent. Node names must be unique across the tree.
     */
    public static FinanceTreeDocument addChild(FinanceTreeDocument doc,
                                               @Nullable String parentName,
                                               FinanceNode child) {
        if (find(doc, child.name()) != null) {
            throw new ToolException("A node named '" + child.name() + "' already exists.");
        }
        if (parentName == null) {
            if (doc.root() != null) {
                throw new ToolException(
                        "Root already exists; pass a parentName to attach '" + child.name() + "'.");
            }
            return withRoot(doc, child);
        }
        if (doc.root() == null) {
            throw new ToolException("Tree has no root yet; add the root node first (no parentName).");
        }
        boolean[] found = {false};
        FinanceNode newRoot = addUnder(doc.root(), parentName, child, found);
        if (!found[0]) throw new ToolException("No node named '" + parentName + "'.");
        return withRoot(doc, newRoot);
    }

    private static FinanceNode addUnder(FinanceNode node, String parentName,
                                        FinanceNode child, boolean[] found) {
        List<FinanceNode> children = new ArrayList<>();
        for (FinanceNode c : node.children()) children.add(addUnder(c, parentName, child, found));
        if (node.name().equals(parentName)) {
            children.add(child);
            found[0] = true;
        }
        return withChildren(node, children);
    }

    /** Update a node's display fields from {@code patch} (name/values/children untouched). */
    public static FinanceTreeDocument updateNode(FinanceTreeDocument doc, String name,
                                                 Map<String, Object> patch) {
        FinanceNode root = requireRoot(doc);
        boolean[] found = {false};
        FinanceNode newRoot = applyUpdate(root, name, patch, found);
        if (!found[0]) throw new ToolException("No node named '" + name + "'.");
        return withRoot(doc, newRoot);
    }

    private static FinanceNode applyUpdate(FinanceNode node, String name,
                                           Map<String, Object> patch, boolean[] found) {
        List<FinanceNode> children = new ArrayList<>();
        for (FinanceNode c : node.children()) children.add(applyUpdate(c, name, patch, found));
        FinanceNode rebuilt = withChildren(node, children);
        if (node.name().equals(name)) {
            found[0] = true;
            return applyPatch(rebuilt, patch);
        }
        return rebuilt;
    }

    /** Remove a node (and its subtree). Removing the root clears it. */
    public static FinanceTreeDocument removeNode(FinanceTreeDocument doc, String name) {
        FinanceNode root = requireRoot(doc);
        if (root.name().equals(name)) {
            return new FinanceTreeDocument(doc.version(), doc.title(), doc.description(), null);
        }
        boolean[] found = {false};
        FinanceNode newRoot = removeFrom(root, name, found);
        if (!found[0]) throw new ToolException("No node named '" + name + "'.");
        return withRoot(doc, newRoot);
    }

    private static FinanceNode removeFrom(FinanceNode node, String name, boolean[] found) {
        List<FinanceNode> children = new ArrayList<>();
        for (FinanceNode c : node.children()) {
            if (c.name().equals(name)) {
                found[0] = true;
            } else {
                children.add(removeFrom(c, name, found));
            }
        }
        return withChildren(node, children);
    }

    /** Replace a node's value records. */
    public static FinanceTreeDocument setValues(FinanceTreeDocument doc, String name,
                                                List<FinanceValue> values) {
        FinanceNode root = requireRoot(doc);
        boolean[] found = {false};
        FinanceNode newRoot = setVals(root, name, values, found);
        if (!found[0]) throw new ToolException("No node named '" + name + "'.");
        return withRoot(doc, newRoot);
    }

    private static FinanceNode setVals(FinanceNode node, String name,
                                       List<FinanceValue> values, boolean[] found) {
        List<FinanceNode> children = new ArrayList<>();
        for (FinanceNode c : node.children()) children.add(setVals(c, name, values, found));
        FinanceNode rebuilt = withChildren(node, children);
        if (node.name().equals(name)) {
            found[0] = true;
            return withValues(rebuilt, List.copyOf(values));
        }
        return rebuilt;
    }

    // ── Copy helpers ──────────────────────────────────────────────

    private static FinanceTreeDocument withRoot(FinanceTreeDocument doc, FinanceNode root) {
        return new FinanceTreeDocument(doc.version(), doc.title(), doc.description(), root);
    }

    private static FinanceNode requireRoot(FinanceTreeDocument doc) {
        FinanceNode root = doc.root();
        if (root == null) throw new ToolException("Tree has no root.");
        return root;
    }

    private static FinanceNode withChildren(FinanceNode n, List<FinanceNode> children) {
        return new FinanceNode(n.name(), n.title(), n.icon(), n.color(), n.sign(),
                n.description(), n.notesRef(), n.values(), children);
    }

    private static FinanceNode withValues(FinanceNode n, List<FinanceValue> values) {
        return new FinanceNode(n.name(), n.title(), n.icon(), n.color(), n.sign(),
                n.description(), n.notesRef(), values, n.children());
    }

    private static FinanceNode applyPatch(FinanceNode n, Map<String, Object> patch) {
        int sign = patch.containsKey("sign")
                ? (toInt(patch.get("sign"), n.sign()) < 0 ? -1 : 1)
                : n.sign();
        return new FinanceNode(
                n.name(),
                strPatch(patch, "title", n.title()),
                strPatch(patch, "icon", n.icon()),
                strPatch(patch, "color", n.color()),
                sign,
                strPatch(patch, "description", n.description()),
                strPatch(patch, "notesRef", n.notesRef()),
                n.values(),
                n.children());
    }

    private static @Nullable String strPatch(Map<String, Object> patch, String key,
                                             @Nullable String current) {
        if (!patch.containsKey(key)) return current;
        Object v = patch.get(key);
        if (v == null) return null;
        String s = v.toString();
        return s.isBlank() ? null : s.trim();
    }

    private static int toInt(@Nullable Object o, int fallback) {
        if (o instanceof Number num) return num.intValue();
        if (o instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                /* fall through */
            }
        }
        return fallback;
    }
}
