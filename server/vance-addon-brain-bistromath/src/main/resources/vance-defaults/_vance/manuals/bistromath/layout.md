---
name: layout
summary: Arranging and showing things in a custom app — row, column, tabs, dialog, plus markdown and embed as the two content widgets.
triggers: your app is one long column, you want two things side by side or in tabs, a confirmation dialog, or you want to show markdown or another document inside your app
---

# Arranging and showing

The widget list and the rules that apply to all of them — `from:`, `show:`,
handlers — are in `manual_read('views')`. Data and input widgets are in
`manual_read('widgets')`. This manual is structure and content.

## `row` and `column`

`row` puts children side by side, sharing the width evenly and wrapping when it
runs out. `column` stacks them.

```yaml
- type: row
  children:
    - { type: input, from: a }
    - type: column
      children:
        - { type: input, from: b }
        - { type: input, from: c }
```

`column` earns its place mostly inside a `tabs`, where a pane holds exactly one
widget — without it, a tab with three widgets had to be a `page` inside a
`page`, which works and reads like a mistake.

The two layout widgets, and there will not be more: no `gap`, no widths, no
`flex-direction`. A view says *what*, the renderer decides *how* — a stylesheet
in an app document is the line this schema draws.

`toolbar` looks like a `row` and is a different job: it sizes to its content and
is meant for buttons; a `row` divides the width.

## `markdown`

Rendered by the **same renderer as the rest of Vancetope**, not by a private
copy of `marked`. That means a `vance:` link and a fenced kind work:

```markdown
# Quartalsbericht

Die Zahlen stehen in [der Tabelle](vance:/apps/demo/table.md).

```records
---
kind: records
schema: posten, betrag
---
- Miete, 1200
- Strom, 90
```
```

The link opens the document in the Cortex; the fence renders as a real table
inside your page. `text:` for a literal, `from:` for a state key — so a program
can `read` a markdown document and show it rendered:

```js
await vance.state.set('note', await vance.documents.read('note.md'));
```

**The fence name is the document kind**, not the tool: `diagram` (not
`mermaid`), `records`, `tree`, `list`, `checklist`, `graph`, `chart`, `map`,
`calendar`, `formula`, `youtube`. An unknown name stays a plain code block —
which is correct, and is what `` ```mermaid `` gets you.

On a surface with no Cortex around it — the standalone view preview — this falls
back to plain markdown. Links stay text and fences stay code blocks there.


## `tabs`

One child per tab; the child's `label:`/`title:` captions it. Use `column` for a
pane with more than one widget.

Which tab is open is **client state** — no state key, nothing written, not in the
URL. A reload lands on the first tab. Only the open pane is rendered, so an
`on.change` in a closed tab does not fire.

A tab may carry `show:`. It then disappears from the strip entirely, and the
remaining tabs do **not** shift under the reader — the open tab is tracked
against the visible ones.


## `dialog`

Children shown over the page while its `show:` key is true:

```yaml
  - type: dialog
    title: Delete this invoice?
    show: confirmOpen
    children:
      - { type: text, text: "This cannot be undone." }
      - type: toolbar
        children:
          - { type: button, label: Delete, on: { click: "main.js:doDelete" } }
          - { type: button, label: Cancel, on: { click: "main.js:closeDialog" } }
```

```js
async function askDelete()   { await vance.state.set('confirmOpen', true); }
async function closeDialog() { await vance.state.set('confirmOpen', false); }
```

There is **no** `dialog:` handler and no `vance.ui.closeDialog()`: a dialog is a
widget whose condition happens to be interesting. The ✕ writes the same key back
to false. `show:` is required — without it there would be no way to close it.

## `embed`

Another document, rendered by whatever knows its kind — Markdown, an image, a
mindmap, a canvas:

```yaml
  - type: embed
    text: "reports/q3.md"      # or: from: someStateKey
```

The path is the **same grammar as everywhere else in an app**: relative to the
app folder, a leading `/` for the project root.

This is why there is no `chart` and no `image` widget. A chart document and an
image document already have renderers; duplicating them here would mean this
addon shipping a charting library.

