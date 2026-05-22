package de.mhus.vance.shared.document.kind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Codec round-trip and validation behaviour for {@code kind: chart}.
 * Tests focus on the parser-side decisions (shape validation per
 * chartType, $meta unwrap, axis defaulting for pie/donut) and on the
 * serialiser's output shape (canonical key order, optional fields
 * elided, point-form preserved per entry).
 */
class ChartCodecTest {

    // ── parse: JSON happy paths ────────────────────────────────────

    @Test
    void parseJson_linePoints_keepsObjectForm() {
        String body = """
                {
                  "$meta": { "kind": "chart" },
                  "chart": { "chartType": "line" },
                  "series": [
                    { "name": "Web", "data": [
                      { "x": "2024-01-01", "y": 10 },
                      { "x": "2024-01-02", "y": 12 }
                    ] }
                  ]
                }
                """;

        ChartDocument doc = ChartCodec.parse(body, "application/json");

        assertThat(doc.kind()).isEqualTo("chart");
        assertThat(doc.chart().chartType()).isEqualTo(ChartType.LINE);
        assertThat(doc.series()).hasSize(1);
        assertThat(doc.series().get(0).name()).isEqualTo("Web");
        assertThat(doc.series().get(0).data()).hasSize(2);
        assertThat(doc.series().get(0).data().get(0)).isInstanceOf(Map.class);
    }

    @Test
    void parseJson_linePoints_keepsTupleForm() {
        String body = """
                {
                  "$meta": { "kind": "chart" },
                  "chart": { "chartType": "bar" },
                  "series": [
                    { "name": "Q1", "data": [["a", 1], ["b", 2]] }
                  ]
                }
                """;

        ChartDocument doc = ChartCodec.parse(body, "application/json");

        assertThat(doc.series().get(0).data())
                .hasSize(2)
                .allMatch(p -> p instanceof List<?>);
    }

    @Test
    void parseJson_candlestickPoints_acceptsBothForms() {
        String body = """
                {
                  "$meta": { "kind": "chart" },
                  "chart": { "chartType": "candlestick" },
                  "series": [
                    { "name": "AAPL", "data": [
                      { "t": "2024-01-02", "o": 1.0, "h": 2.0, "l": 0.5, "c": 1.5 },
                      ["2024-01-03", 1.5, 2.5, 1.0, 2.0, 1000]
                    ] }
                  ]
                }
                """;

        ChartDocument doc = ChartCodec.parse(body, "application/json");

        assertThat(doc.series().get(0).data()).hasSize(2);
    }

    @Test
    void parseJson_pieOmitsAxes() {
        String body = """
                {
                  "$meta": { "kind": "chart" },
                  "chart": { "chartType": "pie" },
                  "xAxis": { "type": "category" },
                  "yAxis": { "type": "value" },
                  "series": [
                    { "name": "Share", "data": [
                      { "name": "A", "value": 30 },
                      { "name": "B", "value": 70 }
                    ] }
                  ]
                }
                """;

        ChartDocument doc = ChartCodec.parse(body, "application/json");

        assertThat(doc.xAxis()).isNull();
        assertThat(doc.yAxis()).isNull();
    }

    @Test
    void parseJson_legendDefaultsTrue() {
        String body = """
                {
                  "$meta": { "kind": "chart" },
                  "chart": { "chartType": "line" },
                  "series": [{ "name": "A", "data": [{ "x": 1, "y": 2 }] }]
                }
                """;

        ChartDocument doc = ChartCodec.parse(body, "application/json");
        assertThat(doc.chart().legend()).isTrue();
    }

    @Test
    void parseJson_axisDefaults_whenBlockMissing() {
        String body = """
                {
                  "$meta": { "kind": "chart" },
                  "chart": { "chartType": "line" },
                  "series": [{ "name": "A", "data": [{ "x": 1, "y": 2 }] }]
                }
                """;

        ChartDocument doc = ChartCodec.parse(body, "application/json");

        assertThat(doc.xAxis()).isNotNull();
        assertThat(doc.xAxis().type()).isEqualTo(AxisType.CATEGORY);
        assertThat(doc.yAxis()).isNotNull();
        assertThat(doc.yAxis().type()).isEqualTo(AxisType.VALUE);
    }

    // ── parse: YAML happy paths ────────────────────────────────────

    @Test
    void parseYaml_metaAtTopLevel() {
        String body = """
                $meta:
                  kind: chart
                chart:
                  chartType: line
                  title: Daily Active Users
                series:
                  - name: Web
                    data:
                      - { x: '2024-01-01', y: 1200 }
                """;

        ChartDocument doc = ChartCodec.parse(body, "application/yaml");

        assertThat(doc.kind()).isEqualTo("chart");
        assertThat(doc.chart().chartType()).isEqualTo(ChartType.LINE);
        assertThat(doc.chart().title()).isEqualTo("Daily Active Users");
        assertThat(doc.series()).hasSize(1);
    }

    @Test
    void parseYaml_emptyBody_returnsEmptyDocument() {
        ChartDocument doc = ChartCodec.parse("", "application/yaml");
        assertThat(doc.kind()).isEqualTo("chart");
        assertThat(doc.series()).isEmpty();
    }

    // ── parse: validation + error paths ────────────────────────────

    @Test
    void parseJson_missingChartType_throws() {
        String body = """
                {
                  "$meta": { "kind": "chart" },
                  "chart": { "title": "Whoops" },
                  "series": []
                }
                """;
        assertThatThrownBy(() -> ChartCodec.parse(body, "application/json"))
                .isInstanceOf(KindCodecException.class)
                .hasMessageContaining("chart.chartType");
    }

    @Test
    void parseJson_unknownChartType_throws() {
        String body = """
                {
                  "$meta": { "kind": "chart" },
                  "chart": { "chartType": "donutbar" },
                  "series": []
                }
                """;
        assertThatThrownBy(() -> ChartCodec.parse(body, "application/json"))
                .isInstanceOf(KindCodecException.class)
                .hasMessageContaining("Unknown chartType");
    }

    @Test
    void parseJson_missingChartBlock_throws() {
        String body = """
                {
                  "$meta": { "kind": "chart" },
                  "series": []
                }
                """;
        assertThatThrownBy(() -> ChartCodec.parse(body, "application/json"))
                .isInstanceOf(KindCodecException.class)
                .hasMessageContaining("`chart` block");
    }

    @Test
    void parseJson_invalidJson_throws() {
        assertThatThrownBy(() -> ChartCodec.parse("not json", "application/json"))
                .isInstanceOf(KindCodecException.class);
    }

    @Test
    void parseJson_malformedDataPoints_dropped() {
        // candlestick needs t/o/h/l/c — these two object-form points
        // lack the keys; tuple-form is too short. Last point is valid.
        String body = """
                {
                  "$meta": { "kind": "chart" },
                  "chart": { "chartType": "candlestick" },
                  "series": [
                    { "name": "OHLC", "data": [
                      { "x": 1, "y": 2 },
                      [1, 2, 3],
                      { "t": "d", "o": 1, "h": 2, "l": 0, "c": 1.5 }
                    ] }
                  ]
                }
                """;

        ChartDocument doc = ChartCodec.parse(body, "application/json");
        assertThat(doc.series().get(0).data()).hasSize(1);
    }

    @Test
    void parseJson_seriesWithNoValidPoints_elided() {
        String body = """
                {
                  "$meta": { "kind": "chart" },
                  "chart": { "chartType": "line" },
                  "series": [
                    { "name": "Empty", "data": [{ "wrong": "shape" }] },
                    { "name": "Good", "data": [{ "x": 1, "y": 2 }] }
                  ]
                }
                """;

        ChartDocument doc = ChartCodec.parse(body, "application/json");
        assertThat(doc.series()).extracting(ChartSeries::name).containsExactly("Good");
    }

    // ── serialize ──────────────────────────────────────────────────

    @Test
    void serializeJson_wrapsWithMeta_andEmitsCanonicalKeyOrder() {
        ChartDocument doc = new ChartDocument(
                "chart",
                new ChartHeader(ChartType.LINE, "Title", null, true, false, false),
                ChartAxis.defaultX(),
                ChartAxis.defaultY(),
                List.of(new ChartSeries("Web", null,
                        List.of(Map.of("x", "a", "y", 1)),
                        Map.of())),
                null,
                Map.of());

        String json = ChartCodec.serialize(doc, "application/json");

        assertThat(json).startsWith("{");
        assertThat(json).contains("\"$meta\"");
        assertThat(json).contains("\"kind\" : \"chart\"");
        assertThat(json).contains("\"chartType\" : \"line\"");
        assertThat(json.indexOf("\"$meta\""))
                .isLessThan(json.indexOf("\"chart\""))
                .isLessThan(json.indexOf("\"series\""));
    }

    @Test
    void serializeJson_omitsAxesWhenNull() {
        // pie chart — promoteToDocument sets x/yAxis to null.
        ChartDocument doc = new ChartDocument(
                "chart",
                ChartHeader.of(ChartType.PIE),
                null, null,
                List.of(new ChartSeries("S", null,
                        List.of(Map.of("name", "A", "value", 1)), Map.of())),
                null, Map.of());

        String json = ChartCodec.serialize(doc, "application/json");

        assertThat(json).doesNotContain("\"xAxis\"");
        assertThat(json).doesNotContain("\"yAxis\"");
    }

    @Test
    void serializeJson_omitsLegendWhenDefault_emitsWhenFalse() {
        ChartDocument legendOn = new ChartDocument(
                "chart",
                new ChartHeader(ChartType.BAR, null, null, true, false, false),
                ChartAxis.defaultX(), ChartAxis.defaultY(),
                List.of(new ChartSeries("S", null, List.of(Map.of("x", "a", "y", 1)), Map.of())),
                null, Map.of());
        ChartDocument legendOff = new ChartDocument(
                "chart",
                new ChartHeader(ChartType.BAR, null, null, false, false, false),
                ChartAxis.defaultX(), ChartAxis.defaultY(),
                List.of(new ChartSeries("S", null, List.of(Map.of("x", "a", "y", 1)), Map.of())),
                null, Map.of());

        assertThat(ChartCodec.serialize(legendOn, "application/json"))
                .doesNotContain("\"legend\"");
        assertThat(ChartCodec.serialize(legendOff, "application/json"))
                .contains("\"legend\" : false");
    }

    @Test
    void serializeYaml_emitsMetaAsTopLevelKey() {
        ChartDocument doc = new ChartDocument(
                "chart",
                ChartHeader.of(ChartType.LINE),
                ChartAxis.defaultX(), ChartAxis.defaultY(),
                List.of(new ChartSeries("Web", null,
                        List.of(Map.of("x", "a", "y", 1)), Map.of())),
                null, Map.of());

        String yaml = ChartCodec.serialize(doc, "application/yaml");

        assertThat(yaml).startsWith("$meta:");
        assertThat(yaml).contains("kind: chart");
        assertThat(yaml).contains("chartType: line");
    }

    // ── round-trip ─────────────────────────────────────────────────

    @Test
    void roundTrip_json_preservesEverything() {
        String body = """
                {
                  "$meta": { "kind": "chart" },
                  "chart": { "chartType": "line", "title": "T", "smooth": true },
                  "xAxis": { "type": "time", "label": "Date" },
                  "yAxis": { "type": "value" },
                  "series": [
                    { "name": "A", "color": "#3b82f6", "data": [
                      { "x": "2024-01-01", "y": 1 },
                      { "x": "2024-01-02", "y": 2 }
                    ] }
                  ]
                }
                """;

        ChartDocument first = ChartCodec.parse(body, "application/json");
        String written = ChartCodec.serialize(first, "application/json");
        ChartDocument second = ChartCodec.parse(written, "application/json");

        assertThat(second.kind()).isEqualTo(first.kind());
        assertThat(second.chart()).isEqualTo(first.chart());
        assertThat(second.series().get(0).name()).isEqualTo("A");
        assertThat(second.series().get(0).color()).isEqualTo("#3b82f6");
        assertThat(second.series().get(0).data()).hasSize(2);
    }

    @Test
    void roundTrip_yaml_preservesEverything() {
        String body = """
                $meta:
                  kind: chart
                chart:
                  chartType: candlestick
                  title: AAPL
                xAxis:
                  type: time
                yAxis:
                  type: value
                series:
                  - name: AAPL
                    data:
                      - { t: '2024-01-02', o: 1.0, h: 2.0, l: 0.5, c: 1.5 }
                """;

        ChartDocument first = ChartCodec.parse(body, "application/yaml");
        String written = ChartCodec.serialize(first, "application/yaml");
        ChartDocument second = ChartCodec.parse(written, "application/yaml");

        assertThat(second.chart().chartType()).isEqualTo(ChartType.CANDLESTICK);
        assertThat(second.chart().title()).isEqualTo("AAPL");
        assertThat(second.series().get(0).data()).hasSize(1);
    }

    // ── extra passthrough ──────────────────────────────────────────

    @Test
    void parseJson_unknownTopLevelKeys_landInExtra() {
        String body = """
                {
                  "$meta": { "kind": "chart" },
                  "chart": { "chartType": "line" },
                  "series": [{ "name": "A", "data": [{ "x": 1, "y": 2 }] }],
                  "customField": "hello"
                }
                """;

        ChartDocument doc = ChartCodec.parse(body, "application/json");
        assertThat(doc.extra()).containsEntry("customField", "hello");
    }

    @Test
    void parseJson_echartsOverride_kept() {
        String body = """
                {
                  "$meta": { "kind": "chart" },
                  "chart": { "chartType": "line" },
                  "series": [{ "name": "A", "data": [{ "x": 1, "y": 2 }] }],
                  "echartsOptionOverride": { "backgroundColor": "#fff" }
                }
                """;

        ChartDocument doc = ChartCodec.parse(body, "application/json");
        assertThat(doc.echartsOptionOverride())
                .isNotNull()
                .containsEntry("backgroundColor", "#fff");
    }

    @Test
    void parse_unsupportedMime_throws() {
        assertThatThrownBy(() -> ChartCodec.parse("---\nkind: chart\n", "text/markdown"))
                .isInstanceOf(KindCodecException.class)
                .hasMessageContaining("Unsupported mime");
    }

    @Test
    void supports_jsonAndYaml_only() {
        assertThat(ChartCodec.supports("application/json")).isTrue();
        assertThat(ChartCodec.supports("application/yaml")).isTrue();
        assertThat(ChartCodec.supports("text/yaml")).isTrue();
        assertThat(ChartCodec.supports("text/markdown")).isFalse();
        assertThat(ChartCodec.supports(null)).isFalse();
    }
}
