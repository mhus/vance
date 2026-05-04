package de.mhus.vance.shared.document.kind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One record in a {@code kind: records} document. {@code values}
 * holds the schema-keyed strings (missing fields are stored as
 * {@code ""}), {@code extra} preserves unknown keys for JSON/YAML
 * round-trip, {@code overflow} preserves surplus markdown values
 * that exceeded the schema length.
 *
 * <p>Spec: {@code specification/doc-kind-records.md} §2.2.
 */
public record RecordsItem(
        Map<String, String> values,
        Map<String, Object> extra,
        List<String> overflow) {

    public RecordsItem {
        if (values == null) values = new LinkedHashMap<>();
        if (extra == null) extra = new LinkedHashMap<>();
        if (overflow == null) overflow = new ArrayList<>();
    }

    /** Build an empty record matching the schema — every field set
     *  to {@code ""}. */
    public static RecordsItem empty(List<String> schema) {
        Map<String, String> v = new LinkedHashMap<>();
        for (String f : schema) v.put(f, "");
        return new RecordsItem(v, new LinkedHashMap<>(), new ArrayList<>());
    }
}
