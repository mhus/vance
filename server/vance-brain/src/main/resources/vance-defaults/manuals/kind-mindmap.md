---
triggers: mindmap, Mindmap, mind map, radial, brainstorm, Brainstorming, idea map, Ideenkarte, concept map, outline (radial), markmap, big picture, structure visually
summary: Render a bullet hierarchy as a radial mindmap (markmap) inline in chat.
---
# Inline kind — `mindmap`

Bullet hierarchy rendered as a radial mindmap (markmap). Edit happens
in the sibling Tree view; the mindmap fence is the *show-it-now*
form.

## Syntax — nested bullets

````
```mindmap
- Vance
  - Brain
    - Engines
  - Foot
  - Face
```
````

Two-space indent per level. The first level becomes the root node.
Multiple top-level bullets render as a forest (parallel trees).

## When to use this

User wants a *brainstorm-style* hierarchy visualised — "mach mir eine
Mindmap zu X", "structure this as a radial map". Triggers:
brainstorming, outlining, "give me the big picture".

## mindmap vs. tree vs. diagram

- **mindmap** — radial visual, anschau-fokussiert. This kind.
- **tree** — same hierarchy, outliner UX (still indented bullets,
  no radial layout). Use ` ```tree` when the user wants a structured
  outline rather than a picture.
- **diagram** (Mermaid) — supports `mindmap` as a diagram type, but
  prefer this `kind: mindmap` for actual mindmap documents; reserve
  Mermaid for flowcharts/sequence/ER/state/gantt/etc.

## Anti-patterns

- **Tab indents.** Use spaces (two per level). Tabs are treated as
  four spaces, which collides with the expected two-per-level depth.
- **Markdown headings inside.** `#`/`##` lines aren't recognised —
  the renderer only reads bullets. Use nesting for hierarchy.
- **More than ~50 nodes inline.** Becomes unreadable in the chat
  viewport. Save as a Document
  (`doc_create_kind(kind="mindmap", …)`).

## When to graduate to a Document

- Mindmap is meant to be edited later (the Tree-tab editor lives on
  the Document, not on the chat fence).
- More than ~30–50 nodes.
- Multiple mindmaps that belong together.

Then `doc_create_kind(kind="mindmap", path="mindmaps/<name>", …)`
and embed the returned `markdownLink`.
