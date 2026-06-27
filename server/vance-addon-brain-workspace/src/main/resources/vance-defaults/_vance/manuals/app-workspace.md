---
triggers: workspace, notion, notebook, wiki, knowledge base, sub-page, page tree, study notes, project notes, multi-page document, workspace app, notebook app
summary: Vance "workspace application" — a folder that contains many `kind: canvas` pages organised in sections, with an auto-generated `_index.md`. Use this when the user wants a Notion-style notebook, study notes with chapters, a documentation site, or any multi-page knowledge container.
---
# Application — `app: workspace`

A **Vance application folder** with `_app.yaml` carrying `kind: application` + `app: workspace`. Every `*.canvas.md` file inside the folder is a page; the parent folder of each page becomes its **section**. An auto-generated `_index.md` gives a single-page overview with sections + links.

Use this pattern when the user wants:
- **A Notion-style notebook** with many richly-formatted pages.
- **Study notes** organised by chapter / topic / subject.
- **A project knowledge base** (briefs, decisions, retros, glossary).
- **Documentation** with cross-linked pages and a landing page.

For **state-based work tracking** use `app: kanban`. For **time-based plans / events** use `app: calendar`. For **a single page**, just create a `kind: canvas` directly — a workspace is overkill if there's nothing to organise.

## Folder layout

```
studium-ws26/                         ← workspace root
├── _app.yaml                         ← manifest (kind: application, app: workspace)
├── _index.md                         ← auto-generated landing (kind: canvas)
├── willkommen.canvas.md              ← top-level page (section = "")
├── mathe/
│   ├── analysis-1.canvas.md          ← section = "mathe"
│   └── lineare-algebra.canvas.md
├── physik/
│   ├── mechanik.canvas.md
│   └── elektrodynamik.canvas.md
└── klausuren/
    └── plan.canvas.md
```

**Section rule:** the leaf folder of each page's path (relative to the workspace root) is its section. Files at the root belong to the unnamed top-level section ("Pages"). Deeper paths (`a/b/c/page.md`) collapse to the **first** sub-folder (`a`) for sidebar grouping.

## `_app.yaml` schema

```yaml
$meta:
  kind: application
  app: workspace
title: "Studium WS 2026"
description: "Notizen + Klausurplan + Notenspiegel"

workspace:
  landingPage: "willkommen.canvas.md"   # optional — sidebar "📌"
  index:
    outputPath: "_index.md"
    style: "cards"                      # cards (default) | list
    showDescriptions: true
    groupBySection: true
  defaultPageKind: "canvas"
```

`title` + `description` live at the YAML top level (NOT inside `workspace:`). The sidebar header reads them; they're also what the LLM's `active-app` block sees.

## `kind: canvas` page schema

Each page is a Markdown file with YAML front-matter. Open the per-page block grammar with `manual_read('doc-kind-canvas')`; for the block cheatsheet (callouts, columns, todos, code, images, …) see `manual_read('canvas-blocks')`.

Workspace-relevant front-matter fields beyond the canvas basics:

| Field | Effect |
|---|---|
| `icon: "📚"` | Emoji shown left of the title in the sidebar |
| `cover: "assets/banner.jpg"` | Banner image above the page header |
| `sortIndex: 20` | Manual sort order inside the section (10, 20, 30…); null → alphabetical |

## Tool inventory

| Tool | What it does |
|---|---|
| **`workspace_app_create(folder, title?, description?, landingPage?, indexStyle?)`** | **First call for any new workspace.** Writes `_app.yaml` and seeds an empty `_index.md` so the folder is immediately mountable. Does **not** create pages — those come from separate `canvas_create` calls. |
| **`canvas_create(path, title?, description?, blocks=[…])`** | Create a page. `path` should sit inside the workspace folder. See `manual_read('canvas-blocks')` for block shapes. |
| `canvas_block_append(path, block)` | Append a block to an existing page. |
| `canvas_block_insert(path, block, anchor)` | Insert at index or after a heading. |
| `canvas_block_update(path, anchor, …)` | Edit one block's content / attrs. |
| `canvas_block_delete(path, anchor)` | Remove a block. |
| `canvas_block_move(path, from, to)` | Reorder blocks within a page. |
| `canvas_query(path, type?, textMatch?)` | Read-only block search inside one page. |
| `app_rebuild(folder)` | Regenerate `_index.md`. Generic — works for every Vance app. |

## Canonical flow

Two phases: bootstrap the manifest, then seed pages. Conclude with one `app_rebuild` so the index reflects the new files.

```
# 1. Manifest first — empty workspace ready to mount.
workspace_app_create(
  folder="studium-ws26",
  title="Studium WS 2026",
  description="Notizen + Klausurplan + Notenspiegel",
  landingPage="willkommen.canvas.md"
)

# 2. Seed pages with their initial blocks.
canvas_create(
  path="studium-ws26/willkommen.canvas.md",
  title="Willkommen",
  blocks=[
    { type: "heading", level: 1, text: "Wintersemester 2026" },
    { type: "paragraph", text: "Mathe, Physik, Algorithmen — Plan + Notizen." }
  ]
)

canvas_create(
  path="studium-ws26/mathe/analysis-1.canvas.md",
  title="Analysis I",
  blocks=[
    { type: "heading", level: 1, text: "Analysis I" },
    { type: "callout", severity: "info", title: "Klausur",
      body: "2026-02-12, 10:00, H2." }
  ]
)

canvas_create(
  path="studium-ws26/klausuren/plan.canvas.md",
  title="Klausurplan"
)

# 3. Refresh the index so it lists the new pages.
app_rebuild(folder="studium-ws26")
```

Setting `icon:` on a page goes into the page's own front-matter via the canvas blocks (the page's first block can be a `heading` and the icon is set by editing the canvas file's YAML header after creation — or by the user in the UI). For LLM-written pages without a real visual identity, leave the icon off; the user can pick one later through the emoji picker.

**Embed the artefact `markdownLink` (returned by `app_rebuild`) in your chat reply** so the user sees the landing page right away.

## Incremental flow — adding pages later

```
canvas_create(
  path="studium-ws26/mathe/lineare-algebra.canvas.md",
  title="Lineare Algebra",
  blocks=[
    { type: "heading", level: 1, text: "Lineare Algebra" },
    { type: "paragraph", text: "Übungsblätter und Notizen." }
  ]
)
# Repeat for more pages, then once at the end:
app_rebuild(folder="studium-ws26")
```

## Editing a page

```
# Append a TODO list to an existing page
canvas_block_append(
  path="studium-ws26/mathe/analysis-1.canvas.md",
  block={
    type: "todo",
    items: [
      { checked: true,  text: "Übungsblatt 1" },
      { checked: false, text: "Übungsblatt 2" }
    ]
  }
)

# Insert a callout BEFORE the existing "Übersicht" heading
canvas_block_insert(
  path="studium-ws26/mathe/analysis-1.canvas.md",
  block={ type: "callout", severity: "warning", title: "Achtung",
          body: "Klausurformeln auf S. 12 ändern sich nächstes Jahr." },
  anchor={ heading: "Übersicht" }
)
```

`canvas_block_*` tools take an **anchor** as either `{ index: N }` or `{ heading: "exact text" }`. Heading match is exact; duplicate headings throw and you disambiguate with `index`.

## Anti-patterns

- **Don't write `_app.yaml` by hand** via `doc_create`. Use `workspace_app_create` so the manifest schema is locked in.
- **Don't edit `_index.md` by hand.** It's a generated artefact and gets overwritten on the next `app_rebuild`. Edit the source pages, then rebuild.
- **Don't nest workspaces.** One `_app.yaml` per tree root. Sub-folders are sections, not sub-workspaces.
- **Don't use a workspace for a single page.** Just `canvas_create` — the index machinery is overhead.
- **Don't move pages with `doc_move` against the path** when the user wants a section change. The path-move works, but using the workspace's own REST/UI endpoints (or telling the user to drag the page in the sidebar) keeps `sortIndex` consistent. From the LLM side, just rewrite the path; the next `app_rebuild` re-groups.
- **Don't call `app_rebuild` after every single edit.** Batch a writing session and rebuild once at the end. Same convention as Kanban.

## When NOT to use this pattern

- **Single document with sections inside it** → just `canvas_create` with `heading` blocks. A workspace is for *multi-file* organisation.
- **State-based work (todo / doing / done)** → `app: kanban`.
- **Time-based events / a calendar** → `app: calendar`.
- **A whole website** → not Vance's scope. Export the workspace's pages to a static-site generator if you need that.

## Web-UI behaviour (LLM context)

The Web-UI renders the workspace inplace: sidebar with the page tree (grouped by section), main area is the active page in the canvas editor. The user can rename / move / duplicate / delete pages and rearrange them by drag-and-drop. Page icon + cover are first-class — when seeding pages, set `icon:` on prominent ones for a polished look. Don't fight the UI by writing front-matter that only makes sense to the LLM — the user sees and edits the same file.
