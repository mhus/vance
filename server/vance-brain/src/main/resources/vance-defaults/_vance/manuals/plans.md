---
triggers: workflow starten, start workflow, plan ausführen, run a plan, run the workflow, workflow_start, vogon, workflowPath, führe den workflow aus, execute workflow, plan laufen lassen, workflow von pfad, run plan from path, existierenden workflow starten, gate approval workflow
summary: How to run a plan that already exists — vogon (someone is waiting, any path) versus workflow_start (unattended, by name). Includes the two ways a plan is addressed and why copying files into _vance/workflows/ is never the answer.
---
# Running a plan that already exists

A **plan** is a state machine written as YAML — states, transitions,
agent steps, gates, terminals. This manual is about *running* one that
exists. For writing a new one, see `manual_read('slartibartfast')`.

## Which way to run it

One question decides it: **is somebody waiting for it?**

| | `vogon` recipe | `workflow_start` tool |
|---|---|---|
| Someone waits for the result | yes — it comes back into this conversation | no — the run belongs to the project |
| Questions from the plan | reach the conversation **and** the inbox | inbox only |
| Addressing | by name or by any path | by name or by any path |
| Started as | a worker (`process_spawn`) | a tool call |

**A person asked you to run something → `vogon`.** They are in the
conversation, they will be asked at gates, and they should get the result
there. That is the entire point of the engine.

**Automation → `workflow_start`.** A scheduler, an event, a hook, or a
step that genuinely nobody is watching.

## Spawning it as a worker

```
process_spawn(
  recipe = "vogon",
  task   = "<what the person asked, verbatim>",
  params = { "workflow": "<plan name>" }        # or workflowPath
)
```

`task` is not decoration. A plan can declare `parameters:`, and whatever
of them the caller did not pass is read out of that text. "release version
1.0.0" fills `version` by itself; the plan does not have to be told twice.

## Starting it unattended

```
workflow_start(name = "release")                       # through the cascade
workflow_start(path = "workflows/helloworld.yaml")     # exactly this document
```

## The two ways to address a plan

The same pair either way, spelled to fit where it is written:

| | as a name | as a path |
|---|---|---|
| `vogon` worker | `params.workflow` | `params.workflowPath` |
| `workflow_start` | `name` | `path` |

- **By name** — resolved through the cascade:
  `_vance/workflows/<name>.yaml`, project before tenant. For the plans that
  live in that folder.
- **By path** — one document, wherever it lies:
  `workflows/helloworld.yaml`, `docs/plans/release.yaml`, anything.

Exactly one of the two. Everything else in `params` goes to the plan as
its caller parameters.

**With Vogon you may also just say it.** If the task text names a plan —
a path like `workflows/helloworld.yaml`, or a plan name — Vogon takes it
from there and no `params` are needed at all. A path in the text is used
verbatim, without asking a model about it.

## Do not copy plans into `_vance/workflows/`

A plan at another path is not a problem to be fixed by moving it. It is
one of the two normal ways to address a plan — `path` for the tool,
`params.workflowPath` for the worker. Copying makes a second copy that
will drift from the first, in a folder the author did not choose.

If a start fails with *"not found in cascade"*, the file is not in the
wrong place: a **name** was used for a plan that does not live under
`_vance/workflows/`. Give the path instead.

## What happens after it starts

The run advances on its own. When it reaches a gate, an inbox item is
created for the assignee and — with `vogon` — the worker reports back so
the question can be raised here. When it ends, the result comes back as
the worker's reply.

You do not have to poll it. Do not spawn a second run of the same plan
because the first one has not answered yet; a plan that waits at a gate is
waiting for a person, not stuck.

## Related

- `manual_read('slartibartfast')` — writing a *new* plan.
- `manual_read('processes')` — spawning and inspecting workers in general.
- `manual_read('inbox-post')` — how the gate questions reach people.
