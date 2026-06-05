---
triggers: kanban, board, kanban board, sprint board, task board, backlog, todo doing done, ticket board, work in progress, WIP, swimlane, kanban app, sprint planning board, task tracker
summary: Vance "kanban application" — a folder that turns into a board with one card per file, column-folders as columns, and automatic board + stats generation. Use this when the user wants a board, sprint planning with workflow states, or a backlog/todo/doing/done structure.
---
# Application — `app: kanban`

A **Vance application folder** with `_app.yaml` carrying `kind: application` + `app: kanban`. The folder becomes a Kanban board: every sub-folder is a column, every `kind: card` file inside is a ticket, and `_board.md` + `_stats.yaml` regenerate from those sources.

Use this pattern when the user wants:
- A **board** for tracking work-in-progress (`backlog`, `todo`, `doing`, `done`).
- **Sprint planning** with workflow states the team moves cards through.
- **A backlog** of ideas / tickets that mature into actionable work.
- WIP-limit awareness, blocked-card surfacing, stale-card detection.

For a **single calendar without lanes** or a **time-anchored plan**, use `app: calendar` instead — see `manual_read('app-calendar')`. Calendars are time-based, Kanban is state-based.

## Folder layout

```
boards/sprint-q3/                 ← suite folder
├── _app.yaml                     ← manifest (kind: application, app: kanban)
├── _board.md                     ← auto-generated (kind: diagram, Mermaid kanban)
├── _stats.yaml                   ← auto-generated (kind: data, WIP/blocked/stale)
├── backlog/
│   ├── search-feature.md         ← kind: card
│   └── notifications.md
├── todo/
├── doing/
│   └── login-flow.md
├── review/
└── done/
    └── logo-refresh.md
```

**Column rule:** the **leaf folder** of a card's path *relative to the suite root* is its column. Files directly in the root land in column `backlog`. Deeply nested (`a/b/c/card.md`) → column = `c`.

## `_app.yaml` schema

```yaml
$meta:
  kind: application
  app: kanban
title: "Sprint Q3 Board"
description: "Auth + Search"

kanban:
  columns:
    backlog: { title: "Backlog",     order: 1 }
    todo:    { title: "To Do",       order: 2, wipLimit: 5 }
    doing:   { title: "In Progress", order: 3, wipLimit: 3 }
    review:  { title: "Review",      order: 4, wipLimit: 2 }
    done:    { title: "Done",        order: 5 }

  board:
    outputPath: "_board.md"
    style: "mermaid"                # mermaid (default) | table
    maxCardsPerColumn: 20

  stats:
    outputPath: "_stats.yaml"
    blockedLabel: "blocked"
    staleThresholdDays: 14

  wipEnforce: "soft"                # soft (default — only warns) | hard
```

## `kind: card` schema

A card is a Markdown file with YAML-style front-matter:

```markdown
---
kind: card
title: Login-Flow implementieren
priority: high
assignee: alice
labels: auth, sprint-q3
dueDate: 2026-07-15
estimate: 5
---

Email + Passwort, JWT-basiert.

## Akzeptanzkriterien
- [x] Registrierung
- [ ] Login
- [ ] Logout
- [ ] Password-Reset
```

GFM checkboxes in the body feed the `progress.subtasks` stat. `priority: high|critical` makes the card stand out on the board. `blocked: true` (or label `blocked`) lifts the card into the `_stats.yaml.blocked` list.

## Tool inventory

| Tool | What it does |
|---|---|
| **`kanban_app_create(folder, columns=[…], cards=[…])`** | **First call for any new board.** Writes `_app.yaml` and (one-shot form) every card. Auto-runs `app_rebuild` when cards are supplied. See `manual_read('kanban-app-create')`. |
| `kanban_card_create(folder, column, title, …)` | Add a single card after the board exists. Doesn't auto-rebuild — batch then call `app_rebuild`. |
| **`kanban_move(folder, card, toColumn)`** | Move a card between columns. Resolves the card by full path, filename, or title. Respects WIP limits (soft warns; hard blocks). See `manual_read('kanban-move')`. |
| `app_rebuild(folder)` | Regenerate `_board.md` + `_stats.yaml`. Generic — works for every Vance app. |
| `kanban_aggregate(folder, column?, assignee?, labels?, blocked?, priority?, includeBody?)` | Read-only query. "What's in `doing`?", "What does Alice have?", "All blocked cards." |

## Canonical flow — one-shot (preferred)

When you know the columns and starting cards, **one** `kanban_app_create` call sets up everything: manifest, per-column files, board, stats.

```
kanban_app_create(
  folder="projects/website-relaunch/board",
  title="Website Relaunch Sprint Q3",
  columns=[
    { name: "backlog", title: "Backlog",     order: 1 },
    { name: "todo",    title: "To Do",       order: 2, wipLimit: 5 },
    { name: "doing",   title: "In Progress", order: 3, wipLimit: 3 },
    { name: "review",  title: "Review",      order: 4, wipLimit: 2 },
    { name: "done",    title: "Done",        order: 5 }
  ],
  cards=[
    { title: "Login-Flow", column: "todo", priority: "high",
      assignee: "alice", labels: ["auth"], estimate: 5,
      body: "Email + Passwort.\n\n## Akzeptanzkriterien\n- [ ] Registrierung\n- [ ] Login" },
    { title: "Search v1", column: "backlog", labels: ["search"], estimate: 8 },
    { title: "Logo refresh", column: "done", assignee: "bob" }
  ],
  boardStyle: "mermaid"
)
# → manifest written, per-column .md files written, app_rebuild ran automatically.
# Result includes artefacts[]: board + stats paths + markdownLinks.
```

**Embed both artefact `markdownLink`s in your chat reply.**

## Incremental flow — adding cards later

```
kanban_card_create(
  folder="projects/website-relaunch/board",
  column="backlog",
  title="Password reset flow",
  priority="med",
  labels=["auth"],
  body="..."
)
# Repeat for more cards, then:
app_rebuild(folder="projects/website-relaunch/board")
```

## Moving cards

```
kanban_move(folder="projects/website-relaunch/board",
            card="login-flow",          # filename, full path, or title
            toColumn="doing")
# Optional: rebuild=true does app_rebuild in the same call.
```

WIP overflow with `wipEnforce: soft` (default) → move succeeds, result carries `warnings: ["wip-exceeded:doing:4/3"]`. With `wipEnforce: hard` the move throws.

## Anti-patterns

- **Don't write `_app.yaml` by hand** via `doc_create_kind` / `doc_create_text`. The schema has tripwires (kind/app/columns shape) that `kanban_app_create` avoids.
- **Don't edit `_board.md` or `_stats.yaml` by hand.** They are regenerated artefacts. Hand-edits get overwritten on next `app_rebuild`. Edit the source cards, then rebuild.
- **Don't move cards via `doc_move` / `doc_edit` against the path.** That bypasses WIP-limit checks and silently corrupts the column mapping if the target folder doesn't exist. Use `kanban_move`.
- **Don't bundle calendar-style time data into a card** when the user means "deadline". A `dueDate:` field is enough. For full calendars use a separate `app: calendar` folder.
- **Don't call `app_rebuild` after every single card move** — soak up a planning session and rebuild once at the end.

## When NOT to use this pattern

- **Free-form todo list with no workflow states** → `kind: checklist`. Kanban is overhead.
- **Time-anchored plan / Gantt** → `app: calendar`.
- **Full project management with dependencies, resource allocation, burndown** → not Vance's scope. Export to Linear / Jira / GitHub Projects.

## Stats output

`_stats.yaml` carries:

```yaml
$meta:
  kind: data
folder: projects/website-relaunch/board
columns:
  backlog:  { count: 4 }
  todo:     { count: 2, wipLimit: 5 }
  doing:    { count: 4, wipLimit: 3, wipExceeded: true }
  review:   { count: 1, wipLimit: 2 }
  done:     { count: 5 }
blocked:
  - projects/website-relaunch/board/doing/db-migration.md
stale:
  - projects/website-relaunch/board/backlog/old-idea.md
progress:
  totalCards: 16
  done: 5
  open: 11
  ratio: 0.31
  subtasks: { total: 22, done: 14, ratio: 0.64 }
```
