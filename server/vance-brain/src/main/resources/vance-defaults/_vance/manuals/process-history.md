---
triggers: worker transcript, worker history, reasoning trail, full worker output, what did worker do, why did worker say, sources from worker, process_history_text, worker details, was hat worker gemacht, welche quellen, transcript ziehen, recherche-trail, worker-history
summary: process_history_text — pull the full chat transcript of another think-process as one Markdown block, for when the worker's <process-event> summary leaves out the detail you need.
---
# `process_history_text` — Worker-Transcript on demand

Every worker terminates with a condensed final reply that arrives
at the caller as `<process-event>` content. That summary is by
design *kompakt* — it answers the question, but it doesn't carry
the reasoning trail (sources consulted, tool-call results,
intermediate decisions). When you need that detail, pull the
worker's full transcript with **`process_history_text`**.

## When to use

- **User asks for sources / reasoning** behind a worker's answer:
  *„welche Quellen?", „warum hat er das gesagt?", „wie ist er
  drauf gekommen?"*. The summary doesn't have it; the transcript
  does.
- **Re-Aufgreifen früherer Recherche-Themen** — before delegating
  the same topic again, check what the previous worker found.
  Avoids re-doing work and re-routing context-less workers.
- **Sibling-Worker-Briefing** — in a fresh `DELEGATE` prompt you
  can tell the new worker *„lies erst
  `process_history_text(name=<previous-worker>)`"* so it inherits
  the trail before starting.
- **Detail-Fragen lange nach Worker-Ende** — terminated workers'
  transcripts are still queryable; the data doesn't disappear when
  the process closes.

**Don't use** for trivial recall — your own chat-history already
contains every RELAY'd worker reply verbatim, with a
`**[Worker <name> → <status>]**` header. Scrolling back is
cheaper than a tool call. Use `process_history_text` only when
the data you need was NOT in the original RELAY (i.e., it lives
in the worker's tool-call results, not in its final message).

## Signature

```text
process_history_text(
  name?: string                     # process name within current session
  id?: string                       # OR Mongo id of the process
  roles?: ["USER","ASSISTANT","SYSTEM"]   # default: USER + ASSISTANT
  since?: ISO-8601 timestamp        # only messages at-or-after this point
  includeArchived?: bool = false    # also surface ARCHIVED_CHAT memories
  maxChars?: int = 30000            # truncate oldest-first beyond budget
)
```

Either `name` or `id` is required. Returns:

```text
{ "processName": "delegated-9ced90",
  "processId":   "6a2ec…",
  "engine":      "ford",
  "status":      "BLOCKED",
  "messageCount": 17,
  "transcript":  "=== delegated-9ced90 (ford) · 2026-06-14 …\n\n[15:18:31] USER: …\n  ↳ tags: TOOL_CALL:research_search\n…" }
```

The `transcript` field is one rendered Markdown block — not a
list of objects. Read it like any other context section. The
header line carries process name, engine, time range, status,
and message count.

## Finding the worker name

You almost never need a separate discovery step:

1. **RELAY-Header in your own chat-history** — every reply you
   relayed starts with `**[Worker <name> → <status>]**`. The name
   is right there as plain text.
2. **`<active_workers>` / `<delegated_workers>` block** in your
   system prompt — lists currently-live workers.
3. **Fallback only:** `process_list(includeTerminated=true)` —
   authoritative list of all workers in the session including
   CLOSED ones. Use when the chat-history was compacted past the
   original RELAY, or when you used ANSWER instead of RELAY and
   no header was emitted.

## Output shape: tool calls and results

Tool-call markers in the transcript appear as tag annotations
under the relevant ASSISTANT message:

```text
[15:25:41] ASSISTANT:
  → research_search({"query":"\"Opus 4.8\" KI-Modell"})
  ↳ tags: TOOL_CALL:research_search, RESOURCE:URL:…
```

That gives you the search query Ford used AND a marker for
which URLs were fetched — enough to reconstruct the trail.

## Filtering tips

- **`roles=["ASSISTANT"]`** — only what the worker said, no user-
  input echoes. Cheap when you just want the answers.
- **`since=…`** — when a worker had a long lifetime and only the
  most recent activity matters (e.g., after the worker was
  process_steer'd with a new question).
- **`includeArchived=true`** — only when you suspect material has
  been compacted away. The archived summary may be lossy.

## Anti-Patterns

- ❌ Pulling the transcript when the answer is in your own
  chat-history — you're just adding context-bloat for no gain.
- ❌ Pulling 30k chars when you only need the last assistant
  turn — set `roles=["ASSISTANT"]` and a tight `maxChars`.
- ❌ Using `process_history_text` to drive **fresh** work — if
  the new task needs the prior findings, write a DELEGATE prompt
  that points the new worker at the transcript. Don't paraphrase
  the trail into prose yourself.
