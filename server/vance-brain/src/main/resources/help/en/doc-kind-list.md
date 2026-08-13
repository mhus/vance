# List

A flat, ordered sequence of short entries. One field per entry — the
text. Nothing else.

Reach for a **checklist** instead when entries have a state you track,
a **tree** when they nest, and **records** when each entry needs
several named fields.

## On disk

Markdown, YAML and JSON all work; the editor is the same for all
three.

```markdown
---
kind: list
---
- first item
- second item
```

```yaml
$meta:
  kind: list
items:
  - text: first item
  - text: second item
```

An empty list (`items: []`) is valid.

## In the editor

*View* gives you the list editor: add, edit inline, reorder by
dragging, select several at once to delete. *Edit* is the raw source
whenever you want to paste a block of lines at once.

Round-trips are lossless: fields the editor doesn't know about — an
`id` or a `tag` you added by hand, or that an agent wrote — survive
editing untouched. Only `text` is ever written by the editor itself.

## Multi-line entries

An entry may span lines. In YAML use a block scalar:

```yaml
items:
  - text: |
      first line
      second line
```
