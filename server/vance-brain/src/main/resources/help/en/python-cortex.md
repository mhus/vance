# Python in Cortex

A workbench for Python snippets that run **server-side** on the brain
host through the project's own venv. Same Cortex layout as JavaScript
(file tree, tabs, chat) — the **▶ Run Python** button drives a real
`python` subprocess, not a sandbox.

## Files

Any `.py` file in the project tree becomes runnable. Cortex picks up
the Python runner automatically — extension wins over MIME type.

- **`scripts/`** — default bucket for stand-alone snippets.
- **`skills/<name>/scripts/`** — skill-bound code where it makes
  sense to live next to its skill.
- Any other path — Cortex imposes no structure.

Other extensions (`.json`, `.yaml`, `.md`, …) open as plain
documents, no Run button.

## How it runs

The first `Run` call on a project's first `.py` file does a one-time
bootstrap:

- Creates a per-project Python RootDir labelled `_python`.
- Provisions a fresh `.venv/` with the brain's configured Python
  interpreter.

Subsequent runs reuse that venv — anything you installed with
`python_install` from the LLM (or via inline metadata, see below)
stays available. Each Run writes the current document body to a
transient `_inline_<timestamp>.py` inside the RootDir and launches
it as:

```
.venv/bin/python <flags> <transient.py> <args>
```

## Inline dependencies (PEP 723)

The Cortex runner honours [PEP 723](https://peps.python.org/pep-0723/)
inline-script-metadata — a top-of-file comment block that declares
which third-party packages the script needs. Run picks the block up,
installs the missing packages into the project venv, then runs the
script. Hash-cached so unchanged scripts don't re-install.

```python
# /// script
# dependencies = [
#   "requests",
#   "rich >= 13",
# ]
# ///

import requests
from rich import print
print(requests.get("https://api.github.com").json())
```

Notes:

- Only the `dependencies` field is consumed in v1. Other PEP 723
  fields (`requires-python`, named labels) are accepted and ignored
  — the brain's configured venv Python version wins.
- Hash marker lives in the venv as `.vance_inline_deps_hash`. Edit
  the dependency list → next Run reinstalls; otherwise pip is
  skipped entirely.
- A failed `pip install` leaves the marker untouched, so the next
  Run retries.
- For long-lived multi-script projects, prefer the LLM's
  `python_install` tool with a `pyproject.toml` — inline deps are
  for self-contained one-shot scripts.

## Args

The toolbar's `{}` field is a JSON object. For Python it's flattened
to shell-args before invocation — each top-level key becomes a
`key=value` token appended to the command. Example:

```json
{ "n": 10, "verbose": true }
```

becomes `python script.py n=10 verbose=true`. Parse them yourself in
the script:

```python
import sys
for arg in sys.argv[1:]:
    key, _, value = arg.partition('=')
    print(f"got {key!r}={value!r}")
```

If you want a plain positional-arg list, future variants of the
toolbar input will accept a JSON array — for now stay with the
key-value convention.

## Output

stdout and stderr both stream into the log panel below the editor,
tagged `[stdout]` / `[stderr]`. The Cortex Python runner **polls**
the brain every ~1.5 s and replaces the log buffer with the latest
snapshot — there's no WebSocket push for Python (unlike the JS
runner). Short scripts feel instant; long scripts get fresh lines
each polling tick.

When the process exits the badge flips to `finished` (exit-code 0)
or `failed` (non-zero exit). The result panel shows `{ exitCode: N }`
on success — Python doesn't have a `return value` like the JS
runner's last expression, just stdout + exit code.

## Cancel

The `■ Cancel` button kills the subprocess via the brain's
`ExecManager.kill`. Best-effort: forks / threads spawned by your
script may need their own cleanup; the main process is SIGKILL-ed
when the watchdog catches it.

## Save first

Cortex flushes any unsaved edits to the server **before** kicking
off the run — the brain loads the document body from MongoDB, not
from your browser buffer. If you've just typed and hit Run, the
saved version is what executes.
