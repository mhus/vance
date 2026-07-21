package de.mhus.vance.shared.document.kind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Parser and serialiser for {@code kind: diagram} document bodies.
 * Three on-disk formats: Markdown (canonical, one fenced source block),
 * JSON, and YAML. The source string itself is opaque to the codec —
 * Mermaid (or whichever dialect) parses it at render time.
 *
 * <p>Markdown round-trip: text before the fence is captured in
 * {@code extra._preamble}, text after in {@code extra._postamble}, and
 * any additional fences in {@code extra._unparsedBody}. All three are
 * re-emitted verbatim on write.
 *
 * <p>Front-matter is parsed as YAML (not flat key:value) so the
 * {@code diagram:} nested block survives. The server-side
 * {@link de.mhus.vance.shared.document.MarkdownHeaderStrategy} only
 * needs {@code kind:} for routing — it reads the nested lines as flat
 * pass-through values, which is harmless.
 *
 * <p>Spec: {@code specification/doc-kind-diagram.md}.
 *
 * <p><b>Parity harness.</b> This codec and its TS twin
 * {@code client/packages/vance-face/src/document/diagramCodec.ts} must agree on the wire
 * format. A shared fixture corpus at
 * {@code test-fixtures/kind-codecs/diagram/} pins that agreement; it
 * is read by both {@code DiagramCodecParityTest} (Java) and
 * {@code diagramCodec.parity.test.ts} (TS). Edit the codec and the
 * corpus together.
 */
public final class DiagramCodec {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> JSON_MAP =
            new TypeReference<>() {};

    /** Reserved keys in {@link DiagramDocument#extra()} carrying the
     *  text fragments that live around the source fence in markdown
     *  bodies. Only meaningful for the markdown serialiser. */
    public static final String EXTRA_PREAMBLE = "_preamble";
    public static final String EXTRA_POSTAMBLE = "_postamble";
    public static final String EXTRA_UNPARSED_BODY = "_unparsedBody";

    private static final String MD_FENCE = "---";
    private static final Pattern CODE_FENCE_OPEN =
            Pattern.compile("^(`{3,}|~{3,})\\s*([A-Za-z0-9_-]*)\\s*$");

    private DiagramCodec() {
        // utility class
    }

    // ── Public API ─────────────────────────────────────────────────

    public static DiagramDocument parse(String body, @Nullable String mimeType) {
        if (isMarkdown(mimeType)) return parseMarkdown(body);
        if (isJson(mimeType)) return parseJson(body);
        if (isYaml(mimeType)) return parseYaml(body);
        throw new KindCodecException("Unsupported mime type for diagram: " + mimeType);
    }

    public static String serialize(DiagramDocument doc, @Nullable String mimeType) {
        if (isMarkdown(mimeType)) return serializeMarkdown(doc);
        if (isJson(mimeType)) return serializeJson(doc);
        if (isYaml(mimeType)) return serializeYaml(doc);
        throw new KindCodecException("Unsupported mime type for diagram: " + mimeType);
    }

    public static boolean supports(@Nullable String mimeType) {
        return isMarkdown(mimeType) || isJson(mimeType) || isYaml(mimeType);
    }

    // ── Mime ───────────────────────────────────────────────────────

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

    // ── Markdown ───────────────────────────────────────────────────

    private static DiagramDocument parseMarkdown(String body) {
        if (body.isBlank()) return DiagramDocument.empty();

        String[] lines = body.split("\\R", -1);
        int cursor = 0;

        Map<String, Object> frontMatter = new LinkedHashMap<>();
        if (lines.length > 0 && MD_FENCE.equals(lines[0].trim())) {
            int end = -1;
            for (int i = 1; i < lines.length; i++) {
                if (MD_FENCE.equals(lines[i].trim())) {
                    end = i;
                    break;
                }
            }
            if (end > 0) {
                StringBuilder fm = new StringBuilder();
                for (int i = 1; i < end; i++) fm.append(lines[i]).append('\n');
                frontMatter = parseFrontMatterYaml(fm.toString());
                cursor = end + 1;
            }
            // Unterminated front-matter: treat the whole body as content
            // (cursor stays at 0). Matches the lenient behaviour of the
            // other codecs that just skip the broken fence.
        }

        String kind = stringOr(frontMatter.get("kind"), "");
        String dialect = stringOr(frontMatter.get("dialect"), DiagramDocument.DEFAULT_DIALECT);
        DiagramHeader header = promoteHeader(frontMatter.get("diagram"));

        // Scan body for the first fenced block matching the active
        // dialect. Anything before it is preamble, anything after is
        // postamble. Additional fences go to _unparsedBody so the
        // round-trip stays stable even if the user accidentally pasted
        // more than one diagram.
        FenceScan scan = scanFences(lines, cursor, dialect);

        Map<String, Object> extra = new LinkedHashMap<>();
        // Pass through unknown front-matter keys (anything that isn't
        // kind/dialect/diagram). The MarkdownHeaderStrategy would have
        // already seen them flat — they live in the document model so
        // a round-trip preserves them.
        for (Map.Entry<String, Object> e : frontMatter.entrySet()) {
            if ("kind".equals(e.getKey())
                    || "dialect".equals(e.getKey())
                    || "diagram".equals(e.getKey())) continue;
            extra.put(e.getKey(), e.getValue());
        }
        if (!scan.preamble.isEmpty()) extra.put(EXTRA_PREAMBLE, scan.preamble);
        if (!scan.postamble.isEmpty()) extra.put(EXTRA_POSTAMBLE, scan.postamble);
        if (!scan.unparsedBody.isEmpty()) extra.put(EXTRA_UNPARSED_BODY, scan.unparsedBody);

        return new DiagramDocument(
                kind.isEmpty() ? "diagram" : kind,
                dialect,
                header,
                scan.source,
                extra);
    }

    private record FenceScan(String preamble, String source, String postamble, String unparsedBody) {}

    private static FenceScan scanFences(String[] lines, int from, String dialect) {
        StringBuilder preamble = new StringBuilder();
        StringBuilder source = new StringBuilder();
        StringBuilder postamble = new StringBuilder();
        StringBuilder unparsed = new StringBuilder();

        // States: 0 = before first fence, 1 = inside first fence
        // (collecting source), 2 = after first fence closes.
        int state = 0;
        String openFence = null;
        boolean firstFenceMatchedDialect = false;

        for (int i = from; i < lines.length; i++) {
            String line = lines[i];
            if (state == 0) {
                Matcher m = CODE_FENCE_OPEN.matcher(line);
                if (m.matches()) {
                    String fenceMark = m.group(1);
                    String info = m.group(2);
                    if (info.isEmpty() || info.equals(dialect)) {
                        // Treat empty-info fence as the dialect fence —
                        // common when the LLM forgets the info string
                        // but only emits one block; better to render
                        // it than to drop everything.
                        state = 1;
                        openFence = fenceMark;
                        firstFenceMatchedDialect = !info.isEmpty();
                        continue;
                    }
                    // Different dialect: treat as preamble content
                    // (escaped fence + content + closing fence will all
                    // land in preamble). Falls through to append below.
                }
                if (preamble.length() > 0) preamble.append('\n');
                preamble.append(line);
            } else if (state == 1) {
                if (line.startsWith(openFence) && line.substring(openFence.length()).trim().isEmpty()) {
                    state = 2;
                    openFence = null;
                    continue;
                }
                if (source.length() > 0) source.append('\n');
                source.append(line);
            } else { // state == 2
                Matcher m = CODE_FENCE_OPEN.matcher(line);
                if (m.matches()) {
                    // Additional fence: capture it (and its content,
                    // and its closing) verbatim into unparsedBody.
                    String mark = m.group(1);
                    int closeIdx = -1;
                    for (int j = i + 1; j < lines.length; j++) {
                        String l2 = lines[j];
                        if (l2.startsWith(mark) && l2.substring(mark.length()).trim().isEmpty()) {
                            closeIdx = j;
                            break;
                        }
                    }
                    if (closeIdx < 0) closeIdx = lines.length - 1;
                    for (int j = i; j <= closeIdx; j++) {
                        if (unparsed.length() > 0) unparsed.append('\n');
                        unparsed.append(lines[j]);
                    }
                    i = closeIdx;
                    continue;
                }
                if (postamble.length() > 0) postamble.append('\n');
                postamble.append(line);
            }
        }

        // Trailing blank lines in preamble / postamble are aesthetic
        // separators, not content — strip them so the round-trip
        // doesn't accumulate them.
        String pre = trimTrailingBlankLines(preamble.toString());
        String post = trimSurroundingBlankLines(postamble.toString());

        // If the source ended unterminated (state == 1 still), treat
        // what we collected as the source — Mermaid will error out at
        // render time which is the desired surfacing path.
        String src = source.toString();
        if (firstFenceMatchedDialect || !src.isBlank()) {
            // strip exactly one trailing newline that the join logic
            // leaves behind (the line before the closing fence).
            if (src.endsWith("\n")) src = src.substring(0, src.length() - 1);
        }

        return new FenceScan(pre, src, post, unparsed.toString());
    }

    private static String trimTrailingBlankLines(String s) {
        int end = s.length();
        while (end > 0) {
            int prevBreak = s.lastIndexOf('\n', end - 1);
            String tail = s.substring(prevBreak + 1, end);
            if (!tail.isBlank()) break;
            end = prevBreak < 0 ? 0 : prevBreak;
        }
        return s.substring(0, end);
    }

    private static String trimSurroundingBlankLines(String s) {
        int start = 0;
        while (start < s.length()) {
            int nextBreak = s.indexOf('\n', start);
            int lineEnd = nextBreak < 0 ? s.length() : nextBreak;
            if (!s.substring(start, lineEnd).isBlank()) break;
            start = nextBreak < 0 ? s.length() : nextBreak + 1;
        }
        return trimTrailingBlankLines(s.substring(start));
    }

    private static String serializeMarkdown(DiagramDocument doc) {
        StringBuilder out = new StringBuilder();

        // Front-matter — always emit kind; emit dialect and diagram
        // block only when they diverge from defaults.
        out.append(MD_FENCE).append('\n');
        out.append("kind: ").append(canonicalKind(doc)).append('\n');
        if (!DiagramDocument.DEFAULT_DIALECT.equals(doc.dialect())) {
            out.append("dialect: ").append(doc.dialect()).append('\n');
        }
        if (!doc.diagram().isDefault()) {
            Map<String, Object> headerMap = headerToMap(doc.diagram());
            String dumped = dumpYamlFragment(Map.of("diagram", headerMap));
            // dumpYamlFragment includes a trailing newline already.
            out.append(dumped);
        }
        // Other pass-through front-matter keys (anything in extra that
        // isn't a reserved markdown roundtrip key).
        for (Map.Entry<String, Object> e : doc.extra().entrySet()) {
            String key = e.getKey();
            if (EXTRA_PREAMBLE.equals(key) || EXTRA_POSTAMBLE.equals(key)
                    || EXTRA_UNPARSED_BODY.equals(key)) continue;
            Object v = e.getValue();
            if (v instanceof String || v instanceof Number || v instanceof Boolean) {
                out.append(key).append(": ").append(v).append('\n');
            } else {
                // Non-scalar pass-through: emit as YAML fragment.
                out.append(dumpYamlFragment(Map.of(key, v)));
            }
        }
        out.append(MD_FENCE).append('\n');

        String preamble = stringOr(doc.extra().get(EXTRA_PREAMBLE), "");
        String postamble = stringOr(doc.extra().get(EXTRA_POSTAMBLE), "");
        String unparsed = stringOr(doc.extra().get(EXTRA_UNPARSED_BODY), "");

        if (!preamble.isEmpty()) {
            out.append('\n').append(preamble).append('\n');
        }

        out.append('\n').append("```").append(doc.dialect()).append('\n');
        out.append(doc.source());
        if (!doc.source().endsWith("\n")) out.append('\n');
        out.append("```").append('\n');

        if (!postamble.isEmpty()) {
            out.append('\n').append(postamble).append('\n');
        }
        if (!unparsed.isEmpty()) {
            out.append('\n').append(unparsed).append('\n');
        }

        return out.toString();
    }

    // ── JSON ───────────────────────────────────────────────────────

    private static DiagramDocument parseJson(String body) {
        if (body.isBlank()) return DiagramDocument.empty();
        Map<String, Object> parsed;
        try {
            parsed = JSON.readValue(body, JSON_MAP);
        } catch (JacksonException e) {
            throw new KindCodecException("Invalid JSON: " + e.getOriginalMessage(), e);
        }
        if (parsed == null) throw new KindCodecException("Top-level JSON must be an object");
        return promoteToDocument(KindHeaderCodec.unwrapJsonMeta(parsed));
    }

    private static String serializeJson(DiagramDocument doc) {
        Map<String, Object> wrapped = KindHeaderCodec.wrapJsonMeta(canonicalKind(doc), buildStructuredBody(doc));
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(wrapped) + "\n";
        } catch (JacksonException e) {
            throw new KindCodecException("Failed to write JSON: " + e.getOriginalMessage(), e);
        }
    }

    // ── YAML ───────────────────────────────────────────────────────

    private static DiagramDocument parseYaml(String body) {
        if (body.isBlank()) return DiagramDocument.empty();
        return promoteToDocument(KindHeaderCodec.parseYamlBody(body));
    }

    private static String serializeYaml(DiagramDocument doc) {
        return KindHeaderCodec.dumpYamlBody(canonicalKind(doc), buildStructuredBody(doc));
    }

    // ── Promotion (JSON / YAML) ────────────────────────────────────

    private static DiagramDocument promoteToDocument(Map<String, Object> obj) {
        String kind = stringOr(obj.get("kind"), "");
        String dialect = stringOr(obj.get("dialect"), DiagramDocument.DEFAULT_DIALECT);
        DiagramHeader header = promoteHeader(obj.get("diagram"));
        String source = stringOr(obj.get("source"), "");

        Map<String, Object> extra = new LinkedHashMap<>(obj);
        extra.remove("kind");
        extra.remove("dialect");
        extra.remove("diagram");
        extra.remove("source");

        return new DiagramDocument(
                kind.isEmpty() ? "diagram" : kind,
                dialect,
                header,
                source,
                extra);
    }

    private static DiagramHeader promoteHeader(@Nullable Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return DiagramHeader.defaults();
        DiagramTheme theme = DiagramTheme.DEFAULT;
        if (map.get("theme") instanceof String ts) {
            DiagramTheme parsed = DiagramTheme.fromWire(ts);
            if (parsed != null) theme = parsed;
            // Unknown theme → silently clamp to default. The spec calls
            // this out as a codec warning; we don't have a structured
            // warning channel here, so the canonical-on-write contract
            // does the surfacing (the file looks "different" after save
            // if a bad theme came in).
        }
        DiagramLook look = DiagramLook.CLASSIC;
        if (map.get("look") instanceof String ls) {
            DiagramLook parsed = DiagramLook.fromWire(ls);
            if (parsed != null) look = parsed;
        }
        String font = (map.get("fontFamily") instanceof String fs && !fs.isEmpty()) ? fs : null;

        Map<String, Object> extra = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!(e.getKey() instanceof String k)) continue;
            if ("theme".equals(k) || "look".equals(k) || "fontFamily".equals(k)) continue;
            extra.put(k, e.getValue());
        }
        return new DiagramHeader(theme, look, font, extra);
    }

    // ── Body builders ──────────────────────────────────────────────

    /** Used for JSON / YAML serialisation. The markdown serialiser
     *  builds its own structure via {@link #serializeMarkdown}. */
    private static Map<String, Object> buildStructuredBody(DiagramDocument doc) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (!DiagramDocument.DEFAULT_DIALECT.equals(doc.dialect())) {
            body.put("dialect", doc.dialect());
        }
        if (!doc.diagram().isDefault()) {
            body.put("diagram", headerToMap(doc.diagram()));
        }
        body.put("source", doc.source());
        // Reserved markdown-roundtrip keys travel through json/yaml too —
        // keeps a md→json→md trip stable.
        for (Map.Entry<String, Object> e : doc.extra().entrySet()) {
            if (!body.containsKey(e.getKey())) body.put(e.getKey(), e.getValue());
        }
        return body;
    }

    private static Map<String, Object> headerToMap(DiagramHeader h) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (h.theme() != DiagramTheme.DEFAULT) m.put("theme", h.theme().wire());
        if (h.look() != DiagramLook.CLASSIC) m.put("look", h.look().wire());
        if (h.fontFamily() != null) m.put("fontFamily", h.fontFamily());
        for (Map.Entry<String, Object> e : h.extra().entrySet()) {
            if (!m.containsKey(e.getKey())) m.put(e.getKey(), e.getValue());
        }
        return m;
    }

    // ── YAML helpers ──────────────────────────────────────────────

    private static Map<String, Object> parseFrontMatterYaml(String frontMatter) {
        if (frontMatter.isBlank()) return new LinkedHashMap<>();
        LoaderOptions opts = new LoaderOptions();
        opts.setAllowDuplicateKeys(false);
        Yaml yaml = new Yaml(new SafeConstructor(opts));
        Object root;
        try {
            root = yaml.load(frontMatter);
        } catch (RuntimeException e) {
            // Lenient: a broken front-matter yields an empty map, not a
            // codec exception — diagram is forgiving about the metadata,
            // strict only about the source fence.
            return new LinkedHashMap<>();
        }
        if (!(root instanceof Map<?, ?> map)) return new LinkedHashMap<>();
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getKey() instanceof String k) out.put(k, e.getValue());
        }
        return out;
    }

    private static String dumpYamlFragment(Map<String, Object> data) {
        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setIndent(2);
        opts.setWidth(100);
        opts.setSplitLines(false);
        Yaml yaml = new Yaml(new Representer(opts), opts);
        return yaml.dump(data);
    }

    // ── Misc ───────────────────────────────────────────────────────

    private static String stringOr(@Nullable Object v, String fallback) {
        return (v instanceof String s) ? s : fallback;
    }

    private static String canonicalKind(DiagramDocument doc) {
        return (doc.kind() == null || doc.kind().isBlank()) ? "diagram" : doc.kind();
    }
}
