---
audience: creator
triggers: custom css, tenant css, customizing, customize the ui, ui customization, brand colors, firmenfarben, ui anpassen, aussehen anpassen, eigenes stylesheet, farbe aendern, schrift aendern, font aendern, logo, firmenlogo, logo in den header, header logo, branding, tenant-ui, ui_custom_css, ui_logo
summary: How I restyle the web UI tenant-wide — the custom stylesheet (`_vance/config/custom.css` via `ui_custom_css_set`, sanitized at write time, no external references) and the header logo (`_vance/config/logo.<ext>` via `ui_logo_set` from a prepared image document: 20px fixed box, square 48-64px @2x raster or SVG, svg/png/webp/jpg only). Both need the invoking user to be tenant-ADMIN; there is no per-project override — the shell is tenant-global.
requires-tools: ui_custom_css_get, ui_custom_css_set, ui_logo_set, doc_import_url, image_resize, image_crop
---
# How I customize the tenant's web UI

Two things here, both tenant-wide (every user of the tenant, on every
page except the login screen, which has no tenant yet):

- **Custom stylesheet** — fonts, colors, radii, spacing. Served to
  every page load as the last sheet in the cascade, so my rules win
  against the built-in styles at equal specificity.
- **Header logo** — replaces the Vance "v" mark in the topbar. The
  bundled "v" stays the fallback (and stays on the login page).

Both live as documents in the `_tenant` system project under
`_vance/config/`. I cannot reach that project with `doc_write` — the
two `ui_*` setters below carry the target themselves. **Both require
the invoking user to be tenant-ADMIN**; when the tool is refused, the
operator must do the change or grant the role — I say so plainly
instead of retrying.

## Custom stylesheet

Read before writing — `ui_custom_css_set` replaces the whole body:

```
invoke_tool(
  name = "ui_custom_css_get",
  params = {}
)
```

Then set the full CSS (never a diff):

```
invoke_tool(
  name = "ui_custom_css_set",
  params = {
    "content": """
:root {
  --brand: oklch(0.62 0.19 25);
}
body { font-family: "Iowan Old Style", Georgia, serif; }
.btn-primary { background-color: var(--brand); border-color: var(--brand); }
.navbar { border-bottom: 2px solid var(--brand); }
.card { border-radius: 0.75rem; }
"""
  }
)
```

Safe, effective levers: font families (`body`), accent colors
(`.btn-primary`, `.navbar`, `--brand` custom variables), border radii
(`.card`, `.btn`), spacing. Changes reach every client within 60
seconds — no re-login.

**Hard limits (the sanitizer strips, at write time, and the response
tells me):**

- No `@import` and no external `url(…)` — Google Fonts, CDN images,
  webfont downloads do not work. Self-hosted fonts are a deployment
  topic, not a CSS one; I use font stacks that exist on the device.
- Only `data:` sources pass in `url()`.
- A `sanitized: true` answer means constructs were dropped — I name
  what was stripped and adjust instead of resubmitting the same CSS.

## Header logo

The topbar box is fixed at 20px with `object-contain` — the image is
never distorted, just fitted. Best sources: **square, SVG** or a
**48-64px PNG/WebP at 2x resolution**. Supported MIME types:
`image/svg+xml`, `image/png`, `image/webp`, `image/jpeg` — anything
else (GIF, AVIF, HEIC) must be converted or re-exported first. Under
2 MB.

Workflow — prepare in the current project, then set:

```
# 1. Acquire (pick what fits): fetch from a URL the user gave …
invoke_tool(
  name = "doc_import_url",
  params = { "url": "https://cdn.example.com/logo.png", "path": "assets/logo.png" }
)

# 2. Shape it: square crop, fit to 48px, optional sharpening
invoke_tool(
  name = "image_resize",
  params = { "path": "assets/logo.png", "mode": "contain", "width": 48, "height": 48 }
)

# 3. Copy the finished image into the tenant config
invoke_tool(
  name = "ui_logo_set",
  params = { "path": "assets/logo.png" }
)
```

`ui_logo_set` trashes any previous tenant logo (all extensions, so a
swap cannot be shadowed by an old file) and answers with the stored
path and size. The user verifies with a page reload; within 60 seconds
the header shows the new logo, the login page keeps the Vance "v".

If the user only has a local file, ask them to upload it into the
current project (drag & drop) and continue at step 2 with its path.

## What I never do

- No project-level "overrides" — there is one tenant stylesheet and
  one tenant logo; a per-project copy does nothing.
- No secrets, tokens, or tracking pixels in the CSS — the sanitizer
  drops the request vectors, and the rest has no business there.
- I do not promise pixel-perfect restyling of every component — the
  CSS targets the shell; app-specific surfaces may need per-app rules
  the user should name explicitly.
