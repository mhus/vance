---
triggers: canvasbook, canvas app, whiteboard app, board of canvases, multiple canvases, collection of boards, spatial notebook, whiteboard collection, mehrere canvas seiten
summary: Vance "canvasbook application" — a folder that contains several spatial `kind: canvas` boards with an auto-generated `_index.md`. Use this when the user wants more than one canvas grouped together (a "canvas app"), switchable from one place. For a single board, create a `kind: canvas` directly.
---
# Application — `app: canvasbook`

A **Vance application folder** with `_app.yaml` carrying `kind: application` +
`app: canvasbook`. Every `*.canvas.yaml` file inside the folder is a spatial
board (`kind: canvas`); an auto-generated `_index.md` lists them.

Use this pattern when the user wants:
- **Several canvases grouped** together and switchable from one place ("a canvas app").
- A **spatial notebook** — multiple whiteboards for one topic.

For a **single board**, just create a `kind: canvas` directly with
`canvas_create` — a canvasbook is overkill if there's nothing to group. For a
**linear rich-text notebook** use `app: workbook`; for **workflow states** use
`app: kanban`.

## Folder layout

```
design-skizzen/                    ← canvasbook root
├── _app.yaml                      ← manifest (kind: application, app: canvasbook)
├── _index.md                      ← auto-generated (list of boards)
├── ideen.canvas.yaml              ← kind: canvas board
└── architektur.canvas.yaml
```

## Tools

| Tool | Purpose |
|------|---------|
| `canvasbook_app_create(folder, title?, landingPage?, pages?)` | One-shot: write the manifest, optionally seed initial boards, build the index. Use this instead of hand-writing `_app.yaml`. |
| `canvasbook_page_create(folder, title?, slug?)` | Add one board to the canvasbook and refresh the index. |
| `app_rebuild(folder)` | Regenerate `_index.md` after structural changes. |

Fill each board with the `canvas_*` tools (`canvas_node_add`, `canvas_edge_add`,
…) — read `manual_read('canvas')` for the node/edge grammar. The boards
themselves are edited visually in the web UI (spatial editor); the standalone
`kind: canvas` view is read-only.

## Anti-patterns

- **Hand-writing `_app.yaml`** — use `canvasbook_app_create`.
- **A canvasbook with a single board** — use a bare `kind: canvas` instead.
- **Hand-edits to `_index.md`** — overwritten on the next `app_rebuild`.
