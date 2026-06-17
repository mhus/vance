package de.mhus.vance.shared.document.kind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * In-memory model of a {@code kind: map} document — markers (points),
 * areas (polygons) and routes (polylines) on a Leaflet-backed
 * OpenStreetMap-style map.
 *
 * @param kind    always {@code "map"}.
 * @param view    optional initial viewport (center + zoom).
 * @param markers point features.
 * @param areas   polygon features.
 * @param routes  polyline features.
 * @param extra   unknown top-level fields, passthrough.
 *
 * <p>Spec: {@code specification/doc-kind-map.md}.
 */
public record MapDocument(
        String kind,
        @Nullable MapView view,
        List<MapMarker> markers,
        List<MapArea> areas,
        List<MapRoute> routes,
        Map<String, Object> extra) {

    public MapDocument {
        if (kind == null || kind.isBlank()) kind = "map";
        if (markers == null) markers = new ArrayList<>();
        if (areas == null) areas = new ArrayList<>();
        if (routes == null) routes = new ArrayList<>();
        if (extra == null) extra = new LinkedHashMap<>();
    }

    public static MapDocument empty() {
        return new MapDocument("map", null,
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                new LinkedHashMap<>());
    }
}
