---
triggers: video, YouTube, clip, tutorial, Tutorial, ride-along, "show me how X looks", play, abspielen, Video zeigen, video_search
summary: Embed a YouTube video inline via a ```youtube fence; use video_search to find one.
---
# Inline kind — `youtube` (inline-only, no stored form)

Embed a YouTube video. The Web-UI renders a privacy-friendly
`youtube-nocookie.com` iframe; the Foot CLI shows the URL as a
link.

Unlike `chart`/`graph`/`mindmap`/`diagram`, **`youtube` has no
stored-document form**. There is no `doc_create_kind(kind="youtube",
…)` path — the URL itself is the artifact. The fenced block is the
only shape of this kind, used directly inside a chat reply.

## Syntax — one URL per fence

````
```youtube
https://youtu.be/dQw4w9WgXcQ
```
````

The body is **exactly one line**: a YouTube URL or the bare 11-char
video ID. No YAML, no metadata block.

Optional fence-meta (on the fence line itself):

````
```youtube start=42 title=Wander Lisbon
https://youtu.be/dQw4w9WgXcQ
```
````

- `start=N` — seek offset in seconds.
- `title=<text>` — caption shown above the player.

## Finding videos — call `video_search`, not `web_search`

When the user asks for a video, tutorial, ride-along, or "show me
how X looks", call `video_search(query=...)`. The tool pre-validates
each result via YouTube oEmbed and drops uploader-disabled,
private, or geo-blocked entries.

Each surviving result carries an `embedFence` field — drop that
string **verbatim** into your reply. Title and channel from the
search result go into **prose around** the fence, not inside it:

```
Hier zwei Treffer:

*Lisbon Walking Tour 4K — by Wander Lisbon (12:34)*

\`\`\`youtube
https://youtu.be/dQw4w9WgXcQ
\`\`\`
```

`web_search` returns generic web pages; YouTube hits from there
aren't validated, often dead, and waste the user's attention.

## Anti-patterns

- **YAML body.** ` ```youtube` is **not** YAML-shaped. Don't write
  `id: …`, `title: …`, `channel: …` inside the fence — that breaks
  the parser and shows nothing. Caption + author go in prose.
- **Multiple URLs in one fence.** One fence = one video. Emit
  several fences for several videos.
- **Non-YouTube URLs.** Vimeo, Twitch, generic MP4 — none of those
  render through this fence. The fence is YouTube-only.

## YouTube is inline-only

There is no `kind: youtube` Document path for external videos. The
URL is the artifact; no need to save it as a Document. If the user
wants to *collect* a list of videos for later, write a
`kind: list` Document with the URLs and embed *that*.
