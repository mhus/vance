---
triggers: theme, report theme, report styling, pdf theme, css theme, customer theme, theme anlegen, theme erstellen, theme bauen, how to theme, pdf styling, customer styling, report style, theme override, css override, web preview, cortex preview, markdown preview, theme preview, theme not showing in preview, theme in browser
summary: How to author and install a report theme — a named CSS file that styles PDF exports and the Cortex markdown preview per customer, project, or document. Two front-matter keys (theme: and css:) select stylesheets; this manual shows how to create, place, override, and preview them.
---

# Manual — Report Themes

A **report theme** is a CSS file that controls how a markdown document
looks when exported to PDF. You write one theme per customer or use
case; documents pick it up by name via a single front-matter line.

This is the operator / author guide. For the architecture contract
(cascade, fail-open policy, why PDF-only) see the
[report-themes spec](../../../../../../../../../../specification/public/report-themes.md).
For the LLM tool that triggers the export, see
[report-export](report-export.md).

## When to read this

- "I want my PDFs to look like customer A's corporate design."
- "Can I round the corners of code blocks?"
- "How do I override the default style for one specific document?"
- "What goes in `_vance/report-themes/`?"

## The one-line version

```markdown
---
theme: acme
---

# My Report
```

…renders the PDF **and the Cortex View-mode preview** with the `acme`
theme instead of the default. That is the whole user-facing surface.
Everything below is about how to make `acme.css` exist and look right.

## Where themes live

Themes are CSS files under `_vance/report-themes/`. Three layers,
innermost wins (same cascade as recipes, manuals, templates):

| Layer | Path | Use case |
|---|---|---|
| **Bundled** (read-only) | `vance-defaults/_vance/report-themes/<name>.css` (on the classpath) | Ships with Vance. `default.css` is always loaded; `acme.css` is the example. You cannot edit these — override them. |
| **Tenant-wide** | `_vance/report-themes/<name>.css` in the `_vance` tenant | Operator-managed default for the whole tenant. |
| **Project** | `_vance/report-themes/<name>.css` in the current project | Per-project theme — beats tenant-wide and bundled. |

The name is the file name without `.css`. `theme: acme` looks up
`_vance/report-themes/acme.css`.

### Name rules

- Lowercase letters, digits, hyphens: `[a-z0-9-]+`.
- No slashes, no dots, no traversal. `acme`, `customer-a`,
  `annual-report` are fine; `../x`, `a.b`, `A` are not.
- No subfolders. `_vance/report-themes/customer/acme.css` is not a
  theme; use `customer-acme.css` instead.

## Creating a theme — step by step

### 1. Write the CSS

Start from the bundled `acme.css` — it is a complete override example.
Open it in the Cortex (or any editor) and copy it as your starting
point. A minimal theme:

```css
/* my-company — corporate override for PDF exports.
 *
 * Loads AFTER default.css, so any rule here wins the cascade.
 */

/* Brand color for headings. */
h1, h2, h3 {
    color: #003366;
}

/* Brand color for links. */
a {
    color: #0066cc;
}

/* Subtle accent on code blocks. */
pre {
    border: 1px solid #003366;
    border-radius: 6px;
    background: #f4f8fc;
}
```

### 2. Place it in the cascade

**For one project:** create `_vance/report-themes/my-company.css` in
that project (use the Cortex, `doc_write`, or a kit — same as any
document). The theme is now available in that project only.

**For the whole tenant:** create `_vance/report-themes/my-company.css`
in the `_vance` tenant. Every project in the tenant sees it, unless a
project has its own `_vance/report-themes/my-company.css` (which then
wins).

You do **not** edit the bundled files. They are read-only; you overlay
them with a same-named file higher in the cascade.

### 3. Use it

In any markdown document that should use the theme:

```markdown
---
theme: my-company
---

# Quarterly Report
...
```

Export to PDF (Cortex "File → Export PDF", or the `report_from_markdown`
tool). The PDF picks up `my-company.css` on top of `default.css`.

## The one trap — angle brackets in comments

openhtmltopdf parses the whole HTML document (including the `<style>`
block) as **XHTML**. A CSS comment with angle brackets breaks the parser:

```css
/* ❌ this breaks the render */
/* override for _vance/report-themes/<name>.css */
```

The parser reads `<name>` as an XML tag and fails with a cryptic error:

```
SAXParseException: The element type "name" must be terminated by the
matching end-tag "</name>".
```

**Rule: write CSS comments in plain prose, no `<` or `>`.**

```css
/* ✓ fine */
/* override for the report-themes folder */
```

The bundled `default.css` and `acme.css` are the canonical examples —
copy their comment style.

## What openhtmltopdf supports

It is an XHTML renderer, not a browser. Keep to this subset:

**Supported:** `@page` (size, margin, `@bottom-right` / `@top-center`
for running headers/footers), `font-family`, `font-size`, `color`,
`margin`, `padding`, `border`, `border-radius`, `background`,
`text-align`, `line-height`, `hyphens`, `white-space`, tables, lists,
blockquotes.

**Not supported:** CSS animations, `@media` queries, JavaScript,
CSS variables (`var(--x)`), grid/flex layouts, `@import`, external
`url()` references (they resolve against a `null` base URI and are
skipped — a theme cannot pull in remote resources).

If you want page numbers, the default already has them
(`@bottom-right { content: counter(page) " / " counter(pages); }`).
Override the styling, not the mechanism.

## Per-document override — the `css:` key

`theme:` is for a **named, reusable** theme. When you need a one-off
stylesheet for a single document (or a small set), use `css:` instead —
or in addition to `theme:`:

```markdown
---
theme: acme
css: vance:/reports/2026/annual.css
---

# Annual Report 2026
```

`css:` takes a `vance:` document reference (or a bare path) to any CSS
document in the project. It loads **after** the theme, so its rules win
the cascade — perfect for "use the house style, but tweak the cover
page for this one report."

| Form | Means |
|---|---|
| `css: vance:/styles/round.css` | Absolute in the current project |
| `css: styles/round.css` | Relative to the project root |
| `css: vance://other-project/styles/round.css` | In another project |
| `css: vance:round.css` | Relative to the referrer's folder |

The referenced document is read with a `READ` permission check. A
dead reference logs a warning and falls back to default + theme; the
render does not fail.

### When to use which

- **`theme:`** — a named style shared by many documents (house style,
  customer brand). One file, many users.
- **`css:`** — a per-document or per-project tweak that does not deserve
  a theme name. One file, one report (or a small set).
- **Both** — house style via `theme:`, document-specific polish via
  `css:`. The cascade order is fixed: default → theme → css.

## What the theme does NOT affect

- **DOCX / ODT exports.** Those formats render through a programmatic
  AST visitor (Apache POI / ODF Toolkit), not CSS. `theme:` and `css:`
  are silently ignored on the DOCX/ODT path.
- **The document content.** A theme is pure styling — it does not add,
  remove, or rearrange content. The markdown body that goes to
  commonmark is the front-matter-stripped source; the theme only
  changes how the resulting HTML is styled.
- **Chat / Inbox / Search.** These surfaces use `MarkdownView` without
  theme injection (untrusted content, no `<style>` surface). The
  theme only applies to the PDF export and the **Cortex View-Mode
  preview** (see below) — not to chat messages or search snippets.

## Web preview in the Cortex (View mode)

When you open a markdown document in the Cortex and switch to **View**
(rendered), the theme **also applies live** — not just in the PDF. The
server assembles the same three-layer cascade (default → theme → css),
filters it for the browser, and scopes every selector to
`.markdown-document-preview` so the theme cannot leak into the rest of
the page. Open the document, switch to View, and you see the themed
rendering immediately. The PDF export uses the same cascade without the
browser filtering.

The web preview is a **separate trust surface** from the PDF: the
browser is a real resource loader (a `url('https://evil')` would fire
an actual request), so the server strips `@import`, external `url()`
(keeps `data:` only), `javascript:` URIs, and IE relics before sending
the CSS. A theme that loads a remote font in `@import` works in the PDF
(openhtmltopdf skips it against a null base URI) but is silently
dropped in the web preview. This is intentional — the PDF path is a
closed box, the browser is not.

The front-matter chip strip (`theme: acme` etc. that `MarkdownView`
shows above the body) is **hidden in the preview** — it is
configuration metadata, not report content. The body itself is
unaffected; only the chip strip is suppressed.

## The front matter is stripped from the body

When you write:

```markdown
---
theme: acme
---

# Body
```

…then `# Body` is what gets rendered. The `---\ntheme: acme\n---` block
is consumed by the front-matter parser and never reaches commonmark —
otherwise it would show up as a "theme: acme" heading in your PDF.
The `$meta:` block Vance documents use for their own metadata is
stripped by the same pass; it does not belong in the exported report
either.

## Debugging — "my theme isn't applied"

1. **Name mismatch.** `theme: acme` looks for `_vance/report-themes/acme.css`
   exactly. Check the file name (without `.css`) matches.
2. **Wrong layer.** A theme in the project beats one in the tenant. If
   you edited the tenant copy but the project has its own, the project
   wins. List `_vance/report-themes/` in both places.
3. **Invalid name.** Names must match `[a-z0-9-]+`. An uppercase letter
   or a dot silently skips the theme (with a WARN in the brain log).
4. **Dead `css:` ref.** The path must resolve to an existing document
   you have `READ` on. Check the brain log for "Report css reference
   … could not be resolved" or "… no such document exists".
5. **Angle bracket in a comment.** If the whole PDF is empty or the
   render fails with an XML parse error, check your CSS comments for
   `<` or `>`.
6. **Wrong mime.** `theme:`/`css:` are parsed **only** for
   `text/markdown`. A YAML or JSON file exported to PDF gets the
   default theme; that is by design.
7. **Theme works in PDF but not in the Cortex preview.** The web
   preview strips `@import` and external `url()` (the PDF path skips
   them silently against a null base URI). If your theme loads a
   remote font or stylesheet via `@import`/`url('https://…')`, it
   shows in the PDF but is dropped in the browser preview. Use
   `data:` URIs for assets you want in both. Also: the preview
   re-fetches the theme on doc reload (60s server cache) — if you
   just edited the theme, reload the document.
8. **Theme color doesn't win in the preview.** The preview scopes every
   selector to `.markdown-document-preview` (doubled, to match the
   specificity of MarkdownView's scoped styles). If your theme works in
   the PDF but a specific property (link color, code background) does
   not show in the preview, you likely wrote a **nested** selector
   (`& .foo`) or used CSS nesting — the prefixer handles only flat
   selectors. Rewrite the rule as a flat selector (`a`, `pre a`,
   `.note a`).

The brain log (WARN level) is your friend — every skip path logs a
line that says exactly which layer failed and why.

## Bundled examples

- `default.css` — the base theme (always loaded). Read it to learn the
  `@page` setup and the typographic baseline you are overriding.
- `acme.css` — a complete override example: rounded code blocks, warm
  heading and link colors, warm code-block background. Copy it as a
  starting point for your own theme.

## Anti-patterns

- **Don't put `<` or `>` in CSS comments.** It breaks the XHTML parser
  with a cryptic error. Plain prose only.
- **Don't try to `@import` external CSS.** openhtmltopdf resolves
  `url()` against a `null` base URI and skips it — and a theme pulling
  remote CSS would be a security hole, not a feature.
- **Don't expect `@import` or remote `url()` in the web preview.**
  The PDF path skips `url()` against a null base URI (closed box); the
  web preview strips `@import` and external `url()` entirely (browser
  is a real resource loader). A theme that loads a remote font works
  in the PDF but is silently dropped in the Cortex preview. Use
  `data:` URIs for embedded assets if you want them in both.
- **Don't create a theme per document.** If you find yourself writing
  `theme: report-2026-08-15`, you want `css: vance:/reports/2026-08-15.css`
  instead — a per-document override, not a named theme.
- **Don't edit the bundled files.** They are read-only. Overlay them
  with a same-named file in the tenant or project cascade.
