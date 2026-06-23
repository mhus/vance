---
triggers: plan, plan machen, planen, planung, plan first, todo, todolist, todo list, todo_write, todo_update, multi step, mehrere schritte, große aufgabe, refactor planen, structure work, break down task
summary: When and how to use todo_write / todo_update to structure a multi-step task. Lunkwill's reduced Plan-Mode variant.
requires-tools: todo_write, todo_update
---

# Making a plan in Lunkwill

For tasks that span more than 2-3 file edits or multiple logical
phases, write a TodoList first. The engine renders it back to you as
a system block on every turn so it stays your anchor.

This is Lunkwill's reduced version of Plan-Mode — no Mode switch,
no user approval, no read-only filter. You write the plan and start
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

## The two tools

### `todo_write` — full replace

Use to seed an initial plan or rewrite the plan structurally when
the original no longer fits (new requirement emerged, original
assumption was wrong).

```json
todo_write({
  "items": [
    {"id": "1", "content": "Read existing parser implementation",
     "activeForm": "Reading parser"},
    {"id": "2", "content": "Add streaming variant alongside",
     "activeForm": "Adding streaming variant"},
    {"id": "3", "content": "Migrate callers in vance-shared",
     "activeForm": "Migrating callers"},
    {"id": "4", "content": "Run mvn -pl vance-shared test"}
  ]
})
```

Rules:

- **3 to 8 items.** Fewer and the structure is wasted; more and
  it stops being a plan and starts being a script.
- **Logical phases**, not atomic tool calls. "Migrate callers" is
  a step; "file_edit X.java line 42" is not.
- **Imperative form** in `content`, present-continuous in
  `activeForm` (used by the UI spinner when status is IN_PROGRESS).
- **Stable IDs.** Once you've written id="2", that is "step 2"
  forever — even if you revise the plan, give the same step the
  same id. `todo_update` keys off id.
- **Status defaults to PENDING.** Specify only if you really mean
  to write a plan with some steps already done.

`todo_write` is **full replace** — it clears the old list and
writes the new one. There is no merge / patch semantics.

### `todo_update` — per-item status

Use to mark progress as you work. Convention:

1. Before starting a step:
   `todo_update({"updates":[{"id":"2","status":"IN_PROGRESS"}]})`
2. After finishing:
   `todo_update({"updates":[{"id":"2","status":"COMPLETED"}]})`

You can update multiple items in one call. Items not in `updates`
stay untouched. Unknown ids are silently ignored — no crash if you
mistype.

## How the prompt block works

When `process.todos` is non-empty, every turn's system prompt
contains an `## Active Plan` block: the full list with markers
(`[ ]/[~]/[✓]`), the current step pointer, and the two tool-call
hints.

That is your read path. **There is no `todo_read` tool** because
the engine pushes the state to you each turn — calling a tool to
read what is already in the prompt would waste a turn.

The current step pointer picks the first non-COMPLETED item. When
everything is COMPLETED the block tells you to call the recipe's
task-complete tool (e.g. `task_complete(summary=...)`) to end
cleanly.

## Hard rules

- **Never downgrade a `[✓] COMPLETED` item.** Once done is done.
  If the work needs redoing, write a new step.
- **Never edit todos via anything other than `todo_write` /
  `todo_update`.** No `file_edit` on Mongo dumps, no clever side
  channels.
- **Work top-to-bottom.** Take the first non-`[✓]` item, IN_PROGRESS,
  do the work, COMPLETED. Don't jump around — the UI shows the user
  a linear progression, and your plan should match.
- **Don't re-write the plan just to add one step.** Use `todo_write`
  only when the structure changes; for "ah, I need to also do X",
  emit a TodoList revision with the new item and stable ids on the
  rest.

## What this is NOT

- **Not a permission gate.** All your normal tools work whether you
  have a plan or not. The plan is a structure aid, not a security
  layer.
- **Not user approval.** The user does not see "approve this plan?"
  — they see the TodoList live-update as you work. If they want to
  redirect, they steer you the normal way (chat / process_steer).
- **Not Marvin.** Marvin spawns sub-processes per plan node and
  aggregates. Lunkwill stays in one process and updates statuses.
  If your task needs branching, fan-out, or per-step delegation,
  call `process_create(recipe='marvin', goal='…')` instead and let
  Marvin plan.
- **Not Arthur Plan-Mode.** Arthur switches modes (EXPLORING /
  PLANNING / EXECUTING) and filters tools to read-only during
  exploration. Lunkwill has no modes and no filter — same
  TodoList persistence, different state-machine.

See `specification/lunkwill-engine.md §9` for the architectural
detail and `specification/plan-mode.md` for Arthur's / Eddie's
full Plan-Mode (which this is a deliberate reduction of).
