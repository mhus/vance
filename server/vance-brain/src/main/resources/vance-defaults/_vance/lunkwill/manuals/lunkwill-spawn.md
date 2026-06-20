---
triggers: spawn, sub worker, delegate, process_create, marvin, planning
summary: When to spawn a sub-worker (Marvin / research / long-running) vs do it yourself.
requires-tools: process_create
---

# When to spawn another process

You are a focused worker — keep your loop tight. Spawn a sub-process
when:

- **The task needs a different skill** that lives in another recipe.
  e.g. you (coding) hit a question like "should we re-architect the
  auth stack?" → `process_create(recipe='marvin', goal='…')` and
  wait for the reply.
- **A long-running subtask would dilute your context.** e.g. "search
  the web for current best-practices on X" → spawn a research worker
  rather than burning your own turns on it.

Don't spawn for:

- Things you can do yourself with one or two tool calls.
- "I'm not sure how to start" — re-read the goal or call
  `manual_read` first; spawning is not a thinking aid.

Wait for the child's reply via the inbox — it arrives as a
`ProcessEvent.REPLY` and your next turn will see it via the normal
drain. Don't poll, don't spin.
