---
name: troubleshooting
summary: Bistromath error messages and what each one actually means — parse failures, handlers that do nothing, empty widgets, refused saves.
triggers: an app shows an error you do not recognise, a button does nothing, a widget stays empty, a save was refused, or the app does not pick up an edit
---

# When something is wrong

The messages are written to name the cause. This is the list of the ones whose
cause is not obvious from the wording.

## The view will not parse

| Message | What happened |
|---|---|
| `is not valid YAML` | a syntax error — the message carries the line and column |
| `unknown widget \`x\`` | a typo in `type:` |
| `is part of the schema but is not rendered yet` | a reserved widget (`chart`) — not a typo |
| `` `visibleIf` is not a thing `` | a condition is a state key: `show: <key>` |
| `` `source` no longer exists `` | use `from:`, and have the program fill the key |
| `a \`table\` needs \`from\`` | name the state key holding the rows |
| `a \`dialog\` needs \`show\`` | the key that opens and closes it |
| `` a `select` needs `options` `` | list the choices |
| `` `options` belongs to a `select` `` | you put them on another widget |
| `needs a \`value\`` | an option mapping without one |
| `field \`x\` carries \`showIf\`` | that is the setting-form condition, not read here |

A view is only re-read when the app reloads. If you edited it in the tab next
door, that already happened — the app watches its own folder. If you **added** a
new view document, press **Rebuild**: a view nothing has scanned yet is not
known to be a view.

## A button does nothing

| Message | What happened |
|---|---|
| `no function named X` | the handler names a function no loaded file defines |
| `X is not defined` | a library that was not loaded — check the `Loads` panel |
| `was not found. Expected a document at` | a `@require` with no library behind it |
| `is required in 2 versions` | two callers disagree; the highest loaded |
| `This app has no running program` | the program failed to load — look above for why |
| `await is only valid in async functions` | the handler reads or writes: make it `async` |
| `The program stopped responding` | an endless loop; the frame was stopped |

The last one only fires on **silence**. Waiting for a `vance.*` call does not
count, so a slow document read is never mistaken for a hang.

A handler that is never reached at all: check the separator. It is `:` and not
`#` — `main.js#save` loses the function name, because `#` is a URI fragment.

## A widget stays empty

Almost always the same cause: **nothing put anything in that state key.** A
widget shows state; the program writes it. Check that

- the key in `from:` and the key in `vance.state.set(...)` are spelled the same,
- the function that fills it actually ran — `onAppInit()` runs once at open,
- and it did not throw. An error in `onAppInit` shows above the page.

A widget that vanished instead of being empty has a `show:` key that is unset or
false. **An unset key counts as hidden.**

## The save was refused

```
'invoices/x.yaml' changed since it was read.
```

Somebody else wrote that document between your read and your write, and
**nothing was stored**. Read it again and decide, or pass `{ force: true }` if
your program is the authority on the content. See `manual_read('data')`.

## The document is not where you meant

```
no document at 'apps/mine/_ext/library/x.pdf'
```

A path without a leading slash resolves **inside the app folder**. The project
root needs one: `/_ext/library/x.pdf`. The runtime adds this hint whenever the
path starts with a reserved namespace, because that is where it bites.

The other direction: paths from `vance.documents.list` come back **with** a
leading slash already, so `read(entry.path)` is right and
`read('invoices/' + entry.path)` is not.

## The app does not look like the view I wrote

Open the view document itself — it renders a **preview**, and anything the
renderer gets wrong shows up there without a program in the way. What is empty
there is empty on purpose: no program runs in a preview.
