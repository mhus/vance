package de.mhus.vance.brain.sheet;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.document.kind.SheetCell;
import de.mhus.vance.shared.document.kind.SheetComputed;
import de.mhus.vance.shared.document.kind.SheetDocument;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SheetEvalServiceTest {

    private final SheetEvalService service = new SheetEvalService();

    private static SheetCell cell(String field, String data) {
        return new SheetCell(field, data, null, null, new LinkedHashMap<>());
    }

    private static SheetDocument sheet(SheetCell... cells) {
        return new SheetDocument("sheet", List.of("A", "B", "C"), null,
                new ArrayList<>(List.of(cells)), new LinkedHashMap<>(),
                new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    private static Map<String, SheetComputed.Value> byField(SheetComputed c) {
        Map<String, SheetComputed.Value> m = new LinkedHashMap<>();
        for (SheetComputed.Value v : c.values()) m.put(v.field(), v);
        return m;
    }

    @Test
    void evaluate_arithmeticFormulaOnLiteral() {
        SheetComputed c = service.evaluate(sheet(cell("B2", "10"), cell("C2", "=B2*1.5")));
        Map<String, SheetComputed.Value> v = byField(c);
        assertThat(v.get("C2").value()).isEqualTo("15");
        assertThat(v.get("C2").type()).isEqualTo("number");
    }

    @Test
    void evaluate_sumOverRange() {
        SheetComputed c = service.evaluate(sheet(
                cell("A1", "1"), cell("A2", "2"), cell("A3", "3"), cell("A4", "=SUM(A1:A3)")));
        assertThat(byField(c).get("A4").value()).isEqualTo("6");
    }

    @Test
    void evaluate_divByZeroIsError() {
        SheetComputed c = service.evaluate(sheet(cell("A1", "=1/0")));
        SheetComputed.Value v = byField(c).get("A1");
        assertThat(v.type()).isEqualTo("error");
        assertThat(v.error()).isNotBlank();
    }

    @Test
    void evaluate_literalCellsAreNotInOverlay() {
        SheetComputed c = service.evaluate(sheet(cell("A1", "hello"), cell("A2", "42")));
        assertThat(c.values()).isEmpty();
    }

    @Test
    void evaluate_badFormulaDegradesToErrorNotThrow() {
        SheetComputed c = service.evaluate(sheet(cell("A1", "=SUM(")));
        assertThat(byField(c).get("A1").type()).isEqualTo("error");
    }

    @Test
    void evaluate_stringFunction() {
        SheetComputed c = service.evaluate(sheet(
                cell("A1", "foo"), cell("B1", "=UPPER(A1)")));
        assertThat(byField(c).get("B1").value()).isEqualTo("FOO");
        assertThat(byField(c).get("B1").type()).isEqualTo("text");
    }

    @Test
    void evaluate_stampsComputedAt() {
        SheetComputed c = service.evaluate(sheet(cell("A1", "=1+1")));
        assertThat(c.computedAt()).isNotBlank();
    }
}
