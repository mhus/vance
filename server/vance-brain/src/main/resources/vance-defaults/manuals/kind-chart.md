---
triggers: chart, plot, graph (numerical), line chart, bar chart, Balkendiagramm, Liniendiagramm, pie, donut, scatter, area, candlestick, OHLC, heatmap, time series, Zeitreihe, Verteilung, ECharts, visualize data, numerical comparison
summary: Render numerical data as a chart (line/bar/area/scatter/pie/donut/candlestick/heatmap) inline in chat.
---
# Inline kind — `chart`

Numerical data with axes, rendered as ECharts. One discriminator
(`chartType`) covers Line, Bar, Area, Scatter, Pie, Donut,
Candlestick and Heatmap — the data-point shape is what varies.

## Syntax — YAML body, fixed schema

````
```chart
$meta:
  kind: chart
chart:
  chartType: bar
  title: Sales Q1
xAxis: { type: category }
yAxis: { type: value }
series:
  - name: Revenue
    data:
      - { x: Jan, y: 12000 }
      - { x: Feb, y: 14500 }
      - { x: Mar, y: 13200 }
```
````

Top-level keys: `$meta`, `chart`, `xAxis`, `yAxis`, `series`.
`chartType` is one of `line`, `bar`, `area`, `scatter`, `pie`,
`donut`, `candlestick`, `heatmap`.

## Data-point shape per `chartType`

| chartType | Object form | Tuple form |
|---|---|---|
| `line` / `bar` / `area` / `scatter` | `{ x, y }` | `[x, y]` |
| `pie` / `donut` | `{ name, value }` | — (no tuple) |
| `candlestick` | `{ t, o, h, l, c }` (`v` optional) | `[t, o, h, l, c]` |
| `heatmap` | `{ x, y, v }` | `[x, y, v]` |

Pie/donut have no axes — omit `xAxis`/`yAxis` (the codec drops them
anyway).

## When to use this

User wants to *see* a numerical comparison or trend right now:
"plot the revenue", "zeig die Verteilung", "compare these numbers as
a chart". The data is small enough to embed inline.

## Anti-patterns

- **Raw ECharts options.** `dataset.source`, bare `series[].type`
  without `name` and `data` — the codec rejects them. Use the Vance
  schema above. The codec error tells you the expected shape.
- **Mismatched data shape.** Points that don't match the per-
  `chartType` shape are **silently dropped**, not coerced. Modes:
  - *Some* points in a series wrong → series renders with the rest
    (no warning).
  - *All* points in a series wrong → the whole series is elided
    (legend slot missing, no warning).
  - *All* series end up empty → codec throws an explicit error.
  So a chart that looks "missing half the data" or "missing a series
  entirely" almost always means the data-point shape is off — check
  the table above for the right keys (`{x,y}` vs `{name,value}` vs
  `{t,o,h,l,c}` vs `{x,y,v}`). The codec is a structural gate; it
  does not coerce string-numbers, swap keys, or guess intent.
- **Charts for non-numerical data.** For categorical hierarchies use
  `kind: mindmap` or `kind: tree`. For node-and-edge data use
  `kind: graph`. For text-driven diagrams (flowchart, sequence) use
  `kind: diagram` with Mermaid.

## When to graduate to a Document

- Body grows past ~50 lines (lots of series or data points).
- The chart is referenced repeatedly across sessions.
- Output is part of a larger report.

Then `doc_create_kind(kind="chart", …)` and embed the returned
`markdownLink` — see `manual_read('embed-documents')`.
