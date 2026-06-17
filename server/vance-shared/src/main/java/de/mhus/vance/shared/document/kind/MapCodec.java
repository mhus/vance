package de.mhus.vance.shared.document.kind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Parser and serialiser for {@code kind: map} document bodies —
 * markers, areas and routes on a Leaflet-rendered OpenStreetMap.
 * JSON and YAML only; markdown is intentionally not supported (same
 * rationale as {@code graph}).
 *
 * <p>Position is carried flat on markers ({@code place}/{@code lat}/
 * {@code lon} at the marker level, no nested {@code location:} object)
 * to match the way an LLM naturally emits such YAML. Area points and
 * route waypoints are lists of the same flat shape.
 *
 * <p>Spec: {@code specification/doc-kind-map.md}.
 */
public final class MapCodec {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> JSON_MAP =
            new TypeReference<>() {};

    private MapCodec() {
        // utility class
    }

    public static MapDocument parse(String body, @Nullable String mimeType) {
        if (isJson(mimeType)) return parseJson(body);
        if (isYaml(mimeType)) return parseYaml(body);
        throw new KindCodecException("Unsupported mime type for map: " + mimeType);
    }

    public static String serialize(MapDocument doc, @Nullable String mimeType) {
        if (isJson(mimeType)) return serializeJson(doc);
        if (isYaml(mimeType)) return serializeYaml(doc);
        throw new KindCodecException("Unsupported mime type for map: " + mimeType);
    }

    public static boolean supports(@Nullable String mimeType) {
        return isJson(mimeType) || isYaml(mimeType);
    }

    // ── Mime ───────────────────────────────────────────────────────

    private static boolean isJson(@Nullable String mime) {
        return "application/json".equals(mime);
    }

    private static boolean isYaml(@Nullable String mime) {
        return "application/yaml".equals(mime)
                || "application/x-yaml".equals(mime)
                || "text/yaml".equals(mime)
                || "text/x-yaml".equals(mime);
    }

    // ── JSON / YAML ────────────────────────────────────────────────

    private static MapDocument parseJson(String body) {
        if (body.isBlank()) return MapDocument.empty();
        Map<String, Object> parsed;
        try {
            parsed = JSON.readValue(body, JSON_MAP);
        } catch (JacksonException e) {
            throw new KindCodecException("Invalid JSON: " + e.getOriginalMessage(), e);
        }
        if (parsed == null) throw new KindCodecException("Top-level JSON must be an object");
        return promoteToDocument(KindHeaderCodec.unwrapJsonMeta(parsed));
    }

    private static MapDocument parseYaml(String body) {
        if (body.isBlank()) return MapDocument.empty();
        return promoteToDocument(KindHeaderCodec.parseYamlBody(body));
    }

    private static String serializeJson(MapDocument doc) {
        Map<String, Object> wrapped = KindHeaderCodec.wrapJsonMeta(canonicalKind(doc), buildBody(doc));
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(wrapped) + "\n";
        } catch (JacksonException e) {
            throw new KindCodecException("Failed to write JSON: " + e.getOriginalMessage(), e);
        }
    }

    private static String serializeYaml(MapDocument doc) {
        return KindHeaderCodec.dumpYamlBody(canonicalKind(doc), buildBody(doc));
    }

    // ── Promotion ──────────────────────────────────────────────────

    private static MapDocument promoteToDocument(Map<String, Object> obj) {
        Object kindRaw = obj.get("kind");
        String kind = (kindRaw instanceof String s) ? s : "";
        MapView view = promoteView(obj.get("view"));
        List<MapMarker> markers = promoteMarkers(obj.get("markers"));
        List<MapArea> areas = promoteAreas(obj.get("areas"));
        List<MapRoute> routes = promoteRoutes(obj.get("routes"));

        Map<String, Object> extra = new LinkedHashMap<>(obj);
        extra.remove("kind");
        extra.remove("view");
        extra.remove("markers");
        extra.remove("areas");
        extra.remove("routes");
        return new MapDocument(kind.isEmpty() ? "map" : kind, view, markers, areas, routes, extra);
    }

    private static @Nullable MapView promoteView(@Nullable Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return null;
        MapLocation center = promoteLocation(map);
        Integer zoom = promoteInt(map.get("zoom"));
        if (center == null && zoom == null) return null;
        return new MapView(center, zoom);
    }

    private static List<MapMarker> promoteMarkers(@Nullable Object raw) {
        List<MapMarker> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return out;
        int index = 0;
        for (Object r : list) {
            index++;
            if (!(r instanceof Map<?, ?> map)) continue;
            MapLocation loc = promoteLocation(map);
            if (loc == null) continue;
            // `label` is accepted as an alias for `title` — that's
            // what LLMs naturally reach for. Title wins on collision.
            String title = promoteString(map.get("title"));
            if (title == null) title = promoteString(map.get("label"));
            String name = promoteString(map.get("name"));
            if (name == null) name = autoName("marker", title, index);
            String color = promoteString(map.get("color"));
            String description = promoteString(map.get("description"));

            Map<String, Object> extra = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!(e.getKey() instanceof String key)) continue;
                if (isMarkerReservedKey(key)) continue;
                extra.put(key, e.getValue());
            }
            out.add(new MapMarker(name, title, loc, color, description, extra));
        }
        return out;
    }

    private static boolean isMarkerReservedKey(String key) {
        return switch (key) {
            case "name", "title", "label", "place", "lat", "lon", "color", "description" -> true;
            default -> false;
        };
    }

    private static List<MapArea> promoteAreas(@Nullable Object raw) {
        List<MapArea> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return out;
        int index = 0;
        for (Object r : list) {
            index++;
            if (!(r instanceof Map<?, ?> map)) continue;
            List<MapLocation> points = promoteLocationList(map.get("points"));
            String title = promoteString(map.get("title"));
            if (title == null) title = promoteString(map.get("label"));
            String name = promoteString(map.get("name"));
            if (name == null) name = autoName("area", title, index);
            String color = promoteString(map.get("color"));
            Double fillOpacity = promoteDouble(map.get("fillOpacity"));

            Map<String, Object> extra = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!(e.getKey() instanceof String key)) continue;
                if (isAreaReservedKey(key)) continue;
                extra.put(key, e.getValue());
            }
            out.add(new MapArea(name, title, points, color, fillOpacity, extra));
        }
        return out;
    }

    private static boolean isAreaReservedKey(String key) {
        return switch (key) {
            case "name", "title", "label", "points", "color", "fillOpacity" -> true;
            default -> false;
        };
    }

    private static List<MapRoute> promoteRoutes(@Nullable Object raw) {
        List<MapRoute> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return out;
        int index = 0;
        for (Object r : list) {
            index++;
            if (!(r instanceof Map<?, ?> map)) continue;
            List<MapLocation> waypoints = promoteLocationList(map.get("waypoints"));
            String title = promoteString(map.get("title"));
            if (title == null) title = promoteString(map.get("label"));
            String name = promoteString(map.get("name"));
            if (name == null) name = autoName("route", title, index);
            String color = promoteString(map.get("color"));
            Integer width = promoteInt(map.get("width"));

            Map<String, Object> extra = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!(e.getKey() instanceof String key)) continue;
                if (isRouteReservedKey(key)) continue;
                extra.put(key, e.getValue());
            }
            out.add(new MapRoute(name, title, waypoints, color, width, extra));
        }
        return out;
    }

    private static boolean isRouteReservedKey(String key) {
        return switch (key) {
            case "name", "title", "label", "waypoints", "color", "width" -> true;
            default -> false;
        };
    }

    /**
     * Derive a stable {@code name} when the on-disk feature has none.
     * LLMs reach for {@code label} (as display) and skip the
     * technical name; slugifying the title keeps the persisted
     * artifact human-readable, while {@code <kind>_<idx>} catches the
     * fully-untitled case.
     */
    private static String autoName(String kind, @Nullable String title, int oneBasedIndex) {
        if (title != null && !title.isBlank()) {
            String slug = title.trim().toLowerCase()
                    .replaceAll("[^a-z0-9]+", "-")
                    .replaceAll("^-+|-+$", "");
            if (!slug.isEmpty()) return slug;
        }
        return kind + "_" + oneBasedIndex;
    }

    private static List<MapLocation> promoteLocationList(@Nullable Object raw) {
        List<MapLocation> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return out;
        for (Object r : list) {
            if (!(r instanceof Map<?, ?> map)) continue;
            MapLocation loc = promoteLocation(map);
            if (loc != null) out.add(loc);
        }
        return out;
    }

    /**
     * Pull {@code place}/{@code lat}/{@code lon} off the given map.
     * Returns {@code null} when neither a usable place name nor a
     * coordinate pair is present.
     */
    private static @Nullable MapLocation promoteLocation(Map<?, ?> map) {
        String place = promoteString(map.get("place"));
        Double lat = promoteDouble(map.get("lat"));
        Double lon = promoteDouble(map.get("lon"));
        if (place == null && (lat == null || lon == null)) return null;
        return new MapLocation(place, lat, lon);
    }

    private static @Nullable String promoteString(@Nullable Object raw) {
        if (raw instanceof String s) {
            String trimmed = s.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
        return null;
    }

    private static @Nullable Double promoteDouble(@Nullable Object raw) {
        if (raw instanceof Number n) {
            double d = n.doubleValue();
            return Double.isFinite(d) ? d : null;
        }
        return null;
    }

    private static @Nullable Integer promoteInt(@Nullable Object raw) {
        if (raw instanceof Number n) {
            double d = n.doubleValue();
            if (!Double.isFinite(d)) return null;
            return (int) Math.round(d);
        }
        return null;
    }

    // ── Body builder ───────────────────────────────────────────────

    private static Map<String, Object> buildBody(MapDocument doc) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (doc.view() != null) {
            Map<String, Object> view = viewToMap(doc.view());
            if (!view.isEmpty()) body.put("view", view);
        }
        body.put("markers", markersToList(doc.markers()));
        body.put("areas", areasToList(doc.areas()));
        body.put("routes", routesToList(doc.routes()));
        for (Map.Entry<String, Object> e : doc.extra().entrySet()) {
            if (!body.containsKey(e.getKey())) body.put(e.getKey(), e.getValue());
        }
        return body;
    }

    private static Map<String, Object> viewToMap(MapView view) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (view.center() != null) writeLocationInto(out, view.center());
        if (view.zoom() != null) out.put("zoom", view.zoom());
        return out;
    }

    private static List<Map<String, Object>> markersToList(List<MapMarker> markers) {
        List<Map<String, Object>> out = new ArrayList<>(markers.size());
        for (MapMarker m : markers) {
            Map<String, Object> obj = new LinkedHashMap<>();
            obj.put("name", m.name());
            if (m.title() != null) obj.put("title", m.title());
            writeLocationInto(obj, m.location());
            if (m.color() != null) obj.put("color", m.color());
            if (m.description() != null) obj.put("description", m.description());
            for (Map.Entry<String, Object> e : m.extra().entrySet()) {
                if (!obj.containsKey(e.getKey())) obj.put(e.getKey(), e.getValue());
            }
            out.add(obj);
        }
        return out;
    }

    private static List<Map<String, Object>> areasToList(List<MapArea> areas) {
        List<Map<String, Object>> out = new ArrayList<>(areas.size());
        for (MapArea a : areas) {
            Map<String, Object> obj = new LinkedHashMap<>();
            obj.put("name", a.name());
            if (a.title() != null) obj.put("title", a.title());
            obj.put("points", locationsToList(a.points()));
            if (a.color() != null) obj.put("color", a.color());
            if (a.fillOpacity() != null) obj.put("fillOpacity", a.fillOpacity());
            for (Map.Entry<String, Object> e : a.extra().entrySet()) {
                if (!obj.containsKey(e.getKey())) obj.put(e.getKey(), e.getValue());
            }
            out.add(obj);
        }
        return out;
    }

    private static List<Map<String, Object>> routesToList(List<MapRoute> routes) {
        List<Map<String, Object>> out = new ArrayList<>(routes.size());
        for (MapRoute r : routes) {
            Map<String, Object> obj = new LinkedHashMap<>();
            obj.put("name", r.name());
            if (r.title() != null) obj.put("title", r.title());
            obj.put("waypoints", locationsToList(r.waypoints()));
            if (r.color() != null) obj.put("color", r.color());
            if (r.width() != null) obj.put("width", r.width());
            for (Map.Entry<String, Object> e : r.extra().entrySet()) {
                if (!obj.containsKey(e.getKey())) obj.put(e.getKey(), e.getValue());
            }
            out.add(obj);
        }
        return out;
    }

    private static List<Map<String, Object>> locationsToList(List<MapLocation> locations) {
        List<Map<String, Object>> out = new ArrayList<>(locations.size());
        for (MapLocation loc : locations) {
            Map<String, Object> obj = new LinkedHashMap<>();
            writeLocationInto(obj, loc);
            out.add(obj);
        }
        return out;
    }

    /**
     * Write {@code place}/{@code lat}/{@code lon} into the given map.
     * Used for flat markers (location fields at the marker level) and
     * for entries inside {@code points}/{@code waypoints} arrays.
     */
    private static void writeLocationInto(Map<String, Object> obj, MapLocation loc) {
        if (loc.hasPlace()) obj.put("place", loc.place());
        if (loc.hasCoords()) {
            obj.put("lat", loc.lat());
            obj.put("lon", loc.lon());
        }
    }

    private static String canonicalKind(MapDocument doc) {
        return (doc.kind() == null || doc.kind().isBlank()) ? "map" : doc.kind();
    }
}
