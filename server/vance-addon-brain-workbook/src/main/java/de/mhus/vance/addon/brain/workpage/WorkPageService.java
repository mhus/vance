package de.mhus.vance.addon.brain.workpage;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.toolpack.ToolException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * High-level operations on {@code kind: workpage} documents — built on top
 * of {@link DocumentService} (no MongoDB collections of its own).
 *
 * <p>All block operations are read-modify-write through the parser /
 * serializer pair. Concurrent edits are handled by
 * {@link DocumentService}'s optimistic-locking (the underlying document
 * has a version field); two near-simultaneous writes resolve as a
 * lost-update on one of them — the live-WS reload picks up the saved
 * state.
 */
@Service
@Slf4j
public class WorkPageService {

    public static final String KIND = "workpage";
    public static final String MIME = "text/markdown";

    private final DocumentService documentService;
    private final WorkPageParser parser;
    private final WorkPageSerializer serializer;
    private final de.mhus.vance.brain.permission.SecurityContextFactory contextFactory;

    public WorkPageService(DocumentService documentService,
                         WorkPageParser parser,
                         WorkPageSerializer serializer,
                         de.mhus.vance.brain.permission.SecurityContextFactory contextFactory) {
        this.documentService = documentService;
        this.parser = parser;
        this.serializer = serializer;
        this.contextFactory = contextFactory;
    }

    // ── Create / read / write ─────────────────────────────────────

    public DocumentDocument create(String tenantId, String projectId, String path,
                                   @Nullable String title,
                                   @Nullable String description,
                                   List<Block> initialBlocks,
                                   @Nullable String userId) {
        String normalisedPath = ensureExtension(path.trim());
        Optional<DocumentDocument> existing = documentService.findByPath(
                tenantId, projectId, normalisedPath);
        if (existing.isPresent()) {
            throw new ToolException(
                    "WorkPage already exists at '" + normalisedPath + "'.");
        }
        WorkPageDocument doc = new WorkPageDocument(title, description,
                initialBlocks == null ? new ArrayList<>() : initialBlocks);
        String body = serializer.serializeDocument(doc);
        try (InputStream in = new java.io.ByteArrayInputStream(
                body.getBytes(StandardCharsets.UTF_8))) {
            DocumentDocument stored = documentService.create(
                    tenantId, projectId, normalisedPath,
                    title, List.of("workpage"), MIME, in, userId,
                    contextFactory.writeActor(tenantId, userId, normalisedPath));
            log.info("WorkPageService.create tenant='{}' project='{}' path='{}'",
                    tenantId, projectId, normalisedPath);
            return stored;
        } catch (IOException e) {
            throw new ToolException(
                    "Could not write workpage '" + normalisedPath + "': " + e.getMessage());
        }
    }

    public WorkPageDocument readDocument(DocumentDocument doc) {
        String body = readBody(doc);
        return parser.parseDocument(body);
    }

    public DocumentDocument writeDocument(DocumentDocument doc, WorkPageDocument page) {
        String body = serializer.serializeDocument(page);
        return documentService.update(
                doc.getId(),
                page.title() != null ? page.title() : doc.getTitle(),
                null, body, null, null, null, null, MIME,
                DocumentService.TOOL_IDENTITY,
                contextFactory.writeActor(doc.getTenantId(), null, doc.getPath()));
    }

    private String readBody(DocumentDocument doc) {
        try (InputStream in = documentService.loadContent(doc)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException(
                    "Could not load workpage '" + doc.getPath() + "': " + e.getMessage());
        }
    }

    // ── Block operations ──────────────────────────────────────────

    public DocumentDocument appendBlock(DocumentDocument doc, Block block) {
        WorkPageDocument page = readDocument(doc);
        List<Block> blocks = new ArrayList<>(page.blocks());
        blocks.add(block);
        return writeDocument(doc, page.withBlocks(blocks));
    }

    public DocumentDocument insertBlock(DocumentDocument doc, BlockAnchor anchor, Block block) {
        WorkPageDocument page = readDocument(doc);
        List<Block> blocks = new ArrayList<>(page.blocks());
        int pos = anchor.resolve(blocks);
        if (pos < 0 || pos > blocks.size()) {
            throw new ToolException("Anchor out of range: " + anchor);
        }
        blocks.add(pos, block);
        return writeDocument(doc, page.withBlocks(blocks));
    }

    public DocumentDocument updateBlock(DocumentDocument doc, BlockAnchor anchor, Block block) {
        WorkPageDocument page = readDocument(doc);
        List<Block> blocks = new ArrayList<>(page.blocks());
        int pos = anchor.resolveExisting(blocks);
        blocks.set(pos, block);
        return writeDocument(doc, page.withBlocks(blocks));
    }

    public DocumentDocument deleteBlock(DocumentDocument doc, BlockAnchor anchor) {
        WorkPageDocument page = readDocument(doc);
        List<Block> blocks = new ArrayList<>(page.blocks());
        int pos = anchor.resolveExisting(blocks);
        blocks.remove(pos);
        return writeDocument(doc, page.withBlocks(blocks));
    }

    public DocumentDocument moveBlock(DocumentDocument doc, BlockAnchor from, int targetIndex) {
        WorkPageDocument page = readDocument(doc);
        List<Block> blocks = new ArrayList<>(page.blocks());
        int src = from.resolveExisting(blocks);
        Block b = blocks.remove(src);
        int dst = Math.max(0, Math.min(blocks.size(), targetIndex));
        blocks.add(dst, b);
        return writeDocument(doc, page.withBlocks(blocks));
    }

    /**
     * Filter the block list. {@code typeFilter} (case-insensitive
     * block-class name, e.g. {@code "Heading"}) and {@code textContains}
     * are AND-combined. Both may be {@code null}.
     */
    public List<Block> query(DocumentDocument doc,
                             @Nullable String typeFilter,
                             @Nullable String textContains) {
        WorkPageDocument page = readDocument(doc);
        String needle = textContains == null ? null
                : textContains.toLowerCase(Locale.ROOT);
        List<Block> out = new ArrayList<>();
        for (Block b : page.blocks()) {
            if (typeFilter != null
                    && !b.getClass().getSimpleName().equalsIgnoreCase(typeFilter)) {
                continue;
            }
            if (needle != null && !blockText(b).toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            out.add(b);
        }
        return out;
    }

    // ── Helpers ───────────────────────────────────────────────────

    private static String ensureExtension(String path) {
        if (path.endsWith(".md")) return path;
        if (path.endsWith(".workpage")) return path + ".md";
        return path + ".workpage.md";
    }

    public static String blockText(Block b) {
        return switch (b) {
            case Block.Paragraph p -> p.text();
            case Block.Heading h -> h.text();
            case Block.BulletList l -> String.join("\n", l.items());
            case Block.NumberedList n -> String.join("\n", n.items());
            case Block.TodoList t -> {
                StringBuilder sb = new StringBuilder();
                for (Block.TodoItem i : t.items()) sb.append(i.text()).append("\n");
                yield sb.toString();
            }
            case Block.Quote q -> q.text();
            case Block.Code c -> c.code();
            case Block.Divider ignored -> "";
            case Block.Image i -> i.alt();
            case Block.Table tbl -> tbl.headers().toString() + tbl.rows().toString();
            case Block.Callout co -> (co.title() == null ? "" : co.title()) + " " + co.body();
            case Block.Toggle tg -> tg.summary() + " " + tg.body();
            case Block.DataviewEmbed dv -> dv.source();
            case Block.LinkCard lc -> lc.href()
                    + (lc.title() == null ? "" : " " + lc.title())
                    + (lc.description() == null ? "" : " " + lc.description());
            case Block.Embed em -> em.uri();
            case Block.Form fo -> fo.data();
            case Block.Input in -> in.data();
            case Block.Button bt -> bt.title() == null ? "" : bt.title();
            case Block.Toc ignored -> "";
            case Block.Columns cols -> {
                StringBuilder sb = new StringBuilder();
                for (Block.Column c : cols.columns()) {
                    for (Block child : c.blocks()) sb.append(blockText(child)).append("\n");
                }
                yield sb.toString();
            }
            case Block.UnknownFence uf -> uf.infoString() + " " + uf.body();
        };
    }

    /**
     * Block-anchor resolved against a current block list. Two flavours —
     * {@code byIndex} (zero-based, may equal {@code size} for insert-at-
     * end) or {@code byHeading} (exact text match against any
     * {@link Block.Heading}; throws if not unique).
     */
    public record BlockAnchor(@Nullable Integer index, @Nullable String heading) {

        public static BlockAnchor at(int index) { return new BlockAnchor(index, null); }

        public static BlockAnchor heading(String text) { return new BlockAnchor(null, text); }

        public static BlockAnchor fromMap(@Nullable Map<String, Object> raw) {
            if (raw == null) {
                throw new ToolException(
                        "anchor required — pass `{ index: N }` or `{ heading: \"...\" }`.");
            }
            Object idx = raw.get("index");
            Object hd = raw.get("heading");
            if (idx instanceof Number n) return at(n.intValue());
            if (hd instanceof String s && !s.isBlank()) return heading(s);
            throw new ToolException(
                    "anchor must contain `index` (number) or `heading` (string).");
        }

        public int resolve(List<Block> blocks) {
            if (index != null) return index;
            return resolveHeading(blocks);
        }

        public int resolveExisting(List<Block> blocks) {
            int pos = resolve(blocks);
            if (pos < 0 || pos >= blocks.size()) {
                throw new ToolException("Anchor points outside block list: " + this);
            }
            return pos;
        }

        private int resolveHeading(List<Block> blocks) {
            int found = -1;
            for (int i = 0; i < blocks.size(); i++) {
                if (blocks.get(i) instanceof Block.Heading h && h.text().equals(heading)) {
                    if (found >= 0) {
                        throw new ToolException(
                                "Heading '" + heading + "' is not unique — use `index` "
                                        + "anchor instead.");
                    }
                    found = i;
                }
            }
            if (found < 0) {
                throw new ToolException("No heading matches '" + heading + "'.");
            }
            return found;
        }

        @Override public String toString() {
            if (index != null) return "index=" + index;
            return "heading='" + heading + "'";
        }
    }

    // ── Block construction from raw param maps ────────────────────

    /**
     * Build a {@link Block} from a generic param map. Used by the
     * LLM-tools — the LLM passes block spec as nested JSON / YAML, this
     * factory unwraps it into a typed record.
     *
     * <p>Required field: {@code type} (case-insensitive). Other fields
     * depend on the type; see {@code workpage-blocks.md} manual for the
     * full grammar.
     */
    @SuppressWarnings("unchecked")
    public static Block buildBlock(Map<String, Object> raw) {
        if (raw == null) throw new ToolException("block is required");
        String type = str(raw, "type");
        if (type == null) throw new ToolException("block.type is required");
        String t = type.toLowerCase(Locale.ROOT);
        return switch (t) {
            case "paragraph" -> new Block.Paragraph(strOrEmpty(raw, "text"));
            case "heading" -> {
                int level = intValue(raw.get("level"), 1);
                if (level < 1 || level > 3) {
                    throw new ToolException("heading.level must be 1, 2 or 3");
                }
                yield new Block.Heading(level, strOrEmpty(raw, "text"));
            }
            case "bullet-list", "bullet_list", "bulletlist" -> new Block.BulletList(strList(raw.get("items")));
            case "numbered-list", "numbered_list", "numberedlist" -> new Block.NumberedList(strList(raw.get("items")));
            case "todo", "todo-list", "todo_list" -> {
                List<Map<String, Object>> items = mapList(raw.get("items"));
                List<Block.TodoItem> out = new ArrayList<>();
                for (Map<String, Object> i : items) {
                    out.add(new Block.TodoItem(
                            boolValue(i.get("checked"), false),
                            strOrEmpty(i, "text")));
                }
                yield new Block.TodoList(out);
            }
            case "quote" -> new Block.Quote(strOrEmpty(raw, "text"));
            case "code" -> new Block.Code(str(raw, "lang"), strOrEmpty(raw, "code"));
            case "divider" -> new Block.Divider();
            case "image" -> new Block.Image(strOrEmpty(raw, "alt"), strOrEmpty(raw, "src"));
            case "table" -> {
                List<String> headers = strList(raw.get("headers"));
                List<List<String>> rows = new ArrayList<>();
                Object rowsRaw = raw.get("rows");
                if (rowsRaw instanceof List<?> rs) {
                    for (Object row : rs) rows.add(strList(row));
                }
                yield new Block.Table(headers, rows);
            }
            case "callout" -> new Block.Callout(
                    strOr(raw, "severity", "info"),
                    str(raw, "title"),
                    strOrEmpty(raw, "body"));
            case "toggle" -> new Block.Toggle(
                    strOrEmpty(raw, "summary"),
                    strOrEmpty(raw, "body"));
            case "dataview", "dataview-embed" -> new Block.DataviewEmbed(strOrEmpty(raw, "source"));
            case "link", "link-card" -> new Block.LinkCard(
                    strOrEmpty(raw, "href"),
                    str(raw, "title"),
                    str(raw, "description"));
            case "embed" -> new Block.Embed(strOrEmpty(raw, "uri"));
            case "form" -> new Block.Form(
                    strOrEmpty(raw, "data"),
                    str(raw, "saveScript"),
                    boolValue(raw.get("session"), false),
                    mapVal(raw.get("form")));
            case "input" -> new Block.Input(
                    strOrEmpty(raw, "data"),
                    boolValue(raw.get("multiline"), false),
                    str(raw, "saveScript"),
                    boolValue(raw.get("session"), false));
            case "button" -> new Block.Button(
                    raw.get("buttonType") != null ? raw.get("buttonType").toString() : "script",
                    strOrEmpty(raw, "script"),
                    str(raw, "title"));
            case "toc", "table-of-contents" -> new Block.Toc();
            case "columns" -> {
                List<Block.Column> cols = new ArrayList<>();
                for (Map<String, Object> cm : mapList(raw.get("columns"))) {
                    List<Block> inner = new ArrayList<>();
                    for (Map<String, Object> bm : mapList(cm.get("blocks"))) {
                        inner.add(buildBlock(bm));
                    }
                    cols.add(new Block.Column(doubleOrNull(cm.get("width")), inner));
                }
                yield new Block.Columns(cols);
            }
            default -> throw new ToolException("Unknown block.type='" + type + "'");
        };
    }

    /** Inverse of {@link #buildBlock} — for surfacing blocks in tool results. */
    public static Map<String, Object> blockToMap(Block b) {
        Map<String, Object> m = new LinkedHashMap<>();
        switch (b) {
            case Block.Paragraph p -> { m.put("type", "paragraph"); m.put("text", p.text()); }
            case Block.Heading h -> { m.put("type", "heading"); m.put("level", h.level()); m.put("text", h.text()); }
            case Block.BulletList l -> { m.put("type", "bullet-list"); m.put("items", l.items()); }
            case Block.NumberedList n -> { m.put("type", "numbered-list"); m.put("items", n.items()); }
            case Block.TodoList t -> {
                m.put("type", "todo");
                List<Map<String, Object>> items = new ArrayList<>();
                for (Block.TodoItem i : t.items()) {
                    Map<String, Object> im = new LinkedHashMap<>();
                    im.put("checked", i.checked());
                    im.put("text", i.text());
                    items.add(im);
                }
                m.put("items", items);
            }
            case Block.Quote q -> { m.put("type", "quote"); m.put("text", q.text()); }
            case Block.Code c -> {
                m.put("type", "code");
                if (c.lang() != null) m.put("lang", c.lang());
                m.put("code", c.code());
            }
            case Block.Divider ignored -> m.put("type", "divider");
            case Block.Image i -> { m.put("type", "image"); m.put("alt", i.alt()); m.put("src", i.src()); }
            case Block.Table tbl -> {
                m.put("type", "table");
                m.put("headers", tbl.headers());
                m.put("rows", tbl.rows());
            }
            case Block.Callout co -> {
                m.put("type", "callout");
                m.put("severity", co.severity());
                if (co.title() != null) m.put("title", co.title());
                m.put("body", co.body());
            }
            case Block.Toggle tg -> { m.put("type", "toggle"); m.put("summary", tg.summary()); m.put("body", tg.body()); }
            case Block.DataviewEmbed dv -> { m.put("type", "dataview"); m.put("source", dv.source()); }
            case Block.LinkCard lc -> {
                m.put("type", "link-card");
                m.put("href", lc.href());
                if (lc.title() != null) m.put("title", lc.title());
                if (lc.description() != null) m.put("description", lc.description());
            }
            case Block.Embed em -> { m.put("type", "embed"); m.put("uri", em.uri()); }
            case Block.Form fo -> {
                m.put("type", "form");
                m.put("data", fo.data());
                if (fo.saveScript() != null) m.put("saveScript", fo.saveScript());
                if (fo.session()) m.put("session", true);
                if (fo.form() != null) m.put("form", fo.form());
            }
            case Block.Input in -> {
                m.put("type", "input");
                m.put("data", in.data());
                m.put("multiline", in.multiline());
                if (in.saveScript() != null) m.put("saveScript", in.saveScript());
                if (in.session()) m.put("session", true);
            }
            case Block.Button bt -> {
                m.put("type", "button");
                m.put("buttonType", bt.buttonType());
                if (bt.title() != null) m.put("title", bt.title());
                m.put("script", bt.script());
            }
            case Block.Toc ignored -> m.put("type", "toc");
            case Block.Columns cols -> {
                m.put("type", "columns");
                List<Map<String, Object>> colOut = new ArrayList<>();
                for (Block.Column c : cols.columns()) {
                    Map<String, Object> cm = new LinkedHashMap<>();
                    if (c.width() != null) cm.put("width", c.width());
                    List<Map<String, Object>> inner = new ArrayList<>();
                    for (Block child : c.blocks()) inner.add(blockToMap(child));
                    cm.put("blocks", inner);
                    colOut.add(cm);
                }
                m.put("columns", colOut);
            }
            case Block.UnknownFence uf -> {
                m.put("type", "unknown-fence");
                m.put("infoString", uf.infoString());
                m.put("body", uf.body());
            }
        }
        return m;
    }

    private static @Nullable Double doubleOrNull(@Nullable Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) {
            try { return Double.parseDouble(s.trim()); } catch (NumberFormatException ignored) { /* skipped */ }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static @Nullable String str(Map<String, Object> raw, String key) {
        Object v = raw.get(key);
        if (v instanceof String s && !s.isBlank()) return s;
        if (v != null && !(v instanceof String)) return v.toString();
        return null;
    }

    private static String strOr(Map<String, Object> raw, String key, String fallback) {
        String s = str(raw, key);
        return s == null ? fallback : s;
    }

    private static String strOrEmpty(Map<String, Object> raw, String key) {
        String s = str(raw, key);
        return s == null ? "" : s;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(@Nullable Object raw) {
        if (!(raw instanceof List<?> list)) return new ArrayList<>();
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            if (o == null) out.add("");
            else out.add(o.toString());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(@Nullable Object raw) {
        if (!(raw instanceof List<?> list)) return new ArrayList<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> mm) {
                Map<String, Object> m = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : mm.entrySet()) {
                    if (e.getKey() != null) m.put(e.getKey().toString(), e.getValue());
                }
                out.add(m);
            }
        }
        return out;
    }

    private static @Nullable Map<String, Object> mapVal(@Nullable Object raw) {
        if (!(raw instanceof Map<?, ?> mm)) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : mm.entrySet()) {
            if (e.getKey() != null) m.put(e.getKey().toString(), e.getValue());
        }
        return m;
    }

    private static int intValue(@Nullable Object o, int fallback) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { /* skipped */ }
        }
        return fallback;
    }

    private static boolean boolValue(@Nullable Object o, boolean fallback) {
        if (o instanceof Boolean b) return b;
        if (o instanceof String s) return Boolean.parseBoolean(s);
        return fallback;
    }
}
