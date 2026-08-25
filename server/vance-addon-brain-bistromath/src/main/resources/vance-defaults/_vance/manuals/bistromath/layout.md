---
name: layout
summary: Bistromath widgets for arranging things — row, column, card, tabs, dialog.
triggers: your app is one long column, you want two things side by side, tabs, a titled box or a confirmation dialog
---

# Arranging

The widget list and the rules that apply to all of them — `from:`, `show:`,
handlers — are in `manual_read('views')`. The other two widget manuals are
`manual_read('widgets')` and `manual_read('content')`.

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

## `card`
```yaml
- type: card
  title: Zusammenfassung
  children:
    - type: row
      children:
        - { type: badge, text: bezahlt, variant: success }
        - { type: badge, from: offeneAnzahl }
    - { type: alert, from: fehler, variant: error, show: hatFehler }
```

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
