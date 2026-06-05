---
triggers: "Document, document_link, doc_create, vance: URI, markdownLink, save as document, save as file, slides, slide deck, presentation, Präsentation, Foliendeck, PDF, audio, video, generated artifact, project file, attach"
summary: How to reference a project Document (PDF, image, audio, slides, generated artifact) via a vance:-URI Markdown link.
---
# Embedding — Project Documents

How to reference a Document that lives in the project workspace.
The transport is a Markdown link with a `vance:` URI. The Web-UI
renders it as a card or inline preview depending on the kind; the
Foot CLI shows it as a labelled reference with open / download
slash-commands.

## When to use this

You have a **real** Document path — either from a tool you just
called (`document_create`, `doc_create`, `image_generate`,
`document_save`), from a path the user mentioned, or from project
memory. You want to point the user at it.

Symptoms that say "use this":

- A worker produced a long output (>30 lines) and you need to
  surface it without pasting the whole thing.
- A tool returned `{ path: "…", markdownLink: "…" }`.
- The user uploaded a file earlier and now asks about it.
- You created a binary artifact (image, PDF, audio) and want it
  in chat.

If you only have an **external** URL (Pixabay, Wikipedia, an
arbitrary `https://`), this is the wrong manual:

- External images → `manual_read('embed-images')`
- External PDFs you need to find → call `pdf_search(query=...)`.
  Each hit is HEAD-validated (`application/pdf` confirmed), so the
  result can be presented as a plain `[Title](url)` Markdown link.
  The Web-UI renders external PDF links as cards with title +
  hostname automatically; no extra embed work needed.

## How to build the link

**Never hand-construct a `vance:` URI.** The format owns invariants
(authority for cross-project, `?kind=` hint, escape rules) that a
single tool keeps correct.

### Path 1: link emitted by a creation tool — preferred

Tools that produce a Document return `markdownLink` directly in
their response. Copy it into your reply verbatim — no extra call:

- `document_create` → `{ path, markdownLink, … }`
- `document_save` → `{ path, markdownLink, … }`
- `doc_create` → `{ path, markdownLink, … }`
- `image_generate` → `{ path, markdownLink, … }`

Example after `doc_create(kind="mindmap", name="onboarding-plan", content=…)`:

```
I drafted a mindmap of the onboarding plan:
![Onboarding plan](vance:/mindmaps/onboarding-plan?kind=mindmap)
```

The `markdownLink` field already contains that string.

### Slide decks via `doc_create(kind="slides")`

When the user asks for a presentation, slide deck, or "slides about X",
create a `kind: slides` Document. The body is Markdown — slides are
separated by a `---` thematic break on its own line. Front-matter is
optional but useful for theme / aspect / pagination.

```
doc_create(
  kind="slides",
  path="decks/q1-review",
  title="Q1 Review",
  content="---\nkind: slides\nslides:\n  paginate: true\n---\n\n# Q1 Review\n\n2026\n\n---\n\n## Headline numbers\n\n- Revenue +12%\n- Active users +8%\n\n---\n\n## What slipped\n\n- Mobile launch (rescheduled to Q2)\n"
)
```

Then link the result verbatim from the tool's `markdownLink`:

```
Here is the Q1 review deck:
[Q1 Review](vance:/decks/q1-review?kind=slides)
```

Slides are **embedded-only** — never wrap them in a `\`\`\`slides` fence,
that just renders as raw `<pre>`.

### Path 2: link to an existing Document — use `document_link`

For a Document the user mentioned or that came up earlier, call
`document_link`:

```
document_link(path="documents/q1/summary.pdf")
  → { markdownLink: "[Q1 summary](vance:/documents/q1/summary.pdf?kind=pdf)",
      path: "documents/q1/summary.pdf",
      kind: "pdf",
      title: "Q1 Summary 2026" }
```

Parameters worth knowing:

- `path` — Document path or search query. Exact path wins; query
  falls back to title/name search.
- `text` — override the link text (default = document title).
- `mode` — `preview` (inline render) or `reference` (compact
  card). Default derives from kind: images/PDFs → preview,
  audio/text → reference.
- `imageStyle` — force `![…](…)` syntax. Default `true` for
  `kind=image`, `false` otherwise.
- `project` — cross-project lookup (same tenant only).

Errors to expect:

- `DOCUMENT_NOT_FOUND` — query matched nothing.
- `DOCUMENT_AMBIGUOUS` — multiple matches; the error body lists
  top-3 candidates. Pick one with `path=` and call again.
- `CROSS_PROJECT_DENIED` / `CROSS_PROJECT_NOT_IN_TENANT` — fix
  your `project` argument or drop it.

## Reference vs. preview rendering

| Syntax | Render mode | Use for |
|---|---|---|
| `[text](vance:/…)` | reference — compact card / badge | links in prose, PDFs, audio, generic docs |
| `![alt](vance:/…)` | preview — inline render | images, mindmaps you want visible, first-page PDF preview |

The tool picks the right syntax by default. Override only if you
have a specific reason.

## Cross-project links

Same tenant only:

```
[Template](vance://templates-shared/reports/q-template.md?kind=markdown)
```

The authority part (`templates-shared`) is the project's `name`,
not its Mongo id. Cross-tenant links are not supported by the
URI schema — exports are the only path.

## Hard rules

- Never invent a path. The validator rejects `vance:` URIs that
  don't resolve.
- Never embed external HTTPS URLs as `vance:`-URIs. They are
  different schemes; mixing them produces a broken link.
- Don't paste the `markdownLink` field's value through string
  manipulation — use it verbatim.
