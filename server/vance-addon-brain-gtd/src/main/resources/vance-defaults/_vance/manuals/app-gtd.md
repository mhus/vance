--- 
title: GTD App
trigger: When the user is in an app:gtd folder, or asks for a Getting-Things-Done / Things-style task system.
---

# GTD (`app: gtd`)

Getting Things Done, **Things-style**. Actions (`kind: action`) live under a
suite folder; their **bucket** is derived, not a folder. See
`manual_read('gtd-buckets')` for the exact rules.

## The five buckets (derived)

| Bucket | Rule |
|---|---|
| Inbox | file sits in `inbox/` (unprocessed) |
| Today | `when: today`, an overdue/`today` date, or a `deadline` ≤ today |
| Upcoming | `when` is a future date (slides into Today on the day) |
| Anytime | active, no `when` |
| Someday | `when: someday` |

There are **no bucket folders**. To move an action between buckets you **set its
`when` attribute** — never move files. The only folder that means anything is
`inbox/` (and `projects/<name>/` for grouping).

## Action fields

`when` (`` / `today` / `someday` / ISO date), `deadline` (ISO, hard due date),
`contexts` (`@calls`/`@home`/…), `done`. Body is Markdown.

## Tools

- `gtd_app_create(folder, title?, description?)` — bootstrap.
- `gtd_capture(folder, title, note?)` — fast capture → Inbox.
- `gtd_action_create(folder, title, when?, deadline?, contexts?, project?, body?)`.
- `gtd_action_update(folder, path, when?, deadline?, contexts?, done?, title?, body?)`
  — change the bucket by setting `when`; complete with `done=true`.
- `gtd_query(folder, bucket?, context?, project?)` — list by derived bucket.
- `gtd_search(folder, query, context?)` — free-text (title/summary/contexts).
- `app_rebuild('folder')` — regenerate `_today.md` / `_upcoming.md` / `_stats.yaml`.
