---
triggers: feed anlegen, feeds app, newsfeed anlegen, nachrichten feed, stream anlegen, endlos scroll, create feed, new feed, feed setup, wikipedia feed, erdbeben feed
summary: Bootstrap a feed application (app: feeds) with feeds_app_create — the endless scroll over foreign time-ordered streams. Use this instead of hand-writing _app.yaml.
requires-tools: feeds_app_create
---
# Tool — `feeds_app_create`

Bootstrap a new feed: an endless scroll over foreign time-ordered streams (news, wiki changes, earthquakes), merged chronologically and filtered.

**First call** whenever the user wants to *read along* with something. Not for searching — that is `research_search`.

## Why this tool exists

The manifest is small and easy to get wrong in two ways that both produce a feed that opens **empty** rather than an error:

- The config block sits under `feeds:`, **not** at the manifest root.
- `streams` is a list of `{ source, selector }` objects, not a list of source names.

The tool builds it server-side from typed parameters, so neither can happen.

## When to use this

- „Leg mir einen Feed an mit den Wikipedia-Änderungen"
- „Ich will die Erdbeben-Meldungen mitlesen"
- „Bau mir eine Morgenlage aus den Nachrichtenquellen"

**Don't** use it for a one-off question about the world — that is `research_search` / `web_fetch`. A feed is a standing view, not an answer.

## Parameters

| Param | Type | Required | Notes |
|---|---|---|---|
| `folder` | string | **yes** | `_app.yaml` lands at `<folder>/_app.yaml`. |
| `title` | string | no | Display title. |
| `description` | string | no | Free text. |
| `streams` | array | no¹ | Each `{ source: "<endpoint id>", selector?: "<selector>" }`. A bare string means that source's default stream. |
| `since` | string | no | Time window, **relative**: `-7d`, `-12h`, `-30m`. |
| `pageSize` | integer | no | Default 20. |
| `overwrite` | boolean | no | Default false. |
| `projectId` | string | no | Default: active project. |

¹ May be empty — a feed without streams opens and says so, and the user picks sources in its configuration tab. That is often the right answer: see the next section.

## You cannot invent a source id

`source` must be an **endpoint configured in this project's settings** (`centauri.endpoint.<id>.*`). Those are operator settings; **no tool can read or write them**, so you cannot look them up and you cannot create one.

Consequences for how you behave:

- If the user named a source you have not seen in this conversation, **do not guess an id**. A wrong id produces a feed that stays empty and a note the user has to decode.
- Either create the feed **with empty `streams`** and tell the user to pick the sources in the configuration tab, or ask which endpoint ids exist.
- Never claim a feed „is now reading X" unless the user gave you that id.

`manual_read('feeds-sources')` has the exact setting keys, for when the user asks how to add a source.

## Time window

`since` is stored **relative** and resolved per request. Say `-7d`, not a date: a fixed date in a stored configuration quietly stops matching as it ages — „last week" configured in August still means August in December.

## Returns

`{ app, folder, manifestPath, markdownLink, nextStep, stats: { streamCount } }`. Give the user the link; `nextStep` already says what is missing.

## What a feed does not do

- It does not archive. Entries are transient and remote — an entry becomes permanent only when somebody **clips** it, which is a button in the UI.
- It does not maintain sources: feed lists, full texts, categories and translation stay with the source.
- It has no unread state. „I do not want to see this" is the `exclude` filter, not a per-entry flag.
