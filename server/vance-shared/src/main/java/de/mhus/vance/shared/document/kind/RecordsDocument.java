package de.mhus.vance.shared.document.kind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory model of a {@code kind: records} document — a flat
 * table with a fixed schema and one record per row.
 *
 * @param kind   always {@code "records"}.
 * @param schema ordered, deduped list of field names. Required and
 *               non-empty for any non-trivial document; the codec
 *               throws when a body without {@code schema} is parsed.
 * @param items  the ordered records.
 * @param extra  unknown top-level fields, re-emitted on save.
 *
 * <p>Spec: {@code specification/doc-kind-records.md}.
 */
public record RecordsDocument(
        String kind,
        List<String> schema,
        List<RecordsItem> items,
        Map<String, Object> extra) {

    public RecordsDocument {
        if (kind == null || kind.isBlank()) kind = "records";
        if (schema == null) schema = new ArrayList<>();
        if (items == null) items = new ArrayList<>();
        if (extra == null) extra = new LinkedHashMap<>();
    }
}
