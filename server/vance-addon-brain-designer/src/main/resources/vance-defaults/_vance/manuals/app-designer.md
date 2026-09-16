---
title: Designer App
description: Create and edit web page designs — folders of plain HTML/CSS/JS files previewed live in a sandboxed iframe.
---

# Designer App

A **designer app** (`app: designer`) is a collection of web page designs.
Each design is a plain folder of web files in the project — no build step,
no framework, just documents. The app renders each design in a sandboxed
iframe, at selectable viewport widths (phone/tablet/desktop) and color
schemes, so the author sees what a browser sees.

## Create the app

```
designer_app_create(folder="designs", title="Website Redesign")
```

This writes the `_app.yaml` manifest, the `_index.md` catalogue — and, when
the folder has no designs yet, a **starter design `hello/`** (a small page
with a relative stylesheet, so the app opens on something visibly
running). Embed the returned `markdownLink` so the user can open the app
with one click.

## File layout

```
designs/                     ← the app folder
  _app.yaml                  ← manifest (written by the create tool)
  _index.md                  ← generated catalogue (app_rebuild refreshes it)
  landing/                   ← one design = one subfolder
    index.html               ← entry file (required — without it the folder
                               is not a design)
    style.css                ← referenced relatively from index.html
    script.js
    assets/logo.png          ← images are ordinary documents; the preview
                               serves any file under the design folder
    design.yaml              ← optional: title, description
  dashboard/
    index.html
    ...
```

## Creating a design (the authoring workflow)

Write real files with `doc_write` — a design that should show a landing
page gets a real landing page, not a stub:

```
doc_write(path="designs/landing/index.html", content="<h1>…</h1> …")
doc_write(path="designs/landing/style.css", content="h1 { … }")
doc_write(path="designs/landing/design.yaml", content="title: Landing\ndescription: Q3 campaign page\n")
```

Rules that matter:

- **Entry contract**: the preview loads `<design>/` → `index.html` —
  exact name, lowercase. A folder with files but a different entry name
  does not show up in the app at all.
- **Reference siblings relatively** (`href="style.css"`,
  `src="assets/logo.png"`). The preview resolves them against the design
  folder.
- **A design name is one folder segment** — letters, digits, dashes work;
  names starting with `_` are reserved.
- **`design.yaml`** (optional) carries `title` and `description` for the
  catalogue card. Without it the folder name shows.
- The user can also create, delete, rename and reorder designs in the app
  UI — your `doc_write` files and their edits meet in the same folder.

## Design skills

Skills tagged **`design`** are design blueprints: activating one puts its
authoring workflow and style assets into your turn. Before designing from
scratch, check whether a design skill fits the brief — activate it yourself
with `skill_activate(name=…)` and let it drive the structure. (The user can
also activate one with `/skill <name>`; either path, the effect is the
same from the next turn on.) The addon bundles one **example**, `design-blueprint` — visible in
`/skill list` but without triggers, so it only runs when called
explicitly (`/skill design-blueprint`). Copy and adapt it for a house
style; installed kits may carry more.

**The `style.css` convention.** A design skill carries its house stylesheet
as a `style.css` sibling of its `SKILL.md` — the same file name a design
folder uses. It is the skill's *real* stylesheet (also declared as its
reference doc, so `manual_read` can load it), never a preview-only copy:
the app's "Design skills" dialogue renders it live, around a fixed demo
body, as a small style preview next to each skill. Keep it a complete,
responsive stylesheet — it is both the preview source and the starting
point the skill tells you to adapt.

## Responsive & dark mode

The preview toolbar can render a design at phone width (390px), tablet
width (820px) and force light/dark. So:

- Write **responsive** CSS (flex/grid, relative units) — the design is
  checked at 390px as often as at full width.
- Support dark mode with the standard media query — it reacts to the
  preview's day/night toggle:

```css
:root { color-scheme: light dark; }
@media (prefers-color-scheme: dark) {
  body { background: #111; color: #eee; }
}
```

## Validating

```
designer_validate(folder="designs")
```

Read-only, returns `{ ok, errors, warnings, findings[] }`. It reports
exactly the states the app silently drops: folders with files but no
`index.html`, broken `design.yaml` files, ghost entries in the manifest's
`designer.order`, and a missing `_app.yaml`. **Run it after creating or
restructuring designs, before telling the user it's done** — an
entry-file typo is invisible in the app and this is the only thing that
names it.

## Deleting a design

```
designer_design_delete(folder="designs", name="landing")
```

Moves **every** document of the design to the trash (recoverable). Use it
when the user asks to remove or replace a design — do not trash the files
one by one.

## Order

The design order in the app's catalogue is manual (drag-and-drop) and
lives in the manifest: `config.designer.order`, a list of design names.
The scan shows listed designs first in that order, everything else
alphabetically. When you edit `_app.yaml` by hand, keep the list to
existing design names — unknown entries are ignored (and reported by
`designer_validate`).

## Self-contained designs

The preview runs in an opaque-origin sandbox: **no cookies, no Vance API,
no access to the app around it**. Do not try to call Vance REST from
design code — design files are static content. External network requests
(CDN fonts, external images) are technically possible but make designs
fragile; prefer local assets.

## What the designer app is not

- Not a website host: the preview route is authenticated, read-only, and
  scoped to the app folder. Use export or an external host to publish.
- Not the place for app *logic*: interactive documents with data access
  are Bistromath apps (`app: custom`), not designs.
