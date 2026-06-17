package de.mhus.vance.shared.document.kind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Codec round-trip and validation behaviour for {@code kind: map}.
 * Covers the three feature types (markers/areas/routes), the
 * dual-shape location ({@code place} vs {@code lat}/{@code lon}),
 * unknown-key passthrough, and serializer key order.
 */
class MapCodecTest {

    // ── parse: JSON happy paths ────────────────────────────────────

    @Test
    void parseJson_markerWithCoords() {
        String body = """
                {
                  "$meta": { "kind": "map" },
                  "markers": [
                    { "name": "altona", "title": "Altona",
                      "lat": 53.55, "lon": 9.92, "color": "#3b82f6" }
                  ]
                }
                """;

        MapDocument doc = MapCodec.parse(body, "application/json");

        assertThat(doc.kind()).isEqualTo("map");
        assertThat(doc.markers()).hasSize(1);
        MapMarker m = doc.markers().get(0);
        assertThat(m.name()).isEqualTo("altona");
        assertThat(m.title()).isEqualTo("Altona");
        assertThat(m.location().hasCoords()).isTrue();
        assertThat(m.location().lat()).isEqualTo(53.55);
        assertThat(m.location().lon()).isEqualTo(9.92);
        assertThat(m.location().place()).isNull();
        assertThat(m.color()).isEqualTo("#3b82f6");
    }

    @Test
    void parseJson_markerWithPlace() {
        String body = """
                {
                  "$meta": { "kind": "map" },
                  "markers": [
                    { "name": "altona", "place": "Hamburg Altona" }
                  ]
                }
                """;

        MapDocument doc = MapCodec.parse(body, "application/json");

        MapMarker m = doc.markers().get(0);
        assertThat(m.location().place()).isEqualTo("Hamburg Altona");
        assertThat(m.location().hasCoords()).isFalse();
        assertThat(m.location().hasPlace()).isTrue();
    }

    @Test
    void parseJson_markerWithBothPrefersCoords() {
        String body = """
                {
                  "$meta": { "kind": "map" },
                  "markers": [
                    { "name": "altona", "place": "Hamburg Altona",
                      "lat": 53.55, "lon": 9.92 }
                  ]
                }
                """;

        MapDocument doc = MapCodec.parse(body, "application/json");

        MapMarker m = doc.markers().get(0);
        assertThat(m.location().place()).isEqualTo("Hamburg Altona");
        assertThat(m.location().hasCoords()).isTrue();
        assertThat(m.location().lat()).isEqualTo(53.55);
    }

    @Test
    void parseJson_markerWithoutLocationDropped() {
        String body = """
                {
                  "$meta": { "kind": "map" },
                  "markers": [
                    { "name": "nope" },
                    { "name": "altona", "lat": 53.55, "lon": 9.92 }
                  ]
                }
                """;

        MapDocument doc = MapCodec.parse(body, "application/json");

        assertThat(doc.markers()).hasSize(1);
        assertThat(doc.markers().get(0).name()).isEqualTo("altona");
    }

    @Test
    void parseJson_markerWithoutNameGetsAutoName() {
        // LLMs typically write only `label` (or just lat/lon). The
        // codec must keep these features and synthesise a stable
        // technical name rather than silently dropping them.
        String body = """
                {
                  "$meta": { "kind": "map" },
                  "markers": [
                    { "label": "Altona", "lat": 53.55, "lon": 9.92 },
                    { "lat": 1.0, "lon": 2.0 }
                  ]
                }
                """;

        MapDocument doc = MapCodec.parse(body, "application/json");

        assertThat(doc.markers()).hasSize(2);
        // `label` becomes the title, slugified for the auto-name.
        assertThat(doc.markers().get(0).title()).isEqualTo("Altona");
        assertThat(doc.markers().get(0).name()).isEqualTo("altona");
        // Untitled fallback uses a 1-based index.
        assertThat(doc.markers().get(1).name()).isEqualTo("marker_2");
    }

    @Test
    void parseJson_labelAliasAcrossFeatureTypes() {
        String body = """
                {
                  "$meta": { "kind": "map" },
                  "markers": [{ "label": "X", "lat": 1.0, "lon": 2.0 }],
                  "areas": [{ "label": "Y", "points": [
                    {"lat":1,"lon":2},{"lat":3,"lon":4},{"lat":5,"lon":6}
                  ]}],
                  "routes": [{ "label": "Z", "waypoints": [
                    {"lat":1,"lon":2},{"lat":3,"lon":4}
                  ]}]
                }
                """;

        MapDocument doc = MapCodec.parse(body, "application/json");

        assertThat(doc.markers().get(0).title()).isEqualTo("X");
        assertThat(doc.areas().get(0).title()).isEqualTo("Y");
        assertThat(doc.routes().get(0).title()).isEqualTo("Z");
        assertThat(doc.markers().get(0).name()).isEqualTo("x");
        assertThat(doc.areas().get(0).name()).isEqualTo("y");
        assertThat(doc.routes().get(0).name()).isEqualTo("z");
    }

    @Test
    void parseJson_titleWinsOverLabel() {
        String body = """
                {
                  "$meta": { "kind": "map" },
                  "markers": [
                    { "title": "Real", "label": "Ignored",
                      "lat": 53.55, "lon": 9.92 }
                  ]
                }
                """;

        MapDocument doc = MapCodec.parse(body, "application/json");

        assertThat(doc.markers().get(0).title()).isEqualTo("Real");
    }

    @Test
    void parseJson_areaWithMixedPoints() {
        String body = """
                {
                  "$meta": { "kind": "map" },
                  "areas": [
                    { "name": "hamburg", "title": "Hamburg",
                      "points": [
                        { "place": "Hamburg" },
                        { "lat": 53.6, "lon": 9.7 },
                        { "lat": 53.4, "lon": 10.3 }
                      ],
                      "color": "#10b981", "fillOpacity": 0.2 }
                  ]
                }
                """;

        MapDocument doc = MapCodec.parse(body, "application/json");

        assertThat(doc.areas()).hasSize(1);
        MapArea a = doc.areas().get(0);
        assertThat(a.name()).isEqualTo("hamburg");
        assertThat(a.points()).hasSize(3);
        assertThat(a.points().get(0).place()).isEqualTo("Hamburg");
        assertThat(a.points().get(1).hasCoords()).isTrue();
        assertThat(a.fillOpacity()).isEqualTo(0.2);
    }

    @Test
    void parseJson_routeWithWaypoints() {
        String body = """
                {
                  "$meta": { "kind": "map" },
                  "routes": [
                    { "name": "hh-berlin", "title": "Hamburg → Berlin",
                      "waypoints": [
                        { "place": "Hamburg" },
                        { "place": "Berlin" }
                      ],
                      "color": "#ef4444", "width": 3 }
                  ]
                }
                """;

        MapDocument doc = MapCodec.parse(body, "application/json");

        assertThat(doc.routes()).hasSize(1);
        MapRoute r = doc.routes().get(0);
        assertThat(r.waypoints()).hasSize(2);
        assertThat(r.waypoints().get(0).place()).isEqualTo("Hamburg");
        assertThat(r.width()).isEqualTo(3);
    }

    @Test
    void parseJson_viewWithCenterAndZoom() {
        String body = """
                {
                  "$meta": { "kind": "map" },
                  "view": { "lat": 53.55, "lon": 9.99, "zoom": 10 },
                  "markers": []
                }
                """;

        MapDocument doc = MapCodec.parse(body, "application/json");

        assertThat(doc.view()).isNotNull();
        assertThat(doc.view().center()).isNotNull();
        assertThat(doc.view().center().lat()).isEqualTo(53.55);
        assertThat(doc.view().zoom()).isEqualTo(10);
    }

    @Test
    void parseJson_unknownTopLevelKeysPassThrough() {
        String body = """
                {
                  "$meta": { "kind": "map" },
                  "markers": [{ "name": "x", "lat": 1.0, "lon": 2.0 }],
                  "customExtra": "stays"
                }
                """;

        MapDocument doc = MapCodec.parse(body, "application/json");

        assertThat(doc.extra()).containsEntry("customExtra", "stays");
    }

    @Test
    void parseJson_unknownPerMarkerKeysPassThrough() {
        String body = """
                {
                  "$meta": { "kind": "map" },
                  "markers": [
                    { "name": "x", "lat": 1.0, "lon": 2.0,
                      "iconName": "star", "weight": 42 }
                  ]
                }
                """;

        MapDocument doc = MapCodec.parse(body, "application/json");

        assertThat(doc.markers().get(0).extra())
                .containsEntry("iconName", "star")
                .containsEntry("weight", 42);
    }

    // ── parse: YAML ────────────────────────────────────────────────

    @Test
    void parseYaml_full() {
        String body = """
                $meta:
                  kind: map
                view:
                  place: Hamburg
                  zoom: 10
                markers:
                  - name: altona
                    title: Altona
                    lat: 53.55
                    lon: 9.92
                areas:
                  - name: hamburg
                    points:
                      - place: Hamburg
                      - lat: 53.6
                        lon: 9.7
                routes:
                  - name: hh-berlin
                    waypoints:
                      - place: Hamburg
                      - place: Berlin
                """;

        MapDocument doc = MapCodec.parse(body, "application/yaml");

        assertThat(doc.kind()).isEqualTo("map");
        assertThat(doc.view().center().place()).isEqualTo("Hamburg");
        assertThat(doc.view().zoom()).isEqualTo(10);
        assertThat(doc.markers()).hasSize(1);
        assertThat(doc.areas()).hasSize(1);
        assertThat(doc.areas().get(0).points()).hasSize(2);
        assertThat(doc.routes()).hasSize(1);
        assertThat(doc.routes().get(0).waypoints()).hasSize(2);
    }

    @Test
    void parseYaml_empty() {
        MapDocument doc = MapCodec.parse("", "application/yaml");
        assertThat(doc.kind()).isEqualTo("map");
        assertThat(doc.markers()).isEmpty();
        assertThat(doc.areas()).isEmpty();
        assertThat(doc.routes()).isEmpty();
    }

    // ── serialize: JSON ────────────────────────────────────────────

    @Test
    void serializeJson_canonicalKeyOrder() {
        MapDocument doc = new MapDocument("map", null,
                List.of(new MapMarker("altona", "Altona",
                        new MapLocation(null, 53.55, 9.92),
                        "#3b82f6", null, new java.util.LinkedHashMap<>())),
                List.of(), List.of(), new java.util.LinkedHashMap<>());

        String out = MapCodec.serialize(doc, "application/json");

        assertThat(out).contains("\"$meta\"");
        assertThat(out).contains("\"kind\" : \"map\"");
        assertThat(out).contains("\"markers\"");
        // name first, then title, then lat/lon, then color — canonical order
        int nameIdx = out.indexOf("\"name\"");
        int titleIdx = out.indexOf("\"title\"");
        int latIdx = out.indexOf("\"lat\"");
        int colorIdx = out.indexOf("\"color\"");
        assertThat(nameIdx).isLessThan(titleIdx);
        assertThat(titleIdx).isLessThan(latIdx);
        assertThat(latIdx).isLessThan(colorIdx);
    }

    @Test
    void serializeJson_omitsUnsetOptionals() {
        MapDocument doc = new MapDocument("map", null,
                List.of(new MapMarker("x", null,
                        new MapLocation(null, 1.0, 2.0),
                        null, null, new java.util.LinkedHashMap<>())),
                List.of(), List.of(), new java.util.LinkedHashMap<>());

        String out = MapCodec.serialize(doc, "application/json");

        assertThat(out).doesNotContain("\"title\"");
        assertThat(out).doesNotContain("\"color\"");
        assertThat(out).doesNotContain("\"description\"");
    }

    // ── Round-trip ──────────────────────────────────────────────────

    @Test
    void roundTripJson_preservesAllFields() {
        String original = """
                {
                  "$meta": { "kind": "map" },
                  "view": { "place": "Hamburg", "zoom": 10 },
                  "markers": [
                    { "name": "altona", "title": "Altona",
                      "lat": 53.55, "lon": 9.92, "color": "#3b82f6" }
                  ],
                  "areas": [
                    { "name": "hamburg", "points": [
                      { "lat": 53.6, "lon": 9.7 },
                      { "lat": 53.4, "lon": 10.3 }
                    ]}
                  ],
                  "routes": [
                    { "name": "hh-berlin", "waypoints": [
                      { "place": "Hamburg" },
                      { "place": "Berlin" }
                    ]}
                  ]
                }
                """;

        MapDocument doc1 = MapCodec.parse(original, "application/json");
        String out = MapCodec.serialize(doc1, "application/json");
        MapDocument doc2 = MapCodec.parse(out, "application/json");

        assertThat(doc2.markers()).hasSize(1);
        assertThat(doc2.areas()).hasSize(1);
        assertThat(doc2.routes()).hasSize(1);
        assertThat(doc2.view().center().place()).isEqualTo("Hamburg");
        assertThat(doc2.view().zoom()).isEqualTo(10);
        assertThat(doc2.markers().get(0).color()).isEqualTo("#3b82f6");
    }

    @Test
    void roundTripYaml_preservesAllFields() {
        String original = """
                $meta:
                  kind: map
                markers:
                  - name: altona
                    title: Altona
                    lat: 53.55
                    lon: 9.92
                areas: []
                routes: []
                """;

        MapDocument doc1 = MapCodec.parse(original, "application/yaml");
        String out = MapCodec.serialize(doc1, "application/yaml");
        MapDocument doc2 = MapCodec.parse(out, "application/yaml");

        assertThat(doc2.markers()).hasSize(1);
        assertThat(doc2.markers().get(0).title()).isEqualTo("Altona");
        assertThat(doc2.markers().get(0).location().lat()).isEqualTo(53.55);
    }

    // ── Errors ──────────────────────────────────────────────────────

    @Test
    void parse_unsupportedMimeThrows() {
        assertThatThrownBy(() -> MapCodec.parse("# md\n", "text/markdown"))
                .isInstanceOf(KindCodecException.class)
                .hasMessageContaining("Unsupported mime type");
    }

    @Test
    void parseJson_invalidJsonThrows() {
        assertThatThrownBy(() -> MapCodec.parse("{not json", "application/json"))
                .isInstanceOf(KindCodecException.class)
                .hasMessageContaining("Invalid JSON");
    }

    @Test
    void supports_truthyForJsonAndYaml() {
        assertThat(MapCodec.supports("application/json")).isTrue();
        assertThat(MapCodec.supports("application/yaml")).isTrue();
        assertThat(MapCodec.supports("text/yaml")).isTrue();
        assertThat(MapCodec.supports("text/markdown")).isFalse();
        assertThat(MapCodec.supports(null)).isFalse();
    }
}
