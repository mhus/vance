package de.mhus.vance.shared.document.kind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Parser and serialiser for {@code kind: tree} document bodies.
 * Mirrors {@code treeItemsCodec.ts} — same wire format, same
 * indent-nesting rules.
 *
 * <p>Markdown indent algorithm (spec §3.1): leading spaces divided
 * by 2 give the depth, tabs count as 4 spaces. Items deeper than
 * {@code lastDepth + 1} are clamped — no skip-level nodes. Continuation
 * lines (≥ {@code (depth + 1) * 2} spaces, no own bullet) append to
 * the previous item's text.
 *
 * <p>Stateless utility — all methods static.
 */
public final class TreeCodec {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> JSON_MAP =
            new TypeReference<>() {};

    private TreeCodec() {
        // utility class
    }

    // ── Public API ──────────────────────────────────────────────────

    public static TreeDocument parse(String body, @Nullable String mimeType) {
        return parse(body, mimeType, "tree");
    }

    /**
     * Variant used by {@link MindmapCodec} — parses through the same
     * codec but keeps the original {@code kind} (e.g. {@code "mindmap"})
     * when the on-disk body has it. The default-kind argument is the
     * fallback when neither {@code $meta.kind} nor a top-level
     * {@code kind} is present.
     */
    static TreeDocument parse(String body, @Nullable String mimeType, String defaultKind) {
        if (isMarkdown(mimeType)) return parseMarkdown(body, defaultKind);
        if (isJson(mimeType)) return parseJson(body, defaultKind);
        if (isYaml(mimeType)) return parseYaml(body, defaultKind);
        throw new KindCodecException("Unsupported mime type for tree: " + mimeType);
    }

    public static String serialize(TreeDocument doc, @Nullable String mimeType) {
        if (isMarkdown(mimeType)) return serializeMarkdown(doc);
        if (isJson(mimeType)) return serializeJson(doc);
        if (isYaml(mimeType)) return serializeYaml(doc);
        throw new KindCodecException("Unsupported mime type for tree: " + mimeType);
    }

    public static boolean supports(@Nullable String mimeType) {
        return isMarkdown(mimeType) || isJson(mimeType) || isYaml(mimeType);
    }

    // ── Mime ────────────────────────────────────────────────────────

    private static boolean isMarkdown(@Nullable String mime) {
        return "text/markdown".equals(mime) || "text/x-markdown".equals(mime);
    }
    private static boolean isJson(@Nullable String mime) {
        return "application/json".equals(mime);
    }
    private static boolean isYaml(@Nullable String mime) {
        return "application/yaml".equals(mime)
                || "application/x-yaml".equals(mime)
                || "text/yaml".equals(mime)
                || "text/x-yaml".equals(mime);
    }

    // ── Markdown ────────────────────────────────────────────────────

    private static final String MD_FENCE = "---";

    private static TreeDocument parseMarkdown(String body, String defaultKind) {
        String[] lines = body.split("\\R", -1);
        int cursor = 0;

        Map<String, Object> extra = new LinkedHashMap<>();
        String kind = "";

        if (lines.length > 0 && MD_FENCE.equals(lines[0].trim())) {
            cursor = 1;
            while (cursor < lines.length && !MD_FENCE.equals(lines[cursor].trim())) {
                String trimmed = lines[cursor].trim();
                cursor++;
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int colon = trimmed.indexOf(':');
                if (colon <= 0) continue;
                String key = trimmed.substring(0, colon).trim();
                String value = trimmed.substring(colon + 1).trim();
                if ("kind".equals(key)) kind = value;
                else extra.put(key, value);
            }
            if (cursor < lines.length && MD_FENCE.equals(lines[cursor].trim())) cursor++;
        }

        // Open-levels stack — root list is depth -1; bullets resolve
        // their depth and pop until they land at parent level.
        List<TreeItem> root = new ArrayList<>();
        record OpenLevel(int depth, List<TreeItem> list) {}
        List<OpenLevel> open = new ArrayList<>();
        open.add(new OpenLevel(-1, root));
        TreeItem lastItem = null;
        int lastDepth = -1;

        for (int i = cursor; i < lines.length; i++) {
            String raw = lines[i];
            if (raw.trim().isEmpty()) {
                lastItem = null;
                continue;
            }
            BulletMatch bullet = matchBullet(raw);
            if (bullet != null) {
                int indent = bullet.indent;
                int depth = indent / 2;
                if (depth > lastDepth + 1) depth = lastDepth + 1;
                if (depth < 0) depth = 0;
                while (open.size() > 1 && open.get(open.size() - 1).depth() >= depth) {
                    open.remove(open.size() - 1);
                }
                List<TreeItem> parentList = open.get(open.size() - 1).list();
                TreeItem item = new TreeItem(bullet.text, new ArrayList<>(), new LinkedHashMap<>());
                parentList.add(item);
                open.add(new OpenLevel(depth, item.children()));
                lastItem = item;
                lastDepth = depth;
                continue;
            }
            // Continuation: leading whitespace, not a bullet
            if (lastItem != null && raw.startsWith(" ")) {
                int contIndent = countIndent(leadingWhitespace(raw));
                if (contIndent >= (lastDepth + 1) * 2) {
                    String stripped = raw.replaceFirst("^\\s+", "");
                    // Records can't mutate; rebuild the item with
                    // appended text and replace it in the parent
                    // list at the same position.
                    TreeItem updated = new TreeItem(
                            lastItem.text() + "\n" + stripped,
                            lastItem.children(),
                            lastItem.extra());
                    replaceLast(open.get(open.size() - 2).list(), lastItem, updated);
                    open.remove(open.size() - 1);
                    open.add(new OpenLevel(lastDepth, updated.children()));
                    lastItem = updated;
                }
            }
        }

        return new TreeDocument(kind.isEmpty() ? defaultKind : kind, root, extra);
    }

    private record BulletMatch(int indent, String text) {}

    private static @Nullable BulletMatch matchBullet(String raw) {
        int indent = 0;
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == ' ') indent++;
            else if (c == '\t') indent += 4;
            else break;
            i++;
        }
        if (i + 1 >= raw.length()) return null;
        char marker = raw.charAt(i);
        if ((marker != '-' && marker != '*') || raw.charAt(i + 1) != ' ') return null;
        return new BulletMatch(indent, raw.substring(i + 2));
    }

    private static String leadingWhitespace(String raw) {
        int i = 0;
        while (i < raw.length() && (raw.charAt(i) == ' ' || raw.charAt(i) == '\t')) i++;
        return raw.substring(0, i);
    }

    private static int countIndent(String prefix) {
        int n = 0;
        for (int i = 0; i < prefix.length(); i++) {
            char c = prefix.charAt(i);
            if (c == ' ') n++;
            else if (c == '\t') n += 4;
        }
        return n;
    }

    private static void replaceLast(List<TreeItem> list, TreeItem oldItem, TreeItem newItem) {
        int idx = list.lastIndexOf(oldItem);
        if (idx >= 0) list.set(idx, newItem);
    }

    private static String serializeMarkdown(TreeDocument doc) {
        StringBuilder out = new StringBuilder();
        out.append(MD_FENCE).append('\n');
        out.append("kind: ").append(canonicalKind(doc, "tree")).append('\n');
        for (Map.Entry<String, Object> e : doc.extra().entrySet()) {
            out.append(e.getKey()).append(": ").append(stringifyMdExtra(e.getValue())).append('\n');
        }
        out.append(MD_FENCE).append('\n');
        emitMdItems(doc.items(), 0, out);
        return out.toString();
    }

    private static void emitMdItems(List<TreeItem> items, int depth, StringBuilder out) {
        String indent = "  ".repeat(depth);
        for (TreeItem item : items) {
            String[] textLines = item.text().split("\n", -1);
            out.append(indent).append("- ").append(textLines.length > 0 ? textLines[0] : "").append('\n');
            for (int i = 1; i < textLines.length; i++) {
                out.append(indent).append("  ").append(textLines[i]).append('\n');
            }
            if (!item.children().isEmpty()) {
                emitMdItems(item.children(), depth + 1, out);
            }
        }
    }

    private static String stringifyMdExtra(@Nullable Object value) {
        if (value == null) return "";
        if (value instanceof String s) return s;
        return String.valueOf(value);
    }

    // ── JSON ────────────────────────────────────────────────────────

    private static TreeDocument parseJson(String body, String defaultKind) {
        if (body.isBlank()) return TreeDocument.empty();
        Map<String, Object> parsed;
        try {
            parsed = JSON.readValue(body, JSON_MAP);
        } catch (JacksonException e) {
            throw new KindCodecException("Invalid JSON: " + e.getOriginalMessage(), e);
        }
        if (parsed == null) throw new KindCodecException("Top-level JSON must be an object");
        return promoteToDocument(KindHeaderCodec.unwrapJsonMeta(parsed), defaultKind);
    }

    private static String serializeJson(TreeDocument doc) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", itemsToList(doc.items()));
        body.putAll(doc.extra());
        Map<String, Object> wrapped = KindHeaderCodec.wrapJsonMeta(canonicalKind(doc, "tree"), body);
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(wrapped) + "\n";
        } catch (JacksonException e) {
            throw new KindCodecException("Failed to write JSON: " + e.getOriginalMessage(), e);
        }
    }

    // ── YAML ────────────────────────────────────────────────────────

    private static TreeDocument parseYaml(String body, String defaultKind) {
        if (body.isBlank()) return TreeDocument.empty();
        Map<String, Object> merged = KindHeaderCodec.mergeYamlMultiDoc(body);
        return promoteToDocument(merged, defaultKind);
    }

    private static String serializeYaml(TreeDocument doc) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", itemsToList(doc.items()));
        body.putAll(doc.extra());
        return KindHeaderCodec.dumpYamlMultiDoc(canonicalKind(doc, "tree"), body);
    }

    // ── Promotion ───────────────────────────────────────────────────

    private static TreeDocument promoteToDocument(Map<String, Object> obj, String defaultKind) {
        Object kindRaw = obj.get("kind");
        String kind = (kindRaw instanceof String s) ? s : "";
        Object itemsRaw = obj.get("items");
        List<TreeItem> items = promoteItems(itemsRaw);
        Map<String, Object> extra = new LinkedHashMap<>(obj);
        extra.remove("kind");
        extra.remove("items");
        return new TreeDocument(kind.isEmpty() ? defaultKind : kind, items, extra);
    }

    private static List<TreeItem> promoteItems(@Nullable Object raw) {
        List<TreeItem> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return out;
        for (Object r : list) {
            if (!(r instanceof Map<?, ?> map)) continue; // string-shorthand not allowed for tree
            Object textRaw = map.get("text");
            String text = (textRaw instanceof String s) ? s : "";
            List<TreeItem> children = promoteItems(map.get("children"));
            Map<String, Object> extra = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!(e.getKey() instanceof String key)) continue;
                if ("text".equals(key) || "children".equals(key)) continue;
                extra.put(key, e.getValue());
            }
            out.add(new TreeItem(text, children, extra));
        }
        return out;
    }

    private static List<Map<String, Object>> itemsToList(List<TreeItem> items) {
        List<Map<String, Object>> out = new ArrayList<>(items.size());
        for (TreeItem item : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("text", item.text());
            m.put("children", itemsToList(item.children()));
            m.putAll(item.extra());
            out.add(m);
        }
        return out;
    }

    private static String canonicalKind(TreeDocument doc, String fallback) {
        return (doc.kind() == null || doc.kind().isBlank()) ? fallback : doc.kind();
    }
}
