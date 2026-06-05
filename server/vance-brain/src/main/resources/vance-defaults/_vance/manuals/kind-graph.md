---
triggers: graph, Graph, network, Netzwerk, dependency, Abhängigkeit, dependencies, Abhängigkeiten, relationships, Beziehungen, m:n, many-to-many, nodes and edges, knoten und kanten, concept map, Konzeptkarte, state machine, dependency graph, knowledge graph
summary: Render a directed or undirected network graph (nodes + first-class edges), either inline in chat or as a stored document.
---
# Document kind — `graph`

Nodes connected by first-class edges, rendered as a network with
arrows (directed) or plain lines (undirected). Auto-laid-out via
Dagre when the LLM doesn't supply positions — which is the normal
case. Vue-Flow as the renderer.

## Two storage forms — pick by intent

The **payload is identical** in both forms. The difference is the
outer wrapper — that decides whether Vance renders the graph
inline in the chat or as a clickable document tab.

Decide first:

| Did the user ask for a saved file / document? | Use form |
|---|---|
| YES — "create a graph document", "speicher das als graph-doc", "save the dependency graph" | **Stored** (below) |
| NO — "show the relationships", "wie hängen die zusammen", "draw the network" | **Inline** (further below) |

### Inline in chat — fence-wrapped, no tool call

When the user wants to *see* the network right now in the assistant's
reply (no save, no `doc_create`), **emit a single
```` ```graph ```` fence in the chat message**. The fenced YAML body
carries the same schema as the stored form below.

````
```graph
$meta:
  kind: graph
graph:
  directed: true
nodes:
  - id: auth
    label: Auth Service
  - id: api
    label: API Gateway
  - id: db
    label: Database
edges:
  - source: auth
    target: api
  - source: api
    target: db
```
````

The reply must CONTAIN this fence verbatim — narrating "Hier ist der
Graph…" without the actual fenced block leaves the user with no
render.

### Stored document — raw JSON or YAML, NO fence

When the user wants to *save* the graph to a file via
`doc_create(kind="graph", path="<…>.json"` or `.yaml`,
`body=<raw schema>)`, **the body must NOT be wrapped in a
```` ```graph ```` fence** — that's the inline-only form. Markdown
bodies are rejected for stored graphs: the codec stores the file
but the Web-UI falls back to the Raw editor and never renders the
graph tab. Always use `.json` or `.yaml` as the path extension.

YAML body example (paste as-is into the `body=` arg):

```yaml
$meta:
  kind: graph
graph:
  directed: true
nodes:
  - id: auth
    label: Auth Service
  - id: api
    label: API Gateway
  - id: db
    label: Database
    color: "#fbbf24"
edges:
  - source: auth
    target: api
    label: validates
  - source: api
    target: db
  - source: api
    target: auth
    label: refresh
```

JSON body is equivalent — same keys, just `{}`/`[]` instead of
indented YAML. Pick YAML for readability, JSON when the body is
generated programmatically.

## Shared schema

Top-level keys: `$meta`, `graph` (config), `nodes`, `edges`.

**Node** — `id` is the only required field. `label` is the display
text (falls back to `id`). `color` is optional, HTML hex.

**Edge** — `source` and `target` are required, both pointing to node
`id`s. `label` and `color` are optional. Edges between unknown ids
are dropped silently at render time but preserved on disk for
round-trip (useful when a node is briefly absent).

**Config** — `graph.directed` defaults to `true` (arrows shown).
Set `directed: false` for an undirected graph.

## When to use this

User wants to *see* relationships, dependencies, or a small
network — "show the dependencies between X, Y, Z", "wie hängen
diese Services zusammen", "draw the relationships". The graph is
**m:n** (a node can connect to many, and be connected to from
many) — that's what distinguishes it from `tree`/`mindmap`.

## graph vs. tree vs. mindmap vs. diagram

- **graph** — m:n relationships, layout computed (Dagre LR by
  default). This kind.
- **tree** / **mindmap** — strictly hierarchical (one parent per
  node). Use when the structure is a single rooted hierarchy, not
  a network with cross-links.
- **diagram** (Mermaid `flowchart`) — text-driven flowchart with
  custom shapes, swimlanes, subgraphs, conditional branches. Use
  when you need control over visual style or special node shapes
  (decision diamonds, cylinders, etc.).
- **chart** — numerical data. Not for networks.

Rule of thumb: if your nodes form a connected DAG and the user
wants to *see* the connections, this is the right kind. If they
need decision diamonds or swimlanes, switch to `diagram`.

## Anti-patterns

- **Wrapping the stored body in a ```` ```graph ```` fence.** That
  is the inline-chat form. When you save via `doc_create`, the
  body must be raw JSON or YAML — no fence. The codec stores it,
  but the Web-UI falls back to the Raw editor — no graph-tab, no
  render. Symptom: user gets a saved doc that opens as plain text
  instead of a graph.
- **Saving as `.md`.** Markdown is explicitly rejected for stored
  graph documents — use `.json` or `.yaml` as the path extension.
- **Cytoscape / GraphML / vue-flow internal shape.** Wrong:
  `{ elements: { nodes: [...], edges: [...] } }` or
  `<graphml>…</graphml>`. Vance graph has `nodes` and `edges` at
  the TOP level, not nested under `elements`. The codec rejects
  the wrong shape silently — the user sees an empty graph.
- **Mermaid flowchart syntax.** `graph TD`, `graph LR`, `A --> B`,
  `A[Label]`, `A((Label))`, `subgraph X` — that's **Mermaid**, not
  Vance graph. Wrong:
  ````
  ```graph
  graph TD
    root --> Bio[Biology]
    Bio --> Wolves["Direwolves"]
  ```
  ````
  Right:
  ````
  ```graph
  $meta:
    kind: graph
  nodes:
    - id: root
      label: Science 2025/26
    - id: bio
      label: Biology
    - id: wolves
      label: Direwolves
  edges:
    - source: root
      target: bio
    - source: bio
      target: wolves
  ```
  ````
  If you really want Mermaid flowchart syntax — with custom node
  shapes, subgraphs, decision diamonds — use ` ```mermaid` (kind
  `diagram`) instead. Vance graph is a *data* structure rendered by
  vue-flow, not a Mermaid DSL.
- **Edge as a node field.** Don't write `nodes: [{ id, edges: [...] }]`
  — edges live at the top level. (Legacy documents with that shape
  are auto-migrated, but new output should be canonical.)
- **String-only nodes.** `nodes: [a, b, c]` is not valid. Every
  node must be an object with at least `id`. The codec drops
  non-object entries silently.
- **Duplicate node ids.** The codec throws — the user sees an
  empty graph and a parse error in the console.
- **Hand-set positions.** Don't include `position: { x, y }` on
  nodes — Dagre will auto-layout. Positions only make sense in
  the editor where the user explicitly drags.
- **More than ~25 nodes / 40 edges inline.** Becomes a noodle
  diagram in the chat viewport. Save as a Document
  (`doc_create(kind="graph", …)`).

## When to graduate from inline to stored

- Graph is meant to be edited later (the Editor tab lives on the
  Document, not on the chat fence).
- More than ~25 nodes or the user wants to drag-layout it.
- Multiple graphs that belong together (architecture set,
  state-machine collection).

Then call `doc_create(kind="graph", path="graphs/<name>.yaml",
content=<raw YAML or JSON>)` and embed the returned `markdownLink` —
see `manual_read('embed-documents')`. Reminder: **raw content, no
fence**.
