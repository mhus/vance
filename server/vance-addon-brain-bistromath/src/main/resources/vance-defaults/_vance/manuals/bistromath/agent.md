---
name: agent
summary: The chat beside a running app — app_describe / app_state_get / app_state_set / app_action / app_reload, and the agent: flag that opens a button.
triggers: you want to fill in or drive an app the reader has open, an app_action came back "not open to agents", the reader asks you to try their app, or you just changed an app's documents and want the reader to see it
---

# Driving an app from the chat

An app opens in a Cortex tab; the chat sits beside it. Five client tools connect
the two:

| | |
|---|---|
| `app_describe` | a **snapshot** of the rendered view — **call this first** |
| `app_state_get` | one key, or all of them: what the widgets show, forms included |
| `app_state_set` | fill a field. Visible at once, commits nothing |
| `app_action` | press a button — only where the app allows it |
| `app_reload` | re-read from the documents; the way back |

They act on the app in the **foreground tab**. No app open is a refusal, not an
empty answer.

## The snapshot is what you read

`app_describe` returns the view as an indented tree, the way a browser
accessibility snapshot does:

```
page (stacked: 4) "Snapshot demo"
  row (side by side: 2)
    input #suche "Suche" ← query = ""
    select #status "Status" ← status = "alle" (3 choices)
  column (stacked: 2)
    text #hinweis "Diese Zeile…" (hidden by the program)
    card (stacked in a box: 1) "Summe"
      text #summe ← summe = "5.290,00 €"
  table #liste ← rows = [3 entries: key, kunde, betrag] columns: key, kunde, betrag
  toolbar (side by side: 2)
    button #neu "Neu" → main.js:neu [agent]
    button #alleLoeschen "Alle löschen" → main.js:wipe [closed]
```

Reading it: `#id` is the handle you pass to `app_action` and the key after `←`
is what you pass to `app_state_set`. `[agent]` means you may press it,
`[closed]` means the app did not offer it. A container says how it arranges its
children, so `row (side by side: 2)` and `column (stacked: 2)` are the
difference between two boxes beside each other and two above each other.

**There is no selector language** — no XPath, no CSS. Read the tree and name an
id. A widget id is unique within a view and the parser enforces that, so the
handle is already in the document.

**Large values are summarised, never printed**: `[3 entries: …]` for a list,
`{a, b}` for an object, a long string cut with its length. Use `app_state_get`
when you need the value itself.

**Structure, not pixels.** The snapshot says what contains what and in which
direction — it does not report widths, wrapping, or whether something scrolled
out of sight. If that matters, ask the reader.

## Read freely, act by declaration

Reading is always allowed: it is what the reader can already see.

**Setting state is allowed too**, and it is how a form gets filled, because form
values *are* state. It changes the screen and nothing else — the app's own
handlers do **not** run, so a person still presses the button.

**Pressing a button needs the app's permission.** In the view document:

```yaml
- type: button
  id: berechnen
  label: Berechnen
  on:
    click: main.js:recalc
  agent: true          # without this, you cannot press it
```

`app_describe` reports `agent: true|false` per action, so you know before you
try. A refusal means **the app did not offer this button** — not that you asked
wrongly, and not that the button is broken. Say so plainly and name the remedy:
somebody has to add `agent: true` to that widget. Do not look for another route
to the same effect.

The reason is not distrust of you. An action runs the app's own code, so it can
write documents — and the app runs in the browser of whoever *opened* it, which
is not always its author. Deny is the only defensible default for that.

## Working with it

```
app_describe                        → the tree: #nameFeld ← name, #gruessen [agent]
app_state_set  key=name value=Ford
app_action     id=gruessen
app_state_get                       → { name: "Ford", ausgabe: "Hallo, Ford!" }
```

Read the state **after** an action rather than assuming what it did — the app's
code decides, and `app_action` returns the fresh state for exactly this reason.

Before an action that clearly writes something, say what you are about to do.
The reader is sitting in front of it.

## `app_reload` is the way back

It discards every state value and widget change and shows what the documents
say. Two uses: after you edited the app's view or program, so the reader sees the
new version; and when the app is in a state you cannot explain. It needs no
permission because it is the exit — it commits nothing and destroys nothing that
was ever written down.

## What these tools are not

They are not browser control. There is no DOM, no coordinates, no clicking
whatever sits at a position. You drive the app through what it **declares** —
state keys and declared actions. If something is not in `app_describe`, there is
no tool for it; edit the app's documents instead
(`manual_read('app-bistromath')`).
