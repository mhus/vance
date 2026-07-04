---
triggers: canvas, whiteboard, freeform board, spatial notes, node graph, node and arrow diagram, sticky notes, concept map, moodboard, canvas app, spatial board, arrange visually, boxes and arrows
summary: Vance "canvas" — a 2D spatial board (kind: canvas) of freely-placed nodes (text notes, referenced documents/images, links, group frames) connected by directed arrows. Use this when the user wants a freeform whiteboard, a boxes-and-arrows diagram, spatial arrangement of ideas, or a moodboard — not a linear document (that is kind: workpage) and not a state board (that is app: kanban).
---
# Kind `canvas` — spatial node/edge board

A **`kind: canvas`** document is a 2D surface: nodes are placed at free `x/y`
positions and connected by directed arrows. It is Vance's answer to
Obsidian-Canvas / a whiteboard — a *thinking / arranging* surface, **not** a
freehand drawing tool (there are no pen strokes, only nodes and edges).

Use a canvas when the user wants:
- a **freeform whiteboard** / boxes-and-arrows diagram,
- to **arrange ideas spatially** and connect them,
- a **moodboard** mixing text notes, images and links on one surface.

For a **linear rich-text page** use `kind: workpage`. For **workflow state
tracking** use `app: kanban`. For a **hierarchical outline** use `kind: mindmap`.

One canvas is **one document** — the whole node/edge graph. Heavy content
(images, other documents) is *not* copied into the graph; a `doc` node holds a
`vance:`-URI **reference** to a separate document. So the graph stays small and
diff-friendly even with many nodes.

## On-disk shape (YAML canonical, JSON dual)

```yaml
$meta:
  kind: canvas
title: "Architektur-Skizze"
canvas:
  nodes:
    - { id: n1, type: text, x: 120, y: 80, w: 240, h: 120, text: "Kernidee" }
    - { id: n2, type: doc,  x: 420, y: 80, w: 320, h: 200, ref: "vance:/assets/diagram.png?kind=image" }
  edges:
    - { id: e1, from: n1, to: n2, toEnd: arrow, label: "belegt durch" }
```

You never hand-write this — use the tools below. Read the whole document with
`doc_read` when you need the current state.

## Node types

| `type`  | Fields | Meaning |
|---------|--------|---------|
| `text`  | `text` (Markdown snippet) | inline note card |
| `doc`   | `ref` (a `vance:`-URI) | references another document/image, rendered as a card / image |
| `link`  | `href`, `title?` | external link card |
| `group` | `label?` | a labelled frame that contains other nodes |

**Grouping is explicit.** A node that belongs to a group carries
`parent: <group-id>`, and its `x`/`y` are then **relative to that group's
top-left corner**. So the graph makes membership machine-readable: to see what
belongs together, read each node's `parent`. A group with no members is just an
empty frame. Deleting a group detaches its members back to the canvas root.

Common geometry on every node: `x`, `y`, `w`, `h`, optional `color`
(palette `"1"`–`"6"` or a hex string), optional `z`, optional `parent`
(containing group id). Node `id`s are minted server-side (`n1`, `n2`, …) — you
don't supply them.

## Edges are directed (arrows)

An edge connects two node ids. Direction is expressed by the arrow ends
`fromEnd` / `toEnd`, each `none` or `arrow`. Default is a simple `from → to`
arrow (`fromEnd: none`, `toEnd: arrow`). Set both to `arrow` for bidirectional,
both to `none` for a plain line. Optional `label`, `color`,
`fromSide`/`toSide` (`top|right|bottom|left`).

## Tools

| Tool | Purpose |
|------|---------|
| `canvas_create(path, title?, description?)` | Create a new empty canvas. Path auto-suffixes to `.canvas.yaml`. |
| `canvas_node_add(path, node)` | Add a node. `node` is `{ type, x, y, w?, h?, … }`; returns the minted `nodeId`. |
| `canvas_node_update(path, id, patch)` | Patch a node (move via `x`/`y`, resize, recolor, change text/ref/…). `id` is immutable. |
| `canvas_node_delete(path, id)` | Delete a node; incident edges are removed too. |
| `canvas_edge_add(path, from, to, label?, toEnd?, …)` | Connect two nodes; returns the minted `edgeId`. |
| `canvas_edge_delete(path, id)` | Delete an edge. |
| `canvas_query(path, type?, textContains?)` | Read-only: list / filter nodes. |

## Typical flow

1. `canvas_create(path="ideen")` → creates `ideen.canvas.yaml`.
2. `canvas_node_add` for each note/image/link, spacing them on the grid
   (e.g. columns at `x = 80, 400, 720`; rows at `y = 80, 300, 520`).
3. `canvas_edge_add(from, to, label)` to connect related nodes.
4. Optionally wrap a cluster in a `group` node placed behind them.

**Never** claim canvases are unsupported without first reading this manual —
the tools above are the canonical path. To *edit* a canvas visually the user
opens it in the web UI (spatial editor); this manual is for building/altering
it from chat.
