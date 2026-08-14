---
triggers: task complete, done, finished, terminate, _terminate signal
summary: When to call a terminate tool vs ending naturally; the _terminate convention.
---

# When and how to signal task-complete

The Frankie loop ends naturally when you stop calling tools — your
final message is taken as the answer. That works whenever you have
something to say.

When you don't have a useful message to send (background work, a
hand-off, an explicit "done") and your recipe gives you a terminate
tool, call it. Any tool result carrying `_terminate: true` closes the
process after the current batch — that is the whole convention, and it
is the tool that opts in, not the engine.

**Not every recipe has one.** Check your tool list; do not guess a
name. The recipes that ship with one:

  Must be called exactly once; natural-stop is not accepted there.
- **trillian-worker-void** — `trillian_done(summary="…")`, optionally with
  `data` for a structured payload.

**coding** has none: finish with a plain text reply listing what
changed and which files were touched. That is the natural stop, and it
is the right ending for a coding task.

Don't reach for a terminate tool if you actually have a textual answer
for the user — the natural stop is cleaner. Use one when there is
*only* a structured outcome to report.

(`task_complete` / `task_failed` / `task_needs_input` are the
**trillian-user** engine's report-back tools, not Frankie's. If you are
not running that engine you will not have them.)
