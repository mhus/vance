---
triggers: scribblebook, scribble app, notebook app, handwriting notebook, collection of sheets, mehrere blätter, notizbuch app
summary: Vance "scribblebook application" — a folder that contains several handwritten `kind: scribble` sheets with an auto-generated `_index.md`. Use this when the user wants more than one handwriting sheet grouped together (a "notebook"), switchable from one place. For a single sheet, create a `kind: scribble` directly.
---
# Application — `app: scribblebook`

A **Vance application folder** with `_app.yaml` carrying `kind: application` +
`app: scribblebook`. Every `*.scribble.yaml` file inside the folder is a
handwriting sheet (`kind: scribble`); an auto-generated `_index.md` lists
them.

Use this pattern when the user wants:
- **Several handwriting sheets grouped** together and switchable from one
  place (a tablet notebook).
- A **pen-first notebook** — multiple sheets for one topic.

For a **single sheet**, just create a `kind: scribble` directly with
`scribble_create`. For a **linear rich-text notebook** use `app: workbook`;
for **spatial boards** use `app: canvasbook`.

## Folder layout

```
notizbuch/                       ← scribblebook root
├── _app.yaml                    ← manifest (kind: application, app: scribblebook)
├── _index.md                    ← auto-generated (list of sheets)
├── meeting.scribble.yaml         ← kind: scribble sheet
└── ideen.scribble.yaml
```

## Tools

- `scribblebook_app_create(folder, title?, pages?)` — one-shot bootstrap
  (writes the manifest, seeds optional sheets, builds `_index.md`).
- `scribblebook_page_create(folder, title)` — add one sheet + index refresh.
- `scribble_create(path, title?)` — a standalone sheet outside any notebook.
- `scribble_validate(path)` — structure check: dropped strokes by index,
  ink outside the sheet raster.
- `app_rebuild(folder)` — regenerate `_index.md` after structural changes.

## What an agent cannot do

Handwriting is **user input, not LLM output** — there are no stroke tools and
no way to read the ink. Do not claim a sheet's content; say that the sheet is
handwritten and offer `scribble_validate` for structure. A future OCR pass
will extract text into parallel `*.scribble.md` files.
