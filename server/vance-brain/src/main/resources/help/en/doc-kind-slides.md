# Slides

A slide deck written as markdown. Slides are separated by `---`; each
one is ordinary markdown.

## On disk

```markdown
---
kind: slides
slides:
  theme: default
  aspect: "16:9"
  paginate: true
---

# First slide

Welcome to your deck.

---

## Second slide

- bullet one
- bullet two
```

The front-matter block at the top configures the deck; the `---` lines
*after* it separate slides.

In YAML and JSON the slides are a list of markdown strings under
`items:` instead — same content, no separator ambiguity.

## Deck settings

| Field | |
|---|---|
| `theme` | Visual theme of the deck |
| `aspect` | `16:9` or `4:3` |
| `paginate` | Show slide numbers |

## In the editor

*View* renders the deck with slide navigation; *Edit* is the markdown.
Since a slide is just markdown, everything you would write in a note
works — headings, lists, code blocks, images, tables.

## Keeping slides readable

The renderer does not shrink text to fit. A slide with more content
than fits simply overflows, which is the honest signal to split it —
a deck of many short slides reads better than a few dense ones anyway.
