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
| `runner_root_list`, `runner_root_create`, `runner_root_delete` | RootDirs of this project on the runner (no generic twin): list them, create a scratch folder, a git clone, a Python venv or a Node project, delete one |
| `exec_run`, `exec_status`, `exec_tail`, `exec_kill` | shell commands in the RootDir; `exec_run` waits up to `waitMs` (at most 90 s), longer jobs keep running — poll `exec_status` / `exec_tail` |

Job ids belong to the project: another project's runner jobs are invisible to you.

**A job that outlasts its `waitMs` tells you when it ends.** You get the same `EXEC_FINISHED` event (or
`EXEC_TIMEOUT` when it hit its `deadlineSeconds` and was killed) as for a job on the server — with the
exit code and the end of stdout/stderr — so you do not have to poll. Such a job also shows up in
`exec_list`, and `exec_status` / `exec_tail` / `exec_kill` find it by id whatever the work target is.
If the runner becomes unreachable for good, you get the event with status `ORPHANED`. A job you kill
yourself sends no event. Events are tracked in the server's memory: after a server restart a running
job keeps running on the runner, ask `runner_exec_status` for it.

## RootDir types

`runner_root_create` makes: `temp` / `ephemeral` (scratch), `git` (a clone — `repoUrl`, optional
`branch`, `commit`, `depth`, and `credentialAlias` for a private repository: the server resolves the
alias, the runner only gets the token for that clone), `python` (a venv at `.venv`, optionally cloned,
optional `packages`) and `node` (a `package.json` project, optional `packages`).

There are no `python_run` / `execute_python` / `node_*` tools on the runner: run your code with
`exec_run`, e.g. `.venv/bin/python script.py`, and install more with
`.venv/bin/python -m pip install …` or `npm install …` in a RootDir of that type.

## Audio and video

When the server itself has no ffmpeg, a runner that has it brings the five audio tools (`audio_info`,
`audio_trim`, `audio_mix`, `audio_concat`, `audio_convert`): they appear in your tool list for that
project, work on documents as always, and the editing happens on the runner. Read `manual_read('audio-editing')`.
They are simply absent for a project whose runner cannot do audio.

`video_transcript` also uses the runner when the server has no yt-dlp/Whisper: download and
transcription run there in one step and only the transcript comes back. There is no progress report for
that — it can take minutes for a long video.

## What it does not have (yet)

- RootDirs live on the runner's volume, not in the server's workspace snapshots: nothing of them is
  suspended with your session. A git RootDir keeps what you commit and push.

## Errors you may see

| Message | Meaning |
|---|---|
| `No runner is registered …` | this project has no runner; use another target |
| `… is not reachable …` | the runner is down or the address is wrong — tell the user, do not retry in a loop |
| `The registration of runner … cannot be used …` | the operator must register it again; not something you can fix |
| `Unknown RootDir` | the RootDir name does not exist on the runner; `file_list` without `dirName` uses your own scratch RootDir |

Never try to reach a runner by URL yourself: its address is operator data you
cannot see, and the call needs a token the server mints per request.
