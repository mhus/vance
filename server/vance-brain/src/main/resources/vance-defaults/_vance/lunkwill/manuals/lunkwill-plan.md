---
triggers: plan, plan machen, planen, planung, plan first, todo, todolist, todo list, todo_create, todo_update, todo_remove, multi step, mehrere schritte, große aufgabe, refactor planen, structure work, break down task
summary: When and how to use todo_create / todo_update / todo_remove to structure a multi-step task. Lunkwill's reduced Plan-Mode variant.
requires-tools: todo_create, todo_update, todo_remove
---

# Making a plan in Lunkwill

For tasks that span more than 2-3 file edits or multiple logical
phases, write a TodoList. The engine renders it back to you as a
system block on every turn so it stays your anchor — and items
shrink off the list as you complete them.

This is Lunkwill's CRUD-style version of Plan-Mode — no Mode switch,
no user approval, no read-only filter. You create items and start
working immediately.

## When to plan

**Plan first** when the task involves:

- Multi-file refactor (rename across modules, API change with
  call-site migration, type-system upgrade).
- Architecture-touching changes (new module, new service, new
  dependency).
- Behaviour change that needs verification (write failing test →
  fix → re-run → other tests still green).
- Open-ended exploration (find why X fails, fix it, regression-test).
- Anything where the user said "refactor", "migrate", "rewrite",
  "umbau", "umstellen", "größer".

**Don't plan** for:

- A single file edit ("add a comment", "fix this typo").
- A direct lookup ("what does function X do").
- A trivial question ("which port does the server listen on").
- A short script run.

Faustregel: if you'd want to see your progress halfway through, plan
it. If you're done in two tool calls, don't.

## The three tools

### `todo_create` — append items

```json
todo_create({
  "items": [
    {"content": "Read existing parser implementation",
     "activeForm": "Reading parser"},
    {"content": "Add streaming variant alongside",
     "activeForm": "Adding streaming variant"},
    {"content": "Migrate callers in vance-shared"},
    {"content": "Run mvn -pl vance-shared test"}
  ]
})
```

Rules:

- **No `id` field — the server assigns ids and shows them in the
  prompt block.** Don't try to guess or supply your own ids.
- **3 to 8 items per plan.** Fewer and the structure is wasted;
  more and it stops being a plan and starts being a script.
- **Logical phases**, not atomic tool calls. "Migrate callers" is
  a step; "file_edit X.java line 42" is not.
- **Imperative form** in `content`, present-continuous in
  `activeForm` (used by the UI spinner when the item is
  IN_PROGRESS — optional).
- Status is always PENDING on create. Use `todo_update` to change
  status.
- You can call `todo_create` again later to add steps as new
  needs emerge — ids of existing items don't change.

The tool returns `{ok, created: [{id, content, activeForm?}, ...]}`
so you immediately see the assigned ids; they'll also appear in
the next turn's prompt block.

### `todo_update` — change one or more items

```json
todo_update({
  "items": [
    {"id": "2", "status": "IN_PROGRESS"},
    {"id": "3", "status": "COMPLETED"}
  ]
})
```

Per-item partial mutate:

- **`id` is required.** Get it from the prompt block.
- **`status`, `content`, `activeForm`** are all optional. Any field
  you include overwrites; fields you omit stay as they were.
- Convention for status: set IN_PROGRESS when you pick a step up,
  COMPLETED when you finish it.
- Unknown ids are silently ignored — no crash if you reference a
  stale step.

**Auto-clear.** When every item in the list becomes COMPLETED, the
list is automatically cleared and the prompt block goes back to
"no active plan". This is the natural finish; there is no
"close the plan" tool to call.

### `todo_remove` — drop items

```json
todo_remove({"ids": ["3", "5"]})
```

Use when a step is no longer needed (requirement changed, item
was an over-decomposition). Unknown ids are ignored.

## How the prompt block works

When the list is empty you see a one-liner suggesting
`todo_create`. When the list has items, only the non-COMPLETED
ones are shown — each with its server-assigned id and status
marker (`[ ]` PENDING, `[~]` IN_PROGRESS). One short footer line
points at the three tools.

That is your read path. There is no `todo_read` tool because the
engine pushes the state to you every turn — calling a tool to read
what is already in the prompt would waste a turn.

## What this is NOT

- **Not a permission gate.** All your normal tools work whether
  you have a plan or not. The plan is a structure aid, not a
  security layer.
- **Not user approval.** The user does not see "approve this
  plan?" — they see the TodoList live-update as you work. If they
  want to redirect, they steer you the normal way.
- **Not Marvin.** Marvin spawns sub-processes per plan node and
  aggregates. Lunkwill stays in one process and updates statuses.
  If your task needs branching, fan-out, or per-step delegation,
  call `process_create(recipe='marvin', goal='…')` instead and let
  Marvin plan.
- **Not Arthur Plan-Mode.** Arthur switches modes (EXPLORING /
  PLANNING / EXECUTING) and filters tools to read-only during
  exploration. Lunkwill has no modes and no filter — same
  TodoList persistence layer, different state-machine.

See `specification/public/lunkwill-engine.md §9` for the
architectural detail and `specification/plan-mode.md` for
Arthur's / Eddie's full Plan-Mode (which this is a deliberate
reduction of).
