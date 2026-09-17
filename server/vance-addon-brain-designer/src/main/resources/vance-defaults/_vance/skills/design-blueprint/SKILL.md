---
name: design-blueprint
title: Design Blueprint
version: 1.0.0
description: |
  Design skill (label: design) — a landing-page blueprint for the designer
  app: section structure, a ready-made responsive stylesheet (light/dark)
  as a reference file, and authoring rules. Bundled EXAMPLE: no triggers,
  so it never activates on its own — call it explicitly with
  /skill design-blueprint. Copy the folder and adapt it for a house style.
tags: [design, landing-page, css, example]
category: design
enabled: true
action: |
  The design-blueprint skill is now active. Apply its workflow and style
  blueprint the next time you create or rework a design in this
  conversation — there is nothing to do right now unless a brief is
  already on the table.
referenceDocs:
  - file: style.css
    title: Designer Blueprint Stylesheet
    loadMode: ON_DEMAND
---

You are operating in **design-blueprint mode**: create a polished landing-page
design in a designer app (`app: designer`).

> This is the bundled example skill — a template to copy, not a hidden
> default. It carries no triggers, so it only ever runs when somebody
> calls it explicitly. Copy the folder (project or tenant layer) and edit
> it to the user's house style.

## Workflow

1. **Confirm the target app** — a designer app folder (e.g. `designs/`).
   If none exists, `designer_app_create(folder=…, title=…)` first and embed
   the returned link. Read the app manual on demand:
   `manual_read('app-designer')`.
2. **Load the blueprint stylesheet** — `style.css` next to this skill's
   `SKILL.md`: `manual_read('Designer Blueprint Stylesheet')`. It defines
   the token set (colors, spacing, fonts), a responsive layout and
   dark-mode support. Use it as the starting point for the design's
   `style.css` and adapt — do not write a stylesheet from scratch when
   the user has no style opinion. (The `style.css` file name is the
   design-skill convention: the designer app renders it as a live style
   preview in its skill catalogue — keep it a real, complete stylesheet.)
3. **Write real files** into `<folder>/<design>/` via `doc_write`:
   - `index.html` — the entry, exact name, lowercase. Semantic sections:
     header with nav, hero (headline + subline + one call-to-action),
     a feature grid (3–4 cards), an optional proof section, footer.
   - `style.css` — the (adapted) blueprint. Reference it relatively.
   - `design.yaml` — `title` and a one-line `description`.
4. **Realistic content, not placeholders.** The brief says what the page
   is for — invent plausible copy, product name, and specifics. "Lorem
   ipsum" and "Feature One" are failures.
5. **Self-check** — `designer_validate(folder=…)` catches the states the
   app silently hides (missing `index.html`, broken `design.yaml`).
   Fix and re-run until `ok` is true.

## Rules

- **Responsive by default**: the blueprint is a mobile-first grid; keep
  `@media (min-width: …)` breakpoints intact — the preview checks the
  design at 390px.
- **Dark mode comes free** with `prefers-color-scheme`; the preview's
  day/night toggle exercises it. Do not hard-code a theme switch.
- **Self-contained designs**: no external fonts/CDNs; system font stack
  and local assets only (the sandbox has no cookies and no Vance API).
- One design = one folder. Multiple variants become multiple designs,
  not one file with commented-out sections.
