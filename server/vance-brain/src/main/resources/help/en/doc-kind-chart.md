# Chart

Numeric data rendered as a chart. The document holds the data and the
chart configuration together — there is no separate "chart of that
table" link.

**JSON and YAML only** — no markdown form.

## Chart types

`line`, `bar`, `area`, `scatter`, `pie`, `donut`, `candlestick`,
`heatmap`.

The first four are axis-shaped: they read `xAxis` / `yAxis` and a
series of `{x, y}` points. `pie` and `donut` are name/value-shaped —
the same `{x, y}` pairs, where `x` is the slice label.

## On disk

```yaml
$meta:
  kind: chart
chart:
  chartType: line
  title: New Chart
xAxis:
  type: category
yAxis:
  type: value
series:
  - name: Series 1
    data:
      - { x: A, y: 10 }
      - { x: B, y: 20 }
      - { x: C, y: 15 }
```

Axis `type` is one of `category`, `value`, `time`, `log`. Several
entries under `series:` draw several lines or grouped bars.

## In the editor

*View* renders the chart; *Edit* is the source. Changing the chart
type is a one-word edit — the data underneath usually stays valid,
which makes trying `bar` instead of `line` cheap.

## When not to use it

If the numbers are the point and the picture is incidental, keep them
in records or a sheet.
A chart document is for when the shape of the data is what you want to
look at.
