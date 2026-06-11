---
triggers: generate image, create image, draw, paint, picture from prompt, Bild erzeugen, Bild generieren, illustration, render, AI image, image_generate, Fenchurch, Logo, Cover, Diagram from text, text-to-image
summary: How to generate a new image from a text prompt with the Fenchurch service — tool contract, style layers, aspect ratios, anti-patterns.
---
# Image Generation — Fenchurch

How to **generate** a new image from a text prompt. Different
problem from `manual_read('embed-images')` (which shows existing
pictures); this one is about producing them.

## When to use this

User wants a picture that does not exist yet:

- "Mal mir ein Cover für …"
- "Generate a logo for …"
- "Make a diagram of …" (illustration-style; structured diagrams
  go through `kind: diagram` instead)
- "Show me what X could look like" (when no real photo will do)

If a real photo or existing graphic would answer the request,
prefer `research_search modality=image` or
`manual_read('embed-images')` — image generation is slow (5 s –
5 min) and uses paid tokens.

## Tool

```
image_generate(prompt, path?, title?, aspectRatio?)
  → { path, mimeType, sizeBytes, modelUsed, durationMs, title }
  or { error: "...", message: "...", retryable: true|false }
```

- **`prompt`** — what to draw. Keep it concrete: subjects, action,
  setting. Don't repeat the style here when a style layer is
  active (see "Style layers" below).
- **`path`** — optional document path. Default
  `images/<uuid>-<slug>.png`; pass an explicit path only when you
  want to overwrite a specific image.
- **`title`** — optional human-readable title. When omitted, the
  service generates one from the prompt.
- **`aspectRatio`** — `1:1` (default, avatars/squares), `16:9`
  (banners, landscapes), `9:16` (vertical posters), `4:3`
  (classic photo), `3:4` (portraits, book covers).

After a successful call, render the picture inline:

```markdown
![<short alt text>](<path returned by tool>)
```

Use the returned `path` verbatim — do not reconstruct it.

## Style layers — persistent prompts

Persistent style ("medieval manuscript", "watercolor", "transparent
background") goes into the **style layer**, not into every
prompt. Four scopes compose additively:

```
tenant → user → project → session
```

- `image_style_prompt()` — read the **merged** prefix that the
  next image call will actually use, plus the per-scope breakdown.
- `image_style_set(prefix, scope?)` — write into one scope.
  Default scope is `session` (cleanest spillover). Use `project`
  when the user says "for this project", `user` for personal
  defaults, `tenant` only for admin-wide policy.
- `image_style_get()` — read just your own (session) layer.
- Sentinel `__none__`: writing this into a scope suppresses every
  outer layer. Use when the user wants a clean slate ("ignore the
  project style for this image").

When the merged style covers the look-and-feel, the prompt should
be **what is in the picture**, not how it looks.

## Aspect-ratio cheatsheet

| Use case | Ratio |
|---|---|
| Avatar, icon, profile | `1:1` |
| Hero banner, blog header, twitter card | `16:9` |
| Phone wallpaper, story-format, vertical poster | `9:16` |
| Classic photo, landscape illustration | `4:3` |
| Book cover, portrait, character art | `3:4` |

Aspect ratio goes into the tool param, **not** into the prompt
text. Words like "Querformat" or "landscape orientation" in the
prompt are unreliable — vendors interpret them inconsistently.

## Latency expectations

- `default:image` (fast model, e.g. Gemini nano-banana): **3–10 s**
- `default:image-high` (quality model, e.g. gpt-image-1):
  **15 s – 5 min**

The service emits `WAITING` heartbeats every 30 s during the
provider call, so the UI knows it's still running. The Lane is
serialised — while you wait, sibling tool calls queue.

## Bulk images

**Don't loop `image_generate`**. Each call is sync and may take
minutes. For "generate one image per chapter / one per slide / a
set of variations", spawn a Marvin plan with one WORKER child per
image — they parallelise across lanes.

## Anti-patterns

- Re-running `image_generate` with the same prompt to "get a
  better one" — image generation is non-deterministic, sometimes
  the next try is worse. Refine the prompt instead, or use
  `image_style_set` to lock in the style direction.
- Style tokens in the prompt **and** in the style layer — they
  collide and produce muddled results. Read
  `image_style_prompt()` first; if a style is in effect, focus
  the prompt on subject + composition only.
- PII or real-person likeness in the prompt — most providers
  refuse and surface `error: "content_policy"`. Use abstract
  descriptors instead.
- Specific brand names, logos, copyrighted characters — same
  refusal pattern.
- Aspect-ratio words inside the prompt instead of the parameter
  ("landscape", "vertical", "portrait orientation"). Use the
  `aspectRatio` parameter; vendors trust that, not the text.
- Embedding the image right after the tool call without using the
  returned `path` — the slug is non-trivial; always read it from
  the tool result.

## Errors

The tool returns a typed error shape on any failure. Common
reasons and what to do:

- `quota_exceeded` — daily/monthly limit reached. Tell the user
  to wait or ask an admin to lift the limit.
- `timeout` — provider took longer than 360 s. Retry with
  `default:image` (fast lane) or simplify the prompt.
- `content_policy` — provider refused. Rephrase to remove the
  triggering element; do not retry verbatim.
- `prompt_too_long` — exceed the model's prompt cap. Trim or
  shorten the style layer with `__none__`.
- `unsupported_aspect_ratio` — pick one from the cheatsheet above.
- `disabled` — image generation is off in this tenant/project.
  Ask an admin to enable it.
- `provider_error` — generic upstream failure. Retry up to 2× —
  if it keeps failing, switch the alias to the other model
  family.
