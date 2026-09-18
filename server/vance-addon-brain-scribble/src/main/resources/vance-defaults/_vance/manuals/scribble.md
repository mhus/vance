---
triggers: scribble, handwriting, handwritten notes, scribble sheet, pen notes, ink notes, ipad notes, stylus notes, scratch notes
summary: Vance "scribble" — a handwriting sheet (kind: scribble): vector pen strokes on a fixed-size sheet, written with a stylus in the web editor. Use this when the user wants to handwrite or sketch notes on the tablet. Not a spatial board (that is kind: canvas) and not a linear document (that is kind: workpage).
---
# Kind `scribble` — handwriting sheet

A **`kind: scribble`** document is one sheet of handwritten ink: an ordered
list of pen strokes on a fixed-size raster (default A4 portrait at 150 dpi,
`1240 × 1754`). The strokes are **vectors** — no image is ever stored — so
the sheet stays sharp at any zoom and a later OCR pass can render it at any
resolution.

Use a scribble when the user wants to:
- **handwrite notes** with a stylus (Apple Pencil on the iPad via the web editor),
- **sketch freely** on a page,
- keep handwriting as a **file per sheet** alongside other documents.

For a **spatial board** of nodes and arrows use `kind: canvas`. For typed
rich text use `kind: workpage`.

## On-disk shape (YAML canonical, JSON dual)

```yaml
$meta:
  kind: scribble
title: "Meeting 2026-09-15"
scribble:
  size: {w: 1240, h: 1754}
  strokes:
  - {tool: pen, c: "1", w: m, p: [[42, 80, 0.4], [47, 81, 0.6]]}
```

You rarely hand-write this — the web editor does. Read a sheet with
`doc_read` when you need its state; `kind_validate` checks the structure.

## Stroke grammar

| Key | Values | Meaning |
|-----|--------|---------|
| `tool` | `pen` (v1) | ink tool |
| `c` | `"1"`–`"4"` | palette index (black, red, blue, green) — free color values are not stored |
| `w` | `s` / `m` / `l` | pen size — three fixed values |
| `p` | `[[x, y, pressure], …]` | points in sheet-space integers, `pressure` in `0..1` |

Coordinates live in the **sheet raster**, never in the viewport — zoom and
pan are view state and are not stored. Strokes carry no ids: they are an
ordered array; erasing removes strokes, it never edits points in place.

## Reading and validation

- `doc_read` returns the YAML above — a full sheet is a large point list,
  so prefer `title` and `kind_validate` for structure questions.
- `kind_validate` reports strokes that would be dropped on save (no usable
  points) by index, and ink that runs off the sheet raster.
- Handwriting text is **not searchable yet** — an OCR pass that extracts
  text into a parallel Markdown file is a later, separate feature.

## Editing

The web editor (tablet: Safari or the Facelift shell) is the authoring
surface; it saves debounced as a whole sheet over the addon REST API.
Standalone sheets are editable directly — there is no read-only view of a
scribble. A multi-sheet notebook container (`app: scribblebook`) follows in
a later PR.
