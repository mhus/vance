package de.mhus.vance.shared.document.kind;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class SheetComputedCodecTest {

    private static SheetDocument sheet() {
        return new SheetDocument("sheet", List.of("A", "B"), 3,
                new java.util.ArrayList<>(List.of(
                        new SheetCell("A1", "10", null, null, new LinkedHashMap<>()),
                        new SheetCell("B1", "=A1*2", null, null, new LinkedHashMap<>()))),
                new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    private static SheetComputed computed() {
        return new SheetComputed("2026-07-25T10:00:00Z",
                List.of(new SheetComputed.Value("B1", "20", "number", null)));
    }

    @Test
    void serialize_writesComputedOverlay_json() {
        String json = SheetCodec.serialize(sheet(), computed(), "application/json");
        assertThat(json).contains("$computed").contains("\"B1\"").contains("\"20\"");
    }

    @Test
    void serialize_writesComputedOverlay_yaml() {
        String yaml = SheetCodec.serialize(sheet(), computed(), "application/yaml");
        assertThat(yaml).contains("$computed").contains("B1").contains("computedAt");
    }

    @Test
    void parse_dropsComputedOverlay_roundTrip() {
        String json = SheetCodec.serialize(sheet(), computed(), "application/json");
        SheetDocument parsed = SheetCodec.parse(json, "application/json");
        // Overlay is derived — never resurfaces in the input model (not even extra).
        assertThat(parsed.extra()).doesNotContainKey("$computed");
        assertThat(parsed.cells()).hasSize(2);
        assertThat(parsed.cells().get(1).data()).isEqualTo("=A1*2");
    }

    @Test
    void serialize_withoutComputed_omitsOverlay() {
        String json = SheetCodec.serialize(sheet(), "application/json");
        assertThat(json).doesNotContain("$computed");
    }

    @Test
    void serialize_emptyComputed_omitsOverlay() {
        String json = SheetCodec.serialize(sheet(),
                new SheetComputed("2026-07-25T10:00:00Z", List.of()), "application/json");
        assertThat(json).doesNotContain("$computed");
    }

    @Test
    void columns_roundTrip() {
        java.util.Map<String, SheetColumn> cols = new LinkedHashMap<>();
        cols.put("A", new SheetColumn(140, null));
        cols.put("B", new SheetColumn(null, "right"));
        SheetDocument doc = new SheetDocument("sheet", List.of("A", "B"), 3,
                new java.util.ArrayList<>(), cols, new LinkedHashMap<>(), new LinkedHashMap<>());

        String json = SheetCodec.serialize(doc, "application/json");
        assertThat(json).contains("columns").contains("140").contains("right");

        SheetDocument parsed = SheetCodec.parse(json, "application/json");
        assertThat(parsed.columns()).containsKeys("A", "B");
        assertThat(parsed.columns().get("A").width()).isEqualTo(140);
        assertThat(parsed.columns().get("B").border()).isEqualTo("right");
        assertThat(parsed.extra()).doesNotContainKey("columns");
    }

    @Test
    void rowHeights_roundTripAndDropInvalid() {
        java.util.Map<String, Integer> heights = new LinkedHashMap<>();
        heights.put("1", 40);
        heights.put("3", 64);
        SheetDocument doc = new SheetDocument("sheet", List.of("A"), 3,
                new java.util.ArrayList<>(), new LinkedHashMap<>(), heights, new LinkedHashMap<>());

        String json = SheetCodec.serialize(doc, "application/json");
        assertThat(json).contains("rowHeights").contains("40").contains("64");

        SheetDocument parsed = SheetCodec.parse(json, "application/json");
        assertThat(parsed.rowHeights()).containsEntry("1", 40).containsEntry("3", 64);
        assertThat(parsed.extra()).doesNotContainKey("rowHeights");

        // invalid keys/values dropped
        SheetDocument bad = SheetCodec.parse(
                "{\"$meta\":{\"kind\":\"sheet\"},\"rowHeights\":{\"1\":-5,\"x\":30,\"2\":48},"
                        + "\"cells\":[]}", "application/json");
        assertThat(bad.rowHeights()).containsOnlyKeys("2");
        assertThat(bad.rowHeights().get("2")).isEqualTo(48);
    }

    @Test
    void columns_dropInvalidBorderAndWidth() {
        SheetDocument parsed = SheetCodec.parse(
                "{\"$meta\":{\"kind\":\"sheet\"},\"columns\":{\"A\":{\"width\":-5,"
                        + "\"border\":\"diagonal\"},\"zz\":{\"width\":10}},\"cells\":[]}",
                "application/json");
        // A: invalid width + invalid border → empty → dropped; zz lowercased→ZZ kept.
        assertThat(parsed.columns()).doesNotContainKey("A");
        assertThat(parsed.columns().get("ZZ").width()).isEqualTo(10);
    }
}
