---
triggers: task complete, done, finished, terminate, _terminate signal
summary: When to call task_complete vs ending naturally; tool _terminate convention.
---

# When and how to signal task-complete

The Lunkwill loop ends naturally when you stop calling tools — your
final message is taken as the answer. That works whenever you have
something to say.

When you don't have a useful message to send (background work, a
hand-off, an explicit "done") use the recipe's task-complete tool
with a short summary. The tool result will carry `_terminate: true`
and the engine closes the process after the current batch.

Per recipe:

- **coding** — `task_complete(summary="…")` after the change set is
  applied and tests pass. Summary lists what changed and which files
  were touched.
- **lunkwill-repair** — `repair_complete(component="…", status="…")`
- **lunkwill-fook-upstream** — `ticket_handed_off(ticketId="…", action="…")`

Don't call task-complete if you actually have a textual answer for
the user — the natural stop is cleaner. Use task-complete when there
is *only* a structured outcome to report.
