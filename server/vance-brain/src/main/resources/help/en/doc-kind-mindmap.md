# Mindmap

The same nested outline as a tree, drawn as a
radial map instead of an indented list. The on-disk shape is
identical — changing `kind: tree` to `kind: mindmap` and back is a
pure change of view.

Pick mindmap when the point is to *see* the branching at a glance:
brainstorming, topic maps, "what belongs to what" overviews. Pick tree
when you mostly read and edit the text top to bottom.

## On disk

```markdown
---
kind: mindmap
---
- root topic
  - branch one
    - detail
  - branch two
```

```yaml
$meta:
  kind: mindmap
items:
  - text: root topic
    children:
      - text: branch one
        children: []
      - text: branch two
        children: []
```

The first top-level entry reads as the centre. Several top-level
entries render as several roots — legal, but usually a sign the
document wants to be split.

## In the editor

*View* renders the map: pan by dragging, zoom with the wheel, click a
branch to fold or unfold it. Editing happens in the *Edit* tab on the
outline itself — the map is a rendering, not an editing surface.

Markdown entries may carry inline formatting (`**bold**`, links); the
renderer shows it.
