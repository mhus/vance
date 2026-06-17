package de.mhus.vance.shared.document.kind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A polygon — closed ring of locations drawn as a filled area
 * (city boundary, region, zone). Each point may be a named place or
 * explicit coords; renderers geocode the named ones before drawing.
 *
 * <p>Spec: {@code specification/doc-kind-map.md} §2.2.
 */
public record MapArea(
        String name,
        @Nullable String title,
        List<MapLocation> points,
        @Nullable String color,
        @Nullable Double fillOpacity,
        Map<String, Object> extra) {

    public MapArea {
        Objects.requireNonNull(name, "name");
        if (points == null) points = new ArrayList<>();
        if (extra == null) extra = new LinkedHashMap<>();
    }
}
