package de.mhus.vance.addon.brain.finance.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.addon.brain.finance.model.FinanceNode;
import de.mhus.vance.addon.brain.finance.model.FinanceTreeDocument;
import de.mhus.vance.addon.brain.finance.model.FinanceValue;
import de.mhus.vance.addon.brain.finance.model.Period;
import de.mhus.vance.addon.brain.finance.model.PeriodUnit;
import de.mhus.vance.addon.brain.finance.model.ValueMode;
import de.mhus.vance.shared.document.kind.ChartCodec;
import de.mhus.vance.shared.document.kind.ChartDocument;
import de.mhus.vance.shared.document.kind.ChartType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SeriesReportProcessorTest {

    private static final String YAML = "application/yaml";
    private static final ReportContext CTX = new ReportContext("t1", "p1", null, null);

    private static FinanceTreeDocument tree() {
        FinanceValue v = new FinanceValue(3650, ValueMode.RECURRING,
                new Period(1, PeriodUnit.YEAR), null, null, null, null);
        FinanceNode root = new FinanceNode("x", null, null, null, 1, null, null,
                List.of(v), List.of());
        return new FinanceTreeDocument(1, "Plan", null, root);
    }

    private static ReportParams params(Map<String, Object> extra) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>(extra);
        m.putIfAbsent("from", "2026-01-01");
        m.putIfAbsent("to", "2026-03-01");
        m.putIfAbsent("granularity", "month");
        return ReportParams.of(m);
    }

    @Test
    void render_producesLineChartWithCategoriesAndXyPoints() {
        FinanceReport report = new SeriesReportProcessor().render(tree(), params(Map.of()), CTX);

        assertThat(report.outputKind()).isEqualTo("chart");
        ChartDocument chart = ChartCodec.parse(report.body(), YAML);

        assertThat(chart.chart().chartType()).isEqualTo(ChartType.LINE);
        assertThat(chart.chart().title()).isEqualTo("Plan");
        assertThat(chart.xAxis().categories()).containsExactly("2026-01", "2026-02");
        assertThat(chart.series()).hasSize(1);
        assertThat(chart.series().get(0).name()).isEqualTo("x");

        @SuppressWarnings("unchecked")
        Map<String, Object> point0 = (Map<String, Object>) chart.series().get(0).data().get(0);
        assertThat(point0.get("x")).isEqualTo("2026-01");
        assertThat(((Number) point0.get("y")).doubleValue()).isEqualTo(310.0);
    }

    @Test
    void render_honoursChartTypeParam() {
        FinanceReport report = new SeriesReportProcessor()
                .render(tree(), params(Map.of("chartType", "bar")), CTX);
        ChartDocument chart = ChartCodec.parse(report.body(), YAML);
        assertThat(chart.chart().chartType()).isEqualTo(ChartType.BAR);
    }

    @Test
    void render_rejectsNonXyChartType() {
        assertThatThrownBy(() -> new SeriesReportProcessor()
                .render(tree(), params(Map.of("chartType", "pie")), CTX))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("line/bar/area/scatter");
    }

    @Test
    void render_requiresRange() {
        assertThatThrownBy(() -> new SeriesReportProcessor()
                .render(tree(), ReportParams.of(Map.of("granularity", "month")), CTX))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'from' and 'to'");
    }
}
