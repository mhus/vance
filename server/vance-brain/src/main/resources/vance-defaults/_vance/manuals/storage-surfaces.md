---
triggers: storage, where to save, persist, Datei speichern, Document vs scratch vs exec, save file, durable storage, ephemeral, scratchpad, RootDir
summary: Where to put a file — Document (long-lived, indexed) vs scratch (project sandbox) vs exec (per-run workspace). Choose by audience + lifetime.
---
# Storage Surfaces — where to put a file

Vance has three distinct stores. Picking the wrong one is hard to
undo: the file ends up where the user can't find it, or where it
can't be processed. Choose by *who reads it next* and *how long it
should live*.

## When to consult

You are about to write something to disk / persist a file / save a
result — and it isn't obviously a Document yet. The right answer
depends on lifetime and audience, not on what the user typed.

## The three stores

### Document — long-lived knowledge

Tools: `doc_create`, `doc_edit`, `doc_*`.

The project's indexed, searchable, auto-summarised, tagged
knowledge base. Default for **anything the user will want to find
again**: research results, summaries, comparisons, decisions,
specs, notes, lists, tables.

When the user says *"speichere X als Markdown"* / *"save this for
later"* → Document.

### Scratch — short-lived work files

Tools: `work_file_write`, `work_file_read`, `work_file_grep`,
`python_run`, `work_exec_run`, `execute_work_javascript`.

The project's on-disk sandbox. Use for:

- intermediate artefacts you'll process next (CSV/JSON fixtures,
  parsed dumps),
- scripts you're about to run,
- pipeline outputs that aren't worth keeping after the step.

**Not searchable, not part of the user's knowledge base, may be
discarded on suspend.** When a scratch file turns out worth
keeping, promote it: `work_file_to_doc`.

### Client file — the user's own filesystem

Tools: `client_file_write`, `client_file_read`, `client_file_*`.

The host machine where the user's foot CLI runs — their actual
disk. Only when the user **explicitly** asks for a local file:

- a code project they edit outside Vance,
- a lab notebook on their machine,
- a download to their downloads folder.

Vance does not index or search these. Don't volunteer this surface
unless the user names a local path.

## Decision shortcuts

| User intent | Surface |
|---|---|
| "Speichere/notiere/schreib mir X auf" | Document |
| "Mach ein Markdown / eine Tabelle / einen Bericht" | Document |
| "Werte mir die CSV aus" (intermediate) | Scratch → result to Document |
| "Schreib die Datei nach `~/Downloads/foo`" | Client file |
| "Skript zum Wegklicken — egal wo" | Scratch |
| Pipeline-intermediates, parsed dumps | Scratch |

## Anti-patterns

- Saving a finished report to **scratch** because "it's just one
  file" — user opens the documents list, doesn't find it, asks
  again. Wrong store.
- Writing to **client_file** when the user didn't mention a local
  path — the file lives where Vance can't see it.
- Treating **Document** as a temp store for intermediates — the
  auto-indexer and summarisation cost are wasted on throwaway data.
