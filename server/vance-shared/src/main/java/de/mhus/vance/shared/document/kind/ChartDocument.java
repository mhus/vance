package de.mhus.vance.shared.document.kind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * In-memory model of a {@code kind: chart} document. One discriminator
 * ({@link ChartHeader#chartType()}) covers all chart types; the per-type
 * data-point shape is documented in
 * {@code specification/doc-kind-chart.md} §2.5.
 *
 * @param kind                    always {@code "chart"}.
 * @param chart                   document-level chart metadata.
 * @param xAxis                   {@code null} for pie/donut where axes
 *                                are meaningless; otherwise a populated
 *                                axis (default {@link ChartAxis#defaultX()}
 *                                when the on-disk body omitted the block).
 * @param yAxis                   {@code null} for pie/donut; populated
 *                                otherwise.
 * @param series                  at least one series is required when the
 *                                document is non-empty; the codec throws
 *                                {@link KindCodecException} when none
 *                                survives shape validation.
 * @param echartsOptionOverride   raw ECharts option subtree applied as a
 *                                deep-merge over the schema-generated
 *                                option in the renderer. {@code null}
 *                                when the on-disk body has no override.
 * @param extra                   unknown top-level fields, passthrough.
 *
 * <p>Spec: {@code specification/doc-kind-chart.md}.
 */
public record ChartDocument(
        String kind,
        ChartHeader chart,
        @Nullable ChartAxis xAxis,
        @Nullable ChartAxis yAxis,
        List<ChartSeries> series,
        @Nullable Map<String, Object> echartsOptionOverride,
        Map<String, Object> extra) {

    public ChartDocument {
        if (kind == null || kind.isBlank()) kind = "chart";
        if (series == null) series = new ArrayList<>();
        if (extra == null) extra = new LinkedHashMap<>();
    }

    /**
     * A blank chart skeleton (line chart, no series). Useful when the
     * codec is asked for an empty body — callers fill the series list
     * before serialising.
     */
    public static ChartDocument empty() {
        return new ChartDocument(
                "chart",
                ChartHeader.of(ChartType.LINE),
                ChartAxis.defaultX(),
                ChartAxis.defaultY(),
                new ArrayList<>(),
                null,
                new LinkedHashMap<>());
    }
}
