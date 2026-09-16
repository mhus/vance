---
triggers: chat theme, chat styling, web theme, session theme, chat css, style chat, transcript styling, webTheme, recipe theme, chat theme anlegen, chat theme erstellen, how to chat theme, theme for chat, restyle chat
summary: How to author and install a chat theme — a named CSS file that styles the web chat transcript when the session's recipe carries a webTheme line. This manual shows how to create, place, override, and debug chat themes, and how they differ from report themes.
---

# Manual — Chat Themes

A **chat theme** is a CSS file that styles the web chat transcript of a
session. A recipe selects it with a single `webTheme:` line; every chat
session started from that recipe loads the stylesheet and lays it over
the transcript.

This is the operator / author guide. For the architecture contract
(cascade, fail-open policy, scoping, dark mode) see the
[chat-themes spec](../../../../../../../../../../specification/public/chat-themes.md).
For the PDF/markdown styling system — a *different* vocabulary, not
interchangeable with this one — see
[report-themes](report-themes.md).

## When to read this

- "This recipe's sessions should look distinctive in the web chat."
- "Can I restyle every chat of a tenant with one file?"
- "My chat theme does not show up — what did I miss?"

## The one-line version

```yaml
# recipes/<name>.yaml
description: …
engine: arthur
webTheme: acme
```

…starts web chat sessions with the `acme` theme. Recipes without a
`webTheme:` line get the neutral bundled default — visually no change.

## Where themes live

Themes are CSS files under `_vance/chat-themes/`. Three layers,
innermost wins (same cascade as recipes, manuals, templates):

| Layer | Path | Use case |
|---|---|---|
| **Bundled** (read-only) | `vance-defaults/_vance/chat-themes/<name>.css` (on the classpath) | Ships with Vance. Only `default.css`, kept rule-free. You cannot edit it — override it. |
| **Tenant-wide** | `_vance/chat-themes/<name>.css` in the `_vance` tenant | Operator-managed theme for the whole tenant. |
| **Project** | `_vance/chat-themes/<name>.css` in the current project | Per-project theme — beats tenant-wide and bundled. |

The name is the file name without `.css`, lower-case letters, digits
and hyphens only. `webTheme: acme` looks up
`_vance/chat-themes/acme.css` — first layer to have it wins; there is
no merging between layers, overriding replaces the file.

## What a theme may contain

The theme reaches the browser filtered and fenced:

- **Filtered out** before serving: `@import`, `url()` with external
  targets (only `url(data:…)` survives), `javascript:` URIs. Custom
  fonts must be embedded as data URIs.
- **Fenced** to the transcript root: every selector is served under
  `.chat-theme`, so a rule like `body { … }` can never escape the
  chat. A selector that already contains `.chat-theme` is passed
  through unchanged — that is the intended form for dark-mode
  overrides.
- **No CSS nesting, no `&`** — flat rules only.

```css
/* acme chat theme — accent left border on user messages */

.msg-user { border-left: 3px solid var(--color-primary); }

.msg-ai blockquote { border-left-color: var(--color-info); }

.chat-theme[data-mode=dark] .msg-user { border-left-color: var(--color-info); }
```

**Prefer design tokens** (`var(--color-primary)`, `var(--color-base-content)`,
`var(--color-info)` …) over hard-coded colors: they flip with the
light/dark mode, so the theme survives both. The full catalogue is in
the comments of the bundled `default.css`.

## The default theme

`default` is a theme name like any other, with one special role: every
fallback converges on it. A recipe without `webTheme:`, an invalid
name, or a name no layer provides — all serve `_vance/chat-themes/default.css`.

The bundled `default.css` has **no rules** (the chat keeps its own
design). Shipping your own `default.css` in the `_vance` tenant or a
project therefore restyles *every* chat of that scope without touching
a single recipe — that is a feature, not an accident.

## Debugging

- Theme not applying: check the recipe actually used for the session
  (worker sessions may run a different recipe than you edited), then
  check the browser network tab for
  `GET …/chat-themes/<name>/css` — an empty body means the name fell
  back to the default, the brain log says which layer missed.
- Served but not visible: the selectors are fenced under
  `.chat-theme` — a rule like `body { … }` never matches. Rules on the
  transcript container itself need the pre-scoped form
  (`.chat-theme[data-mode=dark] …`).
- Cache: the browser caches a theme for 60 seconds — an edit shows up
  on reload after that, or immediately with a hard reload.
