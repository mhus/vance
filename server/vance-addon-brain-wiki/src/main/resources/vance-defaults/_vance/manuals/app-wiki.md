---
triggers: wiki, wikilink, backlinks, knowledge base, name-linked notes, second brain, cross-linked pages, wiki app, zettelkasten, hyperlinked notebook
summary: Vance "wiki application" — a folder of `kind: workpage` pages addressed by name via `[[Wikilinks]]`, with a backlink graph, spaces (sub-folders) and auto-generated `_index.md` pages. Use this when the user wants a name-linked knowledge base rather than a curated page tree (that's `app: workbook`).
---
# Application — `app: wiki`

A **Vance application folder** whose `_app.yaml` carries `kind: application` + `app: wiki`. Pages are `kind: workpage` Markdown files linked **by name** with `[[Wikilinks]]`. Links are the structure — navigation runs over links + backlinks + spaces, not a curated sidebar tree.

Use this when the user wants:
- **A name-linked knowledge base / second brain** where pages reference each other by title.
- **Cross-linked documentation** where "what links here" matters.
- **A zettelkasten-style** hyperlinked notebook.

For a **curated, ordered page tree** (sections, drag-to-reorder, a pinned landing) use `app: workbook` instead. For a **single page**, just create a `kind: workpage` directly.

## Spaces + main/index

**Space = folder in the path.** Pages live as `<space>/<slug>.md`; nested spaces are allowed and the wiki root is itself a space. Each space (incl. the root) has two special pages:

| File | Role | Writer |
|---|---|---|
| `main.md` | **Curated home** of the space (intro + key `[[Links]]`). A normal editable `kind: workpage`. No `_` prefix. | User / LLM |
| `_index.md` | **Auto-generated**, read-only (`_` prefix = system-managed). | only `app_rebuild` |

- The **root `_index.md`** lists the recently-modified pages (wiki-global) plus every page grouped by space.
- A **space `_index.md`** lists that space's pages, recursive over sub-spaces.
- `_backlinks.yaml` (root) maps `pageSlug -> [inbound slugs]`.

`app_rebuild` regenerates **all** `_index.md` files and the `_backlinks.yaml` in one run.

## `[[Wikilink]]` — the core

Syntax: `[[Target]]`, `[[Target|Label]]`, and explicit `[[Space/Target]]`.

Resolution (space-aware):
1. slug in the **current space** → hit;
2. else a **wiki-globally-unique** slug → hit;
3. else ambiguous → first match (v1);
4. else missing → "red" link; clicking it **creates** `<currentSpace>/<slug>.md`.

## `_app.yaml` schema

```yaml
$meta:
  kind: application
  app: wiki
title: "Team Handbook"
description: "How we work"
wiki:
  index:
    outputPath: "_index.md"
    showDescriptions: true
  recentLimit: 10
  defaultPageKind: "workpage"
```

## Tool inventory

| Tool | What it does |
|---|---|
| **`wiki_app_create(folder, title?, description?)`** | **First call for any new wiki.** Writes `_app.yaml`, seeds a root `main.md` home page and generates the `_index.md` files + `_backlinks.yaml`. |
| **`wikipage_create(folder, title, space?)`** | Create a page. Slug is derived from the title; `space` places it in a sub-folder. |
| `workpage_block_append/_insert/_update/_delete/_move` | Edit a page's content (from the workpage tool family). |
| `app_rebuild(folder)` | Regenerate every `_index.md` **and** the `_backlinks.yaml`. Generic — works for every Vance app. |

## Canonical flow

```
wiki_app_create(folder="handbook", title="Team Handbook")
wikipage_create(folder="handbook", title="Onboarding")
wikipage_create(folder="handbook", title="Deploys", space="ops")
# link pages by name in their bodies: [[Onboarding]], [[ops/Deploys]]
app_rebuild(folder="handbook")
```

## Anti-patterns

- **Don't write `_app.yaml` by hand** — use `wiki_app_create`.
- **Don't edit `_index.md` or `_backlinks.yaml`** — they're generated and overwritten on the next `app_rebuild`. Edit `main.md` and content pages, then rebuild.
- **Don't call `app_rebuild` after every edit** — batch a writing session and rebuild once at the end.
- **Don't reach for a wiki when the user wants an ordered tree** — that's `app: workbook`.
