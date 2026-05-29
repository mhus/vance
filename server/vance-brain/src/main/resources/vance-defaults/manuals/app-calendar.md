---
triggers: project plan, Projektplan, Sprint plan, Sprintplanung, gantt, gantt chart, multi calendar, mehrere kalender, calendar app, kalender app, calendars folder, projektplanung, roadmap, milestones, mehrere lanes, planning suite, sprint q3, project schedule
summary: Vance "calendar application" — a folder that turns into a planning suite with one calendar per lane, automatic Gantt + conflict generation. Use this when the user has more than one calendar to coordinate or asks for a project plan / Gantt.
---
# Application — `app: calendar`

A **Vance application folder** is a folder containing an `_app.yaml` manifest at its root. For `app: calendar` this turns the folder into a calendar-suite: one `kind: calendar` file per lane (`design.yaml`, `backend.yaml`, …) plus auto-generated `_gantt.md` and `_conflicts.yaml`.

Use this pattern when the user wants:
- A **project plan** with multiple work streams ("Sprint-Plan", "Roadmap", "Q3-Plan")
- A **Gantt visualisation** of milestones across teams / phases
- **Conflict detection** between vacations / deadlines / meetings
- Anything where a single flat `kind: calendar` file would become a monster

For a **single calendar** without lanes (just "trag morgen 14 Uhr Zahnarzt ein"), stick with the simpler `kind: calendar` document and `calendar_create` — see `manual_read('doc-kind-calendar')`.

## Folder layout

```
calendars/                  ← suite folder
├── _app.yaml               ← manifest (kind: application, app: calendar)
├── _gantt.md               ← auto-generated (kind: diagram)
├── _conflicts.yaml         ← auto-generated (kind: records)
├── overview.yaml           ← calendar, lane: "default" (root file)
├── design/
│   ├── _info.yaml          ← optional lane-local override
│   ├── mockups.yaml        ← calendar, lane: "design"
│   └── review.yaml         ← calendar, lane: "design"
└── backend/
    └── api.yaml            ← calendar, lane: "backend"
```

**Lane rule:** the **leaf folder** of a calendar's path *relative to the suite root* is its lane. Files in the root get lane `default`. Deeply nested (`a/b/c/file.yaml`) → lane = `c`.

## `_app.yaml` schema

```yaml
$meta:
  kind: application
  app: calendar
title: "Sprint Q3 Planning"
description: "Design + Backend + Frontend"

calendar:
  # Window for derived artifacts. Both bounds optional.
  window:
    from: "2026-06-01"
    until: "2026-09-30"

  # Per-lane labels / colors / ordering (all optional — auto-defaults
  # use folder name and alphabetical order).
  lanes:
    design:   { title: "Design",   color: blue,   order: 1 }
    backend:  { title: "Backend",  color: green,  order: 2 }
    frontend: { title: "Frontend", color: purple, order: 3 }

  gantt:
    outputPath: "_gantt.md"          # default
    includeRecurring: false          # default — milestones over standups
    tagFilter: []                    # empty = all
    criticalTags: [milestone, critical]
    doneTags: [done, erledigt]
    sectionOrder: [design, backend, frontend]  # explicit; remainder alpha

  conflicts:
    outputPath: "_conflicts.yaml"    # default
    ignoreWithinTags: [private]      # vacations don't conflict with each other
    ignoreAllDayOverlapsBetweenLanes: false
```

## Tool inventory

| Tool | What it does |
|---|---|
| `app_rebuild(folder)` | **The one you usually want.** Reads `_app.yaml`, regenerates `_gantt.md` + `_conflicts.yaml` in one go. Generic — works for any future Vance app type, not just calendar. |
| `calendar_aggregate(folder, from?, to?, lanes?, tags?, expandRecurring?)` | Read-only query. "Was hab ich nächste Woche?", "welche Termine in Lane backend?", "alle Milestones im Q3". Returns flat sorted event list. **No file written.** |
| `calendar_conflicts(folder, from?, to?)` | Regenerate only `_conflicts.yaml`. Use when you don't need the Gantt updated. |
| `gantt_from_calendars(folder, from?, to?)` | Regenerate only `_gantt.md`. Use when you don't need the conflicts updated. |

## Canonical flow

**1. User asks for a project plan.** Create the manifest:

```
doc_create_kind(
  kind="application",
  mimeType="application/yaml",
  path="projects/website-relaunch/calendars/_app.yaml",
  body="$meta:\n  kind: application\n  app: calendar\ntitle: Website Relaunch\ncalendar:\n  lanes:\n    design:  { title: Design, color: blue, order: 1 }\n    backend: { title: Backend, color: green, order: 2 }\n  gantt:\n    outputPath: _gantt.md\n  conflicts:\n    outputPath: _conflicts.yaml\n"
)
```

**2. Create the per-lane calendars** using `calendar_create` (one call per lane, with `outputPath` pointing inside the lane folder):

```
calendar_create(
  events=[
    { title: "Mockups",     start: "2026-06-01", end: "2026-06-15", allDay: true, tags: [milestone] },
    { title: "Review",      start: "2026-06-16", end: "2026-06-18", allDay: true }
  ],
  title="Design",
  outputPath="projects/website-relaunch/calendars/design/work.yaml"
)
```

```
calendar_create(
  events=[
    { title: "API design",     start: "2026-06-01", end: "2026-06-21", allDay: true },
    { title: "Implementation", start: "2026-06-22", end: "2026-07-31", allDay: true },
    { title: "Beta launch",    start: "2026-08-01", allDay: true, tags: [milestone, critical] }
  ],
  title="Backend",
  outputPath="projects/website-relaunch/calendars/backend/work.yaml"
)
```

**3. Rebuild artifacts**:

```
app_rebuild(folder="projects/website-relaunch/calendars")
```

The response contains the Gantt + conflicts paths and markdownLinks. Embed both in the chat reply:

```markdown
✓ Projektplan: [Website Relaunch](vance:/projects/website-relaunch/calendars/_app.yaml)

📊 [Gantt-Chart](vance:/projects/website-relaunch/calendars/_gantt.md) — 5 lanes, 12 milestones
⚠ [Konflikt-Übersicht](vance:/projects/website-relaunch/calendars/_conflicts.yaml) — 2 Konflikte
```

## Anti-patterns

- **Don't put all events into a single calendar file** when the user has natural lane separation (teams, phases, work streams). The whole point of the app pattern is editability per lane.
- **Don't include recurring events** in the Gantt (daily standups blow it up). Default behaviour (`includeRecurring: false`) drops them; if the user explicitly wants them visible, change the manifest, not the recurring event.
- **Don't write `_gantt.md` or `_conflicts.yaml` by hand.** They are regenerated artifacts. Hand-edits get overwritten on next `app_rebuild`. Edit the source calendars, then rebuild.
- **Don't call `app_rebuild` after every single tiny edit.** It's cheap but not free. Once at the end of a planning session is enough.
- **Don't forget to embed both artifact `markdownLink`s in chat.** That's where the user sees the result.

## When NOT to use this pattern

- **Single calendar without lanes** → `kind: calendar` + `calendar_create`. App pattern is overhead.
- **Free-form task list** → `kind: list` or `kind: records`. Calendar implies time anchors.
- **Project management with task dependencies and resource allocation** → that's not Vance's scope. Recommend exporting to Linear / Jira / GitHub Projects.
