# Compose

A **compose** document is a batch you can run: fetch some inputs, run
a few steps over them in a workspace, write the results out. Linear
and deterministic — no branching, no waiting on people. For a process
with gates, timers and error routing, write a workflow
(`kind: vance-workflow`) instead.

Think of it as a small pipeline that lives next to the material it
operates on, rather than as a script on someone's machine.

## Shape

```yaml
$meta:
  kind: compose
title: My Compose
description: What this compose does.
workspace:
  name: my-workspace
  type: temp
tasks:
  - type: exec
    command: echo "hello from damogran" > out.txt
    outputs:
      - out.txt
```

Four phases run in order: **provision** the workspace, **import**
inputs into it, run the **tasks**, **export** results. Only the parts
you declare happen — a compose with just `tasks:` is fine.

Tasks run top to bottom and stop at the first failure.

## Task types

| `type:` | |
|---|---|
| `exec` | A shell command in the workspace |
| `python` | A Python script |
| `js` | A JavaScript snippet — returns a value, no file access |
| `llm` | A single model call, writing to a file |
| `spawn` | Starts an agent and does not wait |

`outputs:` names files a task produced; they show up under the Run
button after the run.

## Running it

*View* has the Run button and shows progress plus the produced
outputs. Outputs are **transient** — they live in the workspace and are
there to be looked at. Anything that must survive belongs in an
`export:` step, which writes it back as a real document.

The run happens on the server, so closing the tab does not stop it;
reopening the document picks the run back up.

## Secrets

Never paste a credential into a command. An `exec` task takes a
`secrets:` map that injects values as environment variables:

```yaml
tasks:
  - type: exec
    secrets:
      API_TOKEN: "{{secret:vault:deploy-token}}"
    command: curl -H "Authorization: Bearer $API_TOKEN" ...
```

The value is resolved at run time and masked in the log.
