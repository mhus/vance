---
triggers: feed quelle, feed source, centauri endpoint, quelle hinzufügen, add feed source, wikipedia quelle, usgs quelle, hrafnagud, keine quellen, no sources configured, feed leer
summary: How feed sources (centauri.endpoint.*) are configured, and what to tell the user — an agent can neither read nor write these settings.
---
# Feed sources — the settings behind a feed

A feed reads **streams**, and a stream is one configured **source** plus one **selector**. This manual is about the source half: where it comes from and what to do when it is missing.

## Read this first: you cannot do it yourself

There is **no tool and no script API** that reads or writes `centauri.endpoint.*`. That is operator configuration.

So when a feed has no sources, your job is to **say precisely what to set** — not to attempt it, and not to claim you did. Two failure modes to avoid:

- **Inventing a setting key.** The keys below are the whole surface; a near-miss silently configures nothing.
- **Claiming to have checked.** You cannot list the configured sources. If you need to know which exist, ask.

You *can* create the feed itself with empty streams (`manual_read('feeds-app-create')`) and let the user pick sources afterwards in its configuration tab. That is usually the fastest path.

## The keys

Two are enough; the endpoint id (`<id>`) is free and only has to be used consistently.

```
centauri.endpoint.<id>.protocol = ode | usgs | wikipedia
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

## The three protocols

| Protocol | What it is | baseUrl example | Selectors |
|---|---|---|---|
| `ode` | any foreign application serving the ode feed contract (e.g. Hrafnagud) | `https://hrafnagud.example` | whatever it declares |
| `usgs` | USGS earthquakes, no account | `https://earthquake.usgs.gov` | `all`, `m2.5`, `m4.5`, `m6` |
| `wikipedia` | one wiki's recent changes, no account | `https://de.wikipedia.org` | `all`, `article`, `talk`, `category`, `template` |

**One Wikipedia endpoint per language.** Two of them (`wikipedia-de`, `wikipedia-en`) make a bilingual feed where each entry carries its language — which is what gives the feed's language filter something to work with.

## What to tell the user

Three ways, best first:

1. **Setting form** „Feeds — Quellen" in the settings editor — a switch per source, tenant-wide. Covers the ode source and both examples.
2. **`anus`-CLI:** `setting set -T <tenant> -s tenant -k centauri.endpoint.wikipedia-de.protocol -v wikipedia` (and the same for `.baseUrl`).
3. **Admin REST:** `PUT /brain/{tenant}/admin/settings/tenant/_vance/<key>`.

`-s tenant` writes tenant-wide, so every project sees it. For one project only: `-s project -r <project>`.

## „I configured it and the feed is still empty"

The sources are **cached for five minutes** per project. Right after writing the settings the list is still the old one — and with no sources the configuration tab has no „add stream" button, so it looks like a dead end.

Tell the user to press **„Reload sources"** in the configuration tab (it forces the re-read), or to wait out the five minutes. A brain restart also clears it.

If it is still empty afterwards, the brain log says why — the factory logs one line per skipped endpoint: no protocol set, unknown protocol (with the list of known ones), or the protocol refused the configuration.
