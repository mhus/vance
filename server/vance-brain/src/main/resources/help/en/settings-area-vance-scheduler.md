# Area: Schedulers

The Ursa schedulers under `_vance/scheduler/` — **one file per
time-driven trigger**. A scheduler fires a recipe, a workflow or a
script at a time you choose.

## What you configure here

- **What happens** — description, trigger target (recipe, workflow
  or script) and optionally the prompt per run. **Exactly one** of
  the three targets — the loader refuses anything else.
- **When it fires** — a cron expression (recurring) or a concrete
  instant (`at`, one-shot). **Exactly one** of the two.
- **Timezone** — IANA zone; empty means UTC.
- **Enabled** — paused schedulers stay registered but do not fire.

## Boundary

This area is the **configuration inventory**. Manual firing and the
event log live in the Insights "Scheduler" tab — both read the same
documents, there is no second definition.

An entry opens the scheduler form inline (the same surface as the
Cortex document tab).
