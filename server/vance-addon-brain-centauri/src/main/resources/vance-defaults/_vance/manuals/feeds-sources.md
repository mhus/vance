---
triggers: feed quelle, feed source, centauri endpoint, quelle hinzufügen, add feed source, wikipedia quelle, usgs quelle, hrafnagud, keine quellen, no sources configured, feed leer
summary: How feed sources (_vance/config/feeds/*.yaml) are configured and what to tell the user — you can list the sources with feed_sources but cannot create or change one.
requires-tools: feed_sources
---
# Feed sources — the documents behind a feed

A feed reads **streams**, and a stream is one configured **source** plus one **selector**. This manual is about the source half: where it comes from and what to do when it is missing.

## What you can and cannot do

**You can see which sources exist:** `feed_sources()` lists them with their selectors. Use it — do not ask the user and do not guess.

**You cannot create or change one.** A source is a document under `_vance/config/feeds/`, and `_vance/**` is operator territory — a write there needs ADMIN, which you do not have. So when `feed_sources` comes back empty, your job is to **say precisely what to set** — not to attempt it, and not to claim you did.

Two failure modes to avoid:

- **Inventing a field.** The fields below are the whole surface; a near-miss silently configures nothing.
- **Claiming to have configured it.** You can verify afterwards with `feed_sources`, and that is the only honest evidence.

## The document

One file per source: `_vance/config/feeds/<id>.yaml`. **The filename is the source id** — it is free and only has to be used consistently.

```yaml
protocol: ode        # ode | usgs | wikipedia | …   ← an addon can add more
baseUrl: https://…
```

| Optional | Default | Meaning |
|---|---|---|
| `enabled` | `true` | `false` switches the source off without deleting the file |
| `apiKey` | — | sent as `Authorization: Bearer …`. Either a reference (`{{secret:vault:…}}`) or a declared literal (`{noop}…`) — which of the two is the operator's decision |
| `sendActor` | `true` | send the salted reader pseudonym, so the source can personalise |
| `language` | from host | only for `wikipedia` on hosts like `commons.wikimedia.org` |
| `feedPath` | `/ode/feed` | only for `ode`, when the source moved its path |
| `hideSensitive` | `false` | drop entries the source itself flagged as sensitive |
| `blockedHosts` | — | comma-separated hosts whose entries are dropped, subdomains included |
| `blockedAuthors` | — | comma-separated authors whose entries are dropped |

**Without `protocol` the source is skipped entirely**, and so is a file that is not valid YAML — the brain log names it.

## What a source refuses to hand on

The last three fields above are **standing policy, not a filter**. A filter is what the reader wants
right now and arrives per request; these hang on the source and apply to every request, including
yours through `feed_read`. You cannot switch them off from here, and that is deliberate — a rule a
caller can forget is not a rule.

They work for every protocol, not just Mastodon: `blockedHosts` matches the host of an entry's url
(subdomains included, so `a.example` also blocks `www.a.example`), and `hideSensitive` honours an
`extras.sensitive` flag that a protocol sets when its source labelled the entry. A protocol that never
sets it is simply unaffected.

**None of this is a parental control.** A blocklist blocks what is on it; new hosts keep appearing.
For a federated source the effective lever is the **selector** — a local or hashtag timeline never
pulls foreign instances in. Say that when someone asks for the lists to keep unwanted material out:
offer the selector first, the lists as a supplement.

If an operator wrote these fields and nothing changed, the brain log has a warning — the protocol
does not read them yet.

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

1. **A template.** „Neues Dokument" → the „Vorlage" tab → filter on `source`. There is one per shipped protocol (`feed-source-ode`, `feed-source-wikipedia`, `feed-source-usgs`, and Mastodon's own in its addon); it asks for the URL and writes the file to the right place.
2. **By hand** in the Cortex: create `_vance/config/feeds/<id>.yaml` with the two required fields above.

Where the file lives decides who sees the source: in the **`_tenant`** project it applies to every project of the tenant; in one project it applies there only, and a file of the same name overrides the tenant-wide one entirely (whole document, not field by field).

## „I configured it and the feed is still empty"

The sources are **cached for five minutes** per project. Right after writing the document the list may still be the old one — and with no sources the configuration tab has no „add stream" button, so it looks like a dead end.

Tell the user to press **„Reload sources"** in the configuration tab (it forces the re-read), or to wait out the five minutes. A brain restart also clears it.

If it is still empty afterwards, the brain log says why — the factory logs one line per skipped endpoint: no protocol set, unknown protocol (with the list of known ones), or the protocol refused the configuration.
