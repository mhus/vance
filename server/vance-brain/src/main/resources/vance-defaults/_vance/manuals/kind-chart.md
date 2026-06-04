---
triggers: chart, plot, graph (numerical), line chart, bar chart, Balkendiagramm, Liniendiagramm, pie, donut, scatter, area, candlestick, OHLC, heatmap, time series, Zeitreihe, Verteilung, ECharts, visualize data, numerical comparison
summary: Render numerical data as a chart (line/bar/area/scatter/pie/donut/candlestick/heatmap), either inline in chat or as a stored document.
---
# Document kind — `chart`

Numerical data with axes, rendered as ECharts. One discriminator
(`chartType`) covers Line, Bar, Area, Scatter, Pie, Donut,
Candlestick and Heatmap — the data-point shape is what varies.

## Two storage forms — pick by intent

The **payload is identical** in both forms. The difference is the
outer wrapper — that decides whether Vance renders the chart inline
in the chat or as a clickable document tab.

Decide first:

| Did the user ask for a saved file / document? | Use form |
|---|---|
| YES — "create a chart document", "speicher das als chart-doc" | **Stored** (below) |
| NO — "show me a chart", "plot the revenue", "zeig die Verteilung" | **Inline** (further below) |

### Stored document — raw JSON or YAML, NO fence

Call `doc_create_kind(kind="chart", path="<…>.json"` or `.yaml`,
`body=<raw schema>)`. **The body must NOT be wrapped in a
```` ```chart ```` fence** — that's the inline form. Markdown
bodies are rejected for stored charts: the codec stores the file
but the Web-UI falls back to the Raw editor and never renders the
chart tab. Always use `.json` or `.yaml` as the path extension.

YAML body example (paste as-is into the `body=` arg):

```yaml
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

JSON body is equivalent — same keys, just `{}`/`[]` instead of
indented YAML. Pick YAML for readability, JSON when the body is
generated programmatically or needs to be embedded somewhere
strict.

### Inline in chat — fence-wrapped, no tool call

When the user just wants to *see* the chart right now in the
assistant's reply (no save, no `doc_create_kind`), emit a single
```` ```chart ```` fence in the chat message — **same payload as
above, just wrapped in a fence**:

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

## Shared schema

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

When the user asks to **save** or **create a chart document** —
"speicher das als chart-doc", "erstelle ein chart-Dokument", any
phrasing that implies persistence — use the stored form via
`doc_create_kind`.

## Anti-patterns

- **Wrapping the stored body in a ```` ```chart ```` fence.** That
  is the inline-chat form. When you save via `doc_create_kind`, the
  body must be raw JSON or YAML — no fence. The codec will accept a
  fence-wrapped body (kind discrimination still works), but the
  Web-UI falls back to the Raw editor — no chart-tab, no render.
  Symptom: user gets a saved doc that opens as plain text instead of
  a chart.
- **Saving as `.md`.** Markdown is explicitly rejected for stored
  chart documents — use `.json` or `.yaml` as the path extension.
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

## When to graduate from inline to stored

- Body grows past ~50 lines (lots of series or data points).
- The chart is referenced repeatedly across sessions.
- Output is part of a larger report.

Then call `doc_create_kind(kind="chart", path="…", body=<raw YAML or
JSON>)` and embed the returned `markdownLink` — see
`manual_read('embed-documents')`. Reminder: **raw body, no fence**.
