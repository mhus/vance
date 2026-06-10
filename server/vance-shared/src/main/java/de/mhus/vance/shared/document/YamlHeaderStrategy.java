package de.mhus.vance.shared.document;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.events.MappingStartEvent;
import org.yaml.snakeyaml.events.ScalarEvent;

/**
 * YAML front-matter via a reserved top-level {@code $meta} mapping — the
 * exact same convention as {@link JsonHeaderStrategy}. Keeping JSON and
 * YAML symmetric means a single mental model ({@code $meta.kind} lives
 * at the top of the document, regardless of format) and a single parser
 * pathway:
 *
 * <pre>
 * $meta:
 *   kind: list
 *   schema: requirement
 * items:
 *   - item one
 *   - item two
 * </pre>
 *
 * <p>The header is only accepted when the document's top-level value is
 * a YAML mapping containing a {@code $meta} mapping. Sequences, scalars
 * and mappings without {@code $meta} have no header. Non-scalar values
 * inside {@code $meta} (nested map, list) are skipped — same rule as
 * JSON.
 *
 * <p>Multi-document YAML streams (with {@code ---} separators) are no
 * longer treated as Vance-typed documents — that convention was
 * ambiguous with legitimate uses of the YAML spec's multi-doc feature
 * (e.g. Kubernetes manifests). A document with {@code $meta} at the top
 * is the unambiguous signal.
 */
@Component
public class YamlHeaderStrategy implements HeaderStrategy {

    private static final String META_KEY = "$meta";

    @Override
    public boolean supports(@Nullable String mimeType) {
        String mt = DocumentHeaderParser.canonicalMime(mimeType);
        return "application/yaml".equals(mt)
                || "application/x-yaml".equals(mt)
                || "text/yaml".equals(mt)
                || "text/x-yaml".equals(mt);
    }

    @Override
    public Optional<DocumentHeader> parse(String body) {
        // SafeConstructor so we never instantiate arbitrary classes via
        // YAML's tag system. Alias cap defends against billion-laughs
        // style pathological inputs.
        LoaderOptions opts = new LoaderOptions();
        opts.setAllowDuplicateKeys(false);
        opts.setMaxAliasesForCollections(50);
        Yaml yaml = new Yaml(new SafeConstructor(opts));

        Object root;
        try {
            root = yaml.load(body);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (!(root instanceof Map<?, ?> rootMap)) return Optional.empty();
        Object metaVal = rootMap.get(META_KEY);
        if (!(metaVal instanceof Map<?, ?> meta)) return Optional.empty();

        Map<String, String> values = new LinkedHashMap<>();
        String kind = null;
        for (Map.Entry<?, ?> entry : meta.entrySet()) {
            if (!(entry.getKey() instanceof String rawKey)) continue;
            Object rawValue = entry.getValue();
            String value = scalarToString(rawValue);
            if (value == null) continue;
            String key = DocumentHeaderParser.normalizeKey(rawKey);
            if (key.isEmpty()) continue;
            values.put(key, value);
            if ("kind".equals(key) && !value.isEmpty()) {
                kind = value;
            }
        }

        if (kind == null) return Optional.empty();
        return Optional.of(DocumentHeader.builder()
                .kind(kind)
                .values(values)
                .build());
    }

    /**
     * Coerce a YAML scalar leaf to its string form. Returns {@code null} for
     * complex values (nested map, list) so the caller drops the entry —
     * the header projection is intentionally flat.
     */
    private static @Nullable String scalarToString(@Nullable Object value) {
        if (value == null) return "";
        if (value instanceof String s) return s;
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        return null;
    }

    /**
     * Streaming variant driven by SnakeYAML's event API — we never build the
     * full DOM. The algorithm walks the event stream looking for a top-level
     * mapping that contains a {@code $meta} key whose value is itself a
     * mapping; nested values inside {@code $meta} are flattened to scalars
     * exactly as in the string-based parser. Anything else (sequences at the
     * root, missing {@code $meta}, malformed input) returns empty.
     *
     * <p>The {@link Reader} we wrap around the stream is not closed — the
     * caller owns the {@link InputStream} (typically via try-with-resources
     * around a {@code loadContent} stream).
     */
    @Override
    public Optional<DocumentHeader> parse(InputStream body) throws IOException {
        Reader reader = new InputStreamReader(body, StandardCharsets.UTF_8);
        LoaderOptions opts = new LoaderOptions();
        opts.setAllowDuplicateKeys(false);
        opts.setMaxAliasesForCollections(50);
        Yaml yaml = new Yaml(new SafeConstructor(opts));

        try {
            Iterator<Event> events = yaml.parse(reader).iterator();

            // Skip wrapping events (StreamStart, DocumentStart) until we reach
            // the document's root value. If the root is anything other than a
            // mapping, the document has no header.
            Event root = nextStructuralEvent(events);
            if (!(root instanceof MappingStartEvent)) return Optional.empty();

            // Walk the top-level mapping looking for the $meta key.
            while (events.hasNext()) {
                Event next = events.next();
                if (next.is(Event.ID.MappingEnd)) break;
                if (!(next instanceof ScalarEvent keyEvent)) return Optional.empty();
                String key = keyEvent.getValue();
                if (META_KEY.equals(key)) {
                    if (!events.hasNext()) return Optional.empty();
                    Event valueEvent = events.next();
                    if (!(valueEvent instanceof MappingStartEvent)) return Optional.empty();
                    return readMetaMapping(events);
                }
                // Value of this non-$meta key — skip the whole sub-tree.
                if (!events.hasNext()) return Optional.empty();
                skipNextValue(events);
            }
            return Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Optional<DocumentHeader> readMetaMapping(Iterator<Event> events) {
        Map<String, String> values = new LinkedHashMap<>();
        String kind = null;
        while (events.hasNext()) {
            Event next = events.next();
            if (next.is(Event.ID.MappingEnd)) break;
            if (!(next instanceof ScalarEvent keyEvent)) return Optional.empty();
            String rawKey = keyEvent.getValue();
            if (!events.hasNext()) return Optional.empty();
            Event valueEvent = events.next();
            String value = scalarFromEvent(valueEvent);
            if (value == null) {
                // Nested object / sequence inside $meta — skip the whole sub-tree.
                skipValueStartingAt(valueEvent, events);
                continue;
            }
            String key = DocumentHeaderParser.normalizeKey(rawKey);
            if (key.isEmpty()) continue;
            values.put(key, value);
            if ("kind".equals(key) && !value.isEmpty()) {
                kind = value;
            }
        }
        if (kind == null) return Optional.empty();
        return Optional.of(DocumentHeader.builder()
                .kind(kind)
                .values(values)
                .build());
    }

    /**
     * Pull events until we leave the wrapping {@code StreamStart}/
     * {@code DocumentStart} envelope and reach the document's root value.
     */
    private static @Nullable Event nextStructuralEvent(Iterator<Event> events) {
        while (events.hasNext()) {
            Event e = events.next();
            if (e.is(Event.ID.StreamStart) || e.is(Event.ID.DocumentStart)) continue;
            return e;
        }
        return null;
    }

    /** Skip the value starting at {@code events.next()} — depth-tracking. */
    private static void skipNextValue(Iterator<Event> events) {
        Event first = events.next();
        skipValueStartingAt(first, events);
    }

    private static void skipValueStartingAt(Event first, Iterator<Event> events) {
        if (first instanceof MappingStartEvent || first.is(Event.ID.SequenceStart)) {
            int depth = 1;
            while (depth > 0 && events.hasNext()) {
                Event next = events.next();
                if (next instanceof MappingStartEvent || next.is(Event.ID.SequenceStart)) depth++;
                else if (next.is(Event.ID.MappingEnd) || next.is(Event.ID.SequenceEnd)) depth--;
            }
        }
        // Scalars and aliases consume exactly one event — already done by the caller.
    }

    private static @Nullable String scalarFromEvent(Event event) {
        if (event instanceof ScalarEvent scalar) {
            return scalar.getValue();
        }
        return null;
    }
}
