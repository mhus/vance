---
triggers: Python, pip, venv, python script, pandas, numpy, matplotlib, scientific computing, Datenanalyse, requirements.txt, python_create, python_run, python_install
summary: Python tooling (venv per RootDir, python_create / install / uninstall / run / set_interpreter) — use when a JS-only solution is awkward.
---
# Python tooling

When the project has a Python RootDir, you have a local `venv` plus
five tools to drive it. The default `label` is `python`, so the
RootDir's `dirName` is normally `python` (or `python-2` etc. when
the worker created a custom-labeled one alongside it).

Tools — all currently deferred, so `find_tools` / `describe_tool`
surface them unless your recipe promotes them (the bundled `python`
recipe does):

- `python_create` — ensure a Python RootDir + venv exists
- `python_install` — `pip install` one or more packages
- `python_uninstall` — `pip uninstall -y` a package
- `python_run` — execute a Python file in the venv
- `python_set_interpreter` — swap interpreter binary, rebuild venv

`scratch_read` / `scratch_write` / `scratch_list` and friends
resolve files inside the RootDir without needing a `dirName` argument
when it's set as the working RootDir.

## Lifecycle

```text
python_create
   ├─ no rootdir yet → git init + python -m venv .venv (+ pip install -r if cloned with requirements.txt)
   └─ rootdir exists → return existing (status="exists")
python_install package=… (or packages=[…])
   → pip install … && pip freeze > requirements.txt
python_run file=…
   → .venv/bin/python <file>  (cwd = RootDir, stdout/stderr captured)
```

`python_create` is **idempotent on `label`**. Calling it twice for the
same goal returns the existing RootDir the second time. Don't loop
on it; if you got `status="exists"`, the env is already there.

## requirements.txt mechanics

- **On `python_create` with `repoUrl`** — if the cloned repo carries
  a `requirements.txt`, the handler runs `pip install -r` as part of
  init. You don't need a follow-up install step for those deps.
- **On `python_install`** — after `pip install <pkgs>` succeeds, the
  tool runs `pip freeze > requirements.txt`. Existing lockfile
  content is overwritten with the fresh frozen view. That's the
  intended behaviour: the lockfile mirrors the venv state.
- **On `recover`** (pod migration) — the new pod re-creates the venv
  and runs `pip install -r requirements.txt`. No worker action needed.

Don't hand-edit `requirements.txt` and expect `python_install` to
respect your edit — the next install pip-freezes over it. To pin a
version, install with that pin: `python_install package="flask==3.0"`.

## Single vs. multi-package install

One pip call, regardless of how many packages:

```text
python_install package="flask"
python_install packages=["flask", "requests>=2", "numpy"]
python_install package="flask" packages=["requests", "numpy"]   # combined
```

Pass either; both works (combined). Prefer `packages` when you have
more than one — saves N-1 turns of pip + freeze cycles. The `flags`
parameter is appended verbatim to the `pip install` line:

```text
python_install package="cryptography" flags="--no-binary :all:"
```

## Running scripts

`python_run` submits via the same execution machinery as `exec_run`,
so long-running scripts return `status=RUNNING` with a `jobId`. Poll
via `exec_status` / `exec_tail`, kill with `exec_kill` — the same
verbs you'd use for any shell job.

```text
python_run file="train.py" args=["--epochs=10", "--lr=1e-4"]
→ { "status": "RUNNING", "id": "abc12345", "stdoutPath": "…/stdout.log", ... }
exec_status id="abc12345"
exec_tail   id="abc12345" lines=50
```

`args` is a string list (escaped per-element). `flags` carries
interpreter switches placed before the file (`-O`, `-X dev`):

```text
python_run file="bench.py" flags="-O" args=["--quick"]
→ .venv/bin/python -O 'bench.py' '--quick'
```

For a one-liner without a file, write it first:

```text
scratch_write path="check.py" content="import sys; print(sys.version_info)"
python_run file="check.py"
```

## Interpreter switching

`python_set_interpreter pythonPath="/opt/homebrew/bin/python3.13"`
wipes `.venv`, rebuilds it with the new interpreter, and reinstalls
from `requirements.txt`. Source files untouched. Synchronous — the
call blocks until the rebuild finishes.

The descriptor's stored `pythonPath` is informational only. On pod
migration, `recover` uses the new pod's local `python3` regardless
of what the descriptor says — so don't pin a path that's only on
your machine and expect it to survive.

## Suspend semantics

Suspend persists Python work via the same Git mechanism the
`GitHandler` uses:

- **With `repoUrl`** — sources commit + push on `vance/suspend/<dirName>`.
  `requirements.txt` rides along, since it's a normal source file.
  `.venv/` stays out (gitignored by default).
- **Without `repoUrl`** — `python_create` initialised an empty local
  repo. Suspend would fail with `WorkspaceSuspendNotConfiguredException`
  because there's no remote to push to. Either set a remote first
  (`exec_run command="cd python && git remote add origin …"`) or
  accept that the env is local-only.

Recovery on the target pod re-clones, rebuilds the venv, reinstalls
from the lockfile.

## What NOT to do

- **Don't write into `.venv/`** with `scratch_write`. The
  filesystem layout is `pip`-managed; pip will not notice your edits
  and the next `python_install` may overwrite them anyway. If you
  need behaviour changes, install a package instead.
- **Don't trust `python_set_interpreter` for version-pinning** across
  pods. The descriptor stores the path you asked for; recover
  intentionally ignores it and uses the local interpreter.
- **Don't loop on `python_create`**. It's idempotent on `label` —
  if you got `status="exists"`, move on to install/run.
- **Don't expect Windows paths**. The tools call `.venv/bin/python`
  (POSIX). Brain pods are Linux.

## Patterns

### One-shot: set up env, install deps, run a script

```text
python_create
python_install packages=["pandas", "numpy", "matplotlib"]
scratch_write path="analyze.py" content="import pandas as pd; print(pd.__version__)"
python_run file="analyze.py"
```

### Adopt an existing repo

```text
python_create repoUrl="git@github.com:acme/data-pipeline.git" asWorkingDir=true
# requirements.txt auto-installed during create; you can run scripts directly
python_run file="pipeline/main.py" args=["--dry-run"]
```

### Long-running training, poll for completion

```text
python_run file="train.py" args=["--epochs=50"] waitMs=2000
# returned with status=RUNNING, id="job123"
# … in a later turn:
exec_status id="job123"
# when status=COMPLETED:
exec_tail id="job123" lines=80
```

### Switch interpreter, keep code + deps

```text
python_set_interpreter pythonPath="/usr/local/bin/python3.13"
# .venv rebuilt, requirements.txt reinstalled, *.py files untouched
python_run file="check_version.py"
```
