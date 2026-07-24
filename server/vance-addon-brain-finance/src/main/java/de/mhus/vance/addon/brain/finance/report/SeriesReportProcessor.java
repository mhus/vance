package de.mhus.vance.addon.brain.finance.report;

import de.mhus.vance.addon.brain.finance.FinanceProjector;
import de.mhus.vance.addon.brain.finance.model.FinanceProjection;
import de.mhus.vance.addon.brain.finance.model.FinanceTreeDocument;
import de.mhus.vance.addon.brain.finance.model.ProjectionPeriod;
import de.mhus.vance.addon.brain.finance.model.ProjectionRow;
import de.mhus.vance.shared.document.kind.AxisType;
import de.mhus.vance.shared.document.kind.ChartAxis;
import de.mhus.vance.shared.document.kind.ChartCodec;
import de.mhus.vance.shared.document.kind.ChartDocument;
import de.mhus.vance.shared.document.kind.ChartHeader;
import de.mhus.vance.shared.document.kind.ChartSeries;
import de.mhus.vance.shared.document.kind.ChartType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code series} report → {@code kind: chart}. One series per node, the period
 * labels as a category x-axis, {@code {x,y}} data points. The chart type
 * ({@code line}/{@code bar}/{@code area}/{@code scatter}) comes from the
 * {@code chartType} param (default {@code line}). Written through
 * {@link ChartCodec} (byte-identical).
 */
@Component
public class SeriesReportProcessor implements FinanceReportProcessor {

    @Override public String type() { return "series"; }

    @Override public String title() { return "Series (chart)"; }

    @Override public String outputKind() { return "chart"; }

    @Override
    public FinanceReport render(FinanceTreeDocument tree, ReportParams params) {
        ReportSupport.Range r = ReportSupport.resolveRange(type(), params);

        ChartType chartType = ChartType.fromWire(params.getString("chartType"));
        if (chartType == null) chartType = ChartType.LINE;
        if (!chartType.isXyShaped()) {
            throw new IllegalArgumentException(
                    "series report supports line/bar/area/scatter, not '" + chartType.wire() + "'");
        }

        FinanceProjection proj = tree.root() == null
                ? new FinanceProjection(List.of(), List.of())
                : FinanceProjector.project(tree.root(), r.from(), r.to(), r.granularity());

        List<String> categories = new ArrayList<>();
        for (ProjectionPeriod p : proj.periods()) categories.add(p.label());

        List<ChartSeries> series = new ArrayList<>();
        for (ProjectionRow pr : proj.rows()) {
            List<Object> data = new ArrayList<>();
            for (int i = 0; i < pr.amounts().size(); i++) {
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("x", categories.get(i));
                point.put("y", ReportSupport.round2(pr.amounts().get(i)));
                data.add(point);
            }
            series.add(new ChartSeries(pr.name(), null, data, new LinkedHashMap<>()));
        }

        ChartHeader header = new ChartHeader(chartType, tree.title(), null, true, false, false);
        ChartAxis xAxis = new ChartAxis(AxisType.CATEGORY, null, null, null, categories);
        ChartDocument chart = new ChartDocument(
                "chart", header, xAxis, ChartAxis.defaultY(), series, null, new LinkedHashMap<>());

        String body = ChartCodec.serialize(chart, ReportSupport.YAML_MIME);
        return new FinanceReport("chart", ReportSupport.YAML_MIME, body, null);
    }
}
