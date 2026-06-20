---
triggers: work target, work_target, where do files go, client vs work, switch backend, foot vs workspace, dispatch
summary: How file_*/exec_* dispatch via the per-process WorkTarget — when to leave it alone and when to switch.
---

# WorkTarget — where your file_* and exec_* calls land

The generic `file_*` / `exec_*` tools don't know any filesystem
paths or hosts directly. They dispatch to one of two backends
based on the active **WorkTarget** of your process:

| `kind`  | Backend                            | When |
|---------|------------------------------------|------|
| CLIENT  | the user's local host via Foot CLI | a `vance-foot` client is connected and the user wants you to touch their files |
| WORK    | a workspace RootDir on the Brain server | ephemeral scratch — build outputs, generated artefacts, isolated experiments |

## You almost never need to switch

The recipe / profile set the target at spawn time. For `coding`:

- Default-profile (web / API) → WORK with a process-temp RootDir.
- `foot` profile → CLIENT (the user's `vance-foot --workdir` directory).

Once set, the target stays for the whole session. **Just call
`file_read`, `file_edit`, `exec_run`** — the dispatcher picks the
right side. You do not need to inspect or switch the target on
every turn.

## When you DO need to switch

Rare. Real cases:

- The user is on Foot (you're editing their code) but you need a
  throwaway sandbox for a destructive experiment that shouldn't
  touch their files. Switch to WORK temporarily, run the experiment,
  switch back to CLIENT.
- You spawned a sub-step that built artefacts into a workspace
  RootDir, and now you want to read them back without disturbing
  the user's local tree.

For those cases:

```
work_target_set(kind="WORK", dirName="experiment-42")
```

or back:

```
work_target_set(kind="CLIENT")
```

`work_target_set` is not in your primary manifest — call
`find_tools` or `describe_tool` first if you need its exact schema.

## When a call fails because the target is wrong

The dispatcher tells you:

- `WorkTarget is CLIENT but no Foot client is bound to this
  session …` — the user disconnected Foot mid-task. Either ask
  them to reconnect, or `work_target_set(kind="WORK")` to keep
  working on the server side.

## What "CWD" means per target

- **CLIENT**: Foot's `--workdir` is the cwd. Paths you pass to
  `file_*` are resolved against it. Don't pass absolute paths
  outside that directory — Foot rejects them.
- **WORK**: the active RootDir is the cwd. Default = process-temp
  (lazy-created on first call, scrubbed at process close).

## What you should NOT do

- Don't call `client_file_*` / `work_file_*` / `client_exec_*` /
  `work_exec_*` directly. They exist for the dispatcher; calling
  them bypasses the target abstraction and breaks portability
  across profiles. Use `file_*` / `exec_*`.
- Don't ping `work_target_get` every turn just to be safe — the
  target is stable, the manifest signals which side via the
  recipe's promptPrefix, and a needless tool call wastes a
  round-trip.
