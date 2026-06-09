---
triggers: search, web search, rich_search, web_search, image_search, video_search, pdf_search, memory_search, recherche, suchen, "look up", find information online, "what does the web say", project memory, do we have, haben wir was zu
summary: How to pick between memory_search (project-local) and rich_search / web_search / image_search / video_search / pdf_search (web) for a given user intent. Check memory first.
---
# Search tools — picking the right one

Five web-search tools, four use cases. Pick by what the user is
asking for, not by what feels "neutral".

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
little of the right kind and want to know if a different tool is
better.

## The five tools

### `rich_search` — mixed view of a topic

The default for **"show me what you have on X"** requests. Calls
web, image, video, and PDF search in parallel and returns a
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
reply by drawing from whichever buckets fit:

- `text` rows → `[Title](url)` Markdown
- `images` rows → `![alt](imageUrl)` Markdown
- `videos` rows → drop the `embedFence` verbatim
- `pdfs` rows → `[Title](url)` Markdown (LinkPreview renders them
  as PDF cards automatically)

Use when:

- *"zeig mir was zu X"*, *"was findest du zu Y"*, *"gib mir einen
  Eindruck"*, *"Bilder und Videos von Z"*
- Topic-style questions where the user might want a few of each.
- You're unsure which single format fits best.

### `web_search` — precise text research

Plain Serper organic results, no validation, no images / videos /
PDFs. Use when the user explicitly asks for **sources, citations,
articles, papers, references** — or when you need more than the
~4 text hits that `rich_search` returns. This is also right for
multi-hop research (search, follow links, re-search) where you'll
process the URLs further with `web_fetch`.

### `image_search` — only images

When the user asked specifically for **pictures, photos,
screenshots, illustrations** and nothing else. Each `imageUrl` is
pre-validated against the source host. Drop straight into
`![alt](imageUrl)`.

### `video_search` — only YouTube videos

When the user asked specifically for **a video, a tutorial, a
ride-along, "show me how X looks" as motion**. Each result is
oEmbed-checked for embeddability. Drop `embedFence` verbatim.

### `pdf_search` — only PDFs

When the user asked specifically for **papers, reports, standards,
manuals, the PDF of X**. Each URL is HEAD-probed for
`application/pdf`. Present as `[Title](url)` — the Web-UI renders
PDF cards automatically.

## Decision shortcut

| User signal | Tool |
|---|---|
| "Was gibt's zu X?", "zeig mir was über Y" | `rich_search` |
| "Such mir die Quellen zu X", citations needed | `web_search` |
| "Bilder von Lissabon", "wie sieht X aus?" | `image_search` |
| "Video über X", "spiel ein Video" | `video_search` |
| "Find me the PDF", "wo ist das Papier zu X?" | `pdf_search` |

## Anti-patterns

- Calling all four vertical tools separately when `rich_search`
  would do it in one. Four tool-calls cost four turn-validations
  and burn Serper quota that doesn't need burning.
- Using `rich_search` for deep text research. The text bucket is
  capped at 4 — `web_search` gives you 5–10 and is cheaper.
- Calling `web_search` and then trying to extract image URLs from
  the snippets. The result rows are pages, not images; image URLs
  in the snippets are unvalidated. Use `image_search` if you want
  images.
