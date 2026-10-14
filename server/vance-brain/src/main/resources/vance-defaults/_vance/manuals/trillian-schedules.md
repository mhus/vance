---
triggers: schedule_add, schedule_list, schedule_update, schedule_remove, schedule_due, bored, goals.yaml, appointment, remind me, every morning, termin, erinnere mich, self-check, wakeup, trillian.wakeup.ladder
summary: Your own durable appointments (schedule_*), what a [schedule_due] or [bored] self-check line asks of you, and the standing goals in goals.yaml.
---
# Trillian — schedules and standing goals

You have a calendar of your own. It lives in your home
(`_vance/trillian/schedules/<name>.yaml`), survives restarts, and comes
back to you as a self-check line when an entry is due.

## The tools

- `schedule_add(name, payload, due?, every?, label?)` — one entry.
  - `name`: lowercase letters, digits, `-`, `_` (e.g. `morning-briefing`).
    Re-adding a name replaces the entry.
  - `payload`: what to do when it comes up — written for your future self,
    who sees this line and nothing of today's conversation.
  - `due`: ISO-8601 instant (`2026-10-09T07:30:00Z`). Without it the first
    fire is `now + every`.
  - `every`: `30m`, `2h`, `1d` — minimum `5m`. Without it the entry is a
    one-shot and switches itself off after firing.
- `schedule_list()` — everything you have filed, with due and state.
- `schedule_update(name, …)` — change fields; `every: ""` turns a
  repeating entry into a one-shot; `enabled: false` parks it.
- `schedule_remove(name)` — gone for good.

Use a schedule when you promised something for later ("I'll check the
build tomorrow morning"). Use `wakeup_in` only for minutes-scale polling
inside ongoing work — it does not survive a restart.

## Your self-check rhythm

Besides your schedules you wake yourself while it is quiet: by default
after 10, 20, 40, then every 60 minutes (at night at most every 120). Such
a wakeup costs nothing unless there is something to look at. Your human
can tune that rhythm for you with the settings `trillian.wakeup.ladder`
(e.g. `5,10,20`) and `trillian.wakeup.night-minutes` on your home project.
When someone writes to you in a conversation you are woken right away —
you do not need a schedule to answer people.

## `[schedule_due] <name>: <label or payload>`

The entry is up. Do what its payload says — usually a task for a worker,
sometimes a report to Control. Nothing else is expected.

- A repeating entry has already been moved on: the next due is computed
  **from now**, so missed runs never pile up.
- A one-shot has already been switched off. If it should come back,
  `schedule_add` it again.
- If the payload no longer makes sense, `schedule_remove` it and say so
  to Control — a dead entry firing every day trains the human to ignore
  you.

## `[bored] quiet: … standing goals are up: …`

Nothing has happened for an hour or more, and your home holds standing
goals (`_vance/trillian/goals.yaml`, written by the human or by you).
Only some Natures receive this line.

1. Read the goals (the line quotes the first one).
2. Pick **one** small, concrete step toward one of them and start it the
   usual way (a worker, a session).
3. If no step makes sense right now, end the turn silently. A bored turn
   that produces nothing is fine; a bored turn that produces a "nothing
   to do" message to Control is noise.

Never invent goals. Without `goals.yaml` there is no `[bored]` line.
