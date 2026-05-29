package de.mhus.vance.shared.document.kind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Codec round-trip and edge-case behaviour for {@code kind:
 * application}. The codec is structural — the app-specific
 * config payload travels as an untyped {@code Map<String, Object>}.
 * Typing (calendar lanes, gantt etc.) is layered on top by helpers
 * like {@code CalendarsAppConfig}.
 */
class ApplicationCodecTest {

    private static final String JSON_MIME = "application/json";
    private static final String YAML_MIME = "application/yaml";

    // ── parse: $meta scalar fan-out ────────────────────────────────

    @Test
    void parseJson_metaCarriesKindAndApp() {
        String body = """
                {
                  "$meta": { "kind": "application", "app": "calendar" },
                  "title": "Sprint Q3",
                  "calendar": { "window": { "from": "2026-06-01", "until": "2026-09-30" } }
                }
                """;
        ApplicationDocument doc = ApplicationCodec.parse(body, JSON_MIME);

        assertThat(doc.kind()).isEqualTo("application");
        assertThat(doc.app()).isEqualTo("calendar");
        assertThat(doc.title()).isEqualTo("Sprint Q3");
        assertThat(doc.config()).containsKey("calendar");
    }

    @Test
    void parseYaml_metaCarriesKindAndApp() {
        String body = """
                $meta:
                  kind: application
                  app: calendar
                title: Sprint Q3
                description: Planning for Q3
                calendar:
                  window:
                    from: "2026-06-01"
                """;
        ApplicationDocument doc = ApplicationCodec.parse(body, YAML_MIME);

        assertThat(doc.kind()).isEqualTo("application");
        assertThat(doc.app()).isEqualTo("calendar");
        assertThat(doc.title()).isEqualTo("Sprint Q3");
        assertThat(doc.description()).isEqualTo("Planning for Q3");
        assertThat(doc.config()).containsKey("calendar");
    }

    // ── config-block routing ───────────────────────────────────────

    @Test
    void parse_multipleAppFaces_landInConfig() {
        // A folder could in v2 host both a calendar and a wiki app.
        // The codec stays type-agnostic so both blocks survive.
        String body = """
                $meta:
                  kind: application
                  app: calendar
                calendar:
                  lanes:
                    design: { title: Design }
                wiki:
                  startPage: index.md
                """;
        ApplicationDocument doc = ApplicationCodec.parse(body, YAML_MIME);
        assertThat(doc.config()).containsKeys("calendar", "wiki");
    }

    @Test
    void parse_unknownNonMapTopLevel_landsInExtra() {
        String body = """
                {
                  "$meta": { "kind": "application", "app": "calendar" },
                  "version": 3,
                  "calendar": { "lanes": {} }
                }
                """;
        ApplicationDocument doc = ApplicationCodec.parse(body, JSON_MIME);
        assertThat(doc.config()).containsKey("calendar");
        assertThat(doc.extra()).containsEntry("version", 3);
    }

    // ── serialize ─────────────────────────────────────────────────

    @Test
    void serializeJson_metaContainsAppAlongsideKind() {
        Map<String, Object> calConfig = new LinkedHashMap<>();
        calConfig.put("lanes", Map.of("design", Map.of("title", "Design")));
        ApplicationDocument doc = new ApplicationDocument(
                "application", "calendar", "Sprint Q3", null,
                Map.of("calendar", calConfig),
                new LinkedHashMap<>());

        String out = ApplicationCodec.serialize(doc, JSON_MIME);

        assertThat(out).contains("\"$meta\"");
        assertThat(out).contains("\"kind\" : \"application\"");
        assertThat(out).contains("\"app\" : \"calendar\"");
        assertThat(out).contains("\"title\" : \"Sprint Q3\"");
        assertThat(out).contains("\"calendar\"");
    }

    @Test
    void serializeYaml_metaContainsAppAlongsideKind() {
        Map<String, Object> calConfig = new LinkedHashMap<>();
        calConfig.put("window", Map.of("from", "2026-06-01"));
        ApplicationDocument doc = new ApplicationDocument(
                "application", "calendar", null, null,
                Map.of("calendar", calConfig),
                new LinkedHashMap<>());

        String out = ApplicationCodec.serialize(doc, YAML_MIME);

        assertThat(out).contains("$meta:");
        assertThat(out).contains("kind: application");
        assertThat(out).contains("app: calendar");
        assertThat(out).contains("calendar:");
    }

    // ── round-trip ────────────────────────────────────────────────

    @Test
    void roundTrip_yaml_preservesAllSections() {
        String body = """
                $meta:
                  kind: application
                  app: calendar
                title: Website Relaunch
                description: Q3 2026 redesign + rebuild
                calendar:
                  window:
                    from: "2026-06-01"
                    until: "2026-09-30"
                  lanes:
                    design:
                      title: Design
                      color: blue
                      order: 1
                    backend:
                      title: Backend
                      color: green
                      order: 2
                  gantt:
                    outputPath: _gantt.md
                    includeRecurring: false
                  conflicts:
                    outputPath: _conflicts.yaml
                """;

        ApplicationDocument first = ApplicationCodec.parse(body, YAML_MIME);
        String written = ApplicationCodec.serialize(first, YAML_MIME);
        ApplicationDocument second = ApplicationCodec.parse(written, YAML_MIME);

        assertThat(second.app()).isEqualTo("calendar");
        assertThat(second.title()).isEqualTo("Website Relaunch");
        assertThat(second.config()).containsKey("calendar");
        @SuppressWarnings("unchecked")
        Map<String, Object> cal = (Map<String, Object>) second.config().get("calendar");
        assertThat(cal).containsKey("window");
        assertThat(cal).containsKey("lanes");
        assertThat(cal).containsKey("gantt");
        assertThat(cal).containsKey("conflicts");
    }

    @Test
    void roundTrip_json_preservesScalars() {
        String body = """
                {
                  "$meta": { "kind": "application", "app": "calendar" },
                  "title": "T",
                  "description": "D",
                  "calendar": {}
                }
                """;
        ApplicationDocument first = ApplicationCodec.parse(body, JSON_MIME);
        String written = ApplicationCodec.serialize(first, JSON_MIME);
        ApplicationDocument second = ApplicationCodec.parse(written, JSON_MIME);

        assertThat(second.kind()).isEqualTo("application");
        assertThat(second.app()).isEqualTo("calendar");
        assertThat(second.title()).isEqualTo("T");
        assertThat(second.description()).isEqualTo("D");
    }

    // ── empty / defensive ─────────────────────────────────────────

    @Test
    void parse_emptyBody_yieldsEmptyAppManifest() {
        ApplicationDocument doc = ApplicationCodec.parse("", YAML_MIME);
        assertThat(doc.kind()).isEqualTo("application");
        assertThat(doc.app()).isEmpty();
    }

    @Test
    void parse_invalidJson_throws() {
        assertThatThrownBy(() -> ApplicationCodec.parse("{ not json", JSON_MIME))
                .isInstanceOf(KindCodecException.class)
                .hasMessageContaining("Invalid JSON");
    }

    @Test
    void parse_unsupportedMime_throws() {
        assertThatThrownBy(() -> ApplicationCodec.parse("data", "text/markdown"))
                .isInstanceOf(KindCodecException.class)
                .hasMessageContaining("Unsupported mime type");
    }

    @Test
    void supports_recognisesMimeFamily() {
        assertThat(ApplicationCodec.supports("application/json")).isTrue();
        assertThat(ApplicationCodec.supports("application/yaml")).isTrue();
        assertThat(ApplicationCodec.supports("text/yaml")).isTrue();
        assertThat(ApplicationCodec.supports("text/markdown")).isFalse();
    }
}
