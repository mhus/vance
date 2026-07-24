package de.mhus.vance.addon.brain.finance.report;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.addon.brain.finance.model.FinanceNode;
import de.mhus.vance.addon.brain.finance.model.FinanceTreeDocument;
import de.mhus.vance.addon.brain.finance.model.FinanceValue;
import de.mhus.vance.addon.brain.finance.model.Period;
import de.mhus.vance.addon.brain.finance.model.PeriodUnit;
import de.mhus.vance.addon.brain.finance.model.ValueMode;
import de.mhus.vance.shared.document.kind.SheetCell;
import de.mhus.vance.shared.document.kind.SheetCodec;
import de.mhus.vance.shared.document.kind.SheetDocument;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TableReportProcessorTest {

    private static final String YAML = "application/yaml";
    private static final ReportContext CTX = new ReportContext("t1", "p1", null, null);

    /** Single leaf "x" at 3650/year (= 10/day). */
    private static FinanceTreeDocument tree() {
        FinanceValue v = new FinanceValue(3650, ValueMode.RECURRING,
                new Period(1, PeriodUnit.YEAR), null, null, null, null);
        FinanceNode root = new FinanceNode("x", null, null, null, 1, null, null,
                List.of(v), List.of());
        return new FinanceTreeDocument(1, "Plan", null, root);
    }

    private static String cell(SheetDocument s, String field) {
        return s.cells().stream().filter(c -> c.field().equals(field))
                .map(SheetCell::data).findFirst().orElse(null);
    }

    @Test
    void render_producesSheetMatrixWithHeaderPeriodsAndTotal() {
        FinanceReport report = new TableReportProcessor().render(
                tree(),
                ReportParams.of(Map.of("from", "2026-01-01", "to", "2026-03-01",
                        "granularity", "month")), CTX);

        assertThat(report.outputKind()).isEqualTo("sheet");
        SheetDocument sheet = SheetCodec.parse(report.body(), YAML);

        assertThat(cell(sheet, "A1")).isEqualTo("Node");
        assertThat(cell(sheet, "B1")).isEqualTo("2026-01");
        assertThat(cell(sheet, "C1")).isEqualTo("2026-02");
        assertThat(cell(sheet, "D1")).isEqualTo("Total");

        assertThat(cell(sheet, "A2")).isEqualTo("x");
        assertThat(cell(sheet, "B2")).isEqualTo("310.00"); // Jan: 31 days × 10
        assertThat(cell(sheet, "C2")).isEqualTo("280.00"); // Feb: 28 days × 10
        assertThat(cell(sheet, "D2")).isEqualTo("590.00");
    }

    @Test
    void render_bodyRoundTripsThroughSheetCodec() {
        FinanceReport report = new TableReportProcessor().render(
                tree(),
                ReportParams.of(Map.of("from", "2026-01-01", "to", "2026-02-01")), CTX);
        SheetDocument sheet = SheetCodec.parse(report.body(), YAML);
        assertThat(SheetCodec.serialize(sheet, YAML)).isEqualTo(report.body());
    }
}
