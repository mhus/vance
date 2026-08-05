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
| YES — "create a chart document", "save this as a chart doc" | **Stored** (below) |
| NO — "show me a chart", "plot the revenue", "show the distribution" | **Inline** (further below) |

### Inline in chat — fence-wrapped, no tool call

When the user wants to *see* the chart right now in the assistant's
reply (no save, no `doc_write`), **emit a single
```` ```chart ```` fence in the chat message**. The fenced YAML body
carries the same schema as the stored form below.

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

The reply must CONTAIN this fence verbatim — narrating "Here is the
chart…" without the actual fenced block leaves the user with no
render.

### Stored document — raw JSON or YAML, NO fence

To *save* the chart, pass the **same payload without the fence** to
`doc_write(kind="chart", path="<…>.yaml", body=<raw schema>)`. The
fence is the inline-only wrapper: fence-wrapped stored bodies parse,
but the Web-UI falls back to the Raw editor and never renders the
chart tab. Path extension must be `.json` or `.yaml` — Markdown is
rejected for stored charts. JSON is equivalent, same keys.

## Shared schema

Top-level keys: `$meta`, `chart`, `xAxis`, `yAxis`, `series`,
`echartsOptionOverride`.
`chartType` is one of `line`, `bar`, `area`, `scatter`, `pie`,
`donut`, `candlestick`, `heatmap`.

`chart`: `chartType` (required), `title`, `subtitle`, `legend`,
`stacked`, `smooth`.

### Axis keys — this is a CLOSED list

`xAxis` / `yAxis` accept **exactly** `type`, `label`, `min`, `max`,
`categories` — nothing else:

| Key | Meaning |
|---|---|
| `type` | `category`, `value`, `time`, `log` |
| `label` | axis title — **not** `name` |
| `min` / `max` | force a bound (omit for auto) |
| `categories` | `type: category` only — explicit tick order |

**This is the Vance schema, not raw ECharts.** Any other axis key is
dropped silently by the codec — it is not passed through to `extra`,
so there is no warning and no render. The two that get written out of
ECharts habit and always vanish:

- `name` → the field is `label`. (ECharts calls it `name`; Vance does
  not.)
- `axisLabel`, `data`, `splitLine`, `boundaryGap`, … → not in the
  schema. If you genuinely need them, put them under top-level
  `echartsOptionOverride`, e.g. rotated tick labels:

  ```yaml
  echartsOptionOverride:
    xAxis: { axisLabel: { rotate: 45 } }
  ```

## Data-point shape per `chartType`

| chartType | Object form | Tuple form |
|---|---|---|
| `line` / `bar` / `area` / `scatter` | `{ x, y }` | `[x, y]` |
| `pie` / `donut` | `{ name, value }` | — (no tuple) |
| `candlestick` | `{ t, o, h, l, c }` (`v` optional) | `[t, o, h, l, c]` |
| `heatmap` | `{ x, y, v }` | `[x, y, v]` |

Pie/donut have no axes — omit `xAxis`/`yAxis` (the codec drops them
anyway).

## Multiple series on a category axis — `categories` is MANDATORY

Without an explicit `xAxis.categories`, the renderer infers the tick
list **from the first series only**. Every point of every later series
whose `x` is not in that inferred list has no slot on the axis and
**does not render** — silently, no codec error.

So the moment two series don't share the identical `x` set (split a
ranking by group, colour a subset differently, series with gaps), list
**every** `x` from **all** series in `xAxis.categories`, in the order
you want them drawn. Add `chart.stacked: true` so each category keeps
one full-width bar instead of splitting its slot across all series:

```yaml
chart: { chartType: bar, title: Score by licence, stacked: true }
xAxis:
  type: category
  label: Model
  categories: ["Alpha", "Beta", "Gamma", "Delta"]   # all 4, ranked
yAxis: { type: value, label: Score }
series:
  - name: Proprietary
    color: "#3b82f6"
    data: [{ x: "Alpha", y: 57.2 }, { x: "Gamma", y: 52.7 }]
  - name: Open weight
    color: "#10b981"
    data: [{ x: "Beta", y: 55.4 }, { x: "Delta", y: 46.8 }]
```

Per-**point** colour (`{ x, y, color }`) is **not** supported on
`bar`/`line`/`area`/`scatter` — the renderer maps those to `[x, y]` and
drops everything else. Only `pie`/`donut` honour a per-point `color`.
Colour-coding a bar ranking therefore means one series per colour plus
the shared `categories` list above — that is the only way.

## When to use this

User wants to *see* a numerical comparison or trend right now: "plot
the revenue", "show the distribution". Any phrasing that implies
persistence — "save this as a chart doc" — takes the stored form via
`doc_write` instead.

## Anti-patterns

- **Unquoted YAML string containing `: `.** `subtitle: Quelle: llm-stats.com`
  is not a string — it is a YAML syntax error, and it kills the whole
  document, not just that key. **Diagnostic rule: a chart that renders
  completely blank almost never has a data problem — the body failed to
  parse.** The inline renderer catches the parse error, `console.warn`s
  it and falls back to an empty chart, so there is no message in the UI.
  Quote every `title` / `subtitle` / `label` / category that contains
  `:`, `#`, or a leading `-`: `subtitle: "Quelle: llm-stats.com"`.

- **Fence-wrapping a stored body, or saving as `.md`.** Both parse but
  open in the Raw editor — no chart tab, no render. Stored bodies are
  raw JSON/YAML at `.json`/`.yaml`; the fence is inline-chat only.
- **Raw ECharts options.** `dataset.source`, bare `series[].type`
  without `name` and `data` — the codec rejects them. Use the Vance
  schema above. The codec error tells you the expected shape. On axes
  the failure is worse than a rejection: `xAxis.name`, `axisLabel`,
  `data` are dropped **silently** (see the closed axis-key list above).
  Symptom: the chart renders but has no axis titles.

- **`min` on a bar chart.** Bars encode magnitude by length, so the
  baseline must be zero — `yAxis: { min: 40 }` on `bar`/`area` makes a
  46.8-vs-57.2 gap (real factor 1.22) look like factor 2.5. That is a
  misleading chart, not a styling choice. To show small differences
  between large values, keep the zero baseline and switch to
  `line`/`scatter`, or plot the differences themselves. `min`/`max` are
  legitimate on `line`/`scatter`, where position — not length — carries
  the value.

- **Claiming an ordering the chart doesn't show.** If the prose says
  "X ranks 5th, ahead of Y", the bars must actually sit in that order —
  which on a category axis means `categories` is listed in rank order
  (see above). Sorting the data inside each series is not enough.

- **Unsourced numbers.** A chart states facts. Name the benchmark or
  source in `chart.subtitle` when the data is not the user's own (quoted
  — see the first anti-pattern), and do not invent decimals for numbers
  you did not look up — use `research_search` first. A plausible-looking
  axis of fabricated scores is the most expensive failure mode here,
  because it renders perfectly.
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

Body past ~50 lines, referenced across sessions, or part of a larger
report → `doc_write(kind="chart", path="…", content=<raw YAML or JSON>)`
and embed the returned `markdownLink`, see
`manual_read('embed-documents')`. Reminder: **raw content, no fence**.
