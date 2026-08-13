# Graph

Nodes and edges — things and the connections between them. Edges are
first-class: they carry their own label, colour and metadata rather
than being an attribute of a node.

Use it for dependency maps, system diagrams you want to *edit* rather
than write, relationship networks. For a picture generated from text
(sequence diagrams, flowcharts in Mermaid syntax) use a
diagram instead; for strict hierarchy, a
tree.

**JSON and YAML only** — no markdown form.

## On disk

```yaml
$meta:
  kind: graph
graph:
  directed: true
nodes:
  - id: alice
    label: Alice
  - id: bob
    label: Bob
edges:
  - source: alice
    target: bob
    label: reports to
```

`id` is the identity an edge points at; `label` is what gets drawn
(the id is used when no label is set). `directed: false` drops the
arrowheads.

Edges pointing at an unknown id are kept in the file across edits but
not drawn — a dangling edge survives a rename you have not finished.

## Positions

`position: {x, y}` per node is optional. A graph without positions —
which is what an agent normally writes — gets a hierarchical
auto-layout on open. As soon as any node has a position, the layout is
treated as yours and left alone; the *Auto-layout* button recomputes
all of them on request.

## In the editor

*View* is the editor: drag nodes, drag from a node's edge to connect
two, click a node or edge to edit its label and colour in the side
panel, Delete removes the selection. Removing a node removes its edges
so the file stays clean.
