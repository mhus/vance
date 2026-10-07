# Area: Workflows

The Magrathea workflows under `_vance/workflows/` — **one file per
workflow definition**: states, transitions, parameters and budgets.
This is the **first management surface for definitions** — the
Magrathea admin (Insights → Workflows) inspects runs, not what is
supposed to run.

## View

An entry opens the **state graph** inline: states as nodes,
transitions as edges, readable top-down (or left-right — toggle at
the top right). The graph is a **map**, not an editor: workflow YAML
is authored as raw text — "Edit" switches to the source.

## What makes a definition

- `start:` — the start state (must exist in `states:`).
- `states:` — at least one; each has a `type:` (agent_task,
  shell_task, script_task, tool_task, condition, …) and
  type-specific fields.
- `parameters:` — caller parameters, in states as `${params.<key>}`.
- `bounds:` — the run's budget limits (cost, wallclock, spawns).

## Scopes

Project overrides tenant overrides bundled workflows: same name
wins from the inside. Runs are started via the workflow runner,
recipes or schedulers — never from this page.
