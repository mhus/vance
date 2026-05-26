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
  `doc_create_text`, etc.). Return value is the tool result as a
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
then `doc_create_text` first, then `script_run_doc`. For
**one-shot inline**, stay with `execute_javascript` — no doc
detour.

### `python_run` (+ `python_create`, `python_install`)

Full Python in a venv inside the scratch area. Use when you need
a library (pandas, requests, beautifulsoup, numpy …) or when a
script is long enough that Python's ecosystem actually buys
something. Cost: first call spends 5–30 s on venv + pip install.
Reuse the venv across calls — don't recreate it per script.
**No `vance.tools` surface** — Python is isolated, it can't call
other Vance tools from inside.

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
- Recreating the venv on every `python_run` call. Idempotent
  `python_create` is fine; explicit re-install of the same packages
  isn't.
- Reaching for Python because "it's more powerful" when the task
  is `arr.map(x => x.foo)` — JS is the cheap default.
- Using `script_run_doc` for a one-shot. Persist only when the
  script has a future life.
