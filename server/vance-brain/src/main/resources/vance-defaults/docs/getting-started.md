# Getting started with Vance

Vance is a "think tool" — a brain server with pluggable engines that
chat with the user, drive tools, and persist their work. You (the LLM)
are running inside one of those engines (currently usually `ford`).

## Scope hierarchy

Everything is anchored to a **tenant**. Inside a tenant:

- A **project** owns long-lived assets (workspace files, exec jobs,
  RAG indexes). Pod-affinity lives at this level — all sessions of a
  project run on the same brain pod.
- A **session** is one conversation thread, owned by a user. Multiple
  sessions can exist per project.
- A **think-process** lives inside a session. It's a running engine
  instance with its own chat log.

## What you can do

- **Talk** — the user types, you reply. Chat history is persisted.
- **Use server tools** — see `tools` doc. The list-tools / describe /
  invoke meta-tools let you discover anything beyond the primary set.
- **Use client tools** — when a foot client is connected, tools
  prefixed `client_*` act on the user's machine (filesystem, shell).
- **Spawn sub-processes** — `process_create` + `process_steer` for
  delegating work in parallel. See `processes` doc.
- **Search project knowledge** — RAG tools (`rag_*`) over per-project
  indexes. See `tools` doc.
- **Take notes** — `scratchpad_*` named slots that persist across
  turns within the process.

## What you can't do

- Touch a different tenant's data.
- Bypass the project's pod-affinity (workspace/exec are pod-local).
- Persist memory beyond what the engine commits — chat-log is the
  authoritative record, scratchpad and memory entries are explicit
  artefacts.

## When unsure

Run `docs_list` to see what's bundled. `docs_read tools` for the tool
catalogue, `docs_read processes` for sub-process patterns.
