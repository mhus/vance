---
audience: creator
triggers: hook, hook_set, hook_list, hook_get, hook_delete, hook anlegen, hook erstellen, reagiere auf, wenn ein prozess fertig, on completion, on failure, inbox trigger, process.completed, process.failed, inbox.item.created, UrsaHook, ursa hook, event-driven
summary: How to create and maintain hooks — event-driven triggers that fire a recipe, script or workflow when a brain event (process.completed / process.failed / inbox.item.created) is published. YAML shape, the TriggerAction disjunction, the catalog of subscribable events, and the anti-patterns for not over-automating.
---
# How I create and maintain hooks

A **hook** is an event-driven trigger: as soon as a certain event
happens in the brain (a process finishes, a process fails, an inbox item
is created), the hook spawns a worker — via a recipe, a script or a
workflow. Unlike a **scheduler** (time-driven) and unlike an **event**
(incoming webhook), a hook reacts to *internal* brain events.

Hooks live as YAML documents under `_vance/hooks/<event>/<name>.yaml` in
the project (or tenant-wide under `_vance/hooks/…`). They become active
as soon as I have written them — the brain registers them on its own
after every write (delta refresh).

## Which tool for what

| Task | Tool |
|---|---|
| See existing hooks in the project | `hook_list` |
| Look up the full YAML of a hook | `hook_get` |
| Create or change a hook | `hook_set` |
| Remove | `hook_delete` |
| Reload the registry (rarely needed) | `hook_refresh` |

`hook_set` is idempotent: if a hook with the same `(event, name)`
already exists, its YAML is overwritten (the previous state is
automatically archived by the document layer). The answer carries
`created: true|false`, so I know which path ran.

## Which events a hook can listen to

`hook_set` needs the **wire name** of the event as its own parameter
(`event`), separate from the YAML body. Live in v1:

- **`process.completed`** — a think process has terminated successfully.
- **`process.failed`** — a think process has failed.
- **`inbox.item.created`** — a new inbox item was created.

Reserved (valid hook documents, but do **not** fire in v1 because the
emitter is still missing): `session.suspended`, `session.resumed`,
`insight.saved`, `relation.created`. I don't create hooks for those "on
spec" — if the user wants one of them, I point out that it currently
does not get triggered.

## Creating a hook (`hook_set`)

The YAML body defines **exactly one** action — `recipe:`, `script:` or
`workflow:` (a disjunction, enforced at parse time). Example: after
every failed process, start a short review recipe.

```
invoke_tool(
  name = "hook_set",
  params = {
    "event": "process.failed",
    "name": "post-failure-review",
    "yaml": """
description: \"Starts a short root-cause analysis after every failure.\"
recipe: \"analyze\"
initialMessage: |
  A process has failed. Summarize the likely cause in three sentences
  and suggest a next step.
enabled: true
"""
  }
)
```

### Body fields

- **Action (mandatory, exactly one):**
  - `recipe: "<name>"` — spawns a worker with this recipe. Optionally
    with `initialMessage:` (the first prompt) and `params:`.
  - `script: { source: <document|inline>, path: "<path>" }` — runs a
    script document (optionally `dirName`, `timeoutSeconds`, `params`).
  - `workflow: <name>` — starts a named workflow.
- **`enabled`** (optional, default `true`) — set to `false` to disable
  the hook without deleting it.
- **`description`** (optional) — what the hook does, for the list/log.
- **`timeout`** (optional) — a number in seconds or a duration string
  (`"30s"`, `"5m"`). There is a hard upper bound per hook.
- **`runAs`** (optional) — on whose behalf the worker runs (whose inbox
  gets messages/errors). Without a value, the `createdBy` of the
  document.
- **`tags`** (optional) — free labels for filtering.
- **`params`** (optional) — a parameter map that is passed through to
  the action.

**Schema note:** The old `type: js|llm` format is no longer accepted.
JS hooks become `script: { source: document, path: … }`, LLM hooks
become a script that calls `vance.lightllm.call(...)`.

## Adjusting existing hooks

`hook_set` needs the **complete** YAML — full replace, no patch
operation. The previous state is automatically archived. Workflow:

1. `hook_get(event, name)` → current YAML
2. Adjust the spot
3. `hook_set(event, name, yaml)` with the changed full text

Only to disable: `enabled: false` into the YAML, `hook_set`.

## What I do NOT create

- **No hooks on spec.** A hook fires on *every* occurrence of its
  event — that can get loud fast. Ask first whether it should really
  run on every `process.completed`.
- **No hooks for reserved events** (`session.*`, `insight.saved`,
  `relation.created`) without the note that they do not fire in v1.
- **No self-trigger loops.** A hook on `process.completed` that itself
  spawns a process which fires `process.completed` again runs into an
  infinite loop. Before creating, check whether the hook action
  re-triggers the same event.
- **No duplicate hooks** for the same topic. First `hook_list`, then
  update or ask.
