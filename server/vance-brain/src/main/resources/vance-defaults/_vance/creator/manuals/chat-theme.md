---
audience: creator
triggers: chat theme, chat styling, chat css, transcript styling, webTheme, theme the chat, style the chat, chat hintergrund, chat farbe, chat aussehen, chat design, session look, chat theme anlegen, chat theme erstellen, pastell, hintergrund im chat
summary: How I set up a chat theme for this project — a named CSS document under `_vance/chat-themes/` that styles the web chat transcript, selected by the recipe's `webTheme:` line. Project layer only (the `_tenant` layer is operator work), tokens first, flat CSS, dark mode via `.chat-theme[data-mode=dark]`, and the recipe override replaces the whole recipe file — read before writing.
requires-tools: doc_write, doc_read, doc_list, recipe_describe
---
# How I theme this project's web chat

A **chat theme** is a CSS document that restyles the transcript of the
web chat: the message area background, bubble accents, typography. It
applies to chat sessions whose recipe carries a `webTheme:` line
naming the theme. Sessions without one keep the default look — a
recipe with no `webTheme:` renders visually unchanged, so wiring the
theme is an explicit choice, never a side effect.

Two things to set up, in this order:

1. **The theme** — one CSS document in this project.
2. **The selection** — a recipe (usually a project-level override)
   that names it via `webTheme:`.

This themes the **chat transcript only** — not the shell, not the
compose bar, not PDF exports (those are report themes, a different
vocabulary under `_vance/report-themes/`). Tenant-wide UI restyling is
a separate topic: see `tenant-ui-customization`.

## 1. Write the theme document

The name is the file name without `.css`, lower-case letters, digits
and hyphens only (`acme-blue`, `project-warm`) — it becomes a URL
segment when the client fetches the theme, anything else fails the
recipe load.

```
invoke_tool(
  name = "doc_write",
  params = {
    "path": "_vance/chat-themes/acme-blue.css",
    "content": """
/* Project chat theme — soft blue transcript. */

.chat-theme {
  background-color: #e6f0fa;
}

.chat-theme[data-mode='dark'] {
  background-color: #14202e;
}

.msg-user {
  border-left: 3px solid var(--color-primary);
}
"""
  }
)
```

### Authoring rules — what survives to the browser

The CSS is filtered and fenced server-side before it reaches the
client. Write with these rules and nothing gets stripped:

- **Flat CSS only.** No nesting, no `&` — nested selectors render
  wrong (the server prefixes every selector with the scope class).
- **The transcript is fenced under `.chat-theme`** — a plain selector
  applies below it (`.msg-user`, `blockquote`, `h1`). A selector that
  already contains `.chat-theme` passes through **unchanged**: that is
  the intended form for rules on the scope root itself — backgrounds
  (`.chat-theme { background: … }`) and dark mode
  (`.chat-theme[data-mode='dark'] { … }`).
- **Prefer design tokens** over hard-coded colors: `var(--color-primary)`,
  `var(--color-base-content)`, `var(--color-info)`, … They flip with
  the light/dark mode, so a token-based theme survives both. Hard-coded
  colors look right in one mode only — always add a
  `[data-mode='dark']` variant for them.
- **Stable bubble hooks:** `.msg-row` + `.msg-user` / `.msg-assistant` /
  `.msg-system` are the per-role anchors on every bubble.
- **Stripped at serve time:** `@import` (gone), `url()` with external
  targets — only `url(data:…)` survives — and `javascript:` URIs.
  Embed custom fonts as data URIs; prefer system fonts.

If the user wants to restyle **every** chat of the tenant, not just
this project's: that layer lives in the `_tenant` system project, and
I cannot reach it from this worker — the operator has to put the file
there (same path, `_vance/chat-themes/<name>.css`). Overriding the
tenant-wide `default.css` restyles every chat without touching any
recipe — I say so plainly instead of retrying a write I cannot do.

## 2. Select it — the recipe's `webTheme:` line

A chat session gets its theme from the recipe its **chat process**
runs (the session-starter recipe, picked in the recipe picker — not
from the worker recipes whose echoes show up in the transcript).

`webTheme: <name>` is a top-level recipe field. For a bundled recipe
("arthur", "eddie", …) that means a **project-level override** — and
the recipe cascade is first-hit-wins with **no field merge**: the
project file replaces the whole recipe. So:

1. Read the current recipe first:

```
invoke_tool(
  name = "recipe_describe",
  params = { "name": "arthur" }
)
```

2. Write the full recipe to the project layer with every field it
   currently has — plus the one new line:

```
invoke_tool(
  name = "doc_write",
  params = {
    "path": "_vance/recipes/arthur.yaml",
    "content": """<the complete recipe YAML from step 1, plus:>
webTheme: acme-blue"""
  }
)
```

**The `webTheme` name is validated when the recipe loads** — an
invalid name (uppercase, dots, slashes) makes the whole recipe fail
to load, and with it every spawn that tries to use it. An unknown but
well-formed name is safe: the chat falls back to the default theme
and the brain logs a warning.

If the project already has a project-level recipe, `doc_read` it and
edit in place — same completeness rule applies.

## Verify

Recipe and theme layers of the cascade are hot — no restart needed.
Open (or reload) a chat of the session-starter recipe in the web UI;
the client fetches the theme once per session and caches it for 60
seconds. Empty transcript background after a change → hard-reload once
before concluding something is wrong, and check the brain log for a
"chat theme not found" warning — it names the layer that missed.

## Traps

- **`webTheme` under `params:` does nothing** — it is a top-level
  field; misplaced keys silently become engine parameters.
- **A project recipe with missing fields is not a diff** — leaving
  out `promptPrefix` or tool lists because "they come from the
  bundled one" silently drops them. The override is the whole recipe.
- **Report themes are not chat themes** — `_vance/report-themes/`
  styles PDF exports and the Cortex markdown preview; its files are
  invisible here, and mine are invisible there.
