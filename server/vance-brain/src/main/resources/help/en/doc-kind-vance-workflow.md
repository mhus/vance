# Workflow

A **workflow** is an automation you write down once and start many
times: a state machine of typed tasks that survives session
boundaries — some workflows run for weeks, waiting on a person or a
timer. It is the layer *above* the agents: a workflow spawns them,
routes on what they return, and keeps going after they finish.

The document you have open *is* the plan. There is no separate
definition somewhere else.

## Kind and location are separate things

- `kind: vance-workflow` in the `$meta` header says **what this
  document is**. It holds anywhere in the project — a draft, a copy,
  a variant you are trying out. All of them validate the same way.
- Only a document under `_vance/workflows/<name>.yaml` is
  **startable**. That path is where the loader looks up a workflow by
  name; the file name without `.yaml` is the workflow's name.

So: write it wherever you like, move it under `_vance/workflows/`
when it should go live.

## The two tabs

- **View** — the state machine as a diagram. Read-only, and derived:
  there is no saved layout, every render lays the graph out fresh.
- **Edit** — the raw YAML. This is where you change things.

### Reading the diagram

Each box is one state. Its colour band on the left tells you the task
type; a green or red band marks a terminal state's outcome. The
outlined box is the start state, and `↻` marks a state with a
`retry:` block. Under the name you see the one thing that identifies
what the state does — the recipe, the tool, the command, the delay.

Arrows are distinguished by where they come from:

- **solid** — an `on:` outcome. The normal path.
- **dashed, warm** — a `catch:` error kind. The failure lane.
- **coloured** — a `condition_task` branch; dashed for its `else:`.

An arrow pointing at a state that does not exist is drawn in red to a
dashed ghost box, and listed in the banner above the diagram. The
banner shows the problems the picture can see — a dangling
transition, an unknown task type, a `start:` naming nothing. The
authoritative check runs on the server and also appears in the
document's validation findings.

Use the button in the top right to switch between a top-down and a
left-right layout; wide graphs read better sideways.

## The smallest workflow that runs

```yaml
$meta:
  kind: vance-workflow

start: work

states:
  work:
    type: agent_task
    recipe: jeltz
    params:
      prompt: "What the agent should do."
    storeAs: work_result
    on:
      success: done
    catch:
      agent_error: failed

  done:
    type: terminal
    outcome: success

  failed:
    type: terminal
    outcome: failure
```

`start:` and `states:` are the only required fields. Every target of
an `on:`, `catch:` or `transitions:` entry must name a declared
state — the parser rejects the file otherwise.

## Task types

| `type:` | What it does |
|---|---|
| `agent_task` | Spawns an agent by recipe and waits for it |
| `tool_task` | Calls one tool directly |
| `shell_task` | Runs a shell command in a workspace |
| `script_task` | Runs a JS script from a document or workspace |
| `gate_task` | Asks a person, via an inbox item, and waits |
| `timer_task` | Waits for a duration (`7d`, `4h`, `30m`) |
| `condition_task` | Branches on an expression, no side effects |
| `workflow_task` | Runs another workflow and waits for it |
| `terminal` | Ends the run with `success` or `failure` |

## Passing data along

A state writes its output into a variable with `storeAs: <key>`. A
later state reads it as `${state.<key>}` — in a prompt, a tool
parameter, a gate title, anywhere a string appears. Caller arguments
declared under `parameters:` read as `${params.<key>}`. A missing key
becomes an empty string rather than an error.

## Starting one

From the *Workflows* tab under Insights (pick the workflow, fill in
its parameters, hit start), from a scheduler entry, from the REST
endpoint, or by an agent that has the `workflow_start` tool.

Each start freezes the whole YAML into the run. Editing this document
never affects a run already in flight — fix a bug, and the next start
picks it up while the old runs finish on the definition they began
with.
