# Tree

A nested outline: entries that contain entries, arbitrarily deep. The
list with hierarchy added — same entry shape, plus
`children`.

Use it for outlines, breakdowns, categorised collections, anything
where "belongs under" is the relationship you care about. If you want
the same content drawn radially rather than as an indented outline,
use a mindmap — the on-disk shape is identical.

## On disk

Indentation carries the nesting in markdown:

```markdown
---
kind: tree
---
- parent
  - child
    - grandchild
- second parent
```

```yaml
$meta:
  kind: tree
items:
  - text: parent
    children:
      - text: child
        children: []
  - text: second parent
    children: []
```

A leaf writes `children: []` rather than omitting the field.

## In the editor

*View* is the outline editor: add siblings and children, edit inline,
drag to move a whole subtree, collapse and expand branches. Moving a
node takes its children with it.

Unknown fields on an entry are preserved across edits, so anything you
or an agent attached by hand stays put.

## Depth

There is no hard depth limit, but past four or five levels an outline
gets hard to scan — that is usually the signal to split the document
or to promote a branch into its own tree.
