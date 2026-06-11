---
triggers: search, web search, recherche, suchen, "look up", find information online, "what does the web say", project memory, do we have, haben wir was zu, research_search, research_rich, research_search_expert, research_providers, web_search, image_search, video_search, pdf_search, rich_search, memory_search
summary: How to pick between memory_search (project-local), research_search / research_rich (web), and the deferred legacy *_search tools. Check memory first; on the web side everything goes through research_search.
---
# Search tools — picking the right one

The web-search surface is **`research_search`** (modality-aware) plus
**`research_rich`** (multi-modality fan-out) — both routed through the
project's `research.endpoint.*` configuration so the operator controls
which provider serves each modality. The five legacy `web_search` /
`image_search` / `video_search` / `pdf_search` / `rich_search` tools
still exist as deferred fallbacks; the LLM should prefer the
`research_*` successors.

## Before going to the web — check the project first

If the user could plausibly already have something on this topic
(notes, prior research, documents they uploaded), reach for
`memory_search query=<text>` first. It searches the project's
auto-indexed memory (`documents/` and friends) and is the
cheapest, freshest, most context-aware source you have. Hits
there save a web round-trip and tie the answer to the user's own
material — which is almost always what they want.

Web tools below kick in when the project memory comes up empty
or when the user explicitly asks for fresh external information
("what's the latest", "search the web for …").

## When to consult

You're about to call a search tool and want to make sure the
mix of results matches the user's intent — or you got back too
little of the right kind and want to know which `modality` or
which tier to use.

## The toolset

### `research_search` — modality-aware default

`research_search query=<text> modality=<web|image|video|pdf|news|academic|book|encyclopedia|internal_doc> num=<1..10>`

The one search-tool to know. `modality` defaults to `web`; set it
explicitly when you want images, videos, PDFs, news, …. The
dispatcher routes to the project's configured endpoint for that
modality (`research.default.<modality>`) and falls back through
`research.fallback.<modality>` on failure.

Result shape per hit: `title`, `url`, `snippet`, `source`, plus
per-modality extras inlined directly (e.g. `imageUrl` for images,
`embedFence` for YouTube videos, `sizeBytes` / `contentType` for
PDFs). Render in your reply by drawing from whichever extras are
present:

- web/news/encyclopedia/internal_doc hits → `[Title](url)` Markdown
- image hits → `![alt](imageUrl)` Markdown (validated by the
  protocol — drop in verbatim)
- video hits → drop the `embedFence` verbatim
- pdf hits → `[Title](url)` Markdown (the Web-UI renders PDF
  cards automatically)

Use when:

- *"Such mir was zu X"*, *"find articles about Y"*, *"references for Z"*
- The user asks for a specific media type — pass `modality=image`
  for pictures, `modality=video` for videos, `modality=pdf` for
  papers/reports/standards.

### `research_rich` — mixed view of a topic

`research_rich query=<text>`

Fans out web + image + video + pdf in parallel and returns one
bucketed result:

```
{
  "query": "Lissabon",
  "text":   { "results": [...], "count": 4 },
  "images": { "results": [...], "count": 4 },
  "videos": { "results": [...], "count": 2 },
  "pdfs":   { "results": [...], "count": 2 }
}
```

Each bucket is independently validated (images HEAD-probed,
videos oEmbed-checked, PDFs Content-Type-checked). Compose your
reply by drawing from whichever buckets fit.

Use when:

- *"Zeig mir was zu X"*, *"was findest du zu Y"*, *"gib mir einen
  Eindruck"*, *"Bilder und Videos von Z"*
- Topic-style questions where the user might want a few of each
  format.
- You're unsure which single modality fits best.

Sub-limits are fixed in v1 (text=4, images=4, videos=2, pdfs=2).
For deeper text research, call `research_search modality=web num=10`
directly — `research_rich`'s text bucket is intentionally capped.

### `research_search_expert` — precise filter control (deferred)

`research_search_expert query=<text> modality=<...> instance=<endpoint-id> site=<host> filetype=<ext> dateFrom=<yyyy-MM-dd> dateTo=<yyyy-MM-dd> domain=<...> locale=<tag> num=<1..10>`

Deferred (not in the default tool manifest) — activate via
`describe_tool` when the user explicitly asks for finer control:

- **Pin a specific endpoint** (`instance=wiki-de` to force the
  German Wikipedia mirror, `instance=serper-eu` to force a
  specific Serper region). Pinned endpoints bypass the default
  cascade — no fallback.
- **Site / filetype filters** when the user wants results only
  from `arxiv.org` or only PDFs from a topic search.
- **Date range** for "only from 2024", "before March".
- **Locale** for `de` / `en-US` / `fr-CA` routing where the
  protocol speaks language-specific endpoints.

Use when:

- *"Such mir nur arxiv-Papers von 2024"*
- *"Aus der englischen Wikipedia"*
- *"Nur PDFs zum Thema X"*
- The default-cascade picked a provider that the user didn't want
  (look at `research_providers` to see the inventory, then pin).

### `research_providers` — inventory probe (deferred)

`research_providers` (no params)

Returns the list of provider instances assembled for the current
project — instance id, protocol, modalities served, availability
(`READY` / `NO_CREDENTIALS` / `QUOTA_EXHAUSTED` / `COOLDOWN` /
`DISABLED`), and current quota where the protocol exposes one.

Use when:

- You want to pin a specific endpoint via `research_search_expert`
  and need to know what's available.
- A search returned `"no provider instance"` and you want to tell
  the operator what's missing.
- The user asks "which search backends do we have configured?".

## Decision shortcut

| User signal | Tool |
|---|---|
| "Was gibt's zu X?", "zeig mir was über Y" | `research_rich` |
| "Such mir die Quellen zu X", citations needed | `research_search modality=web num=10` |
| "Bilder von Lissabon", "wie sieht X aus?" | `research_search modality=image` |
| "Video über X", "spiel ein Video" | `research_search modality=video` |
| "Find me the PDF", "wo ist das Papier zu X?" | `research_search modality=pdf` |
| "Aktuelle Nachrichten zu X" | `research_search modality=news` |
| "Wikipedia über Y" | `research_search modality=encyclopedia` or `research_search_expert instance=wiki-de` |
| "Find papers about Z" | `research_search modality=academic` |
| "Nur arxiv, nur PDFs von 2024" | `research_search_expert` |
| Which provider is configured? | `research_providers` |

## Anti-patterns

- Calling `research_search` four times in a row when `research_rich`
  would handle a "show me everything about X" in one call. Four
  tool-calls cost four turn-validations and burn quota.
- Using `research_rich` for deep text research. The text bucket is
  capped at 4 — pass `modality=web num=10` to `research_search`
  directly.
- Calling `research_search modality=web` and trying to extract
  image URLs from the snippets. The hits are pages, not images.
  Pass `modality=image` if you want images.
- Pinning an `instance` you found in `research_providers` whose
  availability is not `READY`. The dispatcher returns the result
  with `error` set instead of falling back — pinning is the
  "strict" path.

## Legacy fallback

The original `web_search` / `image_search` / `video_search` /
`pdf_search` / `rich_search` tools are still wired up as
`deferred=true`. They keep working unchanged for legacy recipes
that reference them by name, but the search-hint on each one
points at the `research_*` successor. Don't reach for them from
fresh code paths — the `research_*` tools have the project-aware
provider cascade, the cooldown isolation, and the usage counters
the operator looks at in the Insights "Research" tab.
