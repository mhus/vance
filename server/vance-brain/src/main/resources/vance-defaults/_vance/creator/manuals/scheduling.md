---
audience: creator
triggers: scheduler, scheduler_set, scheduler_list, scheduler_get, scheduler_delete, scheduler_fire, scheduler manuell auslösen, scheduler testen, scheduler log, lief der scheduler, cron, at:, erinnere mich, reminder, jeden Montag, morgen früh, recurring task, einmalig, daily briefing, locked scheduler, lockMode, runAs, timezone, Quartz cron, IANA
summary: How to create and maintain schedulers, fire them manually for testing, and read the per-run log documents — Quartz 6-field cron vs. one-shot `at:`, IANA timezones, recipe choice, lockMode, runAs semantics, plus `scheduler_fire` + `_vance/logs/scheduler/<name>/…` for end-to-end verification.
---
# How I create and maintain schedulers

A **scheduler** is a time-driven trigger that spawns a worker process —
recurring via cron or once at a concrete point in time. When the user
says "remind me every Monday at 9 about the sprint reviews" or "start
the morning routine tomorrow at 8", I create a scheduler for exactly
that.

Schedulers live as YAML documents under `_vance/scheduler/<name>.yaml` in
the project. They run as soon as I have written them — the brain
registers them on its own after every write (delta refresh).

## Which tool for what

| Task | Tool |
|---|---|
| See existing schedulers in the project | `scheduler_list` |
| Look up the full YAML of a scheduler | `scheduler_get` |
| Create or change a scheduler | `scheduler_set` |
| Remove | `scheduler_delete` |
| Fire immediately for testing (bypassing cron) | `scheduler_fire` |
| Read the run result (outcome, timeline) | `doc_read` on `_vance/logs/scheduler/<name>/…` |

Before every `scheduler_set` I should briefly call `scheduler_list` — if
the user wants "a reminder for tomorrow morning" and something like it
already exists, I don't create a second one, but update the existing one
or ask.

## Recurring scheduler (`cron:`)

Standard case: every weekday at 8:00 Berlin time:

```
invoke_tool(
  name = "scheduler_set",
  params = {
    "name": "morning-briefing",
    "yaml": """
description: \"Daily morning briefing.\"
cron: \"0 0 8 * * MON-FRI\"
timezone: \"Europe/Berlin\"
recipe: \"default\"
initialMessage: |
  Create the daily briefing for today.
"""
  }
)
```

Important:
- **Cron format is 6-field Quartz**, NOT 5-field Unix:
  `<sec> <min> <hour> <day> <month> <weekday>`. Seconds are mandatory.
- **Timezone** is an IANA zone (`Europe/Berlin`, `America/New_York`, …),
  not an offset. If it is missing, UTC applies — for "local" times from
  the user always set it explicitly.
- **`recipe`** is mandatory — the spawning worker needs an engine.
  Default choice: `default` (ford-based, fast/cheap). If the user
  explicitly wants research: `analyze`. For multi-stage work: `marvin`.

## One-shot scheduler (`at:`)

For "do this exactly once at point X" — no cron, but `at:`:

```
invoke_tool(
  name = "scheduler_set",
  params = {
    "name": "review-deck-tomorrow",
    "yaml": """
description: \"Review-deck preparation before the meeting.\"
at: \"2026-05-14T07:30:00\"
timezone: \"Europe/Berlin\"
recipe: \"default\"
initialMessage: |
  Go through the slides in the workspace and list the open points.
"""
  }
)
```

Date format rules:
- **`2026-05-14T07:30:00`** — local time, resolved against `timezone:`.
  When the user says "tomorrow at 8" and is in Berlin, this is the right
  format.
- **`2026-05-14T07:30:00+02:00`** — with offset, when the user
  explicitly states an offset.
- **`2026-05-14T05:30:00Z`** — UTC, when I want to store it neutralized.

What happens after firing:
- The process is spawned, the YAML is **automatically moved to the
  project trash** (`_bin/`). It no longer appears in `scheduler_list`.
- The run history stays in the event log and is visible via the web UI.
- Restorable through the document layer if the user means "do that
  again" — but usually you'd rather create a fresh scheduler then.

When the user says "remind me" without saying exactly whether it is
one-off or recurring: **ask**. "Should that run only once on Thursday
or every Thursday?" — the two YAMLs are not interchangeable.

## "This evening" / "in 2 hours" — time conversion

`current_time` gives me the current server time. When the user gives
relative statements ("in 2 hours", "7 pm this evening"), I convert that
**beforehand** into absolute ISO-8601 form and write the finished time
into the `at:` field. Never write `at: \"in 2 hours\"` — the server side
only parses ISO dates.

## Adjusting existing schedulers

`scheduler_set` needs the **complete** YAML — no patch operation, but a
full replace. The previous state is automatically archived by the
document layer; so I lose nothing on overwrite. Workflow:

1. `scheduler_get(name)` → current YAML
2. Adjust the spot (e.g. only the cron line)
3. `scheduler_set(name, yaml)` with the changed full text

If I only want to disable: `enabled: false` into the YAML,
`scheduler_set`. Re-enabling is the same in reverse.

## Locked schedulers (`lockMode`)

Some schedulers I must not touch — typically admin entries (backups,
compliance checks). In the `scheduler_list` output they have
`"locked": true` and a `lockMode` field:

- `lockMode: "protected"` → I see it but cannot change or delete it. If
  the user wants changes, they have to do it in the web UI.
- `lockMode: "hidden"` → does not even appear in the list. If I
  accidentally guess a name that is locked, the server rejects the
  mutation.

When `scheduler_set`/`scheduler_delete` answers with "is locked", tell
the user clearly: "this scheduler is admin-protected and cannot be
changed through me." Do not try to circumvent that with a slightly
different name — that is a bypass attempt.

## Which user runs the scheduler?

`runAs:` determines whose inbox gets the scheduler's messages/errors.
Without an explicit `runAs:` it is the `createdBy` of the doc — that is,
the user on whose behalf I am currently working. That is usually
correct.

If the user explicitly means someone else ("set the scheduler up for
road-runner"), I set `runAs: \"road-runner\"`. Prerequisite: that is a
valid user in the current tenant — otherwise inbox items land in the
void.

## Firing a scheduler for testing (`scheduler_fire`)

Before I leave a new scheduler to the cron run, I should test-fire it
once so it is clear: does the recipe take? Does the RunAs user get the
answer? That is exactly what `scheduler_fire` is for:

```
invoke_tool(
  name = "scheduler_fire",
  params = { "name": "morning-briefing" }
)
```

Answer:
```
{ "correlationId": "run_550e8400-…",
  "logPath": "_vance/logs/scheduler/morning-briefing/2026-06-09T08-15-00Z-run_550e8400-….md",
  "note": "Run started. Read '...' via doc_read for status/outcome." }
```

- The run goes through the same code path as a cron tick — overlap
  policy applies, event log, metrics, everything. Difference: the run
  document carries `trigger: manual` instead of `trigger: cron`.
- The `logPath` is readable **immediately**; until the process
  terminates it says `outcome: pending`. Read it again after a few
  seconds.
- With a running cron tick, the server rejects with "skipped overlap" —
  the overlap policy decides as always.

When the user says "test it", "let the scheduler run once" or "I want
to see if it works": **use the fire tool**, do not create a second
temporary scheduler with `at:`.

## When the user asks: "did it run yesterday?" / "why didn't it work?"

Every run — cron-triggered or via `scheduler_fire` — leaves a markdown
document under:

```
_vance/logs/scheduler/<schedulerName>/<isoStamp>-<correlationId>.md
```

with YAML front matter (`outcome`, `trigger`, `firedAt`, `completedAt`,
`durationMs`, `processId`, …) and a timeline section in the body. The
documents are deleted automatically via MongoDB TTL — default 7 days,
configurable per tenant or project via the setting
`scheduler.log.retentionDays` (tri-state):
- **> 0** → retention in days (≤ 365).
- **0** → no expiry, documents are kept indefinitely (e.g. for compliance).
- **< 0** → logging completely off, no documents are written.

This is how I proceed:

1. `doc_list(pathPrefix = "_vance/logs/scheduler/<schedulerName>/")`
   — lists all runs of the scheduler, newest last (the path is
   ISO-sortable).
2. `doc_read(path = "<one of the listed paths>")` — analyze front matter
   and timeline.
3. On `outcome: failed` → the `## Error` section in the body shows the
   error message. On `outcome: skipped_overlap` → another run was still
   active. On `outcome: pending` → the run is still going, or the brain
   crashed after STARTED.

For a quick overview ("when did it last run") `scheduler_list` with its
`lastRun` field is still enough — the detailed forensics happen through
the log documents.

## What I do NOT create

- Schedulers for topics the user only mentioned in passing. Ask first
  whether that should really run automatically.
- Schedulers with `cron: \"* * * * * *\"` (every second) or similarly
  aggressive — that is almost always a misunderstanding. Ask.
- Duplicate schedulers for the same topic. List first, then update or
  ask.
