---
triggers: kanban app create, kanban anlegen, board anlegen, sprint board anlegen, new kanban, neues board, board einrichten, backlog aufsetzen, kanban setup
summary: Bootstrap a kanban-board application — writes the _app.yaml manifest with correct schema and (one-shot form) every starting card. Always use this BEFORE writing cards manually for new boards.
---
# Tool — `kanban_app_create`

Bootstrap a new kanban-board application folder. **First call** whenever the user wants a Kanban / sprint board / workflow-state task tracker.

## Why this tool exists

The manifest format is small but easy to get wrong:
- `$meta.kind` MUST be `application` (LLMs frequently omit this).
- Columns are a **Map** keyed by column-name, NOT an array.
- The whole config lives under `kanban:` (NOT flat at the manifest root).
- Sub-folder convention: column = sub-folder name.

`kanban_app_create` builds the manifest server-side from typed parameters so none of those can go wrong, and writes every starting card to its column folder in the same call.

## When to use this

- "Lege ein Kanban-Board an für Sprint Q3 mit den Spalten Backlog / Todo / Doing / Review / Done"
- "Mach mir ein Board mit den Tasks A, B, C im Backlog"
- "Sprint-Board für Team Auth mit WIP-Limit 3 in Doing"
- "Initial setup für einen Task-Tracker im Projekt"

**Don't** use this for time-anchored plans — that's `calendar_app_create`. **Don't** use this for free-form todo lists — that's `kind: checklist`.

## Parameters

| Param | Type | Required | Notes |
|---|---|---|---|
| `folder` | string | **yes** | Folder for the new app. `_app.yaml` lands at `<folder>/_app.yaml`. |
| `title` | string | no | Display title. |
| `description` | string | no | Free description. |
| `columns` | array<Column> | no¹ | Each: `{ name, title?, color?, order?, wipLimit? }`. **ACCEPTS SHORTHAND:** plain strings like `["backlog", "todo", "doing"]` work too. |
| **`cards`** | **array<Card>** | **no²** | **One-shot:** pass the full card list here and the tool writes one `.md` per card + auto-rebuilds. Each: `{ title, column?, priority?, assignee?, labels?, dueDate?, estimate?, blocked?, body? }`. Cards without `column` land in `backlog`. |
| `boardStyle` | string | no | `"mermaid"` (default) or `"table"`. |
| `wipEnforce` | string | no | `"soft"` (default — warns) or `"hard"` (blocks moves). |
| `overwrite` | boolean | no | Default false. Allow replacing an existing `_app.yaml`. |
| `projectId` | string | no | Default: active project. |

¹ Optional. The default set when columns are not supplied is just whatever columns the cards reference. For a meaningful board, supply at least 3-4 columns covering the workflow.

² **Strongly recommended.** Passing cards here makes this a single-call setup. Without cards, you have to chain N × `kanban_card_create` + `app_rebuild`.

## Card fields

| Field | Type | Notes |
|---|---|---|
| `title` | string | Required. |
| `column` | string | Target column. Auto-creates the column if missing. Default `backlog`. |
| `priority` | string | Free-form. `critical` / `high` render as standouts. |
| `assignee` | string | User identifier. |
| `labels` | array<string> | Free tags. `blocked` is reserved — flags the card. |
| `dueDate` | string | ISO date e.g. `2026-07-15`. |
| `estimate` | number | Story points / hours. |
| `blocked` | boolean | True flags the card. |
| `body` | string | Markdown body (description, acceptance criteria via GFM checkboxes, notes). |

## Returns

When called with `cards`, the tool returns the manifest, the per-column file structure, AND the artefacts (Board + Stats) from the auto-refresh:

```json
{
  "app": "kanban",
  "folder": "projects/website/board",
  "manifestPath": "projects/website/board/_app.yaml",
  "markdownLink": "[Kanban app](vance:/...)",
  "lanes": [
    { "name": "backlog", "title": "Backlog", "suggestedFilePath": "projects/website/board/backlog/" },
    { "name": "doing",   "title": "Doing",   "suggestedFilePath": "projects/website/board/doing/" }
  ],
  "artefacts": [
    { "name": "board", "path": ".../board/_board.md",
      "markdownLink": "[Board](vance:/...)",
      "stats": { "cardCount": 12, "columnCount": 4, "style": "mermaid" } },
    { "name": "stats", "path": ".../board/_stats.yaml",
      "markdownLink": "[Stats](vance:/...)",
      "stats": { "cardCount": 12, "done": 3, "blockedCount": 1 } }
  ],
  "nextStep": "Board ready — _board.md + _stats.yaml are in the `artefacts` list..."
}
```

Without `cards`, no auto-refresh runs and `artefacts` is empty.

**Always embed both artefact `markdownLink`s in your chat reply** so the user opens the Board and Stats with one click.

## Canonical flow — one-shot

```
kanban_app_create(
  folder="projects/website/board",
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
      body: "Email + Passwort.\n\n## Akzeptanzkriterien\n- [ ] Registrierung\n- [ ] Login\n- [ ] Logout" },
    { title: "API Search",    column: "backlog", labels: ["search"], estimate: 8 },
    { title: "Logo refresh",  column: "done",    assignee: "bob" }
  ]
)
# → manifest + 3 cards + board + stats in one result
```

That's it. One call, full setup.

## Anti-patterns

- **Don't write `_app.yaml` yourself via `doc_create_kind` / `doc_create_text`.** Schema has too many tripwires.
- **Don't chain `kanban_app_create` + N × `kanban_card_create` + `app_rebuild` when you have all the cards up front.** Pass them in `cards` to `kanban_app_create` directly.
- **Don't reuse a folder for two apps.** Each app folder hosts exactly one `_app.yaml`.
- **Don't pass `columns: []`** unless the user really wants whatever columns the cards reference. Without a column model, WIP-limits and ordering are meaningless.

## Failure modes

| Symptom | Likely cause | Recovery |
|---|---|---|
| "Manifest already exists at ..." | The folder is already an app | Pick another folder, or pass `overwrite=true` |
| Card filename collides | Two cards slugified to the same name | The tool auto-appends `-2`, `-3` to disambiguate |
| Column name gets sanitised | Column name had spaces / special chars | The returned `lanes[].name` is the sanitised form |

## Related

- `manual_read('app-kanban')` — full kanban workflow + manifest schema
- `manual_read('kanban-move')` — moving cards between columns
- `manual_read('app-rebuild')` — regeneration of board + stats
