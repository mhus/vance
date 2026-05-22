export type ChartType = 'line' | 'bar' | 'area' | 'scatter' | 'pie' | 'donut' | 'candlestick' | 'heatmap';
export type AxisType = 'category' | 'value' | 'time' | 'log';
export interface ChartHeader {
    chartType: ChartType;
    title?: string;
    subtitle?: string;
    /** Whether the legend is shown. Default true. */
    legend: boolean;
    /** Whether series are stacked (bar / area / line only). Default false. */
    stacked: boolean;
    /** Whether line / area series use spline interpolation. Default false. */
    smooth: boolean;
}
export interface ChartAxis {
    type: AxisType;
    label?: string;
    min?: number;
    max?: number;
    /** Explicit category order; only meaningful when {@code type} is
     *  {@code category}. Empty by default. */
    categories: string[];
}
/** A single data point. Object-form (Map<string, unknown>) or tuple-
 *  form (unknown[]); the renderer interprets the shape based on the
 *  document's {@code chartType}. */
export type ChartDataPoint = Record<string, unknown> | unknown[];
export interface ChartSeries {
    name: string;
    color?: string;
    data: ChartDataPoint[];
    /** Unknown per-series fields, preserved across round-trip. */
    extra: Record<string, unknown>;
}
export interface ChartDocument {
    /** Always `'chart'`. */
    kind: string;
    chart: ChartHeader;
    /** {@code null} for pie / donut where axes are meaningless. */
    xAxis: ChartAxis | null;
    yAxis: ChartAxis | null;
    series: ChartSeries[];
    /** Raw ECharts option subtree, deep-merged over the schema-generated
     *  option at render time. {@code null} when not present on disk. */
    echartsOptionOverride: Record<string, unknown> | null;
    /** Unknown top-level fields. Re-emitted verbatim on save. */
    extra: Record<string, unknown>;
}
export declare class ChartCodecError extends Error {
    readonly cause?: unknown;
    constructor(message: string, cause?: unknown);
}
export declare function parseChart(body: string, mimeType: string): ChartDocument;
export declare function serializeChart(doc: ChartDocument, mimeType: string): string;
/** Whether the codec can handle this mime type — used by the editor
 *  to decide whether to offer the Chart tab. Markdown is intentionally
 *  excluded; spec §3.3. */
export declare function isChartMime(mimeType: string | null | undefined): boolean;
/** {@code true} when the chart type uses {x, y}-shaped data points
 *  (line, bar, area, scatter). Useful for type-switch compatibility
 *  checks in the editor. */
export declare function isXyShaped(type: ChartType): boolean;
/** {@code true} for pie / donut — data points are {name, value}. */
export declare function isNamedValueShaped(type: ChartType): boolean;
export declare function emptyDoc(): ChartDocument;
//# sourceMappingURL=chartCodec.d.ts.map