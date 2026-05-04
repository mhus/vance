package de.mhus.vance.shared.document.kind;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-memory model of a {@code kind: data} document — a free-form
 * JSON/YAML body intended to be consumed by tools or processes
 * rather than hand-edited through a structured editor.
 *
 * <p>{@code body} is whatever the underlying JSON / YAML parser
 * produced: a {@code Map<String, Object>} for the canonical
 * top-level-object form, a {@code List<Object>} when the body is a
 * top-level array, or a primitive (String / Number / Boolean /
 * {@code null}) when the body is a top-level scalar.
 *
 * @param kind always {@code "data"}.
 * @param body the parsed body — see above.
 * @param meta non-{@code kind} entries from the {@code $meta} block
 *             (or the YAML header doc). Empty when the body isn't
 *             a top-level object so the {@code $meta} slot has no
 *             home.
 *
 * <p>Spec: {@code specification/doc-kind-data.md}.
 */
public record DataDocument(String kind, Object body, Map<String, Object> meta) {

    public DataDocument {
        if (kind == null || kind.isBlank()) kind = "data";
        if (body == null) body = new LinkedHashMap<>();
        if (meta == null) meta = new LinkedHashMap<>();
    }

    public static DataDocument empty() {
        return new DataDocument("data", new LinkedHashMap<>(), new LinkedHashMap<>());
    }
}
