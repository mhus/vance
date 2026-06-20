---
triggers: JavaScript vs Python, choose language, which language, JS oder Python, script language, compute, loop over, batch operation, "write a script", "schreib ein Script"
summary: How to pick between execute_javascript and execute_python for a scripting task — default JS, Python only when its libraries earn it.
---
# Scripting — JavaScript or Python?

Default to JavaScript. Switch to Python only when you genuinely
need a library Python has and JS doesn't.

## When to consult

User says *"write a script and run it"*, *"loop over X and do Y"*,
*"compute Z from this data"*, or a worker request explicitly
involves running code. The choice between JS and Python is rarely
critical, but landing in the wrong one costs setup time
(Python venv: 5–30 s the first time).

## The four runners

### `execute_javascript` — first reach for one-shot JS

In-process GraalVM JS, zero setup, sub-second startup. The script
receives a host object `vance` with three bindings:

- `vance.tools.call("<tool>", { …params… })` — invokes **any** tool
  you can call yourself, including API tools
  (`gmail_rest__gmail_users_messages_batchModify`,
  `jira_rest__searchAndReconsileIssuesUsingJqlPost`,
  `doc_create`, etc.). Return value is the tool result as a
  JS object. So a script reaches anything network / API /
  filesystem your tool surface reaches — the script itself has no
  direct socket.
- `vance.context` — read-only scope (tenant, project, session).
- `vance.log("…")` — trace log.

This is your **first reach when the user says "write a script and
run it"** — whether the body is "compute X" or "mark 100 mails as
read". Value of the last expression is returned.

### `execute_scratch_javascript` — JS with scratch FS access

Same engine, plus read/write access to the project scratch area.
Use for short scripts that touch scratch files but don't need a
library.

### `script_run_doc` / `script_run_workspace` — persisted JS

Run a script that already lives as a Document (`script_run_doc`)
or in a workspace RootDir (`script_run_workspace`). Same
`vance.tools` surface. Use these when the script should be
**re-runnable later** (hooks, scheduler, multiple invocations) —
then `doc_create(kind="text", …)` first, then `script_run_doc`. For
**one-shot inline**, stay with `execute_javascript` — no doc
detour.

### `execute_python` — Python analog of `execute_javascript`

One-shot Python execution: pass `code`, get the result. The tool
ensures a default Python RootDir (`_python` with venv) on first
call — idempotent thereafter — so you don't manage RootDirs
explicitly. Same mental model as `execute_javascript`. Use this
for one-shot Python: data transforms, math that needs numpy,
quick HTML parsing with bs4. The venv is reused across calls in
the session, so `python_install`-installed packages stay
available.

```
execute_python(code="import numpy as np; print(np.array([1,2,3]).mean())")
   → "2.0"
```

Cost: first call spends 5–30 s on venv setup; subsequent calls
are fast. **No `vance.tools` surface** — Python runs as an
external process, can't call other Vance tools from inside.

### `python_run` (+ `python_create`, `python_install`)

For **persisted multi-file Python projects**: explicit
`python_create(dirName=…)` followed by `work_file_write` of the
files and `python_run(file=…, dirName=…)` to execute. Use when
your Python work spans multiple files or you want the project
checked into git (RootDir suspend/resume cycles). For
single-snippet calculations stay with `execute_python`.

## Decision rule

If you'd reach for `import pandas`, `import requests`,
`import bs4`, `import numpy` → **Python**.

If it's `arr.filter(x => …)`, JSON manipulation, or you want to
call other tools from the script → **JavaScript**.

When in doubt: **JavaScript**. Switching to Python later is
cheaper than paying 30 s of venv install upfront for what turned
out to be a three-line transform.

## Anti-patterns

- Spawning a worker to run a one-shot script. Workers re-spawn
  `process_create` and stall the chain — use `execute_javascript`
  inline instead.
- Recreating the venv on every Python call. `execute_python`
  reuses the default `_python` RootDir; `python_create` is
  idempotent by label. Explicit re-install of the same packages
  isn't needed.
- Reaching for Python because "it's more powerful" when the task
  is `arr.map(x => x.foo)` — JS is the cheap default.
- Using `script_run_doc` for a one-shot. Persist only when the
  script has a future life.
