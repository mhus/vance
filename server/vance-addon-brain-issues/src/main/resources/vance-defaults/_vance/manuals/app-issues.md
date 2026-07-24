--- 
title: Issues App
trigger: When the user is in an app:issues folder, or asks for an issue / bug / ticket tracker.
---

# Issues (`app: issues`)

A lightweight GitHub-Issues-style tracker. Issues (`kind: issue`) live under
`items/`, each with a **stable number** (#N), an `open`/`closed` **state**
(a field, not a folder), labels, an optional assignee and a **comment thread**.

## Model

- **Number** is assigned automatically on create, monotonic, never reused.
  Reference issues as `#42`.
- **State** `open` / `closed` — a front-matter field. Closing does not move the
  file.
- **Archive** — `archived=true` moves an issue to `archive/`, out of the active
  tracker (lists, counts, index). `archived=false` restores it. Distinct from
  delete (trash). Archiving is a file move; state is unchanged.
- **Comments** are a discussion thread stored as document notes on the issue
  (atomic, no rewrite of the issue body).

## Tools

- `issue_app_create(folder, title?, description?)` — bootstrap.
- `issue_create(folder, title, labels?, assignee?, priority?, body?)` — number auto-assigned.
- `issue_update(folder, path, state?, labels?, assignee?, priority?, title?, body?, archived?)`
  — close/reopen via `state`; tidy away via `archived=true`.
- `issue_comment(path, text)` — add to the discussion.
- `issue_query(folder, state?, label?, assignee?, archived?)` — list.
- `issue_search(folder, query, label?)` — free-text (title/summary/labels).
- `app_rebuild('folder')` — regenerate `_index.md` + `_stats.yaml`.

## Notes

- This is not a Kanban board (no columns) and not Scrum (no sprints). It tracks
  the lifecycle + discussion of individual items.
- The body is a compressed blob and not directly searchable — `issue_search`
  ranks on the issue's summary.
