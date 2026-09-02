--- 
title: GTD App
trigger: When the user is in an app:gtd folder, or asks for a Getting-Things-Done / Things-style task system.
---

# GTD (`app: gtd`)

Getting Things Done, **Things-style**. Actions (`kind: action`) live under a
suite folder; their **bucket** is derived, not a folder. See
`manual_read('gtd-buckets')` for the exact rules.

## The buckets

| Bucket | Rule |
|---|---|
| Inbox | file sits in `inbox/` (unprocessed) |
| Today | `when: today`, an overdue/`today` date, or a `deadline` ≤ today |
| Upcoming | `when` is a future date (slides into Today on the day) |
| Anytime | active, no `when` |
| Someday | `when: someday` |
| Trash | file sits in `trash/` (put away) |

Four of the six are **derived**: to move an action between them you **set its
`when` attribute** — never move files. `inbox/` and `trash/` are the two that
really are folders; reach them with `bucket: "inbox"` / `bucket: "trash"`.
`projects/<name>/` groups actions and is orthogonal to all of it.

## Completing something

`done: true` only sets the flag. The action **stays where it is**, struck
through, and leaves the work list when `app_rebuild` sweeps every completed
action into `trash/`. So:

- Do not follow a completion with a move, a delete, or a rebuild "to tidy up"
  unless the user asked for it.
- Never report a completed action as deleted or gone. It is ticked off, in
  place, and still there to look at.
- **Nothing in this app deletes an action.** The destructive step exists only
  inside the trash, and only in the UI.

## Action fields

`when` (`` / `today` / `someday` / ISO date), `deadline` (ISO, hard due date),
`contexts` (`@calls`/`@home`/…), `done`. Body is Markdown. `trashedFrom` is
written by the app when an action goes into the bin — leave it alone.

## Tools

- `gtd_app_create(folder, title?, description?)` — bootstrap.
- `gtd_capture(folder, title, note?)` — fast capture → Inbox.
- `gtd_action_create(folder, title, when?, deadline?, contexts?, project?, body?)`.
- `gtd_action_update(folder, path, when?, bucket?, deadline?, contexts?, done?, title?, body?, project?)`
  — change the bucket by setting `when`; complete with `done=true`. `bucket`
  (`inbox` | `trash` | `today` | `anytime` | `someday`) is the way to the two
  folder buckets; it is **mutually exclusive** with `when`. `project` re-files
  the action into `projects/<name>/`: pass `""` to file it back out into
  `actions/`, omit it to leave the folder alone. Both `bucket` and `project`
  move the file, so use the returned `path` afterwards.
- `gtd_query(folder, bucket?, context?, project?, includeDone?, includeTrash?)`
  — list by bucket. Completed actions and the trash are left out unless asked
  for.
- `gtd_search(folder, query, context?)` — free-text (title/summary/contexts).
- `app_rebuild('folder')` — regenerate `_today.md` / `_upcoming.md` /
  `_stats.yaml` **and** sweep every completed action into `trash/`. That sweep
  is a visible change to the user's lists: run it when asked to tidy up or
  rebuild, not as a reflex after every edit.
