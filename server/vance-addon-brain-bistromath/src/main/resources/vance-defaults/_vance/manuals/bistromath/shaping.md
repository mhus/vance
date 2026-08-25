---
name: shaping
summary: Changing the rendered view from JavaScript — vance.view.patch, widget ids, hiding fields, relabelling, replacing a select's choices, and where the freedom stops.
triggers: you need a field hidden under a condition, a label that depends on data, select options from a document, or anything the view schema does not have a key for
---

# Shaping the view from the program

A view document is static, and that is fine — but a framework cannot know what
its programmer needs. So instead of a schema key for every thing you might want
to vary (`optionsFrom:`, `labelFrom:`, `hiddenIf:`, …), the program gets the
tree:

```js
await vance.view.patch('edit', {
  label: 'Auftrag bearbeiten',
  fields: {
    kunde:  { label: 'Auftraggeber', help: 'aus dem Kundenstamm' },
    intern: { hide: true },
    betrag: { required: true },
    status: { options: ['offen', { value: 'paid', label: 'Bezahlt' }] },
  },
});

await vance.view.patch('deleteBtn', { hide: true });
await vance.view.reset();          // everything back to what the document says
await vance.view.reset('edit');    // just this one
```

## Give the widget an id

A patch addresses a widget by `id:`, which you add where you want one:

```yaml
- type: form
  id: edit
  from: rec
  fields: [...]
- { type: button, id: deleteBtn, label: Löschen, on: { click: "main.js:del" } }
```

Optional — a widget nobody patches needs no id. **Unique within the view**: two
widgets with one id would make a patch ambiguous, so the parser refuses it.

Fields are addressed by their `name` inside their form's patch, so a form needs
an id and its fields do not.

## What a patch may change

| On a widget | On a field of a `form`/`details` |
|---|---|
| `label`, `text` | `label`, `help` |
| `hide` | `hide` |
| `options` (a `select`) | `options`, `required` |

Patches **accumulate**: a second call adds to the first. `reset` is how you go
back, and a view switch resets by itself.

## Where the freedom stops — and why

A patch changes **appearance and presence, never behaviour**. There is no way to
repoint `on:` at another function, or `from:` at another state key.

That is not caution, it is what keeps the document worth reading: if a program
could rewire which function a button calls, the view document would stop
describing the app, and the only way to know what a button does would be to
read the whole program. The document stays the map; a patch moves furniture.

For behaviour that varies, the handler is already yours — branch inside it.

## Patches do not survive a view switch

A patch names a widget of *this* view; the next view has never heard of it. So
switching views clears them, and the program is told:

```js
async function onViewOpened(handle) {
  if (handle === 'edit') await applyMyShape();
}
```

Presence-checked like every hook: no `onViewOpened`, no call. It runs **after**
the view is up, so a patch inside it lands on something that exists.

## `hide` and `show:` are two gates

Both must pass. A patch cannot *un*-hide something the document gates with
`show:` — otherwise a program could override a condition its author wrote down.
Use `show:` for conditions the document owns, `hide` for what the program
decides.

## When a patch is the wrong tool

- **Values** — that is `vance.state.set`, not a patch. A patch never touches
  what a widget *shows*, only how it looks.
- **A control the vocabulary does not have** — a patch cannot invent a widget.
- **Whole new fields at runtime** — not possible; a patch works on the fields
  the document declares. If you need a form whose field list comes from data,
  say so: that is a different feature and it has not been built.
