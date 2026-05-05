package de.mhus.vance.brain.marvin;

import de.mhus.vance.api.marvin.TaskKind;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.document.kind.KindCodecException;
import de.mhus.vance.shared.document.kind.ListCodec;
import de.mhus.vance.shared.document.kind.ListDocument;
import de.mhus.vance.shared.document.kind.ListItem;
import de.mhus.vance.shared.document.kind.RecordsCodec;
import de.mhus.vance.shared.document.kind.RecordsDocument;
import de.mhus.vance.shared.document.kind.RecordsItem;
import de.mhus.vance.shared.document.kind.TreeCodec;
import de.mhus.vance.shared.document.kind.TreeDocument;
import de.mhus.vance.shared.document.kind.TreeItem;
import de.mhus.vance.shared.marvin.MarvinNodeService.NodeSpec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Materializes the children of an {@code EXPAND_FROM_DOC} node by
 * reading the referenced {@code list}/{@code tree}/{@code records}
 * document and substituting each item into the {@code childTemplate}.
 * No LLM call — the document <em>is</em> the plan.
 *
 * <p>See {@code specification/marvin-engine.md} §7a for the contract.
 *
 * <p>Recursion semantics for {@code tree} documents: an item with
 * non-empty {@code children} becomes a child node carrying the
 * templated {@code taskKind} (typically {@code WORKER}); the item's
 * own children are then attached as children of that node, recursing
 * pre-order. The DFS executes the parent first, then descends —
 * matching the natural "write the chapter, then the sub-sections"
 * outline order. {@code FLAT} mode skips the recursion and only
 * materializes top-level items.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentExpander {

    private final DocumentService documentService;

    /** Container for a fully resolved expansion result — one root-list
     *  of node specs, optionally with nested children for tree-mode. */
    public record ExpansionPlan(List<TemplatedNode> nodes) {}

    /** A single materialized node spec plus its (already templated)
     *  recursive children. The Marvin engine flattens this into the
     *  Mongo task-tree by walking pre-order and calling
     *  {@code appendChildren} per parent. */
    public record TemplatedNode(NodeSpec spec, List<TemplatedNode> children) {}

    /**
     * Reads the document referenced by {@code documentRef} and produces
     * the expansion plan.
     *
     * @param tenantId       process tenant — used for the document lookup.
     * @param projectId      process project — used for the document lookup.
     * @param documentRef    {@code {name|path|id, scope?}}; the EXPAND
     *                       node's {@code taskSpec.documentRef}.
     * @param childTemplate  the {@code taskSpec.childTemplate} the EXPAND
     *                       node carries. Must be a map with at least
     *                       {@code goal} (string template) — {@code taskKind}
     *                       defaults to {@code WORKER}, {@code taskSpec}
     *                       defaults to empty.
     * @param treeMode       {@code "RECURSIVE"} (default for trees) or
     *                       {@code "FLAT"}; ignored for non-tree kinds.
     * @param parentGoal     the EXPAND node's own goal — exposed to the
     *                       template as {@code {{parent.goal}}}.
     * @param strictMissing  if {@code true}, a template referencing a
     *                       missing field throws; otherwise the slot
     *                       resolves to an empty string.
     */
    public ExpansionPlan expand(
            String tenantId,
            String projectId,
            Map<String, Object> documentRef,
            Map<String, Object> childTemplate,
            String treeMode,
            @Nullable String parentGoal,
            boolean strictMissing) {
        DocumentDocument doc = resolveDocument(tenantId, projectId, documentRef)
                .orElseThrow(() -> new ExpandError(
                        "document not found: " + describeRef(documentRef)));

        String body = readBody(doc);
        String kind = doc.getKind() == null ? "" : doc.getKind().toLowerCase();
        Map<String, String> rootVars = rootVariables(doc);

        return switch (kind) {
            case "list" -> expandList(body, doc.getMimeType(),
                    childTemplate, parentGoal, rootVars, strictMissing);
            case "tree" -> expandTree(body, doc.getMimeType(),
                    childTemplate, parentGoal, rootVars,
                    !"FLAT".equalsIgnoreCase(treeMode), strictMissing);
            case "records" -> expandRecords(body, doc.getMimeType(),
                    childTemplate, parentGoal, rootVars, strictMissing);
            case "graph" -> throw new ExpandError(
                    "kind: graph is not supported by EXPAND_FROM_DOC "
                            + "(no canonical traversal order)");
            case "" -> throw new ExpandError(
                    "document has no kind header — needs kind: list / tree / records");
            default -> throw new ExpandError(
                    "unsupported kind for EXPAND_FROM_DOC: " + kind);
        };
    }

    // ─────────────────────── kind dispatch ───────────────────────

    private ExpansionPlan expandList(
            String body, @Nullable String mime,
            Map<String, Object> childTemplate, @Nullable String parentGoal,
            Map<String, String> rootVars, boolean strictMissing) {
        ListDocument list;
        try {
            list = ListCodec.parse(body, effectiveMime(mime));
        } catch (KindCodecException e) {
            throw new ExpandError("failed to parse list body: " + e.getMessage(), e);
        }
        List<TemplatedNode> nodes = new ArrayList<>();
        int i = 0;
        for (ListItem item : list.items()) {
            Map<String, String> vars = listItemVariables(item, i, parentGoal, rootVars);
            nodes.add(new TemplatedNode(materialize(childTemplate, vars, strictMissing), List.of()));
            i++;
        }
        return new ExpansionPlan(nodes);
    }

    private ExpansionPlan expandTree(
            String body, @Nullable String mime,
            Map<String, Object> childTemplate, @Nullable String parentGoal,
            Map<String, String> rootVars, boolean recursive, boolean strictMissing) {
        TreeDocument tree;
        try {
            tree = TreeCodec.parse(body, effectiveMime(mime));
        } catch (KindCodecException e) {
            throw new ExpandError("failed to parse tree body: " + e.getMessage(), e);
        }
        List<TemplatedNode> nodes = new ArrayList<>();
        int i = 0;
        for (TreeItem item : tree.items()) {
            nodes.add(materializeTreeItem(item, i, childTemplate, parentGoal,
                    rootVars, recursive, strictMissing));
            i++;
        }
        return new ExpansionPlan(nodes);
    }

    private TemplatedNode materializeTreeItem(
            TreeItem item, int index,
            Map<String, Object> childTemplate, @Nullable String parentGoal,
            Map<String, String> rootVars, boolean recursive, boolean strictMissing) {
        Map<String, String> vars = treeItemVariables(item, index, parentGoal, rootVars);
        NodeSpec spec = materialize(childTemplate, vars, strictMissing);
        if (!recursive || item.children().isEmpty()) {
            return new TemplatedNode(spec, List.of());
        }
        List<TemplatedNode> kids = new ArrayList<>(item.children().size());
        int j = 0;
        for (TreeItem child : item.children()) {
            kids.add(materializeTreeItem(child, j, childTemplate, parentGoal,
                    rootVars, true, strictMissing));
            j++;
        }
        return new TemplatedNode(spec, kids);
    }

    private ExpansionPlan expandRecords(
            String body, @Nullable String mime,
            Map<String, Object> childTemplate, @Nullable String parentGoal,
            Map<String, String> rootVars, boolean strictMissing) {
        RecordsDocument records;
        try {
            records = RecordsCodec.parse(body, effectiveMime(mime));
        } catch (KindCodecException e) {
            throw new ExpandError("failed to parse records body: " + e.getMessage(), e);
        }
        List<TemplatedNode> nodes = new ArrayList<>();
        int i = 0;
        for (RecordsItem rec : records.items()) {
            Map<String, String> vars = recordVariables(rec, i, parentGoal, rootVars);
            nodes.add(new TemplatedNode(materialize(childTemplate, vars, strictMissing), List.of()));
            i++;
        }
        return new ExpansionPlan(nodes);
    }

    // ─────────────────────── template substitution ───────────────────────

    /**
     * Walks the {@code childTemplate} structure recursively, substituting
     * {@code {{key}}} placeholders inside any string leaf, and produces a
     * {@link NodeSpec} from the result.
     */
    @SuppressWarnings("unchecked")
    private static NodeSpec materialize(
            Map<String, Object> childTemplate,
            Map<String, String> vars,
            boolean strictMissing) {
        Object goalRaw = childTemplate.get("goal");
        String goal = goalRaw instanceof String s
                ? substitute(s, vars, strictMissing)
                : "";
        TaskKind taskKind = TaskKind.WORKER;
        Object kindRaw = childTemplate.get("taskKind");
        if (kindRaw instanceof String ks && !ks.isBlank()) {
            try {
                taskKind = TaskKind.valueOf(ks.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new ExpandError(
                        "childTemplate.taskKind '" + ks + "' is not a valid TaskKind");
            }
        }
        Map<String, Object> taskSpec = new LinkedHashMap<>();
        Object specRaw = childTemplate.get("taskSpec");
        if (specRaw instanceof Map<?, ?> ms) {
            for (Map.Entry<?, ?> e : ms.entrySet()) {
                if (!(e.getKey() instanceof String key)) continue;
                taskSpec.put(key, substituteAny(e.getValue(), vars, strictMissing));
            }
        }
        // Tolerate flat childTemplate format: a model that puts
        // `recipe` / `recipeParams` / `steerContent` etc. directly on
        // the childTemplate (a popular shape because it reads more
        // naturally) gets them merged into taskSpec instead of
        // silently dropped. Two normalisations:
        //   1. `recipeParams` is the user-friendly alias for the
        //      taskSpec key Marvin's runWorker actually reads —
        //      `params`. Rename so Marvin sees the param map.
        //   2. Anything that's not goal/taskKind/taskSpec falls
        //      through into taskSpec with the same name.
        for (Map.Entry<String, Object> e : childTemplate.entrySet()) {
            String key = e.getKey();
            if (key == null) continue;
            if (key.equals("goal") || key.equals("taskKind") || key.equals("taskSpec")) {
                continue;
            }
            String mappedKey = key.equals("recipeParams") ? "params" : key;
            // Don't clobber values already supplied via the explicit
            // taskSpec block — that one wins.
            if (!taskSpec.containsKey(mappedKey)) {
                taskSpec.put(mappedKey,
                        substituteAny(e.getValue(), vars, strictMissing));
            }
        }
        return new NodeSpec(goal, taskKind, taskSpec);
    }

    private static @Nullable Object substituteAny(
            @Nullable Object value, Map<String, String> vars, boolean strictMissing) {
        if (value instanceof String s) return substitute(s, vars, strictMissing);
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!(e.getKey() instanceof String key)) continue;
                out.put(key, substituteAny(e.getValue(), vars, strictMissing));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object o : list) out.add(substituteAny(o, vars, strictMissing));
            return out;
        }
        return value;
    }

    /**
     * Replaces every {@code {{key}}} occurrence with the matching value
     * from {@code vars}. Missing keys resolve to an empty string in
     * lenient mode and throw in strict mode.
     */
    private static String substitute(
            String template, Map<String, String> vars, boolean strictMissing) {
        if (template.indexOf("{{") < 0) return template;
        StringBuilder out = new StringBuilder(template.length());
        int cursor = 0;
        while (cursor < template.length()) {
            int open = template.indexOf("{{", cursor);
            if (open < 0) {
                out.append(template, cursor, template.length());
                break;
            }
            int close = template.indexOf("}}", open + 2);
            if (close < 0) {
                out.append(template, cursor, template.length());
                break;
            }
            out.append(template, cursor, open);
            String key = template.substring(open + 2, close).trim();
            String resolved = vars.get(key);
            if (resolved == null) {
                if (strictMissing) {
                    throw new ExpandError(
                            "template references missing variable '" + key
                                    + "' (strict mode)");
                }
                resolved = "";
            }
            out.append(resolved);
            cursor = close + 2;
        }
        return out.toString();
    }

    // ─────────────────────── variable scoping ───────────────────────

    private static Map<String, String> rootVariables(DocumentDocument doc) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("root.name", doc.getName() == null ? "" : doc.getName());
        vars.put("root.title", doc.getTitle() == null ? "" : doc.getTitle());
        vars.put("root.path", doc.getPath() == null ? "" : doc.getPath());
        return vars;
    }

    private static Map<String, String> listItemVariables(
            ListItem item, int index,
            @Nullable String parentGoal, Map<String, String> rootVars) {
        Map<String, String> vars = new LinkedHashMap<>(rootVars);
        vars.put("index", Integer.toString(index));
        vars.put("index1", Integer.toString(index + 1));
        vars.put("parent.goal", parentGoal == null ? "" : parentGoal);
        vars.put("item.text", item.text());
        for (Map.Entry<String, Object> e : item.extra().entrySet()) {
            vars.put("item." + e.getKey(), stringOf(e.getValue()));
        }
        return vars;
    }

    private static Map<String, String> treeItemVariables(
            TreeItem item, int index,
            @Nullable String parentGoal, Map<String, String> rootVars) {
        Map<String, String> vars = new LinkedHashMap<>(rootVars);
        vars.put("index", Integer.toString(index));
        vars.put("index1", Integer.toString(index + 1));
        vars.put("parent.goal", parentGoal == null ? "" : parentGoal);
        vars.put("item.text", item.text());
        for (Map.Entry<String, Object> e : item.extra().entrySet()) {
            vars.put("item." + e.getKey(), stringOf(e.getValue()));
        }
        return vars;
    }

    private static Map<String, String> recordVariables(
            RecordsItem rec, int index,
            @Nullable String parentGoal, Map<String, String> rootVars) {
        Map<String, String> vars = new LinkedHashMap<>(rootVars);
        vars.put("index", Integer.toString(index));
        vars.put("index1", Integer.toString(index + 1));
        vars.put("parent.goal", parentGoal == null ? "" : parentGoal);
        for (Map.Entry<String, String> e : rec.values().entrySet()) {
            vars.put("record." + e.getKey(), e.getValue() == null ? "" : e.getValue());
        }
        for (Map.Entry<String, Object> e : rec.extra().entrySet()) {
            vars.put("record." + e.getKey(), stringOf(e.getValue()));
        }
        return vars;
    }

    private static String stringOf(@Nullable Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    // ─────────────────────── document lookup ───────────────────────

    /**
     * Resolves the {@code documentRef} block to a concrete document.
     * Accepts {@code id}, {@code path} or {@code name}; for {@code name}
     * the document is found via project listing (path basename match).
     */
    private Optional<DocumentDocument> resolveDocument(
            String tenantId, String projectId, Map<String, Object> ref) {
        Object id = ref.get("id");
        if (id instanceof String s && !s.isBlank()) {
            return documentService.findById(s);
        }
        Object path = ref.get("path");
        if (path instanceof String p && !p.isBlank()) {
            return documentService.findByPath(tenantId, projectId, p);
        }
        Object name = ref.get("name");
        if (name instanceof String n && !n.isBlank()) {
            // Try {name} as a literal path first — by far the common
            // case ("chapters", "outline.md") — then fall back to
            // matching the file basename across the project.
            Optional<DocumentDocument> direct = documentService.findByPath(tenantId, projectId, n);
            if (direct.isPresent()) return direct;
            for (DocumentDocument d : documentService.listByProject(tenantId, projectId)) {
                if (n.equals(d.getName())) return Optional.of(d);
            }
        }
        return Optional.empty();
    }

    private static String describeRef(Map<String, Object> ref) {
        Object name = ref.get("name");
        Object path = ref.get("path");
        Object id = ref.get("id");
        if (id != null) return "id=" + id;
        if (path != null) return "path=" + path;
        if (name != null) return "name=" + name;
        return "<empty documentRef>";
    }

    private String readBody(DocumentDocument doc) {
        try (InputStream in = documentService.loadContent(doc)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ExpandError("failed to read document '" + doc.getPath() + "': "
                    + e.getMessage(), e);
        }
    }

    /**
     * The codecs key off the mime type — when a document slipped in
     * without one we assume markdown, the most common case for inline
     * list/tree bodies.
     */
    private static String effectiveMime(@Nullable String mime) {
        if (mime == null || mime.isBlank()) return "text/markdown";
        int semi = mime.indexOf(';');
        return semi < 0 ? mime : mime.substring(0, semi).trim();
    }

    /** Thrown for any unrecoverable problem while expanding — caller
     *  marks the EXPAND node FAILED with the message. */
    public static class ExpandError extends RuntimeException {
        public ExpandError(String message) { super(message); }
        public ExpandError(String message, Throwable cause) { super(message, cause); }
    }
}
