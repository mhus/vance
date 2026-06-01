---
triggers: diagram, Diagramm, flowchart, Flussdiagramm, sequence diagram, Sequenzdiagramm, state machine, Zustandsdiagramm, ER, ERD, entity-relationship, gantt, Zeitstrahl, gitGraph, git graph, C4, architecture, timeline, journey, user journey, pie chart, Mermaid
summary: Render a Mermaid diagram (flowchart, sequence, state, ER, gantt, gitGraph, journey, C4, timeline) inline in chat.
---
# Inline kind — `diagram` (Mermaid)

Render a flowchart, sequence diagram, state machine, ER model, gantt
chart, gitGraph, journey, pie, C4 or timeline directly in chat. The
body is plain Mermaid source — the Web-UI renders it to SVG; the Foot
CLI shows it as the source text.

## Syntax

````
```mermaid
flowchart TD
  A[Start] --> B{Decision}
  B -->|yes| C[Do it]
  B -->|no| D[Skip]
```
````

The first source line picks the diagram type. Common openings:

| Type | First line |
|---|---|
| Flowchart | `flowchart TD` (top-down) or `flowchart LR` (left-right) |
| Sequence | `sequenceDiagram` |
| State machine | `stateDiagram-v2` |
| ER model | `erDiagram` |
| Gantt | `gantt` |
| GitGraph | `gitGraph` |
| User journey | `journey` |
| Pie (simple) | `pie` |
| C4 architecture | `C4Context` / `C4Container` |
| Timeline | `timeline` |

## When to use this

User wants a *visual* of a process, architecture, or relationship —
"draw a flowchart", "zeig den Ablauf als Sequenz", "mach mir ein
ER-Diagramm". The expectation is **immediate visual output** in chat.

If the user wants a *long-lived* diagram artifact (saved, linkable,
searchable in RAG), graduate to a Document:
`doc_create_kind(kind="diagram", path="diagrams/<name>", body=…)`.
The same Mermaid source goes into a markdown body with one
` ```mermaid` fence (see `manual_read('embed-documents')`).

## Picking diagram vs. other kinds

- **chart** (`kind: chart`) — numerical data with axes (Line, Bar, Pie
  with values, Heatmap). Wants `{ x, y }`-style data points.
- **graph** (`kind: graph`) — m:n node/edge data the user wants to
  edit interactively. JSON `nodes`/`edges`.
- **mindmap** (`kind: mindmap`) — hierarchical brainstorm rendered
  radially. Outliner-editable.
- **diagram** (this one) — anything else visual, text-driven, not
  numerical: flowcharts, sequence, ER, state, gitGraph, C4, gantt.

Mermaid itself supports a `mindmap` diagram type. Don't use it — use
`kind: mindmap` (markmap) for real mindmaps; reserve `diagram` for
the typed diagrams above.

## Anti-patterns

- **No info string on the fence.** ` ``` ` alone (without `mermaid`)
  renders as plain `<pre>` — the diagram won't appear. Always start
  with ` ```mermaid`.
- **HTML/JS in the source.** The renderer enforces
  `securityLevel: 'strict'` — any `<script>`, inline event handler,
  or `javascript:` href is stripped or rejected. Don't try to embed
  interactivity that way.
- **Multiple diagrams in one fence.** One fence = one diagram. For
  several, emit several fences (each its own ` ```mermaid…``` `
  block), or save each as its own Document.
- **Pasting a Vance `chart` body.** Vance `chart` is a JSON/YAML
  schema, *not* Mermaid `pie`. They render differently and have
  different data models. For numerical data prefer `chart`.

## Failure surfacing

Bad Mermaid syntax does **not** crash the chat. The renderer shows a
banner with the parser error (e.g. *"Parse error on line 3: Expecting
NEWLINE, got SEMI"*) and the source for debugging. If you see the
banner echoed back in the next turn, fix the offending line and
re-emit the fence — Mermaid's error messages name the line number.

## When to graduate to a Document

Same trigger as other inline kinds:

- "Save this", "for later", "keep it around".
- Body grows past ~30 lines.
- Several diagrams that belong together as a set.

Then `doc_create_kind(kind="diagram", …)` and embed the returned
`markdownLink` — see `manual_read('embed-documents')`.
