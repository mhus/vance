---
triggers: vance.documents, import vance, vance.py, script reads document, script writes document, Cortex script API, documents.read, documents.write, script-side document access, JS script vance API
summary: Inside a running Cortex script, the project's documents are reachable via `vance.documents.*` (read/write/list/exists/delete/meta). JS gets it as a native binding; Python gets it via `import vance` against a brain-loopback REST call. Cortex spawn-path only — LLM-spawned Python (`execute_python`, `python_run`) does NOT see this API today.
---
# Script Document API (`vance.documents.*`)

A running Cortex script can read, write, list and delete documents
of its own project. Same surface in JavaScript and Python; the host
plumbing differs.

## When to use

- Cortex JavaScript or Python script needs project data (input files,
  configuration documents, prior outputs).
- Script wants to persist its result as a document instead of just
  printing to stdout.
- Script needs to discover what documents exist (e.g. iterate every
  Markdown file under a folder).

If the goal is to *invoke* a script from an LLM turn, see
`python.md` / `scripts.md` for the spawn tools (`python_run`,
`script_run`). This manual is about what the spawned script can
*do* from its own runtime.

## Scope and identity

The script never names its tenant or project explicitly — both come
from the spawn context and the API binds to them automatically. A
script cannot reach documents in another project, even by guessing a
path. Permissions are the spawning user's; nothing is escalated.

Paths follow the standard project layout (`notes/foo.md`,
`data/seed.json`, `scripts/utils.py`). No leading slash. The
`_bin/` trash folder is read-only to scripts — writes there are
rejected with a clear error.

## JavaScript surface

In a JS script the binding is already there as `vance.documents`:

```js
const txt = vance.documents.read("notes/seed.md");

vance.documents.write("output/report.md", "# Summary\n\n…");

if (vance.documents.exists("config.yaml")) {
  const cfg = vance.documents.read("config.yaml");
  // …
}

for (const entry of vance.documents.list("data/")) {
  console.log(entry.path, entry.kind, entry.size);
}

const meta = vance.documents.meta("notes/foo.md");
// meta = { id, path, name, title, kind, mimeType, size, tags, createdAt, version }

vance.documents.delete("scratch/temp.json");  // soft-delete → _bin/
```

Missing documents make `read` / `meta` throw an Error; `exists` and
`delete` return booleans.

## Python surface

`import vance` resolves to a helper module the brain drops next to
the script at spawn time. The API mirrors the JS one:

```python
import vance

txt = vance.documents.read("notes/seed.md")

vance.documents.write("output/report.md", "# Summary\n\n…")

if vance.documents.exists("config.yaml"):
    cfg = vance.documents.read("config.yaml")

for entry in vance.documents.list(prefix="data/"):
    print(entry["path"], entry["kind"], entry["size"])

meta = vance.documents.meta("notes/foo.md")
# meta is a dict with id / path / name / title / kind / mimeType / size / …

vance.documents.delete("scratch/temp.json")  # soft-delete → _bin/
```

Errors come back as `vance.VanceError` — catch it like any
exception. The module also exposes `vance.scope()` returning a small
read-only dict (`brainUrl`, `tenant`, `project`, `session`, `runId`,
`hasToken`) — useful for debugging that the env is wired.

The Python helper uses `urllib.request` only — no `requests`
dependency required in the venv.

## Methods

| Method | JS | Python | Behaviour |
|---|---|---|---|
| read | `vance.documents.read(path)` | `vance.documents.read(path)` | UTF-8 text. Throws on missing. |
| write | `vance.documents.write(path, content)` | `vance.documents.write(path, content)` | Idempotent upsert. Refuses `_bin/`. |
| exists | `vance.documents.exists(path)` | `vance.documents.exists(path)` | Returns bool. |
| delete | `vance.documents.delete(path)` | `vance.documents.delete(path)` | Soft-delete (move to `_bin/`). Returns bool — `false` if not found. |
| list | `vance.documents.list(prefix?)` | `vance.documents.list(prefix=None)` | Summary objects, `_bin/` excluded. |
| meta | `vance.documents.meta(path)` | `vance.documents.meta(path)` | Summary object. Throws on missing. |

`write` is idempotent — creating-or-updating happens transparently.
For existing documents `title` and `tags` are preserved untouched;
only the content changes.

## What's intentionally not in v1

- **No `readBytes` / `writeBytes`.** Binary documents (PDFs, images,
  uploads) require multipart handling on the REST side and have an
  awkward `Int8Array` mapping at the GraalJS boundary. Plain-text
  read/write covers the bulk of use cases today.
- **No cross-project access.** Each script sees only its own
  project. Cross-project workflows go through spawn tools at the LLM
  level, not through script-side imports.
- **No `vance.documents.*` inside LLM-driven Python.** The
  `execute_python` and `python_run` tools spawn Python subprocesses
  that *don't* receive the `VANCE_*` environment — `import vance`
  there fails with `VanceError: Environment variable 'VANCE_BRAIN_URL'
  is not set`. If you need an LLM-spawned Python script to read
  documents, ask the user to author it as a Cortex Python document
  and trigger via the Cortex Run button.
- **No live listing or globbing**, only path-prefix matching via
  `list(prefix)`. Patterns like `data/**/*.json` aren't supported —
  filter client-side after the prefix query.

## Anti-patterns

- **Don't shadow `vance.py`.** Don't name your own Python file
  `vance.py` in the workspace — the bundler overwrites it on every
  spawn. Pick any other name.
- **Don't catch and ignore `VanceError` silently.** A 401 means the
  run's token was revoked (the registry status flipped off `RUNNING`)
  — there's nothing the script can do to recover; let it propagate so
  the Cortex log shows the cause.
- **Don't loop `list()` for polling.** Document changes are not
  pushed to the script; you'd hammer the brain. If you need
  reactive behaviour, write a separate Cortex run triggered manually
  or via a workflow.
- **Don't write to `_vance/` or `_bin/`.** Both are system-managed
  prefixes; writes there are refused server-side.

## Failure modes

- **`Document not found: …`** — the path doesn't resolve in the
  project. Check spelling; `exists()` first if you want a non-throw
  check.
- **HTTP 401 (Python `VanceError`)** — the run's SCRIPT_RUN token is
  no longer accepted. Either the run was killed / completed (the
  registry status flipped) or the request reached the brain from a
  non-loopback peer (unexpected — both are local in the standard
  pod layout).
- **HTTP 403** — the spawning user lacks permission on the target
  document. The script runs with the user's grants; no override.

## See also

- `python.md` — the LLM-facing tooling for managing the Python
  workspace (`python_create`, `python_install`, `python_run`, …).
- `scripts.md` — the JS-script ecosystem (Hactar engine, script
  documents, validation).
- `getting-started.md` — Cortex tabs and the difference between
  documents and runs.
