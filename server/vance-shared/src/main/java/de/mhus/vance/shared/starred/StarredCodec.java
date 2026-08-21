package de.mhus.vance.shared.starred;

import de.mhus.vance.shared.document.kind.KindCodecException;
import de.mhus.vance.shared.document.kind.KindHeaderCodec;
import de.mhus.vance.shared.document.kind.validate.Finding;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Wire form of the {@code vance-starred} control file. YAML only — the file is
 * Vance's own configuration, written by the server and edited by hand, and a
 * second format would only add a way for the two to disagree.
 *
 * <pre>
 * $meta:
 *   kind: vance-starred
 * items:
 *   - project: _user_mhu
 *     path: links/_app.yaml
 *     kind: application
 *     type: links
 *     title: My links
 *     hidden: true
 * </pre>
 *
 * <p>Two properties this codec must have at once, because two very different
 * callers use it:
 *
 * <ul>
 *   <li><b>Lenient on read.</b> {@link #parse} never throws. A broken entry is
 *       skipped and reported as a {@link Finding}; a file a human mistyped must
 *       not take down the landing page <em>and</em> the "send to" menu at the
 *       same time.</li>
 *   <li><b>Round-trip safe on write.</b> Unknown top-level keys and unknown
 *       per-entry keys survive {@link #parse} → {@link #serialize}. The server
 *       owns four fields of an entry and must not touch the rest — including
 *       fields it has never heard of.</li>
 * </ul>
 *
 * <p>Defaults are omitted when serialising ({@code highlight: false},
 * {@code enabled: true}, {@code hidden: false}): the file stays scannable, and a
 * star toggle produces the smallest possible diff. The knobs are documented in
 * the manual, not by writing three lines of noise per entry.
 */
public final class StarredCodec {

    /**
     * Document kind. Member of the {@code vance-*} family that types Vance's own
     * configuration documents, alongside {@code vance-workflow}.
     */
    public static final String KIND = "vance-starred";

    private static final String ITEMS_KEY = "items";

    private static final String F_PROJECT = "project";
    private static final String F_PATH = "path";
    private static final String F_KIND = "kind";
    private static final String F_TYPE = "type";
    private static final String F_TITLE = "title";
    private static final String F_DESCRIPTION = "description";
    private static final String F_HIGHLIGHT = "highlight";
    private static final String F_ENABLED = "enabled";
    private static final String F_HIDDEN = "hidden";

    private static final List<String> KNOWN_FIELDS = List.of(
            F_PROJECT, F_PATH, F_KIND, F_TYPE, F_TITLE, F_DESCRIPTION,
            F_HIGHLIGHT, F_ENABLED, F_HIDDEN);

    private StarredCodec() {
        // utility class
    }

    /** Outcome of a parse: what could be read, and what was wrong with the rest. */
    public record Result(StarredDocument document, List<Finding> findings) {
        public Result {
            findings = List.copyOf(findings);
        }
    }

    /**
     * Parse a control file. Never throws.
     *
     * @param location finding location prefix — the document path, or the kind
     *                 name when the caller has no path (validation of unsaved
     *                 content).
     */
    public static Result parse(@Nullable String body, String location) {
        if (body == null || body.isBlank()) {
            return new Result(StarredDocument.empty(), List.of());
        }

        Map<String, Object> top;
        try {
            top = KindHeaderCodec.parseYamlBody(body);
        } catch (KindCodecException e) {
            return new Result(StarredDocument.empty(), List.of(Finding.error(
                    location, KIND + "-parse", "not valid YAML: " + e.getMessage())));
        }

        List<Finding> findings = new ArrayList<>();
        Object rawItems = top.get(ITEMS_KEY);
        List<StarredItem> items = new ArrayList<>();

        List<?> list;
        if (rawItems == null) {
            // An empty control file is a legitimate state, not a defect.
            list = List.of();
        } else if (rawItems instanceof List<?> l) {
            list = l;
        } else {
            findings.add(Finding.error(location, KIND + "-items",
                    "`items` must be a list, found " + typeName(rawItems)));
            list = List.of();
        }

        int index = 0;
        for (Object raw : list) {
            String at = location + "#items[" + index + "]";
            index++;
            if (!(raw instanceof Map<?, ?> map)) {
                findings.add(Finding.error(at, KIND + "-entry",
                        "entry must be a mapping, found " + typeName(raw)));
                continue;
            }
            StarredItem item = readItem(map, at, findings);
            if (item != null) items.add(item);
        }

        // Everything that is not `items` (and not the header, which
        // parseYamlBody already lifted out) is carried through untouched.
        Map<String, Object> extra = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : top.entrySet()) {
            if (ITEMS_KEY.equals(e.getKey())) continue;
            if (F_KIND.equals(e.getKey())) continue; // came from $meta
            extra.put(e.getKey(), e.getValue());
        }

        return new Result(new StarredDocument(items, extra), findings);
    }

    /** Convenience overload for callers that only want the content. */
    public static StarredDocument parseLenient(@Nullable String body) {
        return parse(body, KIND).document();
    }

    public static String serialize(StarredDocument doc) {
        List<Object> items = new ArrayList<>(doc.items().size());
        for (StarredItem item : doc.items()) {
            items.add(writeItem(item));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(ITEMS_KEY, items);
        for (Map.Entry<String, Object> e : doc.extra().entrySet()) {
            if (!body.containsKey(e.getKey())) body.put(e.getKey(), e.getValue());
        }
        return KindHeaderCodec.dumpYamlBody(KIND, body);
    }

    // ── Entry level ─────────────────────────────────────────────────────

    private static @Nullable StarredItem readItem(
            Map<?, ?> map, String at, List<Finding> findings) {

        String project = string(map.get(F_PROJECT));
        String path = string(map.get(F_PATH));
        if (project == null || project.isBlank()) {
            findings.add(Finding.error(at, KIND + "-entry", "missing `project`"));
            return null;
        }
        if (path == null || path.isBlank()) {
            findings.add(Finding.error(at, KIND + "-entry", "missing `path`"));
            return null;
        }

        String kind = string(map.get(F_KIND));
        if (kind == null || kind.isBlank()) {
            // Recoverable: the kind is a server-written cache, and the fallback
            // is what an unheadered document would resolve to anyway. Reported
            // so a reconcile is the obvious next step, but the tile still works.
            findings.add(Finding.warning(at, KIND + "-entry",
                    "missing `kind` — assuming '" + StarredItem.DEFAULT_KIND
                            + "'; run a reconcile to refresh it"));
            kind = StarredItem.DEFAULT_KIND;
        }

        Map<String, Object> extra = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!(e.getKey() instanceof String key)) continue;
            if (KNOWN_FIELDS.contains(key)) continue;
            extra.put(key, e.getValue());
        }

        return StarredItem.builder()
                .project(project)
                .path(path)
                .kind(kind)
                .type(blankToNull(string(map.get(F_TYPE))))
                .title(blankToNull(string(map.get(F_TITLE))))
                .description(blankToNull(string(map.get(F_DESCRIPTION))))
                .highlight(bool(map.get(F_HIGHLIGHT), false, at, F_HIGHLIGHT, findings))
                .enabled(bool(map.get(F_ENABLED), true, at, F_ENABLED, findings))
                .hidden(bool(map.get(F_HIDDEN), false, at, F_HIDDEN, findings))
                .extra(extra)
                .build();
    }

    private static Map<String, Object> writeItem(StarredItem item) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(F_PROJECT, item.project());
        out.put(F_PATH, item.path());
        out.put(F_KIND, item.kind());
        if (item.type() != null) out.put(F_TYPE, item.type());
        if (item.title() != null) out.put(F_TITLE, item.title());
        if (item.description() != null) out.put(F_DESCRIPTION, item.description());
        if (item.highlight()) out.put(F_HIGHLIGHT, true);
        if (!item.enabled()) out.put(F_ENABLED, false);
        if (item.hidden()) out.put(F_HIDDEN, true);
        for (Map.Entry<String, Object> e : item.extra().entrySet()) {
            if (!out.containsKey(e.getKey())) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    // ── Scalars ─────────────────────────────────────────────────────────

    private static @Nullable String string(@Nullable Object v) {
        if (v == null) return null;
        if (v instanceof String s) return s;
        if (v instanceof Number || v instanceof Boolean) return v.toString();
        return null;
    }

    private static @Nullable String blankToNull(@Nullable String v) {
        return (v == null || v.isBlank()) ? null : v;
    }

    /**
     * Read a boolean switch. A missing key takes the default silently; a value
     * that is neither boolean nor {@code "true"}/{@code "false"} is reported and
     * falls back — a typo in {@code enabled} must not silently disable an entry
     * (nor silently enable one).
     */
    private static boolean bool(
            @Nullable Object v, boolean defaultValue,
            String at, String field, List<Finding> findings) {
        if (v == null) return defaultValue;
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) {
            if ("true".equalsIgnoreCase(s.trim())) return true;
            if ("false".equalsIgnoreCase(s.trim())) return false;
        }
        findings.add(Finding.warning(at, KIND + "-entry",
                "`" + field + "` must be true or false, found " + typeName(v)
                        + " — using " + defaultValue));
        return defaultValue;
    }

    private static String typeName(@Nullable Object v) {
        if (v == null) return "nothing";
        if (v instanceof Map<?, ?>) return "a mapping";
        if (v instanceof List<?>) return "a list";
        return "'" + v + "'";
    }
}
