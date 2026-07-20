---
triggers: desktop, common desktop, dashboard, overview, app overview, status board, launcher, home screen, start page, at a glance, what's active, apps overview
summary: Vance "common-desktop application" — a folder that becomes a launcher + status board over every app under it. Use this when the user wants an overview/dashboard of their apps (kanban, calendar, …) or a start page that shows what's active at a glance.
---
# Application — `app: common-desktop`

A **Vance application folder** with `_app.yaml` carrying `kind: application` + `app: common-desktop`. The folder becomes a **launcher + status board**: it lists every app found under it as a card, each showing that app's live status (e.g. Kanban's "3 in Doing") plus an open button.

Use this when the user wants:
- A **dashboard / overview** of their apps in one place.
- A **start page** showing what's currently active across boards, calendars, …
- A **launcher** to jump into any app under a folder.

## Create

`desktop_app_create(folder, title?, recurse?, include?, exclude?, order?)` — writes the manifest and the first `_desktop.md` snapshot in one call. Prefer this over hand-writing `_app.yaml`.

## How it works

- Apps under the folder are found by their `_app.yaml` manifests. By default only **direct children** are listed (`recurse: true` for the whole subtree).
- Each app supplies its own **icon** and **status**; the desktop never hard-codes per-app behaviour, so new app types appear automatically.
- What "status" means per app is configured **in that app's own manifest** (e.g. `config.kanban.status.column`), not here. The desktop only steers presentation (`include` / `exclude` / `order`).
- `app_rebuild('<folder>')` regenerates the `_desktop.md` snapshot. The live web view refreshes on its own.

## Manifest

```yaml
$meta:
  kind: application
  app:  common-desktop
title: "My Desktop"
common-desktop:
  recurse: false            # only direct child apps
  exclude: [canvas]         # app types to hide
  include: []               # if non-empty, ONLY these app types
  order: [kanban, calendar] # these first, the rest by folder name
```
