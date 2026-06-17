package de.mhus.vance.shared.document.kind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A polyline connecting an ordered sequence of waypoints — drawn as
 * straight line segments (great-circle path between each pair). The
 * renderer does NOT do road routing; "Hamburg → Berlin" becomes a
 * straight line, not a driving route. Road-snapping is a future
 * enhancement that would require an external routing service (OSRM
 * etc.).
 *
 * <p>Spec: {@code specification/doc-kind-map.md} §2.3.
 */
public record MapRoute(
        String name,
        @Nullable String title,
        List<MapLocation> waypoints,
        @Nullable String color,
        @Nullable Integer width,
        Map<String, Object> extra) {

    public MapRoute {
        Objects.requireNonNull(name, "name");
        if (waypoints == null) waypoints = new ArrayList<>();
        if (extra == null) extra = new LinkedHashMap<>();
    }
}
