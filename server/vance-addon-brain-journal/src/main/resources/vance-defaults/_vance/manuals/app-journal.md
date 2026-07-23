--- 
title: Journal App
trigger: When the user is in an app:journal folder, or asks to keep a diary / journal / daily log.
---

# Journal (`app: journal`)

A folder-as-diary: one **`kind: journal-entry`** Markdown file per calendar day,
under `entries/<YYYY>/<YYYY-MM-DD>.md`. Date-anchored reflective prose — not a
task board, not a calendar of appointments.

## Model

- **One entry = one day.** The filename stem is the date (`2026-07-24.md`); the
  `date` front-matter mirrors it and is canonical.
- **Front-matter fields:** `date` (ISO), optional `title`, optional `mood`
  (`great` / `good` / `neutral` / `low` / `bad`, free-form allowed), free `tags`.
- **Body** is Markdown prose (edited with the block editor).
- **Generated artefacts** (never edit by hand): `_index.md` (recent + year-grouped
  list) and `_stats.yaml` (streaks, mood distribution, tag histogram).

## Tools

- `journal_app_create(folder, title?, description?)` — bootstrap the folder.
- `journal_entry_create(folder, date?, body?, title?, mood?, tags?)` — write or
  amend a day. `date` defaults to today; re-running the same date **updates** that
  day (no duplicate).
- `journal_search(folder, query?, mood?, tag?)` — free-text recall over
  title + summary + tags. The prose body is a compressed blob and is **not**
  regex-searchable; hits rank on the entry's LLM summary.
- `journal_query(folder, from?, to?, mood?, tag?)` — deterministic date-range listing.
- `app_rebuild('folder')` — regenerate `_index.md` + `_stats.yaml` after edits.

## Notes

- To recall "what did I write about X", prefer `journal_search`. For "what did I
  write last week", use `journal_query` with a `from`/`to` range.
- Calendar navigation and the "on this day" retrospective are surfaced in the
  Web-UI editor; there is no tool for them (they are read-only views).
