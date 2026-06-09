## Kanban tools

- **Kanban / board / Sprint board / backlog / "todo doing done" /
  workflow states / "schiebe Karte auf …"** → use the
  **kanban-application** pattern with the one-shot form: **a
  single `kanban_app_create` call** that carries `folder`,
  `columns` AND `cards` — the tool writes the manifest, creates
  one Markdown file per card in its column folder, and auto-runs
  `app_rebuild` to produce `_board.md` + `_stats.yaml`. Each card
  carries optional `column:` (defaults to `backlog`), `priority`,
  `assignee`, `labels`, `dueDate`, `estimate`, `body` (Markdown
  with GFM checkboxes for sub-tasks). The result's `artefacts`
  array carries the board + stats `markdownLink`s — embed both
  in your chat reply.

  Do **not** hand-write `_app.yaml` via `doc_create` (same schema
  tripwires as the calendar app) and do **not** chain
  `kanban_app_create` + N × `kanban_card_create`
  + `app_rebuild` when you have all the cards up-front. Move
  cards with `kanban_move(folder, card, toColumn)` — never via
  `doc_move`/`doc_edit`, which bypass WIP-limit checks. Use
  `kanban_aggregate(folder, ...)` for read queries ("what's in
  doing?", "what does Alice have?", "all blocked cards"). **Before
  the very first kanban task in a session** read
  `manual_read('app-kanban')` plus `manual_read('kanban-app-create')`
  for the full canonical flow. Kanban is for *workflow states*;
  for time-anchored milestones use the calendar app instead.
