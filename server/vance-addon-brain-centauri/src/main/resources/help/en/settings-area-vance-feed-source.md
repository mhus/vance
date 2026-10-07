# Area: Feed sources

The Centauri feed sources under `_vance/config/feeds/` — **one file
per feed** (RSS/Atom and the like). The Centauri service merges the
cursors of all sources into one stream: newest entries first,
post-filters apply only after the over-fetch.

## What you configure here

- **Endpoint and protocol** — the feed's address.
- **Credential** — if the feed needs access data, as a vault
  reference.
- **Enabled** — paused feeds stop being polled, the cursor stays
  where it is.
- **Reader identity** — Centauri reads under a pseudonym; the
  configuration controls how the fetch presents itself to the feed
  publisher.

## Scopes

Feeds apply per project; the tenant provides defaults, a project
overrides by name.

This area comes from the **Centauri addon** — without it installed,
the surface is simply absent here.
