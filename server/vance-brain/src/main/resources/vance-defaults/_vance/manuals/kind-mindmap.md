---
triggers: mindmap, Mindmap, mind map, radial, brainstorm, Brainstorming, idea map, Ideenkarte, concept map, outline (radial), markmap, big picture, structure visually
summary: Render a bullet hierarchy as a radial mindmap (markmap), either inline in chat or as a stored document.
---
# Document kind — `mindmap`

Bullet hierarchy rendered as a radial mindmap (markmap). Edit happens
in the sibling Tree view; the chat fence is the *show-it-now* form.

## Two storage forms — pick by intent

The **hierarchy is identical** in both forms. The difference is the
outer wrapper — that decides whether Vance renders the mindmap
inline in the chat or as a clickable document tab.

Decide first:

| Did the user ask for a saved file / document? | Use form |
|---|---|
| YES — "create a mindmap document", "speicher die Mindmap", "save the brainstorm" | **Stored** (below) |
| NO — "show me a mindmap", "mach mir eine Mindmap zu X", "structure this as a radial map" | **Inline** (further below) |

### Inline in chat — fence-wrapped bullets, no tool call

When the user wants to *see* the mindmap right now in the assistant's
reply (no save, no `doc_create_kind`), **emit a single
```` ```mindmap ```` fence in the chat message** — bullet form, two-
space indent per level. The first level becomes the root node;
multiple top-level bullets render as a forest (parallel trees).

````
```mindmap
- Vance
  - Brain
    - Engines
  - Foot
  - Face
```
````

The reply must CONTAIN this fence verbatim — narrating "Hier ist die
Mindmap…" without the actual fenced block leaves the user with no
render.

### Stored document — raw markdown OR YAML/JSON, NO fence

When the user wants to *save* the mindmap to a file via
`doc_create_kind(kind="mindmap", path="<…>", body=<raw>)`, the body
accepts three on-disk formats. **None of them wraps the body in a
```` ```mindmap ```` fence** — that's the inline-chat shape only.

**Markdown form** (path `<…>.md`) — nested bullets, two-space
indent per level. Most readable for humans, canonical for
markmap.

```markdown
- Vance
  - Brain
    - Engines
  - Foot
  - Face
```

**YAML form** (path `<…>.yaml`) — structured `items[]` hierarchy:

```yaml
$meta:
  kind: mindmap
items:
  - text: Vance
    children:
      - text: Brain
        children:
          - text: Engines
      - text: Foot
      - text: Face
```

**JSON form** (path `<…>.json`) — same `items[]` shape as YAML,
just `{}`/`[]` syntax. Pick markdown for human-readable mindmaps,
YAML/JSON when the model needs to attach per-node metadata
(`color`, `icon`, `link`, `tags`).

## Shared schema (YAML/JSON form)

Item fields:

| Field | Type | Required | Note |
|---|---|---|---|
| `text` | string | yes | Node topic |
| `children` | `Item[]` | no | Recursive — leaf if absent/empty |
| `color` | string (hex) | no | Line + text colour, inherited by children |
| `background` | string (hex) | no | Bubble background |
| `icon` | string | no | Single glyph (emoji or short text) |
| `link` | string (URL) | no | Click target |
| `tags` | string[] | no | Tag chips on the node |

In markdown form, only the `text` and `children` parts are
expressible (one bullet per node). For metadata-rich mindmaps use
the YAML/JSON form.

## When to use this

User wants a *brainstorm-style* hierarchy visualised — "mach mir eine
Mindmap zu X", "structure this as a radial map". Triggers:
brainstorming, outlining, "give me the big picture".

## mindmap vs. tree vs. graph vs. diagram

- **mindmap** — radial visual, anschau-fokussiert. This kind.
  Strict tree (one parent per node).
- **tree** — same hierarchy, outliner UX (still indented bullets,
  no radial layout). Use ` ```tree` when the user wants a structured
  outline rather than a picture.
- **graph** (`kind: graph`) — m:n relationships, cross-links between
  nodes (a node can have multiple parents or feed back to ancestors).
  Use ` ```graph` when the structure is a network, not a tree.
- **diagram** (Mermaid) — supports `mindmap` as a diagram type, but
  prefer this `kind: mindmap` for actual mindmap documents; reserve
  Mermaid for flowcharts/sequence/ER/state/gantt/etc.

## Anti-patterns

- **Wrapping the stored body in a ```` ```mindmap ```` fence.**
  That is the inline-chat form. When you save via `doc_create_kind`,
  the body is raw markdown bullets or a raw `items[]` YAML/JSON
  structure — never fence-wrapped. Symptom: user gets a saved doc
  that opens as plain text instead of a radial mindmap.
- **Mermaid-mindmap syntax.** `root((X))`, `root[X]`, `root(X)`, or
  plain indent without bullets is **Mermaid mindmap grammar** — not
  this kind. Use bullets with `-`. Wrong:
  ````
  ```mindmap
  root((Vance))
    Brain
    Foot
  ```
  ````
  Right:
  ````
  ```mindmap
  - Vance
    - Brain
    - Foot
  ```
  ````
  The renderer auto-recovers Mermaid-style input as a best-effort
  fallback, but the canonical form is bullets — do not rely on the
  fallback.
- **OPML / Freemind / XMind XML.** `<opml>…<outline …/></opml>`,
  Freemind's `<map><node>` tags — those are different mindmap
  ecosystems. Vance uses either bullet markdown or `items[]`
  JSON/YAML.
- **Tree-drawing characters as the canonical form.** `├──`,
  `└──`, `│` is a visual rendering, not a parse-able hierarchy.
  Even though the structural check accepts it, prefer real bullets
  (`- `) so the editor can round-trip the file.
- **Tab indents.** Use spaces (two per level). Tabs are treated as
  four spaces, which collides with the expected two-per-level depth.
- **Markdown headings inside.** `#`/`##` lines aren't recognised
  by the markmap renderer — the renderer only reads bullets. Use
  nesting for hierarchy.
- **More than ~50 nodes inline.** Becomes unreadable in the chat
  viewport. Save as a Document
  (`doc_create_kind(kind="mindmap", …)`).

## When to graduate from inline to stored

- Mindmap is meant to be edited later (the Tree-tab editor lives on
  the Document, not on the chat fence).
- More than ~30–50 nodes.
- Multiple mindmaps that belong together.

Then call `doc_create_kind(kind="mindmap", path="mindmaps/<name>.md",
body=<raw markdown bullets or raw YAML/JSON items hierarchy>)` and
embed the returned `markdownLink`. Reminder: **raw body, no fence**.
