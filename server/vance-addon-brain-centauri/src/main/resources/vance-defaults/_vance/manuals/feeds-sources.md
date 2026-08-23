---
triggers: feed quelle, feed source, centauri endpoint, quelle hinzufügen, add feed source, wikipedia quelle, usgs quelle, hrafnagud, keine quellen, no sources configured, feed leer
summary: How feed sources (centauri.endpoint.*) are configured and what to tell the user — you can list the sources with feed_sources but cannot create or change one.
requires-tools: feed_sources
---
# Feed sources — the settings behind a feed

A feed reads **streams**, and a stream is one configured **source** plus one **selector**. This manual is about the source half: where it comes from and what to do when it is missing.

## What you can and cannot do

**You can see which sources exist:** `feed_sources()` lists them with their selectors. Use it — do not ask the user and do not guess.

**You cannot create or change one.** No tool and no script API writes `centauri.endpoint.*`; that is operator configuration. So when `feed_sources` comes back empty, your job is to **say precisely what to set** — not to attempt it, and not to claim you did.

Two failure modes to avoid:

- **Inventing a setting key.** The keys below are the whole surface; a near-miss silently configures nothing.
- **Claiming to have configured it.** You can verify afterwards with `feed_sources`, and that is the only honest evidence.

## The keys

Two are enough; the endpoint id (`<id>`) is free and only has to be used consistently.

```
centauri.endpoint.<id>.protocol = ode | usgs | wikipedia | …   ← an addon can add more
centauri.endpoint.<id>.baseUrl  = https://…
```

| Optional | Default | Meaning |
|---|---|---|
| `.enabled` | `true` | `false` switches the source off without deleting it |
| `.apiKey` | — | sent as `Authorization: Bearer …` (PASSWORD) |
| `.sendActor` | `true` | send the salted reader pseudonym, so the source can personalise |
| `.language` | from host | only for `wikipedia` on hosts like `commons.wikimedia.org` |
| `.feedPath` | `/ode/feed` | only for `ode`, when the source moved its path |

**Without `.protocol` the source is skipped entirely.** That is also why switching a source off in the setting form removes it from the list rather than leaving it behind as `enabled=false`.

## The protocols shipped with this addon

| Protocol | What it is | baseUrl example | Selectors |
|---|---|---|---|
| `ode` | any foreign application serving the ode feed contract (e.g. Hrafnagud) | `https://hrafnagud.example` | whatever it declares |
| `usgs` | USGS earthquakes, no account | `https://earthquake.usgs.gov` | `all`, `m2.5`, `m4.5`, `m6` |
| `wikipedia` | one wiki's recent changes, no account | `https://de.wikipedia.org` | `all`, `article`, `talk`, `category`, `template` |

**One Wikipedia endpoint per language.** Two of them (`wikipedia-de`, `wikipedia-en`) make a bilingual feed where each entry carries its language — which is what gives the feed's language filter something to work with.

**This table is not the whole list.** A protocol arrives as an addon, so an installation can have
others — Mastodon, for instance, is its own addon with its own manual (`manual_read('feeds-mastodon')`).
This manual ships with the feeds addon and only names what ships with it; **`feed_sources()` is the
authority** on what this installation actually has. Read it before telling anyone a source is
impossible, and note what it says about a source's `selectorMode`: `ENUMERABLE` means the selectors
it lists are the whole set, `FREEFORM` means the selector is typed and its grammar belongs to that
protocol's manual.

## What to tell the user

Three ways, best first:

1. **Setting form** „Feeds — Quellen" in the settings editor — a switch per source, tenant-wide. Covers the ode source and both examples. A protocol from another addon brings its own form; Mastodon's is „Feeds — Mastodon".
2. **`anus`-CLI:** `setting set -T <tenant> -s tenant -k centauri.endpoint.wikipedia-de.protocol -v wikipedia` (and the same for `.baseUrl`).
3. **Admin REST:** `PUT /brain/{tenant}/admin/settings/tenant/_vance/<key>`.

`-s tenant` writes tenant-wide, so every project sees it. For one project only: `-s project -r <project>`.

## „I configured it and the feed is still empty"

The sources are **cached for five minutes** per project. Right after writing the settings the list is still the old one — and with no sources the configuration tab has no „add stream" button, so it looks like a dead end.

Tell the user to press **„Reload sources"** in the configuration tab (it forces the re-read), or to wait out the five minutes. A brain restart also clears it.

If it is still empty afterwards, the brain log says why — the factory logs one line per skipped endpoint: no protocol set, unknown protocol (with the list of known ones), or the protocol refused the configuration.
