---
triggers: workpage block, block type, paragraph, heading, bullet, numbered list, todo, checkbox, quote, code block, image, image width, table, callout, info box, warning box, toggle, accordion, columns, multi-column, link card, table of contents, toc, divider, dataview, embed, embedded document, vance uri, reference document, form, input, reactive form, data entry, text input, saveScript, button, run script button, action button, fence block, vance-form, vance-form fence, form fence, vance-embed, vance-embed fence, embed fence, vance-input, vance-input fence, vance-button, vance-button fence
summary: Copy-paste cheatsheet for every workpage block type. JSON shape for `workpage_create` / `workpage_block_append` tools plus the underlying Markdown the block round-trips to. Use this when you need to pick the right block or look up the exact param keys.
---
# WorkPage Block Cheatsheet

Every block has a `type` field plus block-specific attributes. The tools (`workpage_create`, `workpage_block_append`, `workpage_block_insert`, `workpage_block_update`) accept the same shape; the editor renders the same way regardless of which tool wrote the block.

The Markdown form is what ends up on disk and what the user sees if they look at the raw file. The TS editor parses and re-emits this grammar; round-trip is byte-identical for the structures shown.

---

## paragraph

```json
{ "type": "paragraph", "text": "A normal paragraph. **Bold**, *italic*, `code`, [link](https://example.com) all work." }
```

```markdown
A normal paragraph. **Bold**, *italic*, `code`, [link](https://example.com) all work.
```

Inline marks (bold, italic, strike, inline-code, link) live inside paragraph (and heading / list-item) text. They are normal Markdown — no separate block type.

---

## heading

```json
{ "type": "heading", "level": 1, "text": "Section Title" }
{ "type": "heading", "level": 2, "text": "Sub-Section" }
{ "type": "heading", "level": 3, "text": "Smallest" }
```

```markdown
# Section Title

## Sub-Section

### Smallest
```

Only `level: 1 | 2 | 3` is allowed. Each heading gets an auto-slug `id` so `#hash` URLs work. Headings drive the auto-ToC block.

---

## bullet-list

```json
{ "type": "bullet-list", "items": ["First", "Second", "Third with `inline code`"] }
```

```markdown
- First
- Second
- Third with `inline code`
```

---

## numbered-list

```json
{ "type": "numbered-list", "items": ["Step one", "Step two", "Step three"] }
```

```markdown
1. Step one
2. Step two
3. Step three
```

---

## todo

```json
{
  "type": "todo",
  "items": [
    { "checked": true,  "text": "Done already" },
    { "checked": false, "text": "Still open" },
    { "checked": false, "text": "Also open — *italics* allowed" }
  ]
}
```

```markdown
- [x] Done already
- [ ] Still open
- [ ] Also open — *italics* allowed
```

Use `todo` rather than a bullet-list with prose checkmarks — the UI renders real checkboxes and the user can tick them.

---

## quote

```json
{ "type": "quote", "text": "What we observe is not nature itself,\nbut nature exposed to our method of questioning.\n— Heisenberg" }
```

```markdown
> What we observe is not nature itself,
> but nature exposed to our method of questioning.
> — Heisenberg
```

Newlines in `text` become separate `>` lines.

---

## code

```json
{ "type": "code", "lang": "python", "code": "def hello(name):\n    print(f'Hello, {name}!')\n" }
```

```markdown
```python
def hello(name):
    print(f'Hello, {name}!')
```
```

`lang` is the highlight.js language id (`python`, `java`, `typescript`, `bash`, `yaml`, `json`, `sql`, …). `null` or omitted = plain monospace.

---

## divider

```json
{ "type": "divider" }
```

```markdown
---
```

A horizontal rule. Useful as a section separator.

---

## image

```json
{ "type": "image", "alt": "Cluster topology", "src": "vance:/diagrams/topology.png?kind=image" }
```

```markdown
![Cluster topology](vance:/diagrams/topology.png?kind=image)
```

**With width preset** (`small` = 25%, `medium` = 50%, `large` = 75%, `full` = 100% / default):

```json
{ "type": "image", "alt": "Cluster topology", "src": "vance:/diagrams/topology.png?kind=image", "width": "medium" }
```

The width attribute is encoded as a pipe-suffix in the alt text on disk:

```markdown
![Cluster topology|medium](vance:/diagrams/topology.png?kind=image)
```

**Source URI conventions (important):**

The `src` SHOULD be a [`vance:` URI](inline-and-embedded-content), not
an absolute HTTP URL. `vance:` URIs are portable across Brain
instances, project renames, and pod migrations; HTTP URLs are not.

- `vance:/<path>?kind=image` — image in the **current project** (no
  authority).
- `vance://<projectId>/<path>?kind=image` — image in a **specific
  project**, same tenant. Use this for the tenant-shared
  `_tenant` project (`vance://_tenant/workbook/images/foo.png?kind=image`).

The editor's image NodeView resolves the URI via
`documents/by-path` and substitutes the real `<img src>` at render
time. For uploads the workbook addon writes to
`<workbook>/assets/<timestamp>-<filename>` and stores the corresponding
`vance:/<workbook>/assets/<filename>?kind=image` URI in the markdown.

Plain HTTP / HTTPS `src` values still render (for legacy markdown), but
new content should always use `vance:` URIs.

---

## table

```json
{
  "type": "table",
  "headers": ["Engine", "Speed", "Use case"],
  "rows": [
    ["arthur",   "fast",   "Chat, reactive"],
    ["lunkwill", "medium", "Focused workers"],
    ["marvin",   "slow",   "Deep think trees"]
  ]
}
```

```markdown
| Engine | Speed | Use case |
| --- | --- | --- |
| arthur | fast | Chat, reactive |
| lunkwill | medium | Focused workers |
| marvin | slow | Deep think trees |
```

Static table — no merged cells, no sort. Use a `kind: data` YAML file if the user needs structured data.

---

## callout

```json
{
  "type": "callout",
  "severity": "info",
  "title": "ADR-007",
  "body": "We pick MongoDB over Postgres because the document model fits the knowledge graph better."
}
```

```markdown
```vance-callout
severity: info
title: ADR-007
body: |
  We pick MongoDB over Postgres because the document model fits the
  knowledge graph better.
```
```

`severity` values: `info` (blue), `warning` (yellow), `success` (green), `danger` (red). Default `info`. `title` is optional.

---

## toggle

```json
{
  "type": "toggle",
  "summary": "Click to expand: internal details",
  "body": "Hidden by default. Useful for deep-dives next to a summary."
}
```

```markdown
```vance-toggle
summary: Click to expand: internal details
body: |
  Hidden by default. Useful for deep-dives next to a summary.
```
```

Collapsible disclosure block. Use this to keep long pages scannable.

---

## link-card

```json
{
  "type": "link-card",
  "href": "https://prosemirror.net/",
  "title": "ProseMirror — A toolkit for building rich-text editors",
  "description": "Used by Tiptap, Notion, Atlassian Editor."
}
```

```markdown
```vance-link
href: https://prosemirror.net/
title: ProseMirror — A toolkit for building rich-text editors
description: Used by Tiptap, Notion, Atlassian Editor.
```
```

Visual link preview card. Plain `[text](url)` Markdown links inside paragraphs are still the right tool for inline references — `link-card` is for standalone "see this" links.

---

## toc

```json
{ "type": "toc" }
```

```markdown
```vance-toc
```
```

Auto-rendered table of contents from all H1/H2/H3 headings in the document. No params — the renderer derives it on display.

---

## columns

```json
{
  "type": "columns",
  "columns": [
    {
      "width": 0.4,
      "blocks": [
        { "type": "heading", "level": 3, "text": "Pros" },
        { "type": "bullet-list", "items": ["Fast", "Familiar", "Open"] }
      ]
    },
    {
      "width": 0.6,
      "blocks": [
        { "type": "heading", "level": 3, "text": "Cons" },
        { "type": "bullet-list", "items": ["Operational overhead", "Schema discipline needed"] }
      ]
    }
  ]
}
```

```markdown
````vance-columns
### Pros

- Fast
- Familiar
- Open

<!--vance:column 0.6-->
### Cons

- Operational overhead
- Schema discipline needed
````
```

The outer fence is **4 backticks** so columns can themselves contain a normal 3-backtick code block. Separator is `<!--vance:column [width]-->`. `width` is a fraction; if omitted the columns share remaining space equally.

Two columns is the common case, three works, four is the edge. More columns than that on a normal-width page tend to look cramped — use a table instead.

---

## embed

```json
{ "type": "embed", "uri": "vance:/notes/architecture-2026-05.workpage.md?kind=workpage" }
```

```markdown
```vance-embed
uri: vance:/notes/architecture-2026-05.workpage.md?kind=workpage
```
```

Renders as a kind-aware preview card with icon + title + path +
refresh-on-hover. Used when you want to **inline-reference** another
Vance document inside this workpage — a study page that references the
exam schedule, a meeting note that references a project plan, an
overview page that lists pinned documents.

URI forms (same convention as image `src`):

- `vance:/<path>?kind=<kind>` — current project
- `vance://<projectId>/<path>?kind=<kind>` — cross-project, same tenant

**Embeddable kinds** are anything *except*:

- `image`, `svg`, `pdf`, `audio`, `video` — use a regular image block
  or link-card for those
- `application` — applications are folder containers, not content;
  link to a *page inside* the application instead

For unknown kinds the card still renders with a generic 📄 icon. The
v1 NodeView shows only the card; full inline rendering of vance-kinds
(mindmap / tree / calendar / …) is v2.

## form

```json
{ "type": "form", "data": "vance:/apps/ws/data/people.records.json?kind=records",
  "saveScript": "vance:by-role.js" }
```

```markdown
```vance-form
data: vance:/apps/ws/data/people.records.json?kind=records
saveScript: vance:by-role.js
form:
  single: false
  fields:
    - name: name
      type: string
      label: Name
      required: true
```
```

An **editable, typed form** over a `kind: records` data document. The
data document holds **only** `schema` + `items`; the **form definition**
(`form:` — fields + single) and the recompute **`saveScript`** live in the
block's **fence**, not in the file. `data` is the `vance:` URI of the data
doc (create it with `doc_create`, `kind: records`, `items: []`). Use this for
"user enters structured data, a script derives something" pages.

**Before you author the fence, call `manual_read('workbook-forms')`** — it has
the full contract. The essentials you'll otherwise get wrong:

- **Field `type` is a closed set:** `string` | `textarea` | `integer` |
  `boolean` | `select` | `multi_select`. There is **no `number`** — use
  `integer`. `select`/`multi_select` carry `choices`.
- **`label` / `help` are i18n maps**, but a bare `label: Name` is tolerated
  (coerced to `{en: …}`).
- **Running a script = fence `saveScript`**, a **`.js`** document (Python is
  not supported), runs server-side **on Save**. The form's Save button *is* the
  trigger. There is **no** `$meta.onSave` in the file. Add fence `session: true`
  only if the script needs a session (LLM / session-bound tools); default is
  sessionless.
- **Create the script with `doc_write`/`doc_create`, NOT `work_file_write`.**
  It's a project document; `work_file_write` targets the Brain WORK sandbox and
  fails on an app path ("Unknown RootDir").
- **For a standalone "click to run" action, use the `button` block** (below) —
  not a hidden hook.

## input

```json
{ "type": "input", "data": "vance:/notes/intro.md?kind=text", "multiline": true,
  "saveScript": "vance:update.js" }
```

```markdown
```vance-input
data: vance:/notes/intro.md?kind=text
multiline: true
saveScript: vance:update.js
```
```

A **single editable text value** bound to a text document (the **whole file
content** is the value — a text file is plain, no header split).
`multiline: true` renders a growing textarea, `false` a single-line input. Like
`form`, the block may carry an optional **`saveScript`** in its fence (`.js`,
runs on Save; add `session: true` for a session) — see
`manual_read('workbook-forms')`. Use `form`
for structured multi-field data, `input` for one free-text value.

## button

```json
{ "type": "button", "buttonType": "script", "title": "Recompute everything",
  "script": "vance:update_all.js" }
```

```markdown
```vance-button
type: script
title: Recompute everything
script: vance:update_all.js
```
```

A **clickable button** that runs a project `.js` script server-side on click
(v1 `type: script` only). `script` is a `vance:` URI — a bare name resolves
relative to the **app folder**, `vance:/abs/path.js` is project-absolute.
`title` is the label. Use this for an explicit "run this now" action; use
`form`/`input` `saveScript` when the script should run on data save. The script
is a project document — create it with `doc_write`, not `work_file_write`.

## dataview

```json
{ "type": "dataview", "source": "kind = task and projectId = current and status != done" }
```

```markdown
```vance-dataview
source: kind = task and projectId = current and status != done
```
```

v1: placeholder — renders the source string. v2 will execute the query and embed the results inline.

---

## Anchor shape for `workpage_block_*` tools

```json
{ "index": 3 }              // 0-based position in the block list
{ "heading": "Decisions" }  // exact-match heading text
```

Headings must be unique; duplicates throw and you disambiguate with `index`. Heading match is case-sensitive and trim-sensitive — use `workpage_query(path, type: 'heading')` first if you're not sure of the exact text.

## Picking the right block

| Want | Use |
|---|---|
| "Note this is important" | `callout` with `severity: warning` |
| "Things to do" | `todo` |
| "Hide details until clicked" | `toggle` |
| "Compare two options side by side" | `columns` (2 cols, ~equal width) |
| "Quick page navigation" | `toc` (with at least 3 headings present) |
| "Visual reference to a URL" | `link-card` (standalone), `[text](url)` (inline) |
| "Show code with highlighting" | `code` with `lang` set |
| "A horizontal break between sections" | `divider` |
| "Picture from the user's upload" | `image`, `src` = `vance:/<workbook>/assets/...?kind=image` |
| "Reference another Vance document inline" | `embed`, `uri` = `vance:/<path>?kind=<kind>` |
| "Show the result of another document (computed file, chart)" | `embed`, `uri` = `vance:/<path>?kind=<kind>` |
| "Let the user fill in structured data (a form)" | `form`, `data` = `vance:/<records-doc>?kind=records` |
| "Let the user edit one free-text value" | `input`, `data` = `vance:/<text-doc>?kind=text` |
| "A button that runs a script / computes on click" | `button`, `type: script`, `script` = `vance:<script>.js`. For recompute on data save, use the form's/input's fence `saveScript` instead. See `manual_read('workbook-forms')`. |
