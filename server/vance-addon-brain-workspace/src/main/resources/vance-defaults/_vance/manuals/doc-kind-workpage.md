---
triggers: workpage, canvas, page, block document, rich text, notion page, markdown page, notes, block, callout, toggle, columns, todo list, code block, table, link card, table of contents, toc, single page document
summary: Vance "workpage" — a single rich-text page (block document) stored as Markdown with `$meta.kind: workpage`. Use this when the user wants ONE rich page with formatting (callouts, code blocks, todos, columns, images), as opposed to a workspace with many pages or a kanban board. For the full block grammar see `workpage-blocks`.
---
# Document Kind — `workpage`

A **workpage** is one rich-text page in the Notion sense. It's a Markdown file (`*.workpage.md` by convention) with `$meta.kind: workpage` in the YAML front-matter. The body is a sequence of *blocks*: paragraphs, headings, lists, todos, code, images, callouts, toggles, columns, …

WorkPage is the standalone document kind. To organise many workpages under a folder with sections + an auto-index, see `manual_read('app-workspace')`.

## When to use workpage

| Want | Choose |
|---|---|
| A single richly-formatted page | **`workpage`** |
| A notebook with many pages, sections, sidebar | `app: workspace` (each page inside is still a workpage) |
| A task board with columns | `app: kanban` |
| A calendar of events | `app: calendar` |
| A pure data file (no rendering) | `kind: data` (YAML) |
| Plain code | `kind: code` (no body parsing) |

## File shape

```markdown
---
$meta:
  kind: workpage
title: "Architecture Notes"
description: "API + storage decisions, May 2026"
icon: "📐"                 # optional emoji shown in sidebar / header
cover: "assets/banner.jpg" # optional banner image path
sortIndex: 20              # optional manual sort inside a workspace section
---
# Architecture Notes

A paragraph here. Markdown formatting works: **bold**, *italic*, `inline code`, [a link](https://example.com).

## Decisions

```vance-callout
severity: info
title: ADR-007
body: |
  We pick MongoDB over Postgres because the document model fits the
  knowledge graph better than relational tables.
```
```

The front-matter is **YAML**; the body is **Markdown** with a small set of `vance-*`-fenced block extensions. Standard CommonMark renders normally; the extensions add Notion-style primitives.

## Block inventory

Full grammar with copy-paste examples per block: `manual_read('workpage-blocks')`.

Quick reference:

| Block | Markdown form |
|---|---|
| paragraph | bare text |
| heading | `# h1`, `## h2`, `### h3` |
| bullet-list | `- item` |
| numbered-list | `1. item` |
| todo | `- [ ] item` / `- [x] item` |
| quote | `> text` |
| code | ` ```lang … ``` ` (syntax-highlighted via highlight.js) |
| divider | `---` |
| image | `![alt](url)` — optional width preset `![alt|small](url)` |
| table | standard pipe-table |
| callout | fenced `vance-callout` with `severity` + `title` + `body` |
| toggle | fenced `vance-toggle` with `summary` + `body` |
| dataview | fenced `vance-dataview` (stub v1) |
| link-card | fenced `vance-link` with `href` + `title` + `description` |
| toc | fenced `vance-toc` (auto from h1/h2/h3) |
| columns | 4-backtick fenced `vance-columns` with `<!--vance:column-->` separators |
| embed | fenced `vance-embed` with `uri:` body (kind-aware reference card) |

## Tool inventory

| Tool | What it does |
|---|---|
| **`workpage_create(path, title?, description?, blocks=[…])`** | Create a new workpage. `path` auto-suffixed with `.workpage.md` if no extension. `blocks` seeds the document body. |
| `workpage_block_append(path, block)` | Append a block at the end. |
| `workpage_block_insert(path, block, anchor)` | Insert at `{ index: N }` or after `{ heading: "exact text" }`. |
| `workpage_block_update(path, anchor, …)` | Mutate one block — text, level, items, etc. |
| `workpage_block_delete(path, anchor)` | Remove a block. |
| `workpage_block_move(path, from, to)` | Move a block to a new position. |
| `workpage_query(path, type?, textMatch?, todoState?)` | Read-only block search. |

## Canonical flow — seed in one shot

When you know the page structure up front, **one** `workpage_create` call writes the file complete:

```
workpage_create(
  path="notes/architecture-2026-05",
  title="Architecture Notes",
  description="API + storage decisions",
  blocks=[
    { type: "heading", level: 1, text: "Architecture Notes" },
    { type: "paragraph", text: "Brain-dump from the May offsite." },

    { type: "heading", level: 2, text: "Decisions" },
    { type: "callout", severity: "info", title: "ADR-007",
      body: "MongoDB over Postgres — document model fits the knowledge graph better." },

    { type: "heading", level: 2, text: "Open questions" },
    { type: "todo", items: [
      { checked: false, text: "Sharding strategy for memory collection" },
      { checked: false, text: "Backup cadence for project-data" },
      { checked: true,  text: "Retention policy for chat archives" }
    ] }
  ]
)
```

## Incremental flow — edit an existing workpage

```
# Append a new section
workpage_block_append(
  path="notes/architecture-2026-05.workpage.md",
  block={ type: "heading", level: 2, text: "Performance budget" }
)
workpage_block_append(
  path="notes/architecture-2026-05.workpage.md",
  block={ type: "paragraph", text: "p95 LLM call under 4 s. WS broadcast under 50 ms." }
)

# Mark a TODO as done
workpage_block_update(
  path="notes/architecture-2026-05.workpage.md",
  anchor={ heading: "Open questions" },
  items=[
    { checked: true, text: "Sharding strategy for memory collection" },
    { checked: true, text: "Backup cadence for project-data" },
    { checked: true, text: "Retention policy for chat archives" }
  ]
)

# Pull all callouts from the document
workpage_query(
  path="notes/architecture-2026-05.workpage.md",
  type="callout"
)
```

`workpage_block_*` anchors are **`{ index: N }`** (0-based) or **`{ heading: "exact text" }`**. If a heading text occurs more than once the resolver throws and you must disambiguate with `index`.

## Anti-patterns

- **Don't embed YAML or HTML directly in the body** for things that have a block type. Use `callout` not a `<div class="warning">`. The TS editor on the user side won't parse hand-rolled HTML — it round-trips through the block model.
- **Don't add custom `vance-<type>` fences** beyond the inventory above. Unknown ones render as a literal "unknown vance block" placeholder.
- **Don't write `$meta.kind: workpage` page-headers without a `title`.** The UI uses it everywhere (sidebar, breadcrumbs, search).
- **Don't fight the editor** by writing comments or microformats that only make sense to an LLM. The user sees + edits the same file. If a hint to yourself is needed, use a real `callout` with `severity: info`.

## Round-trip guarantee

The TypeScript editor parses and re-emits the same Markdown grammar as the Java backend. So a file written by `workpage_create` and edited in the Web-UI keeps its block structure on save. The image-width preset, columns, callouts, etc. all survive a round-trip; that's what makes the workpage viable as a *file you can also LLM-edit*.

## What v1 does NOT support

- Multi-user CRDT live-edit (the file reloads on remote-write; cursor survives self-writes inside the 3 s quiet window).
- Per-block history (file-level archives exist).
- Cross-workpage block refs / synced blocks.
- Embed-blocks for external services (YouTube, Figma, …).
