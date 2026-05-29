---
triggers: kanban move, karte verschieben, card move, move ticket, drag ticket, status update, change column, status auf doing, ticket nach done, in progress
summary: Move a card between columns on a Kanban board. Resolves the card by filename, path, or title. Respects WIP limits (soft warns, hard blocks).
---
# Tool — `kanban_move`

Move a card between columns on a `app: kanban` board. The on-disk effect is a path rename — the card body stays the same.

## When to use this

- "Schieb Login-Flow auf Doing"
- "Mark Beta-Launch als done"
- "Move ticket 42 to review"
- Any time the user says a card is now in a different workflow state.

**Don't** use `doc_move` or `doc_edit` for the same effect — that bypasses WIP-limit checks and silently corrupts the column mapping if the target folder doesn't yet exist.

## Parameters

| Param | Type | Required | Notes |
|---|---|---|---|
| `folder` | string | **yes** | Kanban app folder. |
| `card` | string | **yes** | Card reference. Resolved in order: full document path → filename (with or without `.md`) → card title (sanitised). |
| `toColumn` | string | **yes** | Target column. Sanitised to filesystem-safe (lowercase, alphanumeric, dashes). |
| `rebuild` | boolean | no | Run `app_rebuild` after the move. Default false — batch moves and rebuild once at the end. |
| `projectId` | string | no | Default: active project. |

## WIP-limit semantics

Set by the manifest's `kanban.wipEnforce`:

- **`soft` (default)** — over-limit moves succeed; the result carries `warnings: ["wip-exceeded:<column>:<count>/<limit>"]`. Surface that warning in the chat reply so the user sees it.
- **`hard`** — over-limit moves are rejected with a `ToolException`. Recovery: move another card out of the target column first.

## Returns

```json
{
  "card": "projects/website/board/doing/login-flow.md",
  "fromColumn": "todo",
  "toColumn": "doing",
  "warnings": ["wip-exceeded:doing:4/3"],
  "nextStep": "Call `app_rebuild('projects/website/board')` when done moving cards..."
}
```

With `rebuild: true`, the response also carries `artefacts[]` like `kanban_app_create` does.

## Canonical usage

```
kanban_move(
  folder="projects/website/board",
  card="login-flow",           # filename, full path, or sanitised title
  toColumn="doing"
)
# → fromColumn: "todo", toColumn: "doing"

# When the user only mentioned the title:
kanban_move(
  folder="projects/website/board",
  card="Login-Flow",           # matches via sanitised-title fallback
  toColumn="review"
)

# When batching multiple moves:
kanban_move(folder=..., card="a", toColumn="doing")
kanban_move(folder=..., card="b", toColumn="doing")
kanban_move(folder=..., card="c", toColumn="done")
app_rebuild(folder="...")      # one refresh at the end
```

## Anti-patterns

- **Don't pass `rebuild=true` on every single move** during a multi-card update — that triggers N rebuilds. Move all the cards, then one `app_rebuild`.
- **Don't try to "move" a card by editing its `column` field.** There is no such field — the column is encoded in the path.
- **Don't fight WIP-limits with `wipEnforce: soft`.** The warning is informational; surface it, don't silently swallow it. With `hard`, ask the user which card to move out first.
- **Don't move a card "to nowhere".** `toColumn` is required. If you want to delete the card, use `doc_delete`, not a move into a non-existent column.

## Failure modes

| Symptom | Likely cause | Recovery |
|---|---|---|
| "No card matching '…' found in …" | Wrong filename, typo, card in different folder | Use `kanban_aggregate(folder)` to list all cards |
| "Target path '…' is already occupied" | Another card has the same filename in the target column | Rename the card first (`doc_move` on the file), then `kanban_move` |
| "Column '…' is at WIP limit (4/3). wipEnforce=hard blocks this move" | Manifest enforces hard WIP | Move a card out of the target column first |

## Related

- `manual_read('app-kanban')` — full board pattern
- `manual_read('kanban-app-create')` — creating a new board
