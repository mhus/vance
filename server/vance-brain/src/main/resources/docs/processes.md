# Sub-processes and orchestration

A think-process is a running engine instance with its own chat log,
scratchpad, and memory. Multiple processes can live in the same
session — typically one main one talking to the user plus zero or
more sub-processes doing focused background work.

## Spawning

```text
process_create name=researcher engine=ford goal="Summarise design docs"
```

The new process starts in `READY` and immediately writes its greeting
into its own chat log. It's now visible to `process_list`.

## Talking to a sub-process

```text
process_steer name=researcher content="What's the project's licence?"
```

Returns the new chat messages produced by the target during that turn
(typically one `ASSISTANT` reply). Synchronous: the call blocks until
the target's turn is done. The target runs on its own scheduler lane
so different sub-processes can work in parallel.

**Self-steer is rejected.** You cannot `process_steer` your own
process — that would deadlock. Reply to the user normally instead.

## Patterns

### Delegate-and-merge

When the user asks for something that needs different expertises or
parallel work:

1. `process_create` two sub-processes with distinct goals.
2. `process_steer` each in turn (or in parallel — they're separate
   lanes).
3. Read their outputs, synthesise, reply to the user.

### Persistent specialist

A long-lived sub-process that the user (or you) keeps consulting:

1. `process_create name=lawyer engine=ford goal="Read all licence files"`.
2. Steer it once with the bulk reading task.
3. From now on, every legal question goes there via `process_steer`.

### Background research

Spawn a process to do something slow while you continue the user
conversation:

1. `process_create name=indexer engine=ford goal="Build a RAG over /docs"`.
2. Steer it once: `"Walk /docs, ingest each into rag_create+add_workspace_file."`.
3. Carry on talking to the user. Later, query the resulting RAG.

## Lifecycle

- `READY` — engine started, idle.
- `RUNNING` — currently processing a turn.
- `SUSPENDED` / `STOPPED` / `DONE` — terminal-ish states (engines decide).

The chat log persists. You can re-attach to a sub-process across
sessions of the same project (project-scoped pod-affinity carries
the persistence with it).
