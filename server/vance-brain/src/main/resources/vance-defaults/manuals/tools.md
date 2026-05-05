# Tools catalogue and composition

Two universes of tools share the same dispatcher:

- **Server tools** — run in the brain. Always available.
- **Client tools** (prefix `client_*`) — run on the user's foot
  client when one is connected. Act on the user's machine.

Use `find_tools query=<keyword>` and `describe_tool name=<x>` to
explore beyond the primary set advertised every turn.

## Server-side workspace + exec

Per-project sandbox the LLM controls in the brain:

- `workspace_write / read / list / delete` — UTF-8 files under
  `data/workspaces/<projectId>/`. Survives restarts; visible to
  every session in the same project.
- `execute_javascript code=…` / `execute_workspace_javascript path=…`
  — sandboxed GraalJS. Scripts can call other tools through the
  `vance.*` host binding. See `manual_read scripts`.
- `exec_run command=… [waitMs=…]` — shell command in the project
  workspace (CWD = workspace root). Returns a job id if it doesn't
  finish in time. `exec_status id=…` and `exec_kill id=…` follow up.

Typical flow: `workspace_write` a file → `exec_run` to act on it (`git
clone`, build, grep) → `workspace_read` to inspect outputs.

## Client-side filesystem + exec

When a foot client is connected, these are available:

- `client_file_list / read / write / edit` — operate on the user's
  actual filesystem. Absolute paths; `~` expands to home.
- `client_exec_run / status / kill` — shell on the user's machine.
- `client_javascript code=…` — sandboxed GraalJS on the foot. Has its
  own `client.*` host binding (see `manual_read scripts`).

Use these when the user asks about *their* files, not project files.

## Memory + RAG

- `scratchpad_set / get / list / delete title=…` — named-slot persistent
  notes per think-process. Use to remember intermediate findings, plans,
  anything you need next turn that isn't natural chat.
- `rag_create / list / query / add_text / add_workspace_file / delete`
  — per-project retrieval indexes. `rag_query name=<rag> query=<text>`
  returns the top-K most similar chunks. Compose with
  `rag_add_workspace_file` to ingest a workspace file.

## Web

- `web_search query=…` — Serper.dev-backed Google search. Use for
  fresh facts or anything you don't already know.

## Compute

- `execute_javascript code=…` — runs JS in the brain. Prefer this
  over guessing arithmetic or algorithmic answers.
- `current_time [zone=…]` — wall-clock time.

## Process orchestration

- `process_list` — siblings in the current session.
- `process_status name=…` — one process detail.
- `process_create name=… engine=ford [goal=…]` — spawn a sub-process.
- `process_steer name=… content=…` — drive another process. See
  `processes` doc.
