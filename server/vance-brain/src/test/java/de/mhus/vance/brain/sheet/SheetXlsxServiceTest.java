package de.mhus.vance.brain.sheet;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.document.kind.SheetCell;
import de.mhus.vance.shared.document.kind.SheetColumn;
import de.mhus.vance.shared.document.kind.SheetDocument;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SheetXlsxServiceTest {

    private final SheetXlsxService service = new SheetXlsxService();

    private static SheetCell cell(String field, String data) {
        return new SheetCell(field, data, null, null, new LinkedHashMap<>());
    }

    private static SheetCell fmtCell(String field, String data, boolean bold,
                                     String align, String numberFormat, String borders,
                                     String bg) {
        return new SheetCell(field, data, null, bg, bold ? Boolean.TRUE : null, null,
                align, numberFormat, borders, new LinkedHashMap<>());
    }

    private static Map<String, SheetColumn> cols(String col, int width) {
        Map<String, SheetColumn> m = new LinkedHashMap<>();
        m.put(col, new SheetColumn(width, null));
        return m;
    }

    @Test
    void xlsx_roundTripsDataAndFormat() {
        SheetDocument doc = new SheetDocument("sheet", List.of("A", "B"), 2,
                new java.util.ArrayList<>(List.of(
                        cell("A1", "Name"),
                        cell("B1", "10"),
                        fmtCell("A2", "Alice", true, "center", null, "trbl", "#ffcc00"),
                        cell("B2", "=B1*2"))),
                cols("A", 160), new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>());

        byte[] xlsx = service.exportXlsx(doc);
        assertThat(xlsx).isNotEmpty();

        SheetDocument back = service.importXlsx(xlsx);
        Map<String, SheetCell> byField = new LinkedHashMap<>();
        for (SheetCell c : back.cells()) byField.put(c.field(), c);

        assertThat(byField.get("A1").data()).isEqualTo("Name");
        assertThat(byField.get("B1").data()).isEqualTo("10");
        assertThat(byField.get("B2").data()).isEqualTo("=B1*2");
        SheetCell a2 = byField.get("A2");
        assertThat(a2.data()).isEqualTo("Alice");
        assertThat(a2.bold()).isTrue();
        assertThat(a2.align()).isEqualTo("center");
        assertThat(a2.borders()).isEqualTo("trbl");
        assertThat(a2.background()).isEqualToIgnoringCase("#ffcc00");
        assertThat(back.schema()).containsExactly("A", "B");
    }

    @Test
    void csv_roundTripsValues() {
        SheetDocument doc = new SheetDocument("sheet", List.of("A", "B"), 2,
                new java.util.ArrayList<>(List.of(
                        cell("A1", "a,b"), cell("B1", "x"), cell("A2", "line\nbreak"))),
                new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(),
                new LinkedHashMap<>());

        String csv = service.exportCsv(doc);
        SheetDocument back = service.importCsv(csv);
        Map<String, String> byField = new LinkedHashMap<>();
        for (SheetCell c : back.cells()) byField.put(c.field(), c.data());

        assertThat(byField.get("A1")).isEqualTo("a,b");
        assertThat(byField.get("B1")).isEqualTo("x");
        assertThat(byField.get("A2")).isEqualTo("line\nbreak");
    }
}
