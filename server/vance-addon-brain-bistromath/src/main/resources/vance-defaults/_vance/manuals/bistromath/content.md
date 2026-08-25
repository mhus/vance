---
name: content
summary: Bistromath widgets for showing content — markdown the Vance way, source code, badges and alerts, embedded documents.
triggers: you want to show markdown with vance: links or fenced kinds, source code, a status label, a message, or another document
---

# Showing

The widget list and the rules that apply to all of them — `from:`, `show:`,
handlers — are in `manual_read('views')`. The other two widget manuals are
`manual_read('widgets')` and `manual_read('layout')`.

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

### Plain HTML works too

You can write HTML in a `markdown` widget and it renders — this is ordinary
Markdown, so raw HTML passes through. Measured, not assumed: `b i u s small
mark code`, `div` and `span` **with a `style` attribute**, `table thead tbody tr
th td`, `details summary`, `form input button`, `img`, `a`, `br`, `hr`.

```yaml
- type: markdown
  from: bericht
```

```js
await vance.state.set('bericht',
  'Summe: <b>' + fmt.money(total) + '</b>'
  + '<div style="color:#b00">' + warnung + '</div>');
```

It is then **sanitised**, and the same list is what does not arrive:

| Removed | |
|---|---|
| `<script>` | never runs |
| `on*` attributes | `onclick`, `onerror` — stripped from every element |
| `href="javascript:…"` | the link stays, its `href` does not |
| `<iframe>`, `<svg>` | not in the allow-list |

So HTML is for **formatting**, not for behaviour: a button you write as HTML is a
button nothing is wired to. Behaviour is a widget with `on:`
(`manual_read('views')`).

Two consequences worth keeping in mind. Anything you interpolate is rendered as
markup, so a document field containing `<b>` will be **bold rather than
visible** — for text you did not write yourself, prefer a `text` widget. And
`breaks: true` is on: a single newline is a line break, which is usually what an
author means and occasionally a surprise.

On a surface with no Cortex around it — the standalone view preview — this falls
back to plain markdown. Links stay text and fences stay code blocks there.

## `html`

When Markdown **restructures** your markup, use this instead:

```yaml
- type: html
  from: karte
```

Markdown is a lossy channel for HTML, and the loss is structural rather than
about which tags survive. Measured, same input through both widgets:

```
markdown:  <div class="karte"><p>  <b>nach einer Leerzeile</b></p></div>
html:      <div class="karte">\n\n  <b>nach einer Leerzeile</b>\n</div>
```

The `<p>` is not in the source. A blank line inside a block element makes the
Markdown parser treat what follows as a paragraph — right for prose, wrong for a
layout you designed. A line beginning with `-` or `#` goes the same way.

**Same sanitiser, no extra authority.** Both widgets run the same allow-list, so
this is not a way to get a `<script>` onto the page — see the table above. The
difference is fidelity, not permission.

Two things `html` does *not* do, and `markdown` does:

- **`vance:` links do not navigate.** The href survives sanitisation, but the
  click delegation belongs to the Markdown renderer, which creates those links
  itself. Write links in a `markdown` widget.
- **No fenced kinds.** ` ```records ` is not markup; it is Markdown.

So the rule is simple: **prose and links → `markdown`; a layout you control →
`html`.** Both take `text:` for a literal and `from:` for a state key.

Without a host renderer (the standalone view preview) an `html` widget shows its
markup **as text**. There is deliberately no local fallback: without a host
there is nothing to sanitise with, and "insert it anyway" is not the lesser
evil.

## `code`

Read-only source with syntax highlighting:

```yaml
- type: code
  label: main.js
  from: quelle
  language: javascript
```

`language` is a name, not a mime type: `markdown` · `json` · `yaml` ·
`javascript` · `typescript` · `python` · `shell` · `r` · `html` · `css` · `xml`
· `sql` · `java` · `text` (plus the short forms `md`, `js`, `ts`, `py`, `sh`,
`yml`). An unknown one is a **parse error**, not a silent fall-through to plain
text.

Read-only on purpose — `code` is to `markdown` what a listing is to prose. An
editable one would be an input widget and would say so in its name.

## `badge` and `alert`

`variant` is one of `neutral` · `info` · `success` · `warning` · `error`, and it
is a **literal**, not a state key: a variant says what the message *is*, and a
condition on the whole widget is what `show:` is for. Two badges with a `show:`
each cover the "green when paid, red when open" case without a new binding.

A `badge` or `alert` takes `text:` for a literal or `from:` for a state key —
the same pair as `text` and `markdown`. A number in state renders fine.

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
