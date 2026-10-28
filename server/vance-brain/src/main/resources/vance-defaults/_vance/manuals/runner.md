---
triggers: runner, runner_file, runner_exec, work_target RUNNER, isolated workspace, tenant runner, where do commands run, no workspace
summary: The project's runner — a separate isolated process with its own RootDirs. What file_* / exec_* can do there, how to select it, and what it does not have.
---
# The runner

A **runner** is a separate process the operator registered for this tenant or
project. Commands and file operations run **there**, isolated from the Vance
server and from other tenants. It has its own RootDirs and its own disk.

It is **not** the server workspace (`WORK`) and **not** the user's machine
(`CLIENT`). Both of those can exist next to it; the work target picks one.

## Selecting it

```
work_target_get                                  # available.runner = true when the project has one
work_target_set(kind="RUNNER")                   # your process's own scratch RootDir on the runner
work_target_set(kind="RUNNER", targetName="x")   # a named RootDir on the runner
```

On a server without a workspace and without a connected client or daemon the
runner already is the work target. Otherwise you choose it explicitly. After
that `file_*` and `exec_*` run on the runner; you do not call the `runner_*`
tools by name unless you need one target regardless of the work target.

## What it offers

| Generic tool | Runner operation |
|---|---|
| `file_read`, `file_write`, `file_edit`, `file_list`, `file_delete` | the same, inside a RootDir on the runner (`contentHash` / `expectedContentHash` work as usual) |
| `file_find`, `file_grep`, `file_count`, `file_head_tail` | the same searches, with the same parameters as on the server workspace |
| `exec_run`, `exec_status`, `exec_tail`, `exec_kill` | shell commands in the RootDir; `exec_run` waits up to `waitMs` (at most 90 s), longer jobs keep running — poll `exec_status` / `exec_tail` |

Job ids belong to the project: another project's runner jobs are invisible to you.

## What it does not have (yet)

- Only plain scratch RootDirs. No `git`, `node` or `python` RootDirs there, so
  `git_checkout`, `node_create` and Python environments do not apply.
- No `ffmpeg`, `whisper` or `yt-dlp` — the audio tools do not use the runner yet.
- No push notification when a job ends: ask `exec_status` while you wait.

## Errors you may see

| Message | Meaning |
|---|---|
| `No runner is registered …` | this project has no runner; use another target |
| `… is not reachable …` | the runner is down or the address is wrong — tell the user, do not retry in a loop |
| `The registration of runner … cannot be used …` | the operator must register it again; not something you can fix |
| `Unknown RootDir` | the RootDir name does not exist on the runner; `file_list` without `dirName` uses your own scratch RootDir |

Never try to reach a runner by URL yourself: its address is operator data you
cannot see, and the call needs a token the server mints per request.
