# Insights inspector

A read-only diagnostic browser for what the brain has persisted.
Useful when you want to know:

- *What sessions exist for a user / project?*
- *What think-processes are inside this session, what engine, what
  status?*
- *What is the chat history of this process — and which messages have
  been compacted into a memory entry?*
- *What memory entries does this process hold? Where do they come from?*
- *What does this Marvin task-tree look like?*

Nothing here mutates state. To stop a stuck process, edit a recipe, or
answer an inbox item, use the dedicated editors.

## Layout

- **Left sidebar** — Sessions, optionally filtered by project / user /
  status. Each session expands to show its think-processes (engine,
  status, recipe).
- **Main pane** — context-sensitive tabs:
  - On a session: *Overview*, *Processes*.
  - On a process: *Overview*, *Chat*, *Memory*, plus *Tree* when the
    engine is `marvin`.

## Tab notes

### Overview (session)
- Business id, owner, project, last activity timestamp.
- `chatProcessId` links to the auto-spawned chat process — usually the
  most interesting starting point.

### Overview (process)
- Engine, version, recipe, status, parent process.
- `engineParams` are the runtime values set at spawn (model alias,
  validation toggles, …).
- *Active skills* lists the skills currently mounted, with their
  origin scope and the `fromRecipe` / `oneShot` flags.
- *Pending queue* shows what the process has not yet drained — handy
  when a turn looks stuck.

### Chat
- The full chat history of the process, in chronological order.
- Messages whose `archivedInMemoryId` is set have been folded into a
  compaction; the UI dims them and links to the memory entry.

### Memory
- Engine-side memory entries scoped to this process.
- `kind` indicates the role:
  - `ARCHIVED_CHAT` — compaction summary of older chat messages.
  - `PLAN` — a plan body (Marvin / Vogon).
  - `SCRATCHPAD` — engine-internal notes.
  - `OTHER` — engine-defined.
- `sourceRefs` ties the entry back to the records it was derived from
  (chat-message ids for archives, etc.). `supersededByMemoryId`
  closes the audit chain when a newer compaction replaces this one.
- Memory at higher scopes (project / session-only) is not shown here —
  use a future project-wide memory view (out of scope for v1).

### Tree (Marvin only)
- The task tree of the Marvin process: PLAN / WORKER / USER_INPUT /
  AGGREGATE nodes with their `goal`, `status`, `artifacts`,
  `failureReason`.
- WORKER nodes link to their `spawnedProcessId` — click to inspect the
  worker process. USER_INPUT nodes link to their `inboxItemId` (open
  in the inbox editor).

## Engine-specific notes

- **arthur / ford** — the interesting state lives in *Chat* (history)
  and *Memory* (compactions). *Tree* is empty.
- **marvin** — *Tree* shows the planning state; *Memory* often holds
  scratchpad and plan revisions.
- **vogon / zaphod** — phase / head state lives in `engineParams` and
  *Memory*; richer per-engine views can come later.
