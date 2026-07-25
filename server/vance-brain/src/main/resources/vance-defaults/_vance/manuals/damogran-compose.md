---
triggers: compose, damogran, workspace, workspace ausführen, prepare files, exec im workspace, kompilieren, latex, tex nach pdf, python analyse, dateien importieren, git clone, git pull, git push, git commit, repo klonen, batch
summary: Provision a named workspace, import documents, run a linear list of tasks (exec/js/python/llm/tex), export results — via compose_run. Run/clear a compose block held by a document via compose_block_run / compose_block_clear_output.
requires-tools: compose_run, compose_block_run, compose_block_clear_output
---

# Damogran — Workspace Compose

Run a *compose*: a named workspace is provisioned, documents/URLs are imported
into it, a linear list of tasks runs against it, and results are exported back
to documents. Use it when a job needs a real working directory + files + shell/
script/LLM steps — not for a single tool call.

## When to use

- Pull files from documents into a working workspace, run something on them
  (shell/script/analysis), write the result back.
- Compile LaTeX → PDF (`tex-task`).
- Multiple steps that need files from one another in the same directory.

Not for: branching/looping workflows (that is a workflow), or a single tool
call.

## Tool

```
compose_run(composePath="documents/build.compose.yaml")
compose_run(composeYaml="workspace:\n  name: …\ntasks:\n  - …")
```

Exactly one of `composePath` (a `compose` document) or `composeYaml`
(inline). The run is **linear** and **stops at the first failed task**.

**Async:** short composes answer inline with `{ success, workspace, tasks:
[{status, outputs, error?}] }`. A **long** run (>15s) answers with
`{ runId, running: true }` — then **end your turn and wait**; you receive
a `COMPOSE_FINISHED` ProcessEvent (payload: `runId`, `status`, `result`)
as soon as it finishes. Do not poll/block. For long runs (e.g. training)
set `deadlineSeconds: 0` in the task (no hard-kill, runs to completion).

### Running a compose block the user is working on

When the user has a `compose` document open and wants you to **complete** it (not
just edit it) — run it and write the result visibly into the block:

```
compose_block_run(id="<documentId>")        # or path="…"
compose_block_clear_output(id="<documentId>")
```

`compose_block_run` reads the manifest from the **saved** document (so your
`doc_edit`s from this turn are included — no race), runs it and
writes the artifacts back into the managed `$output:` block; an open editor
shows them **live** (exactly like the Run button). Same async semantics as
`compose_run` (inline or `COMPOSE_FINISHED`). `compose_block_clear_output`
removes `$output:`/`$run:` again (the manifest stays). Use these two when
the target is a document block; use `compose_run` instead for ad-hoc/`composePath`.

Works for a **top-level `kind: compose` document** *and* for an
**inline `vance-compose` block in a workpage**: for a workpage, the block the
user has **selected** is run (the "this part" context) —
or the only one, if there is just one. If there are multiple blocks without a
selection, the tool asks which to pick. Only that one block is changed; the rest
of the page is left untouched.

## Manifest

```yaml
title: Build report        # optional; shown above the Run button in the view
description: Sorts + summarizes          # optional
showSource: false          # optional, UI-only (runner ignores): true = also show
                           #   the YAML in the rendered workbook page
                           #   (default: only title/description + Run + outputs)
autoRun: true              # optional, UI-only: false = skip on "Run All Until"
                           #   (still runnable manually via ▶)
session:                   # optional: provide a session process (for `agent`,
  enabled: true            #   `spawn` or tool-using `js` on a CHATLESS button run).
  name: my-agent           #   optional: stable identity → re-run continues (continuity)
  recipe: arthur           #   optional: makes the process an agent (for the `agent` task)
  clean: false             #   true = reset the process before the run (fresh start)
workspace:
  name: my-work            # named workspace (survives re-runs within the session)
  type: temp               # temp | git | node | python
  clear: false             # true = empty first, then create empty anew
  target: WORK             # WORK (default) | CLIENT (exec-only, Foot) | DAEMON (exec-only)
  options: { repoUrl: … }  # git: repoUrl/branch — node/python: packages: [numpy, pandas==2.0]
import:
  - from: vance:data.csv   # vance:<path> = document; http(s):// = external source
    to: data.csv           # workspace-relative (the import target is ALWAYS local)
  - from: git:https://github.com/acme/repo.git   # clone (pull on re-run)
    to: repo               # folder in the workspace
    branch: main           # optional; credentialAlias optional
tasks:
  - type: exec
    command: sort data.csv > sorted.csv
    outputs: [sorted.csv]
  - type: llm
    recipe: analyze        # internal:true recipe
    prompt: "Summarize sorted.csv"
    output: summary.md
export:
  - from: summary.md       # the export source is ALWAYS local (workspace)
    to: vance:summary.md
  - from: repo             # git working tree (cloned via git import)
    to: git:https://github.com/acme/repo.git
    message: "Update from Damogran"   # branch/push/credentialAlias optional
```

## Task-Typen

| type | field(s) | does |
|---|---|---|
| `exec` | `command`, opt. `deadlineSeconds` | shell in the workspace |
| `python` | `script` **or** `code`, opt. `deadlineSeconds` | Python file/inline |
| `js` | `script` **or** `code` | workspace JS (return value only) |
| `llm` | `recipe`, `prompt`, `output` | single-shot LLM → output file |
| `spawn` | `recipe`, `prompt` | worker process (fire-and-forget, new process per run) |
| `agent` | `prompt` (recipe via `session.recipe`) | prompt as a turn to the session process, blocks until reply; output `vance-process:<pid>/<msgId>` |
| `tex-task` | `main` (`.tex`), opt. `engine` | LaTeX → PDF |

## Rules

- **`outputs:`/`output:`** declares which workspace files appear as the result
  (rendered in the UI output region: markdown/text/image/PDF by type).
  No auto-import — persistent results go through `export:`.
- **Time limit `exec`/`python`**: `deadlineSeconds` (alias `timeoutSeconds`,
  default **600**) is a **hard-kill** — if the command runs longer, it is
  terminated and the task fails cleanly (`status=TIMED_OUT`, **no**
  orphaned process). The compose waits until completion or kill; a fast
  command returns immediately (the deadline is only the upper bound).
- **Structured display**: `outputs: [{ path: data.yaml, as: records }]`
  renders canonically-formatted content as a table (`records`) / `tree` /
  `chart` etc. — only with explicit `as:`; without it, it stays text.
- **`vance:` paths**: `vance:hello.tex` is relative to the folder of the compose
  document; `vance:/x` is root-absolute (same project); `vance://project/x`
  is cross-project.
- **`import` first, `export` last.** A task reads what `import` + earlier
  tasks left behind.
- **`llm` needs** a declared output file; the response lands there.
- **`js`** calls `vance.tools.call(...)` with the tool set of the **bound
  process**: with an active chat session, its tools (whether `file_*` are
  included depends on the chat engine/recipe); with your own LLM `compose_run`,
  your process. **If the compose runs chatless via the button** (web UI, no chat
  session), `js` with tool usage needs an active `session:` section — otherwise
  the tool surface is **empty** (`vance.files.isEnabled()` → false; the script
  return value still works). `vance.tools.list()`/`has(name)` show what is
  available; `vance.files` is the file adapter (`isEnabled()` checks,
  `read`/`readRaw`/`write`/`list` throw otherwise). For guaranteed file
  creation always use `python`/`exec` (direct cwd).
- **`spawn`** needs an owner process. With your own `compose_run` you always
  have one; a **chatless button run** needs an active `session:` section,
  otherwise the task fails cleanly with *"spawn task requires a process context"*.
  `spawn` creates **a new process per run** (amnesic).
- **`agent`** sends `prompt` as a **turn** to the session process (`session.recipe`
  = which agent, e.g. `arthur`). Because `session.name` is **stable**, every
  run continues **the same** conversation (continuity; `session.clean: true` =
  fresh). The task **blocks until the reply is there** (upper bound
  `deadlineSeconds`, default 300) and returns the concrete reply message as a
  **`vance-process:<pid>/<msgId>` output**. For agent runs that build on one
  another, use `agent` instead of `spawn`.
- Errors appear in the task result (`status: failure`, `error`), not as an exception.
- **`target: CLIENT` / `DAEMON`**: runs against the filesystem of a remote
  host — CLIENT = the connected **Foot** (Foot session required), DAEMON = a
  named **daemon** (`workspace.name` = daemon name, must be connected in the
  project). Tasks: **only `exec`** (no managed workspace, no `delete`, no
  python/tex/js). **`import`/`export` work**: `vance:`/`http:` **text-based**
  (import) and `vance:` (export), and **`git:*`** via the remote host's `git`
  through exec (import = clone/pull, export = commit/push; if `git` is missing
  there → error). Only **binary** copy is WORK-only, and `credentialAlias`
  (Vault) does not apply remotely — there the host's git credentials count
  (ssh key / credential helper). For "several shell steps in sequence on a
  remote machine". Default stays `WORK`.
- **Deps (`node`/`python`):** `workspace.options.packages: [numpy, pandas==2.0]`
  (or npm specs) installs the list at provisioning (pip/npm) — instead of having
  to import `requirements.txt`/`package.json` first. One-time on creation;
  a reused workspace keeps them (change → `clear: true`).
- **Deleting a workspace:** `workspace: { name: x, delete: true }` discards the
  named workspace and stops (idempotent — if it is missing, it is a no-op).
  `delete` is **terminal**: not combinable with `clear`/`import`/`tasks`/`export`
  (otherwise an error). `clear: true`, by contrast, empties + creates empty anew.

## Example (LaTeX)

```yaml
workspace: { name: paper, type: temp }
import:
  - from: vance:thesis.tex
    to: thesis.tex
tasks:
  - type: tex-task
    main: thesis.tex
    output: thesis.pdf
export:
  - from: thesis.pdf
    to: vance:thesis.pdf
```
