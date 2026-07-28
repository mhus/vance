---
triggers: research_document, research into a document, save the research, write up the research, recherche als dokument, research report, "research and save", "put the research in a doc", "make a document from the research", background research, "research this and document it"
summary: When a research result should become a saved document (not just corpus in the chat), use research_document — it researches, writes a Markdown document, attaches sources as notes, and hands back a pointer + summary instead of the full body.
---
# Research → document

Two tools sit on the same research pipeline. Pick by **where the result
should live**.

| You want… | Tool | You get back |
|---|---|---|
| The raw ranked corpus to reason over **right now**, in this turn | `research_investigate` | the sources, in your context |
| The research **saved as a document** you (or another process) work on later | `research_document` | a pointer: path + summary + tags |

## When to use `research_document`

- The user asks to "research X and write it up", "make a document about X",
  "look into X in the background" — anything where the *deliverable is a
  document*, not a chat answer.
- The material will be large, reused, or handed to sub-work. Keeping a big
  corpus in the context window is wasteful; a document + summary is not.

## What it does in one call

1. Runs the curated research pass (same as `research_investigate`).
2. Synthesizes a Markdown document that answers the question, citing sources
   inline as `[n]`.
3. Saves it (default path `research/<slug>.md`, or a `path` you pass).
4. Stores a short **summary** on the document.
5. Attaches every source as a sticky-**note** citation (title + URL + why it
   was relevant).

## Working with the result afterwards

The tool returns a **pointer**, not the body — on purpose. To act on the
document without pulling the whole thing into context:

- The `summary` in the result is your cheap stand-in — often enough on its own.
- Need detail? `doc_read` the returned `path` with a line range, or grep it —
  don't read the whole body unless you must.
- The sources are `doc_note_list` on that document; they are citations, not
  part of the body.

## Notes

- It **creates a document**, so you need write access to the project. It
  writes under your authority, into the active project (or the `projectId`
  you pass).
- If the research finds no usable sources it fails with a clear message —
  narrow the question or check `research_providers`; it will not write an
  empty document.
