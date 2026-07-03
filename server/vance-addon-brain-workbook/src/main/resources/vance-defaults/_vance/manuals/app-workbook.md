---
triggers: workbook, notion, notebook, wiki, knowledge base, sub-page, page tree, study notes, project notes, multi-page document, workbook app, notebook app
summary: Vance "workbook application" — a folder that contains many `kind: workpage` pages organised in sections, with an auto-generated `_index.md`. Use this when the user wants a Notion-style notebook, study notes with chapters, a documentation site, or any multi-page knowledge container.
---
# Application — `app: workbook`

A **Vance application folder** with `_app.yaml` carrying `kind: application` + `app: workbook`. Every `*.workpage.md` file inside the folder is a page; the parent folder of each page becomes its **section**. An auto-generated `_index.md` gives a single-page overview with sections + links.

Use this pattern when the user wants:
- **A Notion-style notebook** with many richly-formatted pages.
- **Study notes** organised by chapter / topic / subject.
- **A project knowledge base** (briefs, decisions, retros, glossary).
- **Documentation** with cross-linked pages and a landing page.

For **state-based work tracking** use `app: kanban`. For **time-based plans / events** use `app: calendar`. For **a single page**, just create a `kind: workpage` directly — a workbook is overkill if there's nothing to organise.

## Folder layout

```
studium-ws26/                         ← workbook root
├── _app.yaml                         ← manifest (kind: application, app: workbook)
├── _index.md                         ← auto-generated landing (kind: workpage)
├── willkommen.workpage.md              ← top-level page (section = "")
├── mathe/
│   ├── analysis-1.workpage.md          ← section = "mathe"
│   └── lineare-algebra.workpage.md
├── physik/
│   ├── mechanik.workpage.md
│   └── elektrodynamik.workpage.md
└── klausuren/
    └── plan.workpage.md
```

**Section rule:** the leaf folder of each page's path (relative to the workbook root) is its section. Files at the root belong to the unnamed top-level section ("Pages"). Deeper paths (`a/b/c/page.md`) collapse to the **first** sub-folder (`a`) for sidebar grouping.

## `_app.yaml` schema

```yaml
$meta:
  kind: application
  app: workbook
title: "Studium WS 2026"
description: "Notizen + Klausurplan + Notenspiegel"

workbook:
  landingPage: "willkommen.workpage.md"   # optional — sidebar "📌"
  index:
    outputPath: "_index.md"
    style: "cards"                      # cards (default) | list
    showDescriptions: true
    groupBySection: true
  defaultPageKind: "workpage"
```

`title` + `description` live at the YAML top level (NOT inside `workbook:`). The sidebar header reads them; they're also what the LLM's `active-app` block sees.

## `kind: workpage` page schema

Each page is a Markdown file with YAML front-matter. Open the per-page block grammar with `manual_read('doc-kind-workpage')`; for the block cheatsheet (callouts, columns, todos, code, images, …) see `manual_read('workpage-blocks')`.

Workbook-relevant front-matter fields beyond the workpage basics:

| Field | Effect |
|---|---|
| `icon: "📚"` | Emoji shown left of the title in the sidebar |
| `cover: "assets/banner.jpg"` | Banner image above the page header |
| `sortIndex: 20` | Manual sort order inside the section (10, 20, 30…); null → alphabetical |

## Tool inventory

| Tool | What it does |
|---|---|
| **`workbook_app_create(folder, title?, description?, landingPage?, indexStyle?)`** | **First call for any new workbook.** Writes `_app.yaml` and seeds an empty `_index.md` so the folder is immediately mountable. Does **not** create pages — those come from separate `workpage_create` calls. |
| **`workpage_create(path, title?, description?, blocks=[…])`** | Create a page. `path` should sit inside the workbook folder. See `manual_read('workpage-blocks')` for block shapes. |
| `workpage_block_append(path, block)` | Append a block to an existing page. |
| `workpage_block_insert(path, block, anchor)` | Insert at index or after a heading. |
| `workpage_block_update(path, anchor, …)` | Edit one block's content / attrs. |
| `workpage_block_delete(path, anchor)` | Remove a block. |
| `workpage_block_move(path, from, to)` | Reorder blocks within a page. |
| `workpage_query(path, type?, textMatch?)` | Read-only block search inside one page. |
| `app_rebuild(folder)` | Regenerate `_index.md` **and** run each page's declared rebuild scripts (see below). Generic — works for every Vance app. |

## Rebuild scripts (`$meta.rebuildScripts`)

`app_rebuild` (and the "Rebuild" button) additionally runs the scripts a page
**explicitly declares** in its front-matter — nothing is auto-discovered. Only
listed scripts run:

```yaml
---
$meta:
  kind: workpage
  rebuildScripts:
    - update_all.js          # .js doc; bare name = relative to the page's folder,
    - vance:/apps/x/agg.js   # vance:/… = project-absolute
title: "Noten"
---
```

Each script runs server-side (synchronous, `vance.documents.*` on the tenant/
project scope, 30 s). A failing script is logged and skipped so it doesn't block
the rest of the rebuild. Use this to recompute derived files (charts, aggregates)
for a whole workbook in one action.

## Canonical flow

Two phases: bootstrap the manifest, then seed pages. Conclude with one `app_rebuild` so the index reflects the new files.

```
# 1. Manifest first — empty workbook ready to mount.
workbook_app_create(
  folder="studium-ws26",
  title="Studium WS 2026",
  description="Notizen + Klausurplan + Notenspiegel",
  landingPage="willkommen.workpage.md"
)

# 2. Seed pages with their initial blocks.
workpage_create(
  path="studium-ws26/willkommen.workpage.md",
  title="Willkommen",
  blocks=[
    { type: "heading", level: 1, text: "Wintersemester 2026" },
    { type: "paragraph", text: "Mathe, Physik, Algorithmen — Plan + Notizen." }
  ]
)

workpage_create(
  path="studium-ws26/mathe/analysis-1.workpage.md",
  title="Analysis I",
  blocks=[
    { type: "heading", level: 1, text: "Analysis I" },
    { type: "callout", severity: "info", title: "Klausur",
      body: "2026-02-12, 10:00, H2." }
  ]
)

workpage_create(
  path="studium-ws26/klausuren/plan.workpage.md",
  title="Klausurplan"
)

# 3. Refresh the index so it lists the new pages.
app_rebuild(folder="studium-ws26")
```

Setting `icon:` on a page goes into the page's own front-matter via the workpage blocks (the page's first block can be a `heading` and the icon is set by editing the workpage file's YAML header after creation — or by the user in the UI). For LLM-written pages without a real visual identity, leave the icon off; the user can pick one later through the emoji picker.

**Embed the artefact `markdownLink` (returned by `app_rebuild`) in your chat reply** so the user sees the landing page right away.

## Incremental flow — adding pages later

```
workpage_create(
  path="studium-ws26/mathe/lineare-algebra.workpage.md",
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
workpage_block_append(
  path="studium-ws26/mathe/analysis-1.workpage.md",
  block={
    type: "todo",
    items: [
      { checked: true,  text: "Übungsblatt 1" },
      { checked: false, text: "Übungsblatt 2" }
    ]
  }
)

# Insert a callout BEFORE the existing "Übersicht" heading
workpage_block_insert(
  path="studium-ws26/mathe/analysis-1.workpage.md",
  block={ type: "callout", severity: "warning", title: "Achtung",
          body: "Klausurformeln auf S. 12 ändern sich nächstes Jahr." },
  anchor={ heading: "Übersicht" }
)
```

`workpage_block_*` tools take an **anchor** as either `{ index: N }` or `{ heading: "exact text" }`. Heading match is exact; duplicate headings throw and you disambiguate with `index`.

## Anti-patterns

- **Don't write `_app.yaml` by hand** via `doc_create`. Use `workbook_app_create` so the manifest schema is locked in.
- **Don't edit `_index.md` by hand.** It's a generated artefact and gets overwritten on the next `app_rebuild`. Edit the source pages, then rebuild.
- **Don't nest workbooks.** One `_app.yaml` per tree root. Sub-folders are sections, not sub-workbooks.
- **Don't use a workbook for a single page.** Just `workpage_create` — the index machinery is overhead.
- **Don't move pages with `doc_move` against the path** when the user wants a section change. The path-move works, but using the workbook's own REST/UI endpoints (or telling the user to drag the page in the sidebar) keeps `sortIndex` consistent. From the LLM side, just rewrite the path; the next `app_rebuild` re-groups.
- **Don't call `app_rebuild` after every single edit.** Batch a writing session and rebuild once at the end. Same convention as Kanban.

## When NOT to use this pattern

- **Single document with sections inside it** → just `workpage_create` with `heading` blocks. A workbook is for *multi-file* organisation.
- **State-based work (todo / doing / done)** → `app: kanban`.
- **Time-based events / a calendar** → `app: calendar`.
- **A whole website** → not Vance's scope. Export the workbook's pages to a static-site generator if you need that.

## Web-UI behaviour (LLM context)

The Web-UI renders the workbook inplace: sidebar with the page tree (grouped by section), main area is the active page in the workpage editor. The user can rename / move / duplicate / delete pages and rearrange them by drag-and-drop. Page icon + cover are first-class — when seeding pages, set `icon:` on prominent ones for a polished look. Don't fight the UI by writing front-matter that only makes sense to the LLM — the user sees and edits the same file.

## Editable forms + recompute (reactive data)

A workbook page can embed an **editable form** over a `kind: records`
document, optionally with a script that recomputes derived files (a chart, a
summary) on Save. Use this when the user wants to *enter structured data and
have something computed from it*. See `manual_read('workbook-forms')` for the
`vance-form` fence (`config` + `form:` + `saveScript` — the record file holds
only `schema` + `items`) and what the saveScript can do.

After building or editing fences, run `workbook_validate(folder)` to confirm
all fence references resolve (config/embed targets, `.js` scripts) before
telling the user it's done — read-only, safe to call anytime.
