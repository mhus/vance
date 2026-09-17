# Scheduler

A **scheduler** is a time-driven trigger: it fires a recipe (or a
workflow, or a script) at a moment you chose — every hour, every
weekday at 9, on the 15th of each month, or exactly once tomorrow
morning. The document you have open *is* the definition; there is no
second copy anywhere.

## Kind and location are separate things

- `kind: vance-scheduler` in the `$meta` header says **what this
  document is**. It holds anywhere in the project — a draft, a copy, a
  variant you are trying out. All of them validate the same way.
- Only a document under `_vance/scheduler/<name>.yaml` is **active**:
  registered with the brain's clock and firing. The file name without
  `.yaml` is the scheduler's name. Everything that addresses a scheduler
  by name — the agent's `scheduler_set` tools, the REST API, the
  Insights → Scheduler tab — finds it only there.

A document elsewhere is a draft. The form still edits it; the banner at
the top reminds you that it does not fire.

## The form

The view edits the definition without touching raw YAML:

- **What happens** — description, trigger target (recipe, workflow, or
  script) and the prompt each run receives.
- **When it fires** — pick a schedule shape:
  - **Hourly** — every N hours, at a fixed minute.
  - **Daily** — one time of day.
  - **Weekly** — weekdays plus a time of day.
  - **Monthly** — day of month plus a time of day.
  - **Once** — a concrete point in time; after firing, the document
    moves to the trash.
  - **Other (cron expression)** — anything else. A cron the form cannot
    express switches here automatically: the raw expression is shown
    exactly as stored, editable as text. The form never silently
    rewrites a schedule it doesn't understand.
- **Identity** — `runAs`, the user whose inbox receives questions and
  errors from the runs.

The grey line under the schedule is live: it shows the cron expression
(or `at:` value) the current form state will save. The timezone is an
IANA zone and must be set for the times to mean what you expect —
without it, the brain assumes UTC.

Everything the form does not show (recipe `params`, `tags`,
`lockMode`, the `$meta` header) is preserved untouched — the form edits
the fields it owns and leaves the rest alone. Use the raw-YAML edit
mode for those.

## Manual runs and history

The Insights → Scheduler tab (and the agent's `scheduler_fire` tool)
trigger a scheduler immediately, cron notwithstanding. Every run leaves
a log document under `_vance/logs/scheduler/<name>/` and an entry in
the activity feed — the run history panel there shows what happened,
when, and why a run failed.
