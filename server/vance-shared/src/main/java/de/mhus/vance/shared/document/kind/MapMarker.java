package de.mhus.vance.shared.document.kind;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A single point of interest on the map. Position is carried flat on
 * the marker — {@code place}/{@code lat}/{@code lon} live at the
 * marker level, not nested under a {@code location} sub-object.
 * That keeps the LLM-emitted YAML compact and matches how the model
 * naturally writes "marker with coords".
 *
 * <p>Spec: {@code specification/doc-kind-map.md} §2.1.
 */
public record MapMarker(
        String name,
        @Nullable String title,
        MapLocation location,
        @Nullable String color,
        @Nullable String description,
        Map<String, Object> extra) {

    public MapMarker {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(location, "location");
        if (extra == null) extra = new LinkedHashMap<>();
    }
}
